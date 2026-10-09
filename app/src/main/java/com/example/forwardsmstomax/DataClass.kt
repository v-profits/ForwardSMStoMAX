package com.example.forwardsmstomax  // или forwardsmstotelegram

/**
 * Данные контакта из адресной книги телефона
 * @param nameContact имя контакта
 * @param phoneNumber номер телефона
 * @param isSelected выбран ли контакт в диалоге выбора
 */
data class ContactData(
    val nameContact: String = "",
    val phoneNumber: String,
    var isSelected: Boolean = false
)

/**
 * Данные фильтра (настройки пересылки SMS)
 * @param id уникальный ID в базе данных
 * @param telegramUserId ID чата в MAX (или Telegram) — куда отправлять
 * @param filterName имя фильтра (для отображения в списке)
 * @param apiTokenBot API токен (для MAX это apiToken из GREEN-API)
 * @param isActive активен ли фильтр (вкл/выкл)
 * @param isOpen открыт ли элемент для свайпа (внутреннее состояние UI)
 */
data class FilterData(
    val id: Long,
    val telegramUserId: String,
    val filterName: String,
    val apiTokenBot: String,
    var isActive: Boolean = true,
    var isOpen: Boolean = false,      // для свайпа в списке
)