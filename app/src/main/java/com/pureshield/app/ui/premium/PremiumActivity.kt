package com.pureshield.app.ui.premium

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pureshield.app.R
import com.pureshield.app.databinding.ActivityPremiumBinding
import com.pureshield.app.databinding.ItemPlanCardBinding
import com.pureshield.app.ui.ThemeManager

/**
 * Premium screen (demo Screen 7, verbatim labels). Reached from the Settings
 * gold card; not in the bottom nav.
 *
 * HONEST LIMIT: Play Billing is not wired in v1. The trial button opens a
 * dialog stating plainly that in-app purchase via Google Play will be
 * available in the release build and nothing is charged now. No purchase is
 * faked and no success toast is shown.
 */
class PremiumActivity : AppCompatActivity() {

    private lateinit var b: ActivityPremiumBinding

    private var selectedPlan: Plan = Plan.MONTHLY

    private val planAdapter = PlanAdapter { plan ->
        selectedPlan = plan
        planAdapter.submit(plans(), selectedPlan)
        b.tvFooterPrice.setText(
            if (plan == Plan.YEARLY) R.string.premium_price_yearly
            else R.string.premium_price_monthly
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityPremiumBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvBack.setOnClickListener { finish() }

        // "Premium" in gold, as in the demo title.
        val title = getString(R.string.premium_title)
        val spannable = SpannableString(title)
        val gold = attrColor(R.attr.goldColor, Color.rgb(0xDD, 0x94, 0x12))
        val start = title.indexOf("Premium")
        if (start >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(gold),
                start, start + "Premium".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        b.tvTitle.text = spannable

        styleGoldCard()
        styleProTag(b.proTag1)
        styleProTag(b.proTag2)
        styleProTag(b.proTag3)
        styleProTag(b.proTag4)
        styleTrialButton()

        b.rvPlans.layoutManager = LinearLayoutManager(this)
        b.rvPlans.adapter = planAdapter
        planAdapter.submit(plans(), selectedPlan)

        b.btnTrial.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.premium_trial_title)
                .setMessage(R.string.premium_trial_msg)
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
    }

    private fun plans(): List<PlanItem> = listOf(
        PlanItem(
            plan = Plan.MONTHLY,
            titleRes = R.string.premium_monthly_title,
            subRes = R.string.premium_monthly_sub,
            priceRes = R.string.premium_monthly_price,
            showSaveTag = false
        ),
        PlanItem(
            plan = Plan.YEARLY,
            titleRes = R.string.premium_yearly_title,
            subRes = R.string.premium_yearly_sub,
            priceRes = R.string.premium_yearly_price,
            showSaveTag = true
        )
    )

    private fun styleGoldCard() {
        val gold = attrColor(R.attr.goldColor, Color.rgb(0xDD, 0x94, 0x12))
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(gold, darken(gold, 0.72f))
        )
        drawable.cornerRadius = attrDimen(R.attr.cardCornerRadius, 22f)
        b.cardGold.background = drawable
    }

    private fun styleTrialButton() {
        val gold = attrColor(R.attr.goldColor, Color.rgb(0xDD, 0x94, 0x12))
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(gold, darken(gold, 0.78f))
        )
        drawable.cornerRadius = attrDimen(R.attr.buttonCornerRadius, 28f)
        b.btnTrial.background = drawable
    }

    private fun styleProTag(tag: TextView) {
        val gold = attrColor(R.attr.goldColor, Color.rgb(0xDD, 0x94, 0x12))
        val drawable = GradientDrawable()
        drawable.setColor(gold)
        drawable.cornerRadius = attrDimen(R.attr.chipCornerRadius, 8f)
        tag.background = drawable
        tag.setTextColor(attrColor(R.attr.buttonText, Color.WHITE))
        val padH = (8 * resources.displayMetrics.density).toInt()
        val padV = (2 * resources.displayMetrics.density).toInt()
        tag.setPadding(padH, padV, padH, padV)
    }

    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun attrDimen(attr: Int, fallbackDp: Float): Float {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) {
            TypedValue.complexToDimension(tv.data, resources.displayMetrics)
        } else {
            fallbackDp * resources.displayMetrics.density
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val bl = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }

    enum class Plan { MONTHLY, YEARLY }

    data class PlanItem(
        val plan: Plan,
        val titleRes: Int,
        val subRes: Int,
        val priceRes: Int,
        val showSaveTag: Boolean
    )

    private inner class PlanAdapter(
        private val onPick: (Plan) -> Unit
    ) : RecyclerView.Adapter<PlanAdapter.Holder>() {

        private var items: List<PlanItem> = emptyList()
        private var selected: Plan = Plan.MONTHLY

        fun submit(newItems: List<PlanItem>, selectedPlan: Plan) {
            items = newItems
            selected = selectedPlan
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemPlanCardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val vb: ItemPlanCardBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(item: PlanItem) {
                vb.tvPlanTitle.setText(item.titleRes)
                vb.tvPlanSub.setText(item.subRes)
                vb.tvPlanPrice.setText(item.priceRes)
                vb.cardSaveTag.visibility =
                    if (item.showSaveTag) android.view.View.VISIBLE else android.view.View.GONE
                val isSelected = item.plan == selected
                vb.rbPlan.isChecked = isSelected
                val stroke = if (isSelected) {
                    attrColor(R.attr.goldColor, Color.rgb(0xDD, 0x94, 0x12))
                } else {
                    attrColor(R.attr.lineColor, Color.LTGRAY)
                }
                vb.root.strokeColor = stroke
                vb.root.setOnClickListener { onPick(item.plan) }
            }
        }
    }
}
