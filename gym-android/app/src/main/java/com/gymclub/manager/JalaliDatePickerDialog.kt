package com.gymclub.manager

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import android.app.Dialog
import android.widget.NumberPicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * انتخابگر تاریخ شمسی با سه چرخ‌دنده (سال / ماه / روز).
 * تعداد روزهای هر ماه بر اساس همان ماه و سال (کبیسه بودن اسفند) تنظیم
 * می‌شود تا تاریخ ناممکن انتخاب نشود.
 */
class JalaliDatePickerDialog : DialogFragment() {

    var initial: JalaliDate.J = JalaliDate.today()
    var onPicked: ((JalaliDate.J) -> Unit)? = null

    private lateinit var year: NumberPicker
    private lateinit var month: NumberPicker
    private lateinit var day: NumberPicker

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_jalali_date, null)
        year = view.findViewById(R.id.pickYear)
        month = view.findViewById(R.id.pickMonth)
        day = view.findViewById(R.id.pickDay)

        setupYear()
        setupMonth()
        refreshDays()

        month.setOnValueChangedListener { _, _, _ -> refreshDays() }
        year.setOnValueChangedListener { _, _, _ -> refreshDays() }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pick_date_title)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                onPicked?.invoke(JalaliDate.J(year.value, month.value, day.value))
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.today) { _, _ ->
                onPicked?.invoke(JalaliDate.today())
            }
            .create()
    }

    private fun setupYear() {
        year.wrapSelectorWheel = false
        year.minValue = MIN_YEAR
        year.maxValue = MAX_YEAR
        year.displayedValues = persianRange(MIN_YEAR, MAX_YEAR)
        year.value = initial.y.coerceIn(MIN_YEAR, MAX_YEAR)
    }

    private fun setupMonth() {
        month.wrapSelectorWheel = false
        month.minValue = 1
        month.maxValue = 12
        month.displayedValues = JalaliDate.monthNames
        month.value = initial.m.coerceIn(1, 12)
    }

    private fun refreshDays() {
        val max = JalaliDate.daysInMonth(year.value, month.value)
        // در اولین صدا زدن، NumberPicker هنوز مقدار نگرفته و صفر است.
        val wanted = if (day.value > 0) day.value else initial.d
        day.wrapSelectorWheel = false
        day.minValue = 1
        day.maxValue = max
        day.displayedValues = persianRange(1, max)
        day.value = wanted.coerceIn(1, max)
    }

    private fun persianRange(from: Int, to: Int): Array<String> =
        (from..to).map { JalaliDate.fa(it) }.toTypedArray()

    companion object {
        private const val MIN_YEAR = 1380
        private const val MAX_YEAR = 1500

        fun newInstance(initial: JalaliDate.J, onPicked: (JalaliDate.J) -> Unit) =
            JalaliDatePickerDialog().apply {
                this.initial = initial
                this.onPicked = onPicked
            }
    }
}
