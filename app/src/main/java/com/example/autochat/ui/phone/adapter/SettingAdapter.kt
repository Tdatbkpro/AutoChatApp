// adapter/SettingAdapter.kt
package com.example.autochat.ui.phone.adapter

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.databinding.ItemSettingBinding
import com.example.autochat.databinding.ItemSettingHeaderBinding
import com.example.autochat.databinding.ItemSettingSectionBinding
import com.example.autochat.databinding.ItemSettingThemePickerBinding
import com.example.autochat.ui.phone.model.SettingAction
import com.example.autochat.ui.phone.model.SettingItem
import com.example.autochat.ui.phone.model.SettingViewType
import com.example.autochat.ui.phone.model.ThemeMode
import com.example.autochat.ui.phone.model.viewType

class SettingAdapter(
    /** Navigate → Fragment xử lý transaction */
    private val onNavigate: (SettingAction.Navigate) -> Unit,
    /** Expand  → ViewModel toggle */
    private val onExpand: (SettingItem) -> Unit,
    /** Dialog  → Fragment hiện dialog/bottom-sheet tương ứng */
    private val onDialog: (SettingAction.Dialog) -> Unit,
    /** Custom  → Fragment xử lý (logout confirm, v.v.) */
    private val onCustom: (SettingAction.Custom) -> Unit,
    /** Theme select (chỉ dùng bởi ThemePickerViewHolder) */
    private val onThemeSelect: (ThemeMode) -> Unit,
) : ListAdapter<SettingItem, RecyclerView.ViewHolder>(DiffCallback()) {

    var username: String = ""
    var email: String    = ""

    override fun getItemViewType(position: Int): Int = getItem(position).viewType().ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (SettingViewType.entries[viewType]) {
            SettingViewType.HEADER       -> HeaderViewHolder(ItemSettingHeaderBinding.inflate(inf, parent, false))
            SettingViewType.SECTION      -> SectionViewHolder(ItemSettingSectionBinding.inflate(inf, parent, false))
            SettingViewType.THEME_PICKER -> ThemePickerViewHolder(ItemSettingThemePickerBinding.inflate(inf, parent, false))
            SettingViewType.ITEM         -> ItemViewHolder(ItemSettingBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is HeaderViewHolder      -> holder.bind(username, email)
            is SectionViewHolder     -> holder.bind(item)
            is ThemePickerViewHolder -> holder.bind(item,
                onToggle = { onExpand(item) },
                onSelect = onThemeSelect,
            )
            is ItemViewHolder        -> holder.bind(item) { dispatchAction(item) }
        }
    }

    /**
     * Điều phối action sang đúng callback.
     * Adapter không biết gì về business logic — chỉ forward.
     */
    private fun dispatchAction(item: SettingItem) {
        when (val action = item.action) {
            is SettingAction.Navigate -> onNavigate(action)
            is SettingAction.Expand   -> onExpand(item)
            is SettingAction.Dialog   -> onDialog(action)
            is SettingAction.Custom   -> onCustom(action)
            is SettingAction.None     -> Unit  // không làm gì
        }
    }

    // ── Header ───────────────────────────────────────────────────────────────
    class HeaderViewHolder(
        private val binding: ItemSettingHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(username: String, email: String) {
            binding.tvAvatarLetter.text   = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvHeaderUsername.text = username
            binding.tvAvatarLetter.background = GradientDrawable().apply {
                shape       = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors      = intArrayOf(
                    binding.root.context.resolveAttrColor(R.attr.colorBrand),
                    binding.root.context.resolveAttrColor(R.attr.colorBrandSoft),
                )
            }
            binding.btnBack.setOnClickListener {
                var ctx = binding.root.context
                while (ctx is ContextWrapper && ctx !is AppCompatActivity) ctx = ctx.baseContext
                (ctx as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
            }
        }
    }

    // ── Section ───────────────────────────────────────────────────────────────
    class SectionViewHolder(
        private val binding: ItemSettingSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem) {
            binding.tvSectionTitle.text       = item.title
            binding.tvSectionTitle.visibility = if (item.title.isBlank()) View.GONE else View.VISIBLE
        }
    }

    // ── Theme Picker ──────────────────────────────────────────────────────────
    class ThemePickerViewHolder(
        private val binding: ItemSettingThemePickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SettingItem, onToggle: () -> Unit, onSelect: (ThemeMode) -> Unit) {
            val ctx = binding.root.context

            // ── Main row ─────────────────────────────────────────────
            // Icon trên main row = icon của theme đang được chọn
            binding.ivIcon.setImageResource(item.currentTheme.iconRes)
            binding.tvCurrentTheme.setText(item.currentTheme.labelRes)

            // Arrow xoay: 0° = phải (đóng), 90° = xuống (mở)
            binding.ivArrow.rotation = if (item.isExpanded) 90f else 0f
            binding.panelOptions.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

            // ── Option rows ───────────────────────────────────────────
            bindOption(ThemeMode.LIGHT,  item.currentTheme, ctx)
            bindOption(ThemeMode.DARK,   item.currentTheme, ctx)
            bindOption(ThemeMode.SYSTEM, item.currentTheme, ctx)

            applyCardShape(item, ctx)
            binding.divider.visibility = if (item.showDivider) View.VISIBLE else View.GONE

            // Toggle expand với animation mũi tên
            binding.rowMain.setOnClickListener {
                val target = if (item.isExpanded) 0f else 90f
                ValueAnimator.ofFloat(binding.ivArrow.rotation, target).apply {
                    duration = 200
                    addUpdateListener { binding.ivArrow.rotation = it.animatedValue as Float }
                    start()
                }
                onToggle()
            }

            binding.optionLight.setOnClickListener  { onSelect(ThemeMode.LIGHT) }
            binding.optionDark.setOnClickListener   { onSelect(ThemeMode.DARK) }
            binding.optionSystem.setOnClickListener { onSelect(ThemeMode.SYSTEM) }
        }

        /**
         * Cập nhật icon tint + checkmark cho một option row.
         * Option đang được chọn: icon tint = brand_purple, check hiện.
         * Option không chọn:     icon tint = dark_text_secondary, check ẩn.
         */
        private fun bindOption(mode: ThemeMode, current: ThemeMode, ctx: Context) {
            val isSelected = mode == current
            val activeTint   = ctx.resolveAttrColor(R.attr.textPrimary)
            val inactiveTint = ctx.resolveAttrColor(R.attr.textSecondary)
            val tint = if (isSelected) activeTint else inactiveTint

            when (mode) {
                ThemeMode.LIGHT -> {
                    binding.ivIconLight.setColorFilter(tint)
                    binding.checkLight.visibility = if (isSelected) View.VISIBLE else View.GONE
                }
                ThemeMode.DARK -> {
                    binding.ivIconDark.setColorFilter(tint)
                    binding.checkDark.visibility = if (isSelected) View.VISIBLE else View.GONE
                }
                ThemeMode.SYSTEM -> {
                    binding.ivIconSystem.setColorFilter(tint)
                    binding.checkSystem.visibility = if (isSelected) View.VISIBLE else View.GONE
                }
            }
        }

        private fun applyCardShape(item: SettingItem, ctx: Context) {
            val d = ctx.resources.displayMetrics.density
            val r = 16f * d
            val tl = if (item.isFirstInGroup) r else 0f
            val tr = if (item.isFirstInGroup) r else 0f
            val bl = if (item.isLastInGroup)  r else 0f
            val br = if (item.isLastInGroup)  r else 0f
            binding.root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
                setColor(ctx.resolveAttrColor(R.attr.bgSettingItem))
            }
            (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                val m = (16 * d).toInt()
                it.leftMargin = m; it.rightMargin = m
                it.topMargin  = 0; it.bottomMargin = 0
                binding.root.layoutParams = it
            }
        }
    }

    // ── Regular Item ──────────────────────────────────────────────────────────
    class ItemViewHolder(
        private val binding: ItemSettingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SettingItem, onClick: () -> Unit) {
            val ctx = binding.root.context
            val d   = ctx.resources.displayMetrics.density

            // Icon
            if (item.iconRes != 0) {
                binding.ivIcon.setImageResource(item.iconRes)
                binding.flIconContainer.visibility = View.VISIBLE
                if (item.isDestructive) {
                    binding.ivIcon.setColorFilter(ctx.resolveAttrColor(R.attr.colorDanger))
                    binding.flIconContainer.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE; cornerRadius = 8f * d
                        setColor(ctx.resolveAttrColor(R.attr.bgDangerSurface))  // theo theme
                    }
                } else {
                    binding.ivIcon.clearColorFilter()
                    binding.ivIcon.imageTintList = null
                }
            } else {
                binding.flIconContainer.visibility = View.GONE
            }

            // Title
            binding.tvTitle.text = item.title
            binding.tvTitle.setTextColor(
                if (item.isDestructive) ctx.resolveAttrColor(R.attr.textDanger)
                else ctx.resolveAttrColor(R.attr.textPrimary)
            )

            // Subtitle
            binding.tvSubtitle.visibility = if (item.subtitle != null) View.VISIBLE else View.GONE
            binding.tvSubtitle.text       = item.subtitle ?: ""
            binding.tvSubtitle.alpha      = if (item.isDestructive) 0.7f else 1f
            if (item.isDestructive) binding.tvSubtitle.setTextColor(ctx.resolveAttrColor(R.attr.textDanger))

            // Value
            binding.tvValue.visibility = if (item.value != null) View.VISIBLE else View.GONE
            binding.tvValue.text       = item.value ?: ""

            // Chevron — hiện khi action là Navigate hoặc Dialog (có tương tác đi sâu hơn)
            binding.ivArrow.visibility = when (item.action) {
                is SettingAction.Navigate,
                is SettingAction.Dialog -> if (!item.isDestructive) View.VISIBLE else View.GONE
                else                    -> View.GONE
            }

            // Divider
            binding.divider.visibility = if (item.showDivider) View.VISIBLE else View.GONE

            // Card shape
            val r  = 16f * d
            val tl = if (item.isFirstInGroup) r else 0f
            val tr = if (item.isFirstInGroup) r else 0f
            val bl = if (item.isLastInGroup)  r else 0f
            val br = if (item.isLastInGroup)  r else 0f
            binding.root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
                setColor(ctx.resolveAttrColor(R.attr.bgSettingItem))
            }
            (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                val m = (16 * d).toInt()
                it.leftMargin = m; it.rightMargin = m
                it.topMargin  = 0; it.bottomMargin = 0
                binding.root.layoutParams = it
            }

            // Click — chỉ active khi action không phải None
            if (item.action !is SettingAction.None) {
                binding.root.setOnClickListener { onClick() }
                binding.root.isClickable = true
            } else {
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SettingItem>() {
        override fun areItemsTheSame(a: SettingItem, b: SettingItem) = a.id == b.id
        override fun areContentsTheSame(a: SettingItem, b: SettingItem) = a == b
    }
}

private fun Context.resolveAttrColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}