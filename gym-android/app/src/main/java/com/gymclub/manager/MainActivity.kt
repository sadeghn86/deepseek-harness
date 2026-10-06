package com.gymclub.manager

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gymclub.manager.databinding.ActivityMainBinding

/**
 * صفحهٔ اصلی: کارت‌های شمارش + جدول اعضا + جست‌وجو + فیلتر وضعیت.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var db: DbHelper
    private lateinit var settings: SettingsStore
    private lateinit var adapter: MemberAdapter

    private var query = ""
    private var filter: Status? = null

    private val askNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            b.notifyBanner.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        db = DbHelper(this)
        settings = SettingsStore(this)

        b.toolbar.inflateMenu(R.menu.main_menu)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                R.id.action_sample -> { seedSampleData(); true }
                R.id.action_clear -> { confirmClearAll(); true }
                else -> false
            }
        }

        adapter = MemberAdapter(settings.soonDays) { m ->
            startActivity(Intent(this, MemberActivity::class.java).putExtra(MemberActivity.EXTRA_ID, m.id))
        }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.isNestedScrollingEnabled = false

        b.search.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            refresh()
        }

        b.chipAll.setOnClickListener { setFilter(null, b.chipAll) }
        b.chipActive.setOnClickListener { setFilter(Status.ACTIVE, b.chipActive) }
        b.chipSoon.setOnClickListener { setFilter(Status.SOON, b.chipSoon) }
        b.chipExpired.setOnClickListener { setFilter(Status.EXPIRED, b.chipExpired) }

        b.fabAdd.setOnClickListener {
            startActivity(Intent(this, MemberActivity::class.java))
        }

        b.notifyClose.setOnClickListener { b.notifyBanner.visibility = android.view.View.GONE }
        b.notifyGrant.setOnClickListener { requestNotificationPermission() }

        ReminderReceiver.ensureChannel(this)
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // اگر ساعت یا تنظیمات عوض شده باشد، زمان‌بندی تازه شود.
        ReminderScheduler.schedule(this)
        refresh()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setFilter(status: Status?, chip: Chip) {
        filter = status
        b.chipGroup.check(chip.id)
        refresh()
    }

    /** خواندن داده، حساب کردن تعداد نفرات و پر کردن جدول. */
    private fun refresh() {
        val all = db.all()
        val counts = db.counts(settings.soonDays)

        b.countTotal.text = JalaliDate.fa(counts.total)
        b.countActive.text = JalaliDate.fa(counts.active)
        b.countSoon.text = JalaliDate.fa(counts.soon)
        b.countExpired.text = JalaliDate.fa(counts.expired)
        b.summary.text = getString(
            R.string.summary_line,
            JalaliDate.fa(counts.total),
            JalaliDate.fa(counts.active),
            JalaliDate.fa(counts.soon),
            JalaliDate.fa(counts.expired)
        )

        val visible = all.filter { m ->
            val q = query
            (q.isEmpty() || m.fullName.contains(q, true) || m.phone.contains(q, true)) &&
                (filter == null || m.status(settings.soonDays) == filter)
        }

        adapter.submit(visible)
        b.empty.visibility = if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        b.tableHeader.visibility = if (visible.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun seedSampleData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sample_title)
            .setMessage(R.string.sample_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                val today = JalaliDate.today()
                val rows = listOf(
                    Triple("علی رضایی", "09121234567", -3),
                    Triple("مریم احمدی", "09351112233", 2),
                    Triple("حسین کریمی", "09123334455", 6),
                    Triple("سارا موسوی", "09900001122", 21),
                    Triple("رضا نوری", "09127778899", 45)
                )
                val months = intArrayOf(1, 3, 1, 6, 12)
                rows.forEachIndexed { i, (name, phone, offset) ->
                    val end = JalaliDate.plusDays(today, offset)
                    val start = JalaliDate.plusDays(end, -months[i] * 30)
                    db.upsert(
                        Member(
                            fullName = name,
                            phone = phone,
                            plan = Plans.labels[months.indexOf(months[i])],
                            months = months[i],
                            start = start,
                            end = end,
                            fee = (500_000L + i * 150_000L)
                        )
                    )
                }
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                db.deleteAll()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
