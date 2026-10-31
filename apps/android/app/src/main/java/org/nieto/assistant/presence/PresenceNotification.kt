package org.nieto.assistant.presence

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import org.nieto.assistant.MainActivity
import org.nieto.assistant.R

internal fun Service.presenceNotification(id: Int, title: String, text: String): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("tino_presence", "TINO a mano", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java).putExtra("destination","chat")
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(this, id, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, "tino_presence").setSmallIcon(R.drawable.ic_nieto)
        .setContentTitle(title).setContentText(text).setContentIntent(open).setOngoing(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
        .addAction(Notification.Action.Builder(null, "Desactivar", stop).build()).build()
}

internal fun Service.showPresence(id: Int, title: String, text: String, microphone: Boolean) {
    val notification = presenceNotification(id, title, text)
    if (Build.VERSION.SDK_INT >= 34) startForeground(id, notification,
        if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    else startForeground(id, notification)
}
