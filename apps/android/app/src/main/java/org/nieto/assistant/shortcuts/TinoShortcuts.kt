package org.nieto.assistant.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import org.nieto.assistant.MainActivity
import org.nieto.assistant.R
import org.nieto.assistant.voice.VoiceInputActivity

class TinoTileService:TileService() {
    override fun onStartListening(){super.onStartListening();qsTile?.let{it.state=Tile.STATE_ACTIVE;it.label="TINO";it.contentDescription="Hablar con TINO";it.updateTile()}}
    // PendingIntent overload exists only from API 34; older Android needs the guarded legacy overload.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick(){super.onClick();val intent=Intent(this,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,7100,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
    }
}
class TinoWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        ids.forEach { id->
            val intent=Intent(context,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val views=RemoteViews(context.packageName,R.layout.tino_widget)
            views.setOnClickPendingIntent(R.id.widget_open,PendingIntent.getActivity(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(id,views)
        }
    }
}
