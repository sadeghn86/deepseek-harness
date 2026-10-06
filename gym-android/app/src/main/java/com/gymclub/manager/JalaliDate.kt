package com.gymclub.manager

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * تبدیل تاریخ میلادی ↔ شمسی و محاسبهٔ روزهای باقی‌مانده، بدون هیچ کتابخانهٔ
 * خارجی. همه‌چیز حساب سادهٔ عددی است تا هم آفلاین باشد و هم روی JVM قابل
 * تست (این کلاس هیچ وابستگی به اندروید ندارد).
 *
 * الگوریتم تبدیل، همان الگوریتم شناخته‌شدهٔ jalaali است که برای سال‌های
 * ۱۲۰۰ تا ۱۶۰۰ شمسی درست کار می‌کند.
 */
object JalaliDate {

    /** یک تاریخ شمسی. */
    data class J(val y: Int, val m: Int, val d: Int)

    val monthNames = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    private val gCumulative = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    // ---------------------------------------------------------------- میلادی → شمسی

    /** تبدیل میلادی به شمسی. */
    fun toJalali(gy: Int, gm: Int, gd: Int): J {
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + gd + gCumulative[gm - 1]

        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        return if (days < 186) {
            J(jy, 1 + (days / 31), 1 + (days % 31))
        } else {
            J(jy, 7 + ((days - 186) / 30), 1 + ((days - 186) % 30))
        }
    }

    // ---------------------------------------------------------------- شمسی → میلادی

    /** تبدیل شمسی به میلادی. */
    fun toGregorian(j: J): Triple<Int, Int, Int> {
        var gy = if (j.y > 979) 1600 else 621
        val jy2 = j.y - (if (j.y > 979) 979 else 0)
        var days = (365 * jy2) + ((jy2 / 33) * 8) + (((jy2 % 33) + 3) / 4) + 78 + j.d +
            (if (j.m < 7) (j.m - 1) * 31 else ((j.m - 7) * 30) + 186)

        gy += 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            days -= 1
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days += 1
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }

        var gd = days + 1
        val feb = if ((gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0) 29 else 28
        val sal = intArrayOf(0, 31, feb, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 13 && gd > sal[gm]) {
            gd -= sal[gm]
            gm += 1
        }
        return Triple(gy, gm, gd)
    }

    // ---------------------------------------------------------------- شمارش روز

    /** روز از مبدأ ۱۹۷۰/۰۱/۰۱ میلادی (الگوریتم Hinnant). */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val y2 = (if (m <= 2) y - 1 else y).toLong()
        val era = (if (y2 >= 0) y2 else y2 - 399) / 400
        val yoe = y2 - era * 400
        val mp = ((m + 9) % 12).toLong()
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((y + (if (m <= 2) 1 else 0)).toInt(), m.toInt(), d.toInt())
    }

    /** شمارهٔ روز مطلق یک تاریخ شمسی؛ برای تفاضل گرفتن. */
    fun epochDay(j: J): Long {
        val (gy, gm, gd) = toGregorian(j)
        return daysFromCivil(gy, gm, gd)
    }

    fun fromEpochDay(e: Long): J {
        val (gy, gm, gd) = civilFromDays(e)
        return toJalali(gy, gm, gd)
    }

    /** امروز به شمسی (بر اساس ساعت دستگاه). */
    fun today(): J {
        val c = GregorianCalendar.getInstance()
        return toJalali(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** تعداد روزهای باقی‌مانده؛ عدد منفی یعنی عضویت تمام شده. */
    fun daysRemaining(end: J, from: J = today()): Int = (epochDay(end) - epochDay(from)).toInt()

    // ---------------------------------------------------------------- طول ماه / کبیسه

    /** آیا سال شمسی کبیسه است (اسفند ۳۰ روزه). */
    fun isLeap(jy: Int): Boolean = epochDay(J(jy + 1, 1, 1)) - epochDay(J(jy, 1, 1)) == 366L

    /** تعداد روزهای یک ماه شمسی. */
    fun daysInMonth(jy: Int, jm: Int): Int = when {
        jm in 1..6 -> 31
        jm in 7..11 -> 30
        else -> if (isLeap(jy)) 30 else 29
    }

    // ---------------------------------------------------------------- حساب عضویت

    /** n ماه بعد از یک تاریخ؛ روز بزرگ‌تر از طول ماه، به آخر ماه گرد می‌شود. */
    fun plusMonths(j: J, n: Int): J {
        val total = j.y * 12 + (j.m - 1) + n
        val y = Math.floorDiv(total, 12)
        val m = Math.floorMod(total, 12) + 1
        return J(y, m, minOf(j.d, daysInMonth(y, m)))
    }

    fun plusDays(j: J, n: Int): J = fromEpochDay(epochDay(j) + n)

    /**
     * تاریخ پایان عضویت: n ماه کامل از [start]، به‌طوریکه خود روز شروع هم
     * جزو عضویت حساب شود. مثال: شروع ۱۴۰۵/۰۷/۱۴ ماهانه → پایان ۱۴۰۵/۰۸/۱۳
     */
    fun endOfTerm(start: J, months: Int): J = plusDays(plusMonths(start, months), -1)

    // ---------------------------------------------------------------- نمایش

    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** ارقام لاتین را به فارسی تبدیل می‌کند. */
    fun fa(value: Any?): String {
        val s = value?.toString().orEmpty()
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in '0'..'9') persianDigits[ch - '0'] else ch)
        }
        return sb.toString()
    }

    /** جداکنندهٔ هزارگان با ارقام فارسی: ۱٬۲۵۰٬۰۰۰ */
    fun faMoney(value: Long): String {
        val neg = value < 0
        val digits = Math.abs(value).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append('٬')
            sb.append(persianDigits[ch - '0'])
        }
        return (if (neg) "−" else "") + sb.toString()
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()

    /** ۱۴۰۵/۰۷/۱۴ */
    fun short(j: J): String = fa("${j.y}/${two(j.m)}/${two(j.d)}")

    /** ۱۴ مهر ۱۴۰۵ */
    fun long(j: J): String = fa("${j.d} ${monthNames[j.m - 1]} ${j.y}")

    /**
     * متن خوانای روزهای باقی‌مانده برای نمایش در جدول.
     */
    fun remainingLabel(days: Int): String = when {
        days > 1 -> fa(days) + " روز مانده"
        days == 1 -> "۱ روز مانده"
        days == 0 -> "امروز تمام می‌شود"
        days == -1 -> "۱ روز از پایان گذشته"
        else -> fa(-days) + " روز از پایان گذشته"
    }
}
