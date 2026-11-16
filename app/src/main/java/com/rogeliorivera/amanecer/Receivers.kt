package com.rogeliorivera.amanecer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try {
            c.startForegroundService(AlarmService.intent(c, AlarmService.ACTION_RING))
        } catch (e: Exception) {
            try {
                c.startActivity(Intent(c, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
            }
        }
        if (i.action == AlarmScheduler.ACTION_MAIN) AlarmScheduler.schedule(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        AlarmScheduler.schedule(c)
    }
}
