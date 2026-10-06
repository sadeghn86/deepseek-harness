package com.gymclub.manager

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.Snackbar
import com.gymclub.manager.databinding.ActivitySettingsBinding
import java.util.Calendar
import java.util.GregorianCalendar

/** تنظیمات یادآوری شهریه: چند روز قبل، چه ساعتی، و روشن/خاموش. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var s: SettingsStore

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        s = SettingsStore(this)

        b.toolbar.setNavigationOnClickListener { finish() }
        ReminderReceiver.ensureChannel(this)

        b.switchEnabled.isChecked = s.remindersEnabled
        b.switchExpired.isChecked = s.remindExpired
        b.soonDays.setText(s.soonDays.toString())

        b.switchEnabled.setOnCheckedChangeListener { _, v ->
            s.remindersEnabled = v
            apply()
        }
        b.switchExpired.setOnCheckedChangeListener { _, v ->
            s.remindExpired = v
        }
        b.soonDays.doAfterTextChanged {
            it?.toString()?.toIntOrNull()?.let { n -> s.soonDays = n }
            renderStatus()
        }
        b.timeButton.setOnClickListener {
            TimePickerDialog(
                this,
                { _: TimePicker, h: Int, m: Int ->
                    s.hour = h
                    s.minute = m
                    apply()
                },
                s.hour,
                s.minute,
                true
            ).show()
        }
        b.grantButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        b.testButton.setOnClickListener { sendTest() }

        renderStatus()
    }

    /** ذخیره + زمان‌بندی دوبارهٔ آلارم. */
    private fun apply() {
        ReminderScheduler.schedule(this)
        renderStatus()
    }

    private fun renderStatus() {
        val scheduled = s.remindersEnabled && ReminderScheduler.isScheduled(this)
        b.timeButton.text = JalaliDate.fa(s.timeLabel())
        b.statusText.text = getString(
            R.string.settings_status,
            JalaliDate.fa(s.soonDays),
            JalaliDate.fa(s.timeLabel()),
            getString(if (scheduled) R.string.state_on else R.string.state_off)
        )
        b.grantButton.isEnabled = Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun sendTest() {
        ReminderReceiver.ensureChannel(this)
        if (!hasNotificationPermission()) {
            Snackbar.make(b.root, R.string.need_permission, Snackbar.LENGTH_LONG).show()
            return
        }
        val next = ReminderScheduler.nextTriggerMillis(s.hour, s.minute, GregorianCalendar.getInstance())
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        try {
            NotificationManagerCompat.from(this).notify(
                2,
                NotificationCompat.Builder(this, ReminderReceiver.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.test_title))
                    .setContentText(
                        getString(
                            R.string.test_body,
                            JalaliDate.fa(s.soonDays),
                            JalaliDate.fa(
                                "%02d:%02d".format(
                                    cal.get(Calendar.HOUR_OF_DAY),
                                    cal.get(Calendar.MINUTE)
                                )
                            )
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            )
            Snackbar.make(b.root, R.string.test_sent, Snackbar.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Snackbar.make(b.root, R.string.need_permission, Snackbar.LENGTH_LONG).show()
        }
    }
}
