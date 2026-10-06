package com.gymclub.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * زمان‌بندی بررسی روزانهٔ شهریه با AlarmManager.
 *
 * از `setInexactRepeating` استفاده می‌شود تا نیاز به دسترسی
 * SCHEDULE_EXACT_ALARM (اندروید ۱۲+) نباشد؛ چند دقیقه جابه‌جایی برای یک
 * یادآوری روزانه هیچ اشکالی ندارد و مصرف باتری هم کمتر است.
 */
object ReminderScheduler {

    const val ACTION_CHECK = "com.gymclub.manager.action.CHECK_DUES"
    private const val REQ_CODE = 4711

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQ_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * زمان‌بندی (یا زمان‌بندی دوباره) بررسی روزانه در ساعت تنظیم‌شده.
     * بی‌ضرر است اگر چند بار صدا زده شود.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val s = SettingsStore(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        if (!s.remindersEnabled) {
            cancel(app)
            return
        }

        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMillis(s.hour, s.minute),
            AlarmManager.INTERVAL_DAY,
            pendingIntent(app)
        )
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(app))
    }

    /** آیا این زمان‌بندی فعال است. */
    fun isScheduled(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQ_CODE,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_CHECK),
            flags
        ) != null
    }

    /**
     * نزدیک‌ترین لحظهٔ آینده که ساعت دستگاه به [hour]:[minute] می‌رسد.
     * تابع خالص است تا بتوان تستش کرد.
     */
    fun nextTriggerMillis(hour: Int, minute: Int, now: Calendar = GregorianCalendar.getInstance()): Long {
        val next = now.clone() as Calendar
        next.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        next.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        next.set(Calendar.SECOND, 0)
        next.set(Calendar.MILLISECOND, 0)
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }
}
