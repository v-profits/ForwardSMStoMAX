package com.example.forwardsmstomax

import java.text.SimpleDateFormat
import java.util.*

/**
 * Класс данных для сообщения в очереди
 * Хранит всю информацию, необходимую для повторной отправки
 *
 * @param id уникальный ID в БД
 * @param sender отправитель SMS (номер или имя контакта)
 * @param messageText полный отформатированный текст сообщения
 * @param chatId ID чата в MAX (куда отправлять)
 * @param idInstance ID Instance из GREEN-API
 * @param apiToken API Token из GREEN-API
 * @param createdAt время получения исходного SMS (timestamp в миллисекундах)
 * @param retryCount сколько раз уже пытались отправить это сообщение
 */
data class QueuedMessage(
    val id: Long,
    val sender: String,
    val messageText: String,
    val chatId: String,
    val idInstance: String,
    val apiToken: String,
    val createdAt: Long,
    val retryCount: Int = 0
) {
    /**
     * Форматирует время получения SMS для отображения
     * Формат: dd.MM.yy HH:mm:ss  (например: 15.12.24 14:35:22)
     */
    fun getFormattedTime(): String {
        val date = Date(createdAt)
        val format = SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.getDefault())
        return format.format(date)
    }

    /**
     * Возвращает текст сообщения с добавленным префиксом и временем
     * Формат: 🔄 [dd.MM.yy HH:mm:ss]\n{исходный текст}
     *
     * Это сообщение будет отправлено в MAX
     */
    fun getMessageWithTimestamp(): String {
        return "🔄 [${getFormattedTime()}]\n$messageText"
    }
}