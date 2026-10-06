package com.gymclub.manager

/**
 * یک عضو باشگاه. تاریخ‌ها به شمسی نگه داشته می‌شوند چون چیزی است که کاربر
 * می‌بیند و وارد می‌کند؛ تبدیل به میلادی فقط برای محاسبهٔ روزها انجام می‌شود.
 */
data class Member(
    val id: Long = 0L,
    val fullName: String,
    val phone: String = "",
    val plan: String = "",
    val months: Int = 1,
    val start: JalaliDate.J,
    val end: JalaliDate.J,
    val fee: Long = 0L,
    val note: String = "",
    /**
     * بیت ۱ = اعلان «نزدیک اتمام» برای این دوره ارسال شده.
     * بیت ۲ = اعلان «منقضی شده» برای این دوره ارسال شده.
     * با تغییر تاریخ پایان، صفر می‌شود تا اعلان دورهٔ جدید دوباره برود.
     */
    val notified: Int = 0
) {
    val endEpochDay: Long get() = JalaliDate.epochDay(end)

    val daysRemaining: Int get() = JalaliDate.daysRemaining(end)

    val isExpired: Boolean get() = daysRemaining < 0

    companion object {
        const val NOTIFIED_SOON = 1
        const val NOTIFIED_EXPIRED = 2
    }
}

/** وضعیت عضویت، برای رنگ‌بندی و فیلتر جدول. */
enum class Status { ACTIVE, SOON, EXPIRED }

fun Member.status(soonDays: Int): Status = when {
    endEpochDay - JalaliDate.epochDay(JalaliDate.today()) < 0 -> Status.EXPIRED
    endEpochDay - JalaliDate.epochDay(JalaliDate.today()) <= soonDays -> Status.SOON
    else -> Status.ACTIVE
}

/**
 * کدام اعلان هنوز برای این عضو ارسال نشده؟ صفر یعنی چیزی لازم نیست.
 * تابع خالص است (ورودی‌ها همه عددند) تا بدون اندروید تست شود.
 *
 * @param soonDays آستانهٔ «نزدیک اتمام» بر حسب روز
 * @param todayEpoch شمارهٔ روز امروز
 */
fun Member.pendingReminderFlag(soonDays: Int, todayEpoch: Long): Int {
    val remaining = (endEpochDay - todayEpoch).toInt()
    return when {
        remaining < 0 && notified and Member.NOTIFIED_EXPIRED == 0 -> Member.NOTIFIED_EXPIRED
        remaining in 0..soonDays && notified and Member.NOTIFIED_SOON == 0 -> Member.NOTIFIED_SOON
        else -> 0
    }
}

/** بسته‌های عضویت آماده؛ تعداد ماه هر بسته. */
object Plans {
    val labels = arrayOf("ماهانه", "دو ماهه", "سه ماهه", "شش ماهه", "سالانه")
    val months = intArrayOf(1, 2, 3, 6, 12)

    fun monthsFor(label: String): Int {
        val i = labels.indexOf(label)
        return if (i >= 0) months[i] else 1
    }
}
