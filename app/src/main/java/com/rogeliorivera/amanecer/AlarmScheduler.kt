package com.rogeliorivera.amanecer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_MAIN = "com.rogeliorivera.amanecer.MAIN"
    const val ACTION_SNOOZE = "com.rogeliorivera.amanecer.SNOOZE"

    private fun firePI(c: Context, main: Boolean): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction(if (main) ACTION_MAIN else ACTION_SNOOZE)
        return PendingIntent.getBroadcast(
            c, if (main) 100 else 101, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPI(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Lunes = 1 … domingo = 7 */
    private fun isoDay(cal: Calendar): Int {
        val d = cal.get(Calendar.DAY_OF_WEEK) // domingo = 1
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    /** Próximo disparo según hora y días activos, o null si no hay días. */
    fun nextTrigger(c: Context): Long? {
        val days = Prefs.days(c)
        if (days.isEmpty()) return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, Prefs.hour(c))
        cal.set(Calendar.MINUTE, Prefs.minute(c))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis() + 5000) cal.add(Calendar.DAY_OF_YEAR, 1)
        repeat(8) {
            if (isoDay(cal) in days) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    fun canExact(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Programa (o cancela) la alarma. Devuelve false si Android no lo permitió. */
    fun schedule(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java)
        val next = nextTrigger(c)
        if (!Prefs.enabled(c) || next == null) {
            am.cancel(firePI(c, true))
            return true
        }
        if (!canExact(c)) return false
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next, showPI(c)), firePI(c, true))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun scheduleIn(c: Context, minutes: Int): Boolean {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!canExact(c)) return false
        val at = System.currentTimeMillis() + minutes * 60_000L
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPI(c)), firePI(c, false))
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
