package com.semenenko.smstomax.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView

/**
 * Единое оформление всплывающих диалогов: цвет заголовка, текста и кнопок.
 * Опасные действия (удаление, замена данных) выделяются красным.
 */
object DialogStyle {
    private const val TITLE = "#203B47"      // заголовок
    private const val MESSAGE = "#33414A"    // текст сообщения
    private const val POSITIVE = "#2E7D5B"   // главное действие
    private const val SECONDARY = "#5F6B73"  // отмена и второстепенное
    private const val DANGER = "#D32F2F"     // опасное действие

    /**
     * Применяет оформление при показе диалога.
     * dangerButtons — какие кнопки (AlertDialog.BUTTON_POSITIVE и т.д.) покрасить красным.
     */
    fun decorate(dialog: AlertDialog, dangerButtons: Set<Int> = emptySet()) {
        dialog.setOnShowListener {
            val titleId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
            dialog.findViewById<TextView>(titleId)?.apply {
                setTextColor(Color.parseColor(TITLE))
                typeface = Typeface.DEFAULT_BOLD
            }
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.parseColor(MESSAGE))

            val buttons = listOf(
                AlertDialog.BUTTON_POSITIVE to POSITIVE,
                AlertDialog.BUTTON_NEUTRAL to SECONDARY,
                AlertDialog.BUTTON_NEGATIVE to SECONDARY
            )
            for ((id, normalColor) in buttons) {
                val color = if (id in dangerButtons) DANGER else normalColor
                dialog.getButton(id)?.apply {
                    setTextColor(Color.parseColor(color))
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
        }
    }
}
