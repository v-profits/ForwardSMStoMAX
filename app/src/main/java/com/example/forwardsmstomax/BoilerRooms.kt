package com.example.forwardsmstomax

import com.example.forwardsmstomax.Generators.parseTemperatureToDouble

// ==================== КОНФИГУРАЦИЯ ====================
// ☎️ Списки номеров телефонов котельных
val listPhoneBoilerRooms = listOf("+79272732416", "+79272733491", "+79272732472", "+79278023612") // телефоны котельных

/**
 * Объект для форматирования SMS от котельных
 * Преобразует сырые SMS в читаемый вид с:
 * - Температурами (помещение, подача, обратка, ГВС)
 * - Состояниями зон (норма/обрыв/замыкание)
 * - Статусами насосов
 * - Тревожными сообщениями
 */
object BoilerRooms {
    //=== Функция для форматирования температуры (T1=+24,5C; -> +24,5°C)
    private fun formatTemperature(line: String): String {
        return line.substringAfter("=").replace(";", "").replace("C", "°C")
    }

    //=== Функция для преобразования кода состояния зоны в текст
    private fun statusZone(c: Char, index: Int = -1): String {
        val status = when (c) {
            'n' -> {
                if (index == 5) "220В" // для зоны 5, газогенератора
                else "в норме" // для всех остальных зон
            }
            'H' -> "⚠\uFE0F" // "обрыв"
            'L' -> "⚠\uFE0F" // "замыкание"
            'x' -> "откл."
            'X' -> "❌ откл. >4 сработок"
            else -> "неизвестно"
        }
        return status
    }

