package com.example.forwardsmstomax  // ← для MAX версии
// package com.example.forwardsmstotelegram  // ← для Telegram версии

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Объект для управления разрешениями Android
 *
 * В приложении требуются следующие разрешения:
 * - READ_SMS / RECEIVE_SMS — для чтения входящих SMS
 * - READ_CONTACTS — для выбора контактов из адресной книги
 * - POST_NOTIFICATIONS — для показа уведомлений (Android 13+)
 * - Игнорирование оптимизации батареи — для работы в фоне
 */
object PermissionHelper {

    /**
     * Показывает диалог с просьбой предоставить разрешения
     * Вызывается при нажатии на кнопку "+" (добавить фильтр)
     *
     * @param activity MainActivity, в котором показывается диалог
     */
    fun showPermissionDialog(activity: MainActivity) {
        val builder = AlertDialog.Builder(activity)
        builder.setIcon(R.drawable.shlagbaum_512)      // Иконка диалога
        builder.setTitle(" Разрешения")                 // Заголовок
        builder.setMessage("Чтобы продолжить, необходимо получить разрешения.") // Текст

        // Кнопка "Да" — запрашиваем разрешения
        builder.setPositiveButton("Да") { dialog, which ->
            // 1. Проверяем и запрашиваем отключение оптимизации батареи
            activity.checkBatteryOptimization()
            // 2. Проверяем и запрашиваем разрешения (SMS, контакты, уведомления)
            checkAndRequestPermissions(activity)
        }

        // Кнопка "Нет" — закрываем диалог
        builder.setNegativeButton("Нет") { dialog, which ->
            dialog.dismiss()
        }

        val dialog = builder.create()

        // Настройка внешнего вида диалога (цвета кнопок, заголовка, текста)
        dialog.setOnShowListener {
            // Цвет кнопок (используется цвет minusColor3 из ресурсов)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.minusColor3))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.minusColor3))

            // Цвет заголовка (чёрный)
            val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
            val titleTextView = dialog.findViewById<TextView>(titleId)
            titleTextView?.setTextColor(ContextCompat.getColor(activity, R.color.black))

            // Цвет текста сообщения (чёрный)
            val messageTextView = dialog.findViewById<TextView>(android.R.id.message)
            messageTextView?.setTextColor(ContextCompat.getColor(activity, R.color.black))
        }

        // Устанавливаем фон диалога (из ресурсов)
        dialog.window?.setBackgroundDrawableResource(R.color.fonDialogFilter)
        dialog.show()
    }

    /**
     * Проверяет, все ли необходимые разрешения предоставлены
     * Вызывается при запуске приложения и перед показом диалога
     *
     * @param activity MainActivity
     * @return true — если все разрешения есть, false — если каких-то не хватает
     */
    fun checkPermissions(activity: MainActivity): Boolean {
        var check = true

        // 1. Проверяем разрешения: SMS, контакты, уведомления
        val deniedPermissions = activity.requiredPermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (deniedPermissions.isNotEmpty())
            check = false  // есть хотя бы одно непредоставленное разрешение

        // 2. Проверяем, отключена ли оптимизация батареи для приложения
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(activity.packageName))
            check = false  // оптимизация батареи включена — приложение может не работать в фоне

        return check
    }

    /**
     * Запрашивает недостающие разрешения у пользователя
     * Вызывается из showPermissionDialog() после нажатия "Да"
     *
     * @param activity MainActivity
     */
    private fun checkAndRequestPermissions(activity: MainActivity) {
        // Собираем список разрешений, которые ещё не предоставлены
        val deniedPermissions = activity.requiredPermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (deniedPermissions.isNotEmpty()) {
            // Если есть непредоставленные разрешения — запрашиваем их
            ActivityCompat.requestPermissions(
                activity,
                deniedPermissions.toTypedArray(),
                REQUEST_CODE_ALL_PERMISSIONS  // код запроса (определён в MainActivity)
            )
        } else {
            // Если все разрешения уже есть — запускаем фоновую службу
            Functions.startForegroundServiceManually(activity)
        }
    }
}