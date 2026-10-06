package com.gymclub.manager

import android.content.Context
import android.content.SharedPreferences

/** تنظیمات یادآوری شهریه؛ در SharedPreferences و کاملاً محلی. */
class SettingsStore(context: Context) {

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** یادآوری روشن باشد یا نه. */
    var remindersEnabled: Boolean
        get() = p.getBoolean(KEY_ENABLED, true)
        set(v) = p.edit().putBoolean(KEY_ENABLED, v).apply()

    /** چند روز قبل از اتمام، اعلان برود. */
    var soonDays: Int
        get() = p.getInt(KEY_SOON_DAYS, 7).coerceIn(1, 60)
        set(v) = p.edit().putInt(KEY_SOON_DAYS, v.coerceIn(1, 60)).apply()

    /** ساعت بررسی روزانه (۰ تا ۲۳). */
    var hour: Int
        get() = p.getInt(KEY_HOUR, 9).coerceIn(0, 23)
        set(v) = p.edit().putInt(KEY_HOUR, v.coerceIn(0, 23)).apply()

    /** دقیقهٔ بررسی روزانه. */
    var minute: Int
        get() = p.getInt(KEY_MINUTE, 0).coerceIn(0, 59)
        set(v) = p.edit().putInt(KEY_MINUTE, v.coerceIn(0, 59)).apply()

    /** برای اعضایی که عضویتشان تمام شده هم اعلان برود. */
    var remindExpired: Boolean
        get() = p.getBoolean(KEY_EXPIRED, true)
        set(v) = p.edit().putBoolean(KEY_EXPIRED, v).apply()

    /** ساعت به شکل ۰۹:۳۰ */
    fun timeLabel(): String = "%02d:%02d".format(hour, minute)

    companion object {
        private const val PREFS = "gym_settings"
        private const val KEY_ENABLED = "reminders_enabled"
        private const val KEY_SOON_DAYS = "soon_days"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
        private const val KEY_EXPIRED = "remind_expired"
    }
}