    //=== преобразуем текст смс
    fun changeText(sender: String, message: String): String {

        //--- переменные
        val lines = message.split("\n") // Разбиваем сообщение на строки
        val result = mutableListOf<String>()
        var fullMessage = false // флаг полного отчёта
        var releStatus = "" // состояние реле
        var zoneStatus = "" // состояния зон (строка из n,H,L,x,X)

        //--- кастомные имена для термодатчиков
        val tempLabels = listOf(
            "Помещ.",
            "Подача",
            "Обратка",
            "ГВС",
            "Вен.канал")

        //--- кастомные имена для активных зон сигналов
        val zoneLabels = listOf(
            "",
            "Давл.газа   ",
            "Давл.воды ",
            "Сигн.БСУ    ",
            "" ,
            "Питание горелки ",
            "Работа системы  ",
            "")

        //--- создаем кастомный вид смс
        for (line in lines) {
            when {
                line.startsWith("T1=") -> { // если первая строка - температура, значит это отчет
                    fullMessage = true
                    result.add("${tempLabels[0]}:      ${formatTemperature(line)}") // добавляем температуру помещения
                }
                line.startsWith("T2=") -> result.add("${tempLabels[1]}:      ${formatTemperature(line)}") // температура подачи
                line.startsWith("T3=") -> result.add("${tempLabels[2]}:    ${formatTemperature(line)}") // температура обратки
                line.startsWith("T4=") -> { // температура ГВС
                    if (sender != "+79272732472")
                        result.add("${tempLabels[3]}:             ${formatTemperature(line)}") // температура вен. канала
                }
                // Проверяем, что строка содержит только n, H, L, x, X и имеет длину 4, 8 или 12 символов
                line.matches(Regex("[nHLxX]{4}|[nHLxX]{8}|[nHLxX]{12}")) -> zoneStatus = line
                line.startsWith("RELE=") -> releStatus = line.substringAfter("RELE=")
            }
        }

        //--- Обработка зон (например, "nHLxX" -> "Зона1: норма", "Зона2: обрыв" и т. д.)
        if (zoneStatus.isNotEmpty()) {
            zoneStatus.forEachIndexed { index, c ->
                // Функция для преобразования кода состояния зоны в текст
                val status = statusZone(c)

                if (sender == listPhoneBoilerRooms[0] || sender == listPhoneBoilerRooms[2]) { // Котельная КТП, Котельная ФРОВ
                    if (index < zoneLabels.size && index in listOf(2, 3)) result.add("${zoneLabels[index]} – $status") // для зон 2 и 3
                } else if (sender == listPhoneBoilerRooms[1] || sender == listPhoneBoilerRooms[3]) { // Котельная Офис, Котельная Большая
                    if (index < zoneLabels.size && index in listOf(1, 2)) result.add("${zoneLabels[index]} – $status") // для зон 1 и 2
                } else {
                    if (index < zoneLabels.size && index in listOf(1, 2, 3)) result.add("${zoneLabels[index]} – $status") // для зон 1, 2 и 3
                }
            }
        }

        //--- для газогенератора на фров-север (добавляем текст генератора к котельной)
        if (sender == listPhoneBoilerRooms[2]) { // listPhoneBoilerRooms[2]

            // --- создаем заголовок Газогенератору
            var temperature = 0.0
            for (line in lines)
                when {
                    line.startsWith("T4=") -> temperature = parseTemperatureToDouble(line) // температура вен. канала
                }
            val temp = if (temperature > 30.0) "\uD83D\uDD25" else "❄\uFE0F" // 🔥❄️ добавляем иконку в заголовок генератора
            result.add("\n$temp Генератор 1 (IH/AR_100)")

            for (line in lines) {
                when {
                    line.startsWith("T4=")      -> result.add("${tempLabels[4]}:     ${formatTemperature(line)}") // температура вен. канала
                }
            }

            // Обработка зон (например, "nHLxX" -> "Зона1: норма", "Зона2: обрыв" и т. д.)
            if (zoneStatus.isNotEmpty()) {
                zoneStatus.forEachIndexed { index, c ->
                    // Функция для преобразования кода состояния зоны в текст
                    val status = statusZone(c, index)
                    if (index < zoneLabels.size && index in listOf(5, 6)) result.add("${zoneLabels[index]} – $status") // для выборочных зон
                }
            }
        }

        //--- Выбираем вид суточного отчета или вид смс-ТРЕВОГИ
        if (fullMessage) { // полный суточный отчет
            fullMessage = false
        } else { // ТРЕВОГА, сообщение с тревогами и предупреждениями по сработкам датчиков зон
            result.clear()
            //--- создаем кастомный вид смс
            for (line in lines) { // проходим по строкам
                var modifiedLine = line // Создаем копию строки для модификации

                // если сообщение для котельных
                if (sender in listPhoneBoilerRooms) {
                    if (modifiedLine.contains("T2=")) modifiedLine = modifiedLine.replace("T2=", "(Подача) T2=")
                    if (modifiedLine.contains("T3=")) modifiedLine = modifiedLine.replace("T3=", "(Обратка) T3=")
                    if (modifiedLine.contains("T4=")) modifiedLine = modifiedLine.replace("T4=", "(ГВС) T4=")

                    if (modifiedLine.contains("Зона N 02")) modifiedLine = modifiedLine.replace("Зона N 02", "Зона N 02 (Давл. газа)")
                    if (modifiedLine.contains("Зона N 03")) modifiedLine = modifiedLine.replace("Зона N 03", "Зона N 03 (Давл. воды)")
                    if (modifiedLine.contains("Зона N 04")) modifiedLine = modifiedLine.replace("Зона N 04", "Зона N 04 (Сигн. БСУ)")
                }

                // сообщение для генератора фров-север
                if (modifiedLine.contains("Зона N 06")) modifiedLine = modifiedLine.replace("Зона N 06", "Зона N 06 (Питание горелки)")
                if (modifiedLine.contains("Зона N 07")) modifiedLine = modifiedLine.replace("Зона N 07", "Зона N 07 (Работа системы)")

                // расстовляем имодзи в тексте
                if (modifiedLine.contains("Зона"))     modifiedLine = modifiedLine.replace("Зона",     "✅ Зона")
                if (modifiedLine.contains("Внимание"))
                    if (modifiedLine.contains("Внимание! Восстановлено 220В!"))
                        modifiedLine = modifiedLine.replace("Внимание! Восстановлено 220В!", "✅ Внимание! Восстановлено 220В!")
                    else
                        modifiedLine = modifiedLine.replace("Внимание", "⚠\uFE0F Внимание") // "Внимание! Пропало напряжение 220В"
                if (modifiedLine.contains("DAVLENIE")) modifiedLine = modifiedLine.replace("DAVLENIE", "⚠\uFE0F Внимание! Давление")
                if (modifiedLine.contains("VODI"))     modifiedLine = modifiedLine.replace("VODI",     "воды")
                if (modifiedLine.contains("GAZA"))     modifiedLine = modifiedLine.replace("GAZA",     "газа")
                if (modifiedLine.contains("ТРЕВОГА"))  modifiedLine = modifiedLine.replace("ТРЕВОГА",  "⚠\uFE0F Внимание")
                if (modifiedLine.contains("Cистема"))  modifiedLine = modifiedLine.replace("Cистема",  "✅ Cистема") // Система на контроле
                if (modifiedLine.contains("Контроль")) modifiedLine = modifiedLine.replace("Контроль", "⚠\uFE0F Контроль") // Контроль снят
                if (modifiedLine.contains("BSU SIGNALING")) modifiedLine = modifiedLine.replace("BSU SIGNALING", "⚠\uFE0F Внимание! БСУ сигнализатор")

                // убираем заголовки в соощениях
                if (modifiedLine.contains("Kotelnaya KTP"))  modifiedLine = modifiedLine.replace("Kotelnaya KTP",  "")
                if (modifiedLine.contains("Kotelnaya 420"))  modifiedLine = modifiedLine.replace("Kotelnaya 420",  "")
                if (modifiedLine.contains("Kotelnaya FROV")) modifiedLine = modifiedLine.replace("Kotelnaya FROV", "")
                if (modifiedLine.contains("Kotelnaya Bols")) modifiedLine = modifiedLine.replace("Kotelnaya Bols", "")

                // Добавляем модифицированную строку (или исходную, если изменений не было)
                if (modifiedLine != "") // пропускаем пустые строки
                    result.add(modifiedLine)
            }
        }
        return result.joinToString("\n")
    }
}