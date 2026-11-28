package com.hongguo.watch.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hongguo.watch.R
import com.hongguo.watch.databinding.DialogHistoryFilterBinding

/** 历史筛选底栏：深色可滚动，手表近全宽；首开即展开，圆角安全内收。 */
object HistoryFilterSheet {
    fun show(
        context: Context,
        initial: HistoryFilterPolicy.Criteria,
        onApply: (HistoryFilterPolicy.Criteria) -> Unit,
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(context, R.style.Theme_HongguoWatch_BottomSheet)
        val binding = DialogHistoryFilterBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        // 首帧即按手表圆角/大圆角内收，避免 X / 清空·确定贴角被裁。
        val preferred = WatchUi.preferredInsets(context)
        binding.root.setPadding(
            preferred.horizontal,
            preferred.top,
            preferred.horizontal,
            preferred.vertical,
        )

        var draft = initial

        fun bindDuration() {
            fillChips(
                grid = binding.historyFilterDurationGrid,
                labels = listOf(
                    R.string.history_filter_all to HistoryFilterPolicy.DurationMin.ALL,
                    R.string.history_filter_dur_over_5s to HistoryFilterPolicy.DurationMin.OVER_5_SEC,
                    R.string.history_filter_dur_over_1m to HistoryFilterPolicy.DurationMin.OVER_1_MIN,
                    R.string.history_filter_dur_over_5m to HistoryFilterPolicy.DurationMin.OVER_5_MIN,
                    R.string.history_filter_dur_over_15m to HistoryFilterPolicy.DurationMin.OVER_15_MIN,
                    R.string.history_filter_dur_over_30m to HistoryFilterPolicy.DurationMin.OVER_30_MIN,
                ),
                selected = draft.duration,
                onSelect = { draft = draft.copy(duration = it); bindDuration() },
            )
        }

        fun bindEpisodes() {
            fillChips(
                grid = binding.historyFilterEpisodesGrid,
                labels = listOf(
                    R.string.history_filter_all to HistoryFilterPolicy.EpisodesMin.ALL,
                    R.string.history_filter_ep_over_1 to HistoryFilterPolicy.EpisodesMin.OVER_1,
                    R.string.history_filter_ep_over_3 to HistoryFilterPolicy.EpisodesMin.OVER_3,
                    R.string.history_filter_ep_over_10 to HistoryFilterPolicy.EpisodesMin.OVER_10,
                    R.string.history_filter_ep_over_20 to HistoryFilterPolicy.EpisodesMin.OVER_20,
                    R.string.history_filter_ep_over_50 to HistoryFilterPolicy.EpisodesMin.OVER_50,
                ),
                selected = draft.episodes,
                onSelect = { draft = draft.copy(episodes = it); bindEpisodes() },
            )
        }

        fun bindTime() {
            fillChips(
                grid = binding.historyFilterTimeGrid,
                labels = listOf(
                    R.string.history_filter_all to HistoryFilterPolicy.TimeRange.ALL,
                    R.string.history_filter_time_today to HistoryFilterPolicy.TimeRange.TODAY,
                    R.string.history_filter_time_yesterday to HistoryFilterPolicy.TimeRange.YESTERDAY,
                    R.string.history_filter_time_1m to HistoryFilterPolicy.TimeRange.WITHIN_1_MONTH,
                    R.string.history_filter_time_3m to HistoryFilterPolicy.TimeRange.WITHIN_3_MONTHS,
                    R.string.history_filter_time_earlier to HistoryFilterPolicy.TimeRange.EARLIER,
                ),
                selected = draft.time,
                onSelect = { draft = draft.copy(time = it); bindTime() },
            )
        }

        fun bindComplete() {
            fillChips(
                grid = binding.historyFilterCompleteGrid,
                labels = listOf(
                    R.string.history_filter_all to HistoryFilterPolicy.CompleteState.ALL,
                    R.string.history_filter_complete_yes to HistoryFilterPolicy.CompleteState.COMPLETED,
                    R.string.history_filter_complete_no to HistoryFilterPolicy.CompleteState.NOT_COMPLETED,
                ),
                selected = draft.complete,
                onSelect = { draft = draft.copy(complete = it); bindComplete() },
            )
        }

        bindDuration()
        bindEpisodes()
        bindTime()
        bindComplete()

        binding.historyFilterDismiss.setOnClickListener { dialog.dismiss() }
        binding.historyFilterClear.setOnClickListener {
            draft = HistoryFilterPolicy.Criteria()
            bindDuration()
            bindEpisodes()
            bindTime()
            bindComplete()
        }
        binding.historyFilterConfirm.setOnClickListener {
            onApply(draft)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet,
            ) ?: return@setOnShowListener
            sheet.setBackgroundResource(android.R.color.transparent)

            // 内容必须填满 sheet：ScrollView weight 在 wrap_content 父级下首测无效，
            // 拖动手势才会二次 measure —— 表现为「是否看完」被裁切，拖一下才正常。
            sheet.layoutParams = sheet.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            binding.root.layoutParams = binding.root.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }

            val behavior = BottomSheetBehavior.from(sheet)
            behavior.skipCollapsed = true
            behavior.isFitToContents = false
            behavior.expandedOffset = HistoryFilterSheetLayout.expandedTopOffsetPx(
                context.resources.displayMetrics.heightPixels,
            )
            behavior.state = BottomSheetBehavior.STATE_EXPANDED

            WatchUi.applySafePadding(
                binding.root,
                topChrome = binding.historyFilterHeader,
                bottomChrome = binding.historyFilterActions,
            )

            // 再贴一帧确保 EXPANDED + match_parent 在首绘前落稳，不依赖用户 fling。
            sheet.post {
                if (!behavior.isFitToContents) {
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
                binding.root.requestLayout()
            }
        }
        dialog.show()
        return dialog
    }

    private fun <T> fillChips(
        grid: GridLayout,
        labels: List<Pair<Int, T>>,
        selected: T,
        onSelect: (T) -> Unit,
    ) {
        grid.removeAllViews()
        val ctx = grid.context
        val padH = dp(ctx, 6f)
        val padV = dp(ctx, 5f)
        val selectedColor = ctx.getColor(R.color.accent)
        val idleColor = ctx.getColor(R.color.text_secondary)
        labels.forEach { (labelRes, value) ->
            val chip = TextView(ctx).apply {
                text = ctx.getString(labelRes)
                gravity = Gravity.CENTER
                setPadding(padH, padV, padH, padV)
                setBackgroundResource(R.drawable.bg_history_filter_chip)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                isSelected = value == selected
                setTextColor(if (isSelected) selectedColor else idleColor)
                paint.isFakeBoldText = isSelected
                setOnClickListener { onSelect(value) }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(ctx, 2f), dp(ctx, 2f), dp(ctx, 2f), dp(ctx, 2f))
            }
            grid.addView(chip, lp)
        }
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).toInt()
}
