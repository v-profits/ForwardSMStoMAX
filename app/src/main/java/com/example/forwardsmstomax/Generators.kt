package com.example.forwardsmstomax

// ☎️ Списки номеров телефонов тепло-генераторов
val listPhoneGenerators = listOf("+79278015683", "+79278015702", "+79278015726", "+79278015728", "+79278015740") // телефоны генераторов

object Generators {
    //=== Функция для форматирования температуры (T1=+24,5C; -> +24,5°C)
    private fun formatTemperature(line: String): String {
        return line.substringAfter("=").replace(";", "").replace("C", "°C")
    }

    //=== Функция для форматирования температуры в число (T1=+24,5C; -> 24.5; T2=-24,5C; -> -24.5)
    fun parseTemperatureToDouble(line: String): Double {
        val tempString = line.substringAfter("=")
            .replace(";", "")
            .replace("C", "")
            .replace(",", ".")
            .removePrefix("+")

        return tempString.toDoubleOrNull() ?: 0.0
    }

    //=== Функция для преобразования кода состояния зоны в текст
    private fun statusZone(c: Char, index: Int = -1): String {
        val status = when (c) {
            'n' -> {
                if (index in listOf(1, 5, 9)) "220В" // для зоны 5, газогенератора
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
        var fullMessage = false
        var releStatus = ""
        var zoneStatus = ""

        //--- кастомные имена для активных зон сигналов
        val zoneLabels = listOf(
            "",
//            "Напр. 220В   ",
//            "Темп.канала ",
            "Питание горелки ",
            "Работа системы  ",
            "",
            "",
            "Питание горелки ",
            "Работа системы  ",
            "",
            "",
            "Питание горелки ",
            "Работа системы  ",
            "")

        //--- создаем кастомный вид смс для данных Узла телеметрии
        for (line in lines) {
            when {
                // подменяем текст в сообщении отчета для данных Узла телеметрии
                line.startsWith("T1=") -> { // если первая строка - температура помещения, значит это отчет
                    fullMessage = true // показываем, что это полный суточный отчет
                    result.add("Помещ.:      ${formatTemperature(line)}") // добавляем температуру помещения
                }

                // Проверяем, что строка содержит только n, H, L, x, X и имеет длину 4, 8 или 12 символов
                line.matches(Regex("[nHLxX]{4}|[nHLxX]{8}|[nHLxX]{12}")) -> zoneStatus = line
                line.startsWith("RELE=") -> releStatus = line.substringAfter("RELE=")
            }
        }


        //--- Выбираем вид суточного отчета или вид смс-ТРЕВОГИ
        if (fullMessage) { // полный суточный отчет
            fullMessage = false // сбрасываем флаг

            //--- создаем кастомный вид смс для данных Газогенераторов первой очереди
            if (sender in listPhoneGenerators) {
                // --- создаем заголовки Газогенераторов первой очереди
                var temperature = 0.0
                for (line in lines)
                    when {
                        line.startsWith("T2=") -> temperature = parseTemperatureToDouble(line) // температура вен. канала
                    }
                val temp = if (temperature > 30.0) "\uD83D\uDD25" else "❄\uFE0F" // 🔥❄️ добавляем иконку в заголовок генератора

                when(sender) {
                    listPhoneGenerators[0] -> result.add("\n$temp Генератор 1 (75 кВт)")
                    listPhoneGenerators[1] -> result.add("\n$temp Генератор 1 (300 кВт)")
                    listPhoneGenerators[2] -> result.add("\n$temp Генератор 1 (300 кВт)")
                    listPhoneGenerators[3] -> result.add("\n$temp Генератор 1 (300 кВт)")
                    listPhoneGenerators[4] -> result.add("\n$temp Генератор 1 (300 кВт)")
                    else -> result.add("\n➖ Генератор 1")
                }
                //--- 1️⃣ создаем кастомный вид смс для данных Газогенераторов первой очереди
                for (line in lines) {
                    when {
                        line.startsWith("T2=")      -> result.add("Вен.канал:     ${formatTemperature(line)}") // температура вен. канала
                    }
                }
                //--- Обработка зон (например, "nHLxX" -> "Зона1: норма", "Зона2: обрыв" и т. д.)
                if (zoneStatus.isNotEmpty()) {
                    if (sender == listPhoneGenerators[4]) { // Узел 6
                        zoneStatus.forEachIndexed { index, c ->
                            // Функция для преобразования кода состояния зоны в текст
                            val status = statusZone(c, index)
                            if (index < zoneLabels.size && index in listOf(1,2)) result.add("${zoneLabels[index]} – $status") // для выборочных зон
                        }
                    }
                }
            }

            // --- 2️⃣ Газогенераторы второй очереди
            if (sender in listOf(listPhoneGenerators[0], listPhoneGenerators[2], listPhoneGenerators[3])) {
                // --- создаем заголовки Газогенераторов второй очереди
                var temperature = 0.0
                for (line in lines)
                    when {
                        line.startsWith("T3=") -> temperature = parseTemperatureToDouble(line) // температура вен. канала
                    }
                val temp = if (temperature > 30.0) "\uD83D\uDD25" else "❄\uFE0F" // 🔥❄️ добавляем иконку в заголовок генератора
                when(sender) {
                    listPhoneGenerators[0] -> result.add("\n$temp Генератор 2 (IH/AR_100)")
                    listPhoneGenerators[2] -> result.add("\n$temp Генератор 2 (IH/AR_300)")
                    listPhoneGenerators[3] -> result.add("\n$temp Генератор 2 (IH/AR_300)")
                    else -> result.add("\n➖ Генератор 2")
                }

                //--- создаем кастомный вид смс для данных Газогенераторов второй очереди
                for (line in lines) {
                    when {
                        line.startsWith("T3=")      -> result.add("Вен.канал:     ${formatTemperature(line)}") // температура вен. канала
                    }
                }
            }

            // --- 3️⃣ Газогенераторы третьей очереди
            if (sender == listPhoneGenerators[0]) {
                // создаем заголовки Газогенераторов третьей очереди
                var temperature = 0.0
                for (line in lines)
                    when {
                        line.startsWith("T4=") -> temperature = parseTemperatureToDouble(line) // температура вен. канала
                    }
                val temp = if (temperature > 30.0) "\uD83D\uDD25" else "❄\uFE0F" // 🔥❄️ добавляем иконку в заголовок генератора
                result.add("\n$temp Генератор 3 (IH/AR_20)")

                // создаем кастомный вид смс для данных Газогенераторов третьей очереди
                for (line in lines) {
                    when {
                        line.startsWith("T4=")      -> result.add("Вен.канал:     ${formatTemperature(line)}") // температура вен. канала
                    }
                }
            }
        } else { // ТРЕВОГА, сообщение с тревогами и предупреждениями по сработкам датчиков зон
            result.clear()
            //--- создаем кастомный вид смс
            for (line in lines) { // проходим по строкам
                var modifiedLine = line // Создаем копию строки для модификации

                // сообщение для генераторов
                if (modifiedLine.contains("Зона N 02")) modifiedLine = modifiedLine.replace("Зона N 02", "Зона N 02 (Генератор-1. Напр. 220В)")
                if (modifiedLine.contains("Зона N 03")) modifiedLine = modifiedLine.replace("Зона N 03", "Зона N 03 (Генератор-1. Темп. вен.канала)")
                if (modifiedLine.contains("Зона N 06")) modifiedLine = modifiedLine.replace("Зона N 06", "Зона N 06 (Генератор-2. Напр. 220В)")
                if (modifiedLine.contains("Зона N 07")) modifiedLine = modifiedLine.replace("Зона N 07", "Зона N 07 (Генератор-2. Темп. вен.канала)")
                if (modifiedLine.contains("Зона N 10")) modifiedLine = modifiedLine.replace("Зона N 10", "Зона N 10 (Генератор-3. Напр. 220В)")
                if (modifiedLine.contains("Зона N 11")) modifiedLine = modifiedLine.replace("Зона N 11", "Зона N 11 (Генератор-3. Темп. вен.канала)")

                // расстовляем имодзи в тексте
                if (modifiedLine.contains("Зона"))     modifiedLine = modifiedLine.replace("Зона",     "✅ Зона")
                if (modifiedLine.contains("Внимание"))
                    if (modifiedLine.contains("Внимание! Восстановлено 220В!"))
                        modifiedLine = modifiedLine.replace("Внимание! Восстановлено 220В!", "✅ Внимание! Восстановлено 220В!")
                    else
                        modifiedLine = modifiedLine.replace("Внимание", "⚠\uFE0F Внимание") // "Внимание! Пропало напряжение 220В"
                if (modifiedLine.contains("ТРЕВОГА"))  modifiedLine = modifiedLine.replace("ТРЕВОГА",  "⚠\uFE0F Внимание")
                if (modifiedLine.contains("Cистема"))  modifiedLine = modifiedLine.replace("Cистема",  "✅ Cистема") // Система на контроле
                if (modifiedLine.contains("Контроль")) modifiedLine = modifiedLine.replace("Контроль", "⚠\uFE0F Контроль") // Контроль снят

                // убираем заголовки в соощениях
                if (modifiedLine.contains("Node 2")) modifiedLine = modifiedLine.replace("Node 2", "")
                if (modifiedLine.contains("Node 3")) modifiedLine = modifiedLine.replace("Node 3", "")
                if (modifiedLine.contains("Node 4")) modifiedLine = modifiedLine.replace("Node 4", "")
                if (modifiedLine.contains("Node 5")) modifiedLine = modifiedLine.replace("Node 5", "")
                if (modifiedLine.contains("Node 6")) modifiedLine = modifiedLine.replace("Node 6", "")
                if (modifiedLine.contains("Node 6-")) modifiedLine = modifiedLine.replace("Node 6-", "")

                // Добавляем модифицированную строку (или исходную, если изменений не было)
                if (modifiedLine != "") // пропускаем пустые строки
                    result.add(modifiedLine)
            }
        }
        return result.joinToString("\n")
    }
}