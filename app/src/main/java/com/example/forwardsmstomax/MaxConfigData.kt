package com.example.forwardsmstomax

/**
 * Класс данных для конфигурации MAX (GREEN-API)
 *
 * @param chatId ID чата в MAX (куда отправлять сообщения)
 * @param idInstance ID Instance из GREEN-API
 * @param apiToken API Token из GREEN-API
 * @param isActive Активен ли фильтр
 */
data class MaxConfigData(
    val chatId: String,
    val idInstance: String,
    val apiToken: String
)