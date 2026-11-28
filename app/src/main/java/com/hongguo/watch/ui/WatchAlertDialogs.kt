// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ListAdapter
import android.widget.TextView
import android.widget.WrapperListAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hongguo.watch.R

/**
 * 手表深色弹窗：统一 Builder / 近全宽展示，并在列表适配器层强制浅色字，
 * 避免个别 ROM 忽略 theme 的 textColorAlertDialogListItem（黑底黑字）。
 */
object WatchAlertDialogs {
    fun builder(context: Context): AlertDialog.Builder =
        AlertDialog.Builder(context, R.style.Theme_HongguoWatch_AlertDialog)

    /**
     * 展示手表弹窗：近全宽、深色底、按钮非全大写，并对 list/single/multi 列表项钉白字。
     * 不覆盖调用方已设置的 [AlertDialog.setOnShowListener]。
     */
    fun show(activity: Activity, dialog: AlertDialog): AlertDialog {
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(
                WatchUi.dialogContentWidthPx(activity),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            window.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isAllCaps = false
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isAllCaps = false
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isAllCaps = false
        ensureListContrast(dialog)
        dialog.listView?.post { ensureListContrast(dialog) }
        return dialog
    }

    /** 包装 ListView adapter，保证每一行 text1 / TextView 为浅色。 */
    fun ensureListContrast(dialog: AlertDialog) {
        val listView = dialog.listView ?: return
        val color = ContextCompat.getColor(dialog.context, R.color.text_primary)
        val current = listView.adapter ?: return
        if (current is HighContrastListAdapter) {
            tintVisible(listView, color)
            return
        }
        val base = if (current is WrapperListAdapter) {
            current.wrappedAdapter ?: current
        } else {
            current
        }
        listView.adapter = HighContrastListAdapter(base, color)
        tintVisible(listView, color)
    }

    private fun tintVisible(listView: ViewGroup, color: Int) {
        for (index in 0 until listView.childCount) {
            tintRow(listView.getChildAt(index), color)
        }
    }

    private fun tintRow(row: View?, color: Int) {
        if (row == null) return
        (row as? TextView)?.setTextColor(color)
        row.findViewById<TextView>(android.R.id.text1)?.setTextColor(color)
    }

    private class HighContrastListAdapter(
        private val delegate: ListAdapter,
        private val textColor: Int,
    ) : ListAdapter by delegate {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = delegate.getView(position, convertView, parent)
            tintRow(view, textColor)
            return view
        }
    }
}
