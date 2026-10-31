package org.nieto.assistant.family

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object FamilyNotifications {
    private const val CHANNEL = "nieto_family_help_v1"
    private const val TAG = "nieto_family_help"
    private const val ID = 7001
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pedidos de ayuda de Familia", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisos locales tras comprobar pedidos de ayuda autorizados. La comprobación puede demorarse."
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            })
    }
    fun permitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun show(context: Context, count: Int) {
        if (!permitted(context)) return
        val open = PendingIntent.getActivity(context, 7001, Intent(context, FamilyActivity::class.java).putExtra("caregiver", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TINO Familia").setContentText("Hay pedidos de acompañamiento pendientes. Abrí Familia para revisarlos.")
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setNumber(count).build()
        // Permission can change between the check and notify; never crash a worker for that race.
        try { NotificationManagerCompat.from(context).notify(TAG, ID, notification) } catch (_: SecurityException) { }
    }
    fun cancel(context: Context) { NotificationManagerCompat.from(context).cancel(TAG, ID) }
}

class FamilyRepository(private val context: Context) {
    private val store = FamilyStore(context)
    suspend fun refresh(access: FamilyAccess, notify: Boolean): FamilySummary? = refreshLock.withLock {
        accept(access, FamilyApi().summary(access), notify)
    }
    fun accept(access: FamilyAccess, summary: FamilySummary, notify: Boolean): FamilySummary? = store.ifCurrent(access) {
        val plan = FamilyNotificationPolicy.evaluate(summary, store.checkpoint())
        val activeIds = plan.eligible.map { it.id }.toSet()
        var posted = store.postedIds().intersect(activeIds)
        val shouldNotify = notify && store.notificationsEnabled && FamilyNotifications.permitted(context) && plan.fresh.isNotEmpty()
        if (shouldNotify) posted = posted + plan.fresh.map { it.id }
        if (!store.notificationsEnabled || !FamilyNotifications.permitted(context)) posted = emptySet()
        // Commit the cursor before notifying: worker retries must not repeat a delivered alert.
        check(store.record(plan.checkpoint, posted)) { "No pude guardar el estado de avisos." }
        if (posted.isEmpty()) FamilyNotifications.cancel(context)
        else if (shouldNotify) FamilyNotifications.show(context, posted.size)
        summary
    }
    companion object { private val refreshLock = Mutex() }
}

object FamilyBackground {
    private const val WORK = "nieto_family_periodic_alerts"
    fun schedule(context: Context) {
        val store = FamilyStore(context)
        if (store.access() == null || !store.notificationsEnabled) return
        val request = PeriodicWorkRequestBuilder<FamilyAlertsWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        FamilyNotifications.cancel(context)
    }
    fun closeAccess(context: Context, access: FamilyAccess? = null) {
        val store = FamilyStore(context)
        if (access == null) { store.clear(); stop(context) }
        else store.ifCurrent(access) { store.clear(); stop(context) }
    }
}

/** Local polling, not FCM/push. Android may defer a 15-minute request because of battery/network limits. */
class FamilyAlertsWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = FamilyStore(applicationContext)
        val access = store.access() ?: return Result.success()
        if (!store.notificationsEnabled || !FamilyNotifications.permitted(applicationContext)) return Result.success()
        return try {
            FamilyRepository(applicationContext).refresh(access, notify = true)
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: FamilyApiFailure) {
            if (error.status == 401 || error.status == 403) { FamilyBackground.closeAccess(applicationContext, access); Result.success() }
            else if (runAttemptCount < 2) Result.retry() else Result.success()
        } catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.success() }
    }
}
