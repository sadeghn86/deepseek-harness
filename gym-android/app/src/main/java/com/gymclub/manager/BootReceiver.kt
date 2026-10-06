package com.gymclub.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * بعد از خاموش/روشن شدن گوشی یا به‌روزرسانی برنامه، زمان‌بندی یادآوری
 * از بین می‌رود؛ اینجا دوباره ساخته می‌شود.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            ReminderScheduler.schedule(context)
        }
    }
}
