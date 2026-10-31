package org.nieto.assistant.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.nieto.assistant.data.LocalSettings
import org.nieto.assistant.data.NietoApi
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Foreground app transitions only, after explicit opt-in. Never reads URLs, typed text or messages. */
object DeviceUsage {
    fun permitted(context: Context): Boolean=context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED
    fun enable(context: Context,allowed: Boolean) {
        val settings=LocalSettings(context)
        if(allowed && settings.read("usageSince").isBlank()) settings.write("usageSince",System.currentTimeMillis().toString())
        settings.write("usageConsent",allowed.toString())
        if(!allowed){WorkManager.getInstance(context).cancelUniqueWork("tino_usage");WorkManager.getInstance(context).cancelUniqueWork("tino_usage_now");settings.write("usagePending","");settings.write("usageCursor","");settings.write("usageSince","");return}
        schedule(context)
    }
    fun schedule(context: Context) {
        if(LocalSettings(context).read("usageConsent")!="true" || !permitted(context))return
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork("tino_usage_now",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<UsageWorker>().setConstraints(constraints).build())
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("tino_usage",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<UsageWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    private fun label(pkg: String)=when(pkg){"com.whatsapp"->"WhatsApp";"com.google.android.gm"->"Gmail";"com.android.chrome"->"Chrome";"com.google.android.youtube"->"YouTube";"org.nieto.assistant"->"TINO";else->"Otra app"}
    private fun category(pkg: String)=when{pkg=="com.whatsapp" || pkg=="com.google.android.gm"->"communication";pkg.contains("maps")->"navigation";pkg.contains("youtube")->"learning";else->"other"}
    suspend fun collect(context: Context) {
        val settings=LocalSettings(context)
        if(settings.read("usageConsent")!="true" || !permitted(context) || settings.token.isBlank())return
        val server=settings.baseUrl;val token=settings.token
        val retained=settings.read("usagePending").takeIf{it.isNotBlank()}?.let(::JSONObject)
        if(retained!=null) {
            if(retained.optString("server")!=server || retained.optString("token")!=token){settings.write("usagePending","");return}
            val result=NietoApi(settings).request("/usage/batch","POST",retained.getJSONObject("body"),serverUrl=server,installationToken=token)
            check(result.optBoolean("ok")){"No se confirmó el envío de uso."}
            if(settings.token==token && settings.baseUrl==server && settings.read("usageConsent")=="true"){settings.write("usageCursor",retained.getLong("cursor").toString());settings.write("usagePending","")}
            return
        }
        val now=System.currentTimeMillis();val end=now/300000*300000
        var cursor=settings.read("usageCursor").toLongOrNull() ?: settings.read("usageSince").toLongOrNull() ?: return
        cursor=maxOf(cursor,now-TimeUnit.DAYS.toMillis(7))
        if(end<=cursor)return
        val stop=minOf(end,cursor+TimeUnit.HOURS.toMillis(6))
        val events=context.getSystemService(UsageStatsManager::class.java).queryEvents(cursor-TimeUnit.DAYS.toMillis(1),stop)
        var current: String?=null;var started=cursor;val intervals=JSONArray();val event=UsageEvents.Event()
        fun finish(at: Long) {
            val pkg=current ?: return;val from=maxOf(started,cursor);if(at<=from)return
            val seconds=(at-from)/1000.0
            val key=MessageDigest.getInstance("SHA-256").digest("$pkg:$from:$at".toByteArray()).joinToString(""){"%02x".format(it)}
            intervals.put(JSONObject().put("id",key).put("category",category(pkg)).put("appName",label(pkg)).put("startedAt",Instant.ofEpochMilli(from).toString()).put("endedAt",Instant.ofEpochMilli(at).toString()).put("accompaniedSeconds",if(pkg==context.packageName)seconds else 0))
        }
        while(events.hasNextEvent()) {
            events.getNextEvent(event);val time=event.timeStamp
            when(event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED->{if(time>=cursor)finish(time);current=event.packageName;started=maxOf(cursor,time)}
                UsageEvents.Event.ACTIVITY_PAUSED->{if(current==event.packageName){if(time>=cursor)finish(time);current=null}}
                UsageEvents.Event.SCREEN_NON_INTERACTIVE->{if(time>=cursor)finish(time);current=null}
            }
            if(intervals.length()>=50)break
        }
        if(intervals.length()<50)finish(stop)
        if(settings.token!=token || settings.baseUrl!=server || settings.read("usageConsent")!="true")return
        val delivered=if(intervals.length()>=50)Instant.parse(intervals.getJSONObject(intervals.length()-1).getString("endedAt")).toEpochMilli() else stop
        if(intervals.length()>0) {
            val body=JSONObject().put("consent",true).put("intervals",intervals)
            settings.write("usagePending",JSONObject().put("server",server).put("token",token).put("cursor",delivered).put("body",body).toString())
            val result=NietoApi(settings).request("/usage/batch","POST",body,serverUrl=server,installationToken=token)
            check(result.optBoolean("ok")){"No se confirmó el envío de uso."}
        }
        if(settings.token==token && settings.baseUrl==server && settings.read("usageConsent")=="true"){settings.write("usageCursor",delivered.toString());settings.write("usagePending","")}

    }
}
class UsageWorker(context: Context,parameters: WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result=try{DeviceUsage.collect(applicationContext);Result.success()}catch(error:CancellationException){throw error}catch(_:Exception){if(runAttemptCount<3)Result.retry() else Result.failure()}
}
