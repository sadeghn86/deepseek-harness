package com.gymclub.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست منطق تاریخ شمسی و پرچم‌های یادآوری. این کلاس‌ها هیچ وابستگی به
 * اندروید ندارند، پس تست روی JVM معمولی اجرا می‌شود (`testDebugUnitTest`).
 */
class JalaliDateTest {

    /** میلادی به شمسی */
    @Test
    fun gregorianToJalali() {
        assertEquals(JalaliDate.J(1405, 7, 14), JalaliDate.toJalali(2026, 10, 6))
        assertEquals(JalaliDate.J(1405, 1, 1), JalaliDate.toJalali(2026, 3, 21))
    }

    /** شمسی به میلادی */
    @Test
    fun jalaliToGregorian() {
        assertEquals(Triple(2026, 3, 21), JalaliDate.toGregorian(JalaliDate.J(1405, 1, 1)))
        assertEquals(Triple(2025, 3, 20), JalaliDate.toGregorian(JalaliDate.J(1403, 12, 30)))
    }

    /** رفت و برگشت تاریخ بدون از دست رفتن مقدار */
    @Test
    fun roundTrip() {
        val samples = listOf(
            JalaliDate.J(1405, 7, 14), JalaliDate.J(1403, 12, 30), JalaliDate.J(1404, 12, 29),
            JalaliDate.J(1399, 1, 1), JalaliDate.J(1408, 6, 31), JalaliDate.J(1420, 11, 5)
        )
        for (j in samples) {
            assertEquals("epoch roundtrip for $j", j, JalaliDate.fromEpochDay(JalaliDate.epochDay(j)))
            val g = JalaliDate.toGregorian(j)
            assertEquals("civil roundtrip for $j", j, JalaliDate.toJalali(g.first, g.second, g.third))
        }
    }

    /** سال کبیسه */
    @Test
    fun leapYears() {
        assertTrue(JalaliDate.isLeap(1403))
        assertFalse(JalaliDate.isLeap(1404))
        assertTrue(JalaliDate.isLeap(1408))
        assertFalse(JalaliDate.isLeap(1405))
    }

    /** طول ماه‌ها */
    @Test
    fun daysInMonth() {
        assertEquals(31, JalaliDate.daysInMonth(1405, 1))
        assertEquals(31, JalaliDate.daysInMonth(1405, 6))
        assertEquals(30, JalaliDate.daysInMonth(1405, 7))
        assertEquals(30, JalaliDate.daysInMonth(1405, 11))
        assertEquals(30, JalaliDate.daysInMonth(1403, 12)) // اسفند کبیسه
        assertEquals(29, JalaliDate.daysInMonth(1404, 12)) // اسفند ساده
    }

    /** پایان دورهٔ ماهانه: خود روز شروع هم جزو عضویت است */
    @Test
    fun endOfTermMonthly() {
        assertEquals(JalaliDate.J(1405, 8, 13), JalaliDate.endOfTerm(JalaliDate.J(1405, 7, 14), 1))
    }

    /** پایان دورهٔ سالانه */
    @Test
    fun endOfTermYearly() {
        assertEquals(JalaliDate.J(1406, 7, 13), JalaliDate.endOfTerm(JalaliDate.J(1405, 7, 14), 12))
    }

    /** روز سی‌ویکم به آخر ماه کوتاه‌تر گرد می‌شود */
    @Test
    fun endOfTermClampsLongDay() {
        // ۳۱ فروردین + ۶ ماه → ۳۰ مهر → منهای یک روز = ۲۹ مهر
        assertEquals(JalaliDate.J(1405, 7, 29), JalaliDate.endOfTerm(JalaliDate.J(1405, 1, 31), 6))
    }

    /** پایان اسفند کبیسه */
    @Test
    fun endOfTermLeapEsfand() {
        // ۳۰ بهمن ۱۴۰۳ + ۱ ماه → ۳۰ اسفند → منهای یک روز = ۲۹ اسفند
        assertEquals(JalaliDate.J(1403, 12, 29), JalaliDate.endOfTerm(JalaliDate.J(1403, 11, 30), 1))
    }

    /** روزهای باقی‌مانده */
    @Test
    fun daysRemaining() {
        val today = JalaliDate.today()
        assertEquals(7, JalaliDate.daysRemaining(JalaliDate.plusDays(today, 7), today))
        assertEquals(0, JalaliDate.daysRemaining(today, today))
        assertEquals(-3, JalaliDate.daysRemaining(JalaliDate.plusDays(today, -3), today))
    }

    /** نمایش با ارقام فارسی */
    @Test
    fun persianFormatting() {
        assertEquals("۱۴۰۵/۰۷/۱۴", JalaliDate.short(JalaliDate.J(1405, 7, 14)))
        assertEquals("۱۴ مهر ۱۴۰۵", JalaliDate.long(JalaliDate.J(1405, 7, 14)))
        assertEquals("۱٬۲۵۰٬۰۰۰", JalaliDate.faMoney(1250000L))
    }

    /** پرچم اعلان شهریه: چه کسی هنوز اعلان نگرفته است */
    @Test
    fun pendingReminderFlag() {
        val today = JalaliDate.today()
        val te = JalaliDate.epochDay(today)
        fun m(offset: Int, flags: Int) = Member(
            id = 1L,
            fullName = "تست",
            start = today,
            end = JalaliDate.plusDays(today, offset),
            notified = flags
        )

        assertEquals(Member.NOTIFIED_SOON, m(3, 0).pendingReminderFlag(7, te))
        assertEquals(0, m(3, Member.NOTIFIED_SOON).pendingReminderFlag(7, te))
        assertEquals(Member.NOTIFIED_EXPIRED, m(-1, 0).pendingReminderFlag(7, te))
        assertEquals(0, m(-1, Member.NOTIFIED_EXPIRED).pendingReminderFlag(7, te))
        assertEquals(Member.NOTIFIED_SOON, m(0, 0).pendingReminderFlag(7, te))
        assertEquals(0, m(30, 0).pendingReminderFlag(7, te))
        // آستانهٔ بزرگ‌تر → همین عضو حالا «نزدیک اتمام» حساب می‌شود
        assertEquals(Member.NOTIFIED_SOON, m(20, 0).pendingReminderFlag(30, te))
    }

    /** دسته‌بندی وضعیت برای رنگ جدول و فیلترها */
    @Test
    fun memberStatus() {
        val today = JalaliDate.today()
        fun m(offset: Int) = Member(1L, "تست", start = today, end = JalaliDate.plusDays(today, offset))

        assertEquals(Status.ACTIVE, m(40).status(7))
        assertEquals(Status.SOON, m(5).status(7))
        assertEquals(Status.SOON, m(0).status(7))
        assertEquals(Status.EXPIRED, m(-1).status(7))
    }

    /** بسته‌های عضویت */
    @Test
    fun planMonths() {
        assertEquals(1, Plans.monthsFor("ماهانه"))
        assertEquals(6, Plans.monthsFor("شش ماهه"))
        assertEquals(12, Plans.monthsFor("سالانه"))
        assertEquals(1, Plans.monthsFor("دلخواه"))
    }
}
