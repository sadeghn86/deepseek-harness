package com.gymclub.manager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gymclub.manager.databinding.ActivityMemberBinding

/**
 * فرم افزودن / ویرایش عضو: نام، موبایل، بستهٔ عضویت، تاریخ شروع و
 * «تاریخ اتمام» (به شمسی)، شهریه و یادداشت.
 */
class MemberActivity : AppCompatActivity() {

    private lateinit var b: ActivityMemberBinding
    private lateinit var db: DbHelper

    private var editingId = 0L
    private var start: JalaliDate.J = JalaliDate.today()
    private var end: JalaliDate.J = JalaliDate.endOfTerm(start, 1)
    private var months = 1
    private var notified = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMemberBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = DbHelper(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        val plans = arrayOf(*(Plans.labels), getString(R.string.plan_custom))
        b.planSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, plans)
        b.planSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long
            ) {
                if (pos < Plans.labels.size) {
                    months = Plans.months[pos]
                    // با عوض شدن بسته، تاریخ اتمام دوباره حساب می‌شود.
                    end = JalaliDate.endOfTerm(start, months)
                    renderDates()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        b.startButton.setOnClickListener {
            JalaliDatePickerDialog.newInstance(start) { picked ->
                start = picked
                end = JalaliDate.endOfTerm(start, months)
                renderDates()
            }.show(supportFragmentManager, "start")
        }

        b.endButton.setOnClickListener {
            JalaliDatePickerDialog.newInstance(end) { picked ->
                end = picked
                renderDates()
            }.show(supportFragmentManager, "end")
        }

        b.save.setOnClickListener { save() }
        b.delete.setOnClickListener { confirmDelete() }
        b.extend.setOnClickListener { extend() }
        b.sms.setOnClickListener { sendSms() }

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        if (editingId != 0L) {
            db.byId(editingId)?.let { m ->
                b.title.setText(m.fullName)
                b.phone.setText(m.phone)
                b.fee.setText(if (m.fee == 0L) "" else m.fee.toString())
                b.note.setText(m.note)
                start = m.start
                end = m.end
                months = m.months.coerceAtLeast(1)
                notified = m.notified
                val idx = Plans.months.indexOf(months)
                b.planSpinner.setSelection(if (idx >= 0) idx else plans.size - 1)
                b.toolbar.setTitle(R.string.edit_member)
            }
        } else {
            b.delete.isEnabled = false
            b.extend.isEnabled = false
            b.sms.isEnabled = false
            b.toolbar.setTitle(R.string.new_member)
        }
        renderDates()
    }

    private fun renderDates() {
        b.startButton.text = JalaliDate.long(start)
        b.endButton.text = JalaliDate.long(end)
        val remaining = JalaliDate.daysRemaining(end)
        b.remaining.text = getString(
            R.string.remaining_line,
            JalaliDate.short(end),
            JalaliDate.remainingLabel(remaining)
        )
        val color = when {
            remaining < 0 -> R.color.status_expired
            remaining <= SettingsStore(this).soonDays -> R.color.status_soon
            else -> R.color.status_active
        }
        b.remaining.setTextColor(getColor(color))
    }

    private fun save() {
        val name = b.title.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            b.titleLayout.error = getString(R.string.err_name_required)
            return
        }
        b.titleLayout.error = null

        val id = db.upsert(
            Member(
                id = editingId,
                fullName = name,
                phone = b.phone.text?.toString()?.trim().orEmpty(),
                plan = b.planSpinner.selectedItem?.toString().orEmpty(),
                months = months,
                start = start,
                end = end,
                fee = b.fee.text?.toString()?.trim()?.toLongOrNull() ?: 0L,
                note = b.note.text?.toString()?.trim().orEmpty(),
                // تاریخ پایان عوض شده → اعلان دورهٔ جدید دوباره می‌رود.
                notified = if (editingId != 0L && db.byId(editingId)?.end == end) notified else 0
            )
        )
        Snackbar.make(b.root, getString(R.string.saved, JalaliDate.fa(id)), Snackbar.LENGTH_SHORT).show()
        ReminderScheduler.schedule(this)
        finish()
    }

    private fun extend() {
        val newStart = JalaliDate.plusDays(end, 1)
        val newEnd = JalaliDate.endOfTerm(newStart, months)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.extend_title)
            .setMessage(getString(R.string.extend_message, JalaliDate.long(newEnd)))
            .setPositiveButton(R.string.extend) { _, _ ->
                db.extend(editingId, months, newStart, newEnd)
                Snackbar.make(
                    b.root,
                    getString(R.string.extended, JalaliDate.long(newEnd)),
                    Snackbar.LENGTH_LONG
                ).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                db.delete(editingId)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * ساختن متن آمادهٔ پیامک شهریه و باز کردن برنامهٔ پیامک گوشی.
     * هیچ اینترنتی لازم نیست؛ فقط متن آماده می‌شود.
     */
    private fun sendSms() {
        val m = db.byId(editingId) ?: return
        val remaining = JalaliDate.daysRemaining(m.end)
        val body = getString(
            R.string.sms_body,
            m.fullName,
            JalaliDate.long(m.end),
            JalaliDate.remainingLabel(remaining)
        )

        val targets = listOf(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${m.phone}")).putExtra("sms_body", body),
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
        )
        for (i in targets) {
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
                // برنامهٔ بعدی را امتحان می‌کنیم
            }
        }
        // هیچ برنامه‌ای نبود: متن را در کلیپ‌بورد بگذار.
        val clip = android.content.ClipData.newPlainText("sms", body)
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        Snackbar.make(b.root, R.string.sms_copied, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_ID = "member_id"
    }
}
