package com.gymclub.manager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gymclub.manager.databinding.ItemMemberBinding

/** ردیف‌های جدول اعضا. */
class MemberAdapter(
    private val soonDays: Int,
    private val onClick: (Member) -> Unit
) : RecyclerView.Adapter<MemberAdapter.VH>() {

    private val items = mutableListOf<Member>()

    fun submit(list: List<Member>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemMemberBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(m: Member) {
            val ctx = b.root.context
            b.cellName.text = m.fullName
            b.cellPhone.text = if (m.phone.isBlank()) "—" else JalaliDate.fa(m.phone)
            b.cellEnd.text = JalaliDate.short(m.end)

            val remaining = m.daysRemaining
            val status = m.status(soonDays)
            b.cellStatus.text = JalaliDate.remainingLabel(remaining)

            val color = when (status) {
                Status.ACTIVE -> ctx.getColor(R.color.status_active)
                Status.SOON -> ctx.getColor(R.color.status_soon)
                Status.EXPIRED -> ctx.getColor(R.color.status_expired)
            }
            b.cellStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

            b.cellPlan.text = if (m.plan.isBlank()) "—" else m.plan
            b.root.setOnClickListener { onClick(m) }
        }
    }
}
