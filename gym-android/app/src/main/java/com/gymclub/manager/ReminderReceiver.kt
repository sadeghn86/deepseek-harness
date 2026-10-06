package com.gymclub.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * هر روز یک بار بیدار می‌شود، اعضای نزدیک اتمام یا منقضی را پیدا می‌کند و
 * اعلان شهریه می‌سازد. همه‌چیز روی خود گوشی انجام می‌شود.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_EXTEND -> {
                val id = intent.getLongExtra(EXTRA_ID, 0L)
                if (id != 0L) extendNow(app, id)
            }

            ReminderScheduler.ACTION_CHECK -> {
                // کار با دیتابیس روی ترد اصلی ممنوع است؛ goAsync تا ۱۰ ثانیه
                // وقت می‌دهد که برای چند صد عضو کاملاً کافی است.
                val pending = goAsync()
                Thread {
                    try {
                        checkDues(app)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    /** اعلان‌های لازم را می‌سازد و پرچم «ارسال شد» را در دیتابیس می‌زند. */
    private fun checkDues(context: Context) {
        val settings = SettingsStore(context)
        if (!settings.remindersEnabled) return

        val db = DbHelper(context)
        val due = db.dueForReminder(settings.soonDays)
        if (due.isEmpty()) return

        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // دسترسی اعلان داده نشده؛ پرچم‌ها را نمی‌زنیم تا بعد از دادن
            // دسترسی، اعلان‌ها در بررسی بعدی بروند.
            return
        }

        val todayEpoch = JalaliDate.epochDay(JalaliDate.today())
        var shown = 0

        for (m in due) {
            if (shown >= MAX_NOTIFICATIONS) break
            val remaining = (m.endEpochDay - todayEpoch).toInt()
            if (remaining < 0 && !settings.remindExpired) {
                // فقط پرچم را می‌زنیم تا هر روز تکرار نشود.
                db.addNotifiedFlags(m.id, Member.NOTIFIED_EXPIRED)
                continue
            }

            val flag = m.pendingReminderFlag(settings.soonDays, todayEpoch)
            if (flag == 0) continue

            val (title, text) = if (remaining < 0) {
                "شهریه ${m.fullName} عقب افتاده" to
                    "عضویت در ${JalaliDate.long(m.end)} تمام شده (${JalaliDate.remainingLabel(remaining)})"
            } else {
                "یادآوری شهریه: ${m.fullName}" to
                    "عضویت در ${JalaliDate.long(m.end)} تمام می‌شود (${JalaliDate.remainingLabel(remaining)})"
            }

            try {
                nm.notify(notificationId(m.id), buildNotification(context, m, title, text, remaining))
            } catch (_: SecurityException) {
                return
            }
            db.addNotifiedFlags(m.id, flag)
            shown++
        }

        if (shown > 1) {
            try {
                nm.notify(
                    SUMMARY_ID,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("یادآوری شهریه باشگاه")
                        .setContentText(JalaliDate.fa(shown) + " اعلان شهریه ارسال شد")
                        .setStyle(
                            NotificationCompat.BigTextStyle().bigText(
                                JalaliDate.fa(shown) +
                                    " عضو در وضعیت تمدید است. برای دیدن فهرست، برنامه را باز کنید."
                            )
                        )
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(openIntent(context, 0L))
                        .setAutoCancel(true)
                        .build()
                )
            } catch (_: SecurityException) {
                // بدون دسترسی اعلان، کاری نمی‌شود کرد.
            }
        }
    }

    private fun buildNotification(
        context: Context,
        m: Member,
        title: String,
        text: String,
        remaining: Int
    ): android.app.Notification {
        val extend = PendingIntent.getBroadcast(
            context,
            (m.id * 2).toInt(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_EXTEND)
                .putExtra(EXTRA_ID, m.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val big = buildString {
            appendLine(text)
            if (m.plan.isNotBlank()) appendLine("بسته: ${m.plan}")
            if (m.phone.isNotBlank()) appendLine("موبایل: ${JalaliDate.fa(m.phone)}")
            if (m.fee > 0) appendLine("شهریه: ${JalaliDate.faMoney(m.fee)} تومان")
            if (remaining < 0) appendLine("برای تمدید، روی «تمدید یک دوره» بزنید.")
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openIntent(context, m.id))
            .addAction(0, "تمدید یک دوره", extend)
            .setAutoCancel(true)
            .build()
    }

    /** تمدید سریع از داخل خود اعلان. */
    private fun extendNow(context: Context, id: Long) {
        val db = DbHelper(context)
        val m = db.byId(id) ?: return
        val months = m.months.coerceAtLeast(1)
        val newStart = JalaliDate.plusDays(m.end, 1)
        val newEnd = JalaliDate.endOfTerm(newStart, months)
        db.extend(id, months, newStart, newEnd)

        NotificationManagerCompat.from(context).cancel(notificationId(id))
        ensureChannel(context)
        try {
            NotificationManagerCompat.from(context).notify(
                notificationId(id),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("تمدید شد: ${m.fullName}")
                    .setContentText("عضویت تا ${JalaliDate.long(newEnd)} تمدید شد")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(openIntent(context, id))
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {
            // دسترسی اعلان داده نشده؛ تمدید در دیتابیس انجام شده است.
        }
    }

    private fun openIntent(context: Context, memberId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        (memberId + 9000).toInt(),
        Intent(context, if (memberId == 0L) MainActivity::class.java else MemberActivity::class.java)
            .apply { if (memberId != 0L) putExtra(MemberActivity.EXTRA_ID, memberId) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val CHANNEL_ID = "dues_channel"
        const val ACTION_EXTEND = "com.gymclub.manager.action.EXTEND_MEMBER"
        const val EXTRA_ID = "member_id"

        private const val SUMMARY_ID = 1
        private const val MAX_NOTIFICATIONS = 10

        fun notificationId(memberId: Long): Int = 1000 + memberId.toInt()

        /** کانال اعلان را می‌سازد (اندروید ۸ به بالا). */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = context.getString(R.string.channel_desc)
                        enableVibration(true)
                    }
                )
            }
        }
    }
}
