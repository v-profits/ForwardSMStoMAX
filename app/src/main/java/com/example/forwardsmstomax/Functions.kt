package com.example.forwardsmstomax  // или forwardsmstotelegram

import android.content.Intent

/**
 * Вспомогательные функции для работы приложения
 */
object Functions {

    /**
     * Запуск фонового сервиса (для приёма SMS в фоне)
     * @param activity MainActivity
     */
    fun startForegroundServiceManually(activity: MainActivity) {
        val serviceIntent = Intent(activity, ForegroundService::class.java)
        activity.startService(serviceIntent)
    }

    /**
     * Нормализация номера телефона
     * - Если есть буквы → возвращаем как есть (имя отправителя)
     * - Иначе приводим к формату +7XXXXXXXXXX
     *
     * Примеры:
     * "Tinkoff" → "Tinkoff"
     * "+7 (927) 273-24-72" → "+79272732472"
     * "89272732472" → "+79272732472"
     * "9272732472" → "+79272732472"
     */
    fun normalizePhoneNumber(phone: String): String {
        // Если есть буквы — это имя, не нормализуем
        if (phone.any { it.isLetter() }) return phone

        // Оставляем только цифры
        val digits = phone.filter { it.isDigit() }

        // Приводим к формату +7XXXXXXXXXX
        return when {
            digits.length == 11 && digits.startsWith("8") -> "+7" + digits.substring(1)
            digits.length == 11 && digits.startsWith("7") -> "+7" + digits.substring(1)
            digits.length == 10 && digits.startsWith("9") -> "+7$digits"
            else -> digits
        }
    }
}