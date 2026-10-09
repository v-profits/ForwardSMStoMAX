package com.example.forwardsmstomax  // или forwardsmstotelegram

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/**
 * Диалог с инструкцией по настройке фильтра
 * Открывается по нажатию на иконку "?" (manualIcon)
 */
class ManualDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Создаём полноэкранный диалог (без заголовка)
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.fragment_manual)
        dialog.setCanceledOnTouchOutside(true)  // закрытие по клику вне диалога

        // Получаем элементы из layout
        val versionTextView = dialog.findViewById<TextView>(R.id.versionTextView)
        val canselTextView = dialog.findViewById<TextView>(R.id.canselTextView)

        // Читаем версию приложения из манифеста
        val versionName = try {
            requireActivity().packageManager.getPackageInfo(requireActivity().packageName, 0).versionName
        } catch (e: Exception) {
            "?.?.?"
        }
        versionTextView.text = versionName  // выводим версию

        // Кнопка закрытия
        canselTextView.setOnClickListener {
            dismiss()
        }

        return dialog
    }
}