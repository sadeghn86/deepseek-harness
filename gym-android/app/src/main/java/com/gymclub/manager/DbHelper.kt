package com.gymclub.manager

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * لایهٔ ذخیره‌سازی: یک فایل SQLite ساده در حافظهٔ داخلی برنامه.
 * بدون سرور، بدون اینترنت، بدون کتابخانهٔ اضافه.
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Counts(val total: Int, val active: Int, val soon: Int, val expired: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID      INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME    TEXT NOT NULL,
                $COL_PHONE   TEXT NOT NULL DEFAULT '',
                $COL_PLAN    TEXT NOT NULL DEFAULT '',
                $COL_MONTHS  INTEGER NOT NULL DEFAULT 1,
                $COL_SY      INTEGER NOT NULL,
                $COL_SM      INTEGER NOT NULL,
                $COL_SD      INTEGER NOT NULL,
                $COL_EY      INTEGER NOT NULL,
                $COL_EM      INTEGER NOT NULL,
                $COL_ED      INTEGER NOT NULL,
                $COL_FEE     INTEGER NOT NULL DEFAULT 0,
                $COL_NOTE    TEXT NOT NULL DEFAULT '',
                $COL_NOTIF   INTEGER NOT NULL DEFAULT 0,
                $COL_END_EPOCH INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // برای مرتب‌سازی جدول بر اساس نزدیک‌ترین تاریخ اتمام.
        db.execSQL("CREATE INDEX idx_members_end ON $TABLE ($COL_END_EPOCH)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // نسخهٔ ۱؛ هنوز مهاجرتی لازم نیست.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ---------------------------------------------------------------- نوشتن

    /** درج یا به‌روزرسانی یک عضو؛ شناسهٔ ردیف را برمی‌گرداند. */
    fun upsert(m: Member): Long {
        val v = ContentValues().apply {
            put(COL_NAME, m.fullName)
            put(COL_PHONE, m.phone)
            put(COL_PLAN, m.plan)
            put(COL_MONTHS, m.months)
            put(COL_SY, m.start.y)
            put(COL_SM, m.start.m)
            put(COL_SD, m.start.d)
            put(COL_EY, m.end.y)
            put(COL_EM, m.end.m)
            put(COL_ED, m.end.d)
            put(COL_FEE, m.fee)
            put(COL_NOTE, m.note)
            put(COL_NOTIF, m.notified)
            put(COL_END_EPOCH, m.endEpochDay)
        }
        return if (m.id == 0L) {
            v.put(COL_CREATED, System.currentTimeMillis())
            writableDatabase.insert(TABLE, null, v)
        } else {
            writableDatabase.update(TABLE, v, "$COL_ID = ?", arrayOf(m.id.toString()))
            m.id
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    /** اعلان‌های ارسال‌شده برای یک عضو را علامت می‌زند. */
    fun addNotifiedFlags(id: Long, mask: Int) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_NOTIF = ($COL_NOTIF | ?) WHERE $COL_ID = ?",
            arrayOf<Any>(mask, id)
        )
    }

    /**
     * تمدید عضویت: شروع دورهٔ تازه = روز بعد از پایان قبلی، پایان = یک دورهٔ
     * کامل بعد از آن. پرچم اعلان صفر می‌شود تا برای دورهٔ جدید دوباره برود.
     */
    fun extend(id: Long, months: Int, newStart: JalaliDate.J, newEnd: JalaliDate.J) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COL_MONTHS, months)
                put(COL_SY, newStart.y)
                put(COL_SM, newStart.m)
                put(COL_SD, newStart.d)
                put(COL_EY, newEnd.y)
                put(COL_EM, newEnd.m)
                put(COL_ED, newEnd.d)
                put(COL_END_EPOCH, JalaliDate.epochDay(newEnd))
                put(COL_NOTIF, 0)
            },
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    // ---------------------------------------------------------------- خواندن

    fun all(): List<Member> =
        readableDatabase.query(TABLE, null, null, null, null, null, "$COL_END_EPOCH ASC, $COL_NAME ASC")
            .use { c ->
                val out = ArrayList<Member>(c.count)
                while (c.moveToNext()) out.add(read(c))
                out
            }

    fun byId(id: Long): Member? =
        readableDatabase.query(TABLE, null, "$COL_ID = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) read(c) else null }

    /** تعداد نفرات در هر دسته — همان چیزی که بالای جدول نمایش داده می‌شود. */
    fun counts(soonDays: Int): Counts {
        var total = 0; var active = 0; var soon = 0; var expired = 0
        for (m in all()) {
            total++
            when (m.status(soonDays)) {
                Status.ACTIVE -> active++
                Status.SOON -> soon++
                Status.EXPIRED -> expired++
            }
        }
        return Counts(total, active, soon, expired)
    }

    /**
     * اعضایی که باید برایشان اعلان شهریه فرستاد: کسانی که در [soonDays] روز
     * آینده عضویتشان تمام می‌شود یا تمام شده و هنوز اعلان نگرفته‌اند.
     */
    fun dueForReminder(soonDays: Int, limit: Int = 20): List<Member> {
        val todayEpoch = JalaliDate.epochDay(JalaliDate.today())
        return all()
            .filter { it.pendingReminderFlag(soonDays, todayEpoch) != 0 }
            .take(limit)
    }

    private fun read(c: android.database.Cursor) = Member(
        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
        fullName = c.getString(c.getColumnIndexOrThrow(COL_NAME)),
        phone = c.getString(c.getColumnIndexOrThrow(COL_PHONE)).orEmpty(),
        plan = c.getString(c.getColumnIndexOrThrow(COL_PLAN)).orEmpty(),
        months = c.getInt(c.getColumnIndexOrThrow(COL_MONTHS)),
        start = JalaliDate.J(
            c.getInt(c.getColumnIndexOrThrow(COL_SY)),
            c.getInt(c.getColumnIndexOrThrow(COL_SM)),
            c.getInt(c.getColumnIndexOrThrow(COL_SD))
        ),
        end = JalaliDate.J(
            c.getInt(c.getColumnIndexOrThrow(COL_EY)),
            c.getInt(c.getColumnIndexOrThrow(COL_EM)),
            c.getInt(c.getColumnIndexOrThrow(COL_ED))
        ),
        fee = c.getLong(c.getColumnIndexOrThrow(COL_FEE)),
        note = c.getString(c.getColumnIndexOrThrow(COL_NOTE)).orEmpty(),
        notified = c.getInt(c.getColumnIndexOrThrow(COL_NOTIF))
    )

    companion object {
        private const val DB_NAME = "gym_members.db"
        private const val DB_VERSION = 1

        const val TABLE = "members"
        const val COL_ID = "_id"
        const val COL_NAME = "name"
        const val COL_PHONE = "phone"
        const val COL_PLAN = "plan"
        const val COL_MONTHS = "months"
        const val COL_SY = "start_y"
        const val COL_SM = "start_m"
        const val COL_SD = "start_d"
        const val COL_EY = "end_y"
        const val COL_EM = "end_m"
        const val COL_ED = "end_d"
        const val COL_FEE = "fee"
        const val COL_NOTE = "note"
        const val COL_NOTIF = "notified"
        const val COL_END_EPOCH = "end_epoch"
        const val COL_CREATED = "created_at"
    }
}
