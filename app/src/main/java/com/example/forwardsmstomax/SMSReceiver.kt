
package com.example.forwardsmstomax

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telephony.SmsMessage
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ВНЕСЁННЫЕ ИСПРАВЛЕНИЯ (см. пояснения в коде по месту):
 *
 * 1) URL для GREEN-API теперь строится из реального apiUrl инстанса
 *    (у вас это https://3100.api.green-api.com), а не из жёстко
 *    зашитого https://api.green-api.com. Это, вероятнее всего,
 *    и была причина "вроде отправляет, но не доходит".
 *
 * 2) Все "общие" изменяемые структуры (буфер частей SMS, таймеры,
 *    флаг очереди) перенесены в companion object. BroadcastReceiver
 *    пересоздаётся системой при каждом входящем broadcast — раньше
 *    состояние экземпляра терялось между частями одного длинного SMS,
 *    что могло приводить к дублям/потере кусков сообщения.
 *
 * 3) Добавлена дедупликация: если система (или прошивка) повторно
 *    доставит один и тот же SMS_RECEIVED (частый баг на некоторых
 *    устройствах), повторное сообщение с тем же отправителем и текстом
 *    в течение короткого окна будет проигнорировано.
 *
 * 4) HTTP-код 200 больше не считается гарантией успеха. Тело ответа
 *    парсится через JSONObject, и "успех" — это только "принято в
 *    очередь GREEN-API" (наличие idMessage). Реальную доставку
 *    подтверждает статус, который выясняется отдельным опросом
 *    receiveNotification/deleteNotification (см. ниже) — сама
 *    документация GREEN-API прямо говорит, что код 200 может прийти
 *    и при сообщении, которое реально не будет доставлено.
 *
 * 5) Добавлен периодический опрос receiveNotification, который ловит
 *    вебхук-события outgoingMessageStatus (delivered/read/failed) по
 *    уже отправленным сообщениям. Если статус "failed" — сообщение
 *    автоматически кладётся в вашу существующую очередь на повтор.
 *
 * ВАЖНО: если apiUrl для инстанса НЕ подчиняется схеме
 * "https://{первые 4 цифры idInstance}.api.green-api.com" (например,
 * GREEN-API поменяет схему в будущем, или у вас несколько инстансов
 * в разных кластерах с нестандартными доменами) — тогда правильнее
 * всего добавить в БД (DatabaseHelper/MaxConfig) отдельное поле
 * apiUrl и брать хост оттуда, а не вычислять. Ниже это отмечено
 * TODO в функции resolveApiHost().
 */
class SMSReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "aaa"

        // Буфер частей длинных SMS. Общий на процесс (а не на конкретный
        // экземпляр BroadcastReceiver) — переживает пересоздание ресивера.
        private val messageBuffer = mutableMapOf<String, MutableList<String>>()
        private val pendingRunnables = mutableMapOf<String, Runnable>()
        private val handler = Handler(Looper.getMainLooper())

        private var isProcessingQueue = false
        private val queueCheckHandler = Handler(Looper.getMainLooper())
        private var queueCheckRunnable: Runnable? = null

        // --- Защита от дублей входящих SMS ---
        // Ключ: "sender::messageBody", значение: время получения (мс)
        private val recentlyProcessed = mutableMapOf<String, Long>()
        private const val DEDUP_WINDOW_MS = 15_000L // 15 секунд

        // --- Опрос статусов отправленных сообщений ---
        private val notificationPollHandler = Handler(Looper.getMainLooper())
        private var notificationPollRunnable: Runnable? = null
        private const val NOTIFICATION_POLL_INTERVAL_MS = 2 * 60 * 1000L // 2 минуты

        // Информация о сообщениях, ожидающих подтверждения статуса, чтобы
        // при "failed" можно было переотправить их в очередь.
        // Ключ — idMessage, который вернул GREEN-API при отправке.
        private data class PendingStatusCheck(
            val chatId: String,
            val idInstance: String,
            val apiToken: String,
            val formattedText: String
        )
        private val pendingStatusChecks = mutableMapOf<String, PendingStatusCheck>()

        // Коды, которые считаем временными сбоями инфраструктуры GREEN-API —
        // имеет смысл быстро повторить запрос, а не ждать полные 2 минуты.
        private val TRANSIENT_HTTP_CODES = setOf(502, 503, 504)
        private val TRANSIENT_RETRY_DELAYS_MS = listOf(5_000L, 10_000L, 15_000L) // до 3 быстрых попыток

        /**
         * Держит CPU разбуженным на время сетевого вызова. Не гарантирует,
         * что процесс не будет убит системой (для этого нужен foreground
         * Service / WorkManager), но снижает риск, что Doze/сон CPU
         * оборвёт запрос на середине. ВАЖНО: требует разрешение
         * android.permission.WAKE_LOCK в AndroidManifest.xml.
         */
        private fun withWakeLock(context: Context, tag: String, timeoutMs: Long, block: () -> Unit) {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForwardSmsToMax:$tag")
                wakeLock?.acquire(timeoutMs)
                Log.d(TAG, "🔒 WakeLock захвачен ($tag), таймаут $timeoutMs мс")
                block()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Исключение внутри withWakeLock ($tag): ${e.message}", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                        Log.d(TAG, "🔓 WakeLock освобождён ($tag)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка при освобождении WakeLock ($tag): ${e.message}", e)
                }
            }
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as? Array<*>
                if (pdus.isNullOrEmpty()) {
                    Log.e(TAG, "Ошибка: pdus пустой или null.")
                    return
                }

                for (pdu in pdus) {
                    val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                    val sender = sms.originatingAddress ?: "Неизвестный номер"
                    val messageBody = sms.messageBody ?: "Нет текста"

                    // --- Защита от дублей ---
                    val dedupKey = "$sender::$messageBody"
                    val now = System.currentTimeMillis()
                    val lastSeen = recentlyProcessed[dedupKey]
                    if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
                        Log.w(TAG, "⚠️ Пропускаем дубликат SMS от $sender (получено повторно за ${now - lastSeen} мс)")
                        continue
                    }
                    recentlyProcessed[dedupKey] = now
                    // лёгкая чистка старых записей, чтобы карта не росла бесконечно
                    if (recentlyProcessed.size > 200) {
                        val cutoff = now - DEDUP_WINDOW_MS
                        recentlyProcessed.entries.removeAll { it.value < cutoff }
                    }

                    // Получаем имя контакта, если номер есть в адресной книге
                    var contactName = getContactName(context, sender)
                    if (contactName == "bot") contactName = "Узел 3 (вор. 36-37)" // 🟢🟢🟢🟢🟢🟢
                    val senderLabel = if (!contactName.isNullOrEmpty()) "$sender\n➖ $contactName ➖" else sender

                    // Добавляем часть сообщения в буфер (для длинных SMS)
                    messageBuffer.getOrPut(senderLabel) { mutableListOf() }.add(messageBody)

                    // Отменяем предыдущий таймер, чтобы перезапустить
                    pendingRunnables[senderLabel]?.let {
                        handler.removeCallbacks(it)
                    }

                    // Запускаем новый таймер на 3 секунды после последней части
                    val runnable = Runnable {
                        sendCompleteMessage(context, sender, senderLabel)
                        pendingRunnables.remove(senderLabel)
                    }
                    pendingRunnables[senderLabel] = runnable
                    handler.postDelayed(runnable, 3000)
                }
            } else {
                Log.e(TAG, "Ошибка: Bundle пустой.")
            }
        }
    }

    // Получает имя контакта по номеру телефона из адресной книги
    private fun getContactName(context: Context, rawNumber: String): String? {
        val normalizedNumber = Functions.normalizePhoneNumber(rawNumber)
        val contentResolver = context.contentResolver
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalizedNumber)
        )
        val cursor = contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )
        var contactName: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                contactName = it.getString(
                    it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                )
            }
        }
        return contactName
    }

    // Собирает полное сообщение из частей и отправляет
    private fun sendCompleteMessage(context: Context, sender: String, senderLabel: String) {
        val fullMessage = messageBuffer[senderLabel]?.joinToString("") ?: ""
        if (fullMessage.isNotEmpty()) {
            processAndSendMessage(context, sender, fullMessage, senderLabel)
            messageBuffer.remove(senderLabel)
        }
    }

    // Основная логика: форматирование, отправка, работа с очередью
    private fun processAndSendMessage(context: Context, sender: String, message: String, senderLabel: String) {

        val dbHelper = DatabaseHelper(context)
        val phone = Functions.normalizePhoneNumber(sender)

        // Получаем все ID фильтров, связанных с этим номером телефона
        val userIds: List<Long> = dbHelper.getUserIdsByPhoneNumber(phone)

        // Форматируем текст сообщения в зависимости от отправителя
        val formattedText = if (sender in listPhoneBoilerRooms) {
            "📬 $senderLabel\n${BoilerRooms.changeText(sender, message)}"
        } else if (sender in listPhoneGenerators) {
            "📬 $senderLabel\n${Generators.changeText(sender, message)}"
        } else {
            "📬 $senderLabel\n$message"
        }

        // Отправляем сообщение для каждого активного фильтра
        for (userId in userIds) {
            val userData = dbHelper.getTelegramDataByUserId(userId)
            val isActive = userData?.third == 1L

            if (isActive) {
                val maxConfig = dbHelper.getMaxConfigByUserId(userId)
                if (maxConfig != null) {
                    val result = sendToMax(
                        context = context,
                        message = formattedText,
                        chatId = maxConfig.chatId,
                        idInstance = maxConfig.idInstance,
                        apiToken = maxConfig.apiToken
                    )

                    when (result) {
                        is SendResult.Success -> {
                            Log.d(TAG, "✅ Принято GREEN-API в очередь: ${maxConfig.chatId}, idMessage=${result.idMessage}")
                            // Запоминаем, чтобы позже проверить реальный статус доставки
                            if (result.idMessage != null) {
                                pendingStatusChecks[result.idMessage] = PendingStatusCheck(
                                    chatId = maxConfig.chatId,
                                    idInstance = maxConfig.idInstance,
                                    apiToken = maxConfig.apiToken,
                                    formattedText = formattedText
                                )
                            }
                            ensureNotificationPollingStarted(context)
                        }
                        is SendResult.Failed -> {
                            Log.d(TAG, "💾 Сохраняем в очередь (не удалось отправить: ${result.reason})")
                            dbHelper.saveMessageToQueue(
                                sender = senderLabel,
                                messageText = formattedText,
                                chatId = maxConfig.chatId,
                                idInstance = maxConfig.idInstance,
                                apiToken = maxConfig.apiToken
                            )
                        }
                    }
                }
            }
        }

        // Запускаем обработчик очереди через 1 минуту (не сразу),
        // чтобы новое SMS и отложенные сообщения не шли подряд
        handler.postDelayed({
            startQueueProcessor(context)
        }, 60 * 1000)
    }

    private sealed class SendResult {
        data class Success(val idMessage: String?) : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    /**
     * Определяет хост API для конкретного инстанса.
     *
     * GREEN-API выдаёт на новых инстансах индивидуальный apiUrl вида
     * https://{кластер}.api.green-api.com, где {кластер} — как правило
     * первые 4 цифры idInstance (например, для 3100631802 это
     * https://3100.api.green-api.com — как в вашем личном кабинете).
     *
     * TODO: если у вас есть несколько инстансов и хотя бы один не
     * подчиняется этой схеме — добавьте колонку apiUrl в таблицу с
     * MAX-конфигом и берите хост оттуда напрямую, вместо вычисления.
     */
    private fun resolveApiHost(idInstance: String): String {
        val cluster = idInstance.take(4)
        return "https://$cluster.api.green-api.com"
    }

    /**
     * 📤 Отправка сообщения в MAX через GREEN-API.
     * Возвращает SendResult.Success только если ответ реально содержит
     * idMessage (т.е. GREEN-API приняло сообщение в очередь на отправку).
     * Это НЕ означает подтверждённую доставку — за неё отвечает
     * pollOutgoingStatuses().
     */
    private fun sendToMax(
        context: Context,
        message: String,
        chatId: String,
        idInstance: String,
        apiToken: String
    ): SendResult {
        val host = resolveApiHost(idInstance)
        val url = "$host/waInstance$idInstance/sendMessage/$apiToken"
        Log.d(TAG, "➡️ sendToMax: host=$host chatId=$chatId idInstance=$idInstance")

        val jsonBody = JSONObject().apply {
            put("chatId", chatId)
            put("message", message)
        }.toString()

        var result: SendResult = SendResult.Failed("не выполнено")

        withWakeLock(context, "send", 20_000L) {
            result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    Log.d(TAG, "🌐 sendToMax: открываю соединение $url")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.doOutput = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000

                    connection.outputStream.use { os ->
                        os.write(jsonBody.toByteArray(Charsets.UTF_8))
                    }

                    val responseCode = connection.responseCode
                    Log.d(TAG, "📥 sendToMax: получен код ответа $responseCode")
                    val bodyText = if (responseCode in 200..299) {
                        connection.inputStream.bufferedReader().readText()
                    } else {
                        connection.errorStream?.bufferedReader()?.readText() ?: ""
                    }

                    if (responseCode !in 200..299) {
                        Log.e(TAG, "❌ Ошибка отправки. Код: $responseCode, $bodyText")
                        return@runBlocking SendResult.Failed("HTTP $responseCode: $bodyText")
                    }

                    val idMessage = try {
                        JSONObject(bodyText).optString("idMessage", "").ifEmpty { null }
                    } catch (e: Exception) {
                        Log.e(TAG, "⚠️ Не удалось распарсить ответ GREEN-API: $bodyText")
                        null
                    }

                    if (idMessage == null) {
                        // Код 200, но тело не похоже на успешное — считаем провалом,
                        // т.к. GREEN-API документированно может вернуть 200 без
                        // реальной отправки (например, при сервисном чате).
                        Log.e(TAG, "❌ Код 200, но нет idMessage в ответе: $bodyText")
                        SendResult.Failed("200 без idMessage: $bodyText")
                    } else {
                        SendResult.Success(idMessage)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Исключение при отправке: ${e.message}", e)
                    SendResult.Failed(e.message ?: "unknown exception")
                }
            }
        } // конец withWakeLock

        Log.d(TAG, "🏁 sendToMax завершён, результат: $result")
        return result
    }

    /**
     * Опрашивает receiveNotification, чтобы получить реальные статусы
     * ранее отправленных сообщений (outgoingMessageStatus: sent /
     * delivered / read / failed / noAccount и т.д.).
     * Каждое полученное уведомление обязательно подтверждается через
     * deleteNotification — иначе оно будет возвращаться повторно.
     */
    private fun pollOutgoingStatuses(context: Context) {
        Log.d(TAG, "🔍 pollOutgoingStatuses: старт, pendingStatusChecks.size=${pendingStatusChecks.size}, ключи=${pendingStatusChecks.keys}")

        if (pendingStatusChecks.isEmpty()) {
            Log.d(TAG, "🔍 pollOutgoingStatuses: нечего проверять, выходим")
            return
        }

        // Берём хост/idInstance/token из любой из ожидающих записей —
        // в рамках одного инстанса они одинаковы для receiveNotification.
        val sample = pendingStatusChecks.values.firstOrNull() ?: return
        val host = resolveApiHost(sample.idInstance)
        Log.d(TAG, "🔍 pollOutgoingStatuses: host=$host idInstance=${sample.idInstance}")

        withWakeLock(context, "poll", 60_000L) {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    var keepPolling = true
                    var safetyCounter = 0
                    var transientRetryIndex = 0
                    while (keepPolling && safetyCounter < 20) {
                        safetyCounter++
                        val url = "$host/waInstance${sample.idInstance}/receiveNotification/${sample.apiToken}"
                        Log.d(TAG, "🌐 pollOutgoingStatuses: запрос #$safetyCounter -> $url")
                        val connection = URL(url).openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000

                        val responseCode = connection.responseCode
                        Log.d(TAG, "📥 pollOutgoingStatuses: код ответа $responseCode")

                        if (responseCode in TRANSIENT_HTTP_CODES) {
                            val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
                            if (transientRetryIndex < TRANSIENT_RETRY_DELAYS_MS.size) {
                                val delayMs = TRANSIENT_RETRY_DELAYS_MS[transientRetryIndex]
                                transientRetryIndex++
                                Log.w(TAG, "⚠️ Временная ошибка $responseCode (тело: $err), быстрый повтор #$transientRetryIndex через ${delayMs / 1000} сек")
                                kotlinx.coroutines.delay(delayMs)
                                continue // пробуем снова, не тратя итерацию основного цикла впустую
                            } else {
                                Log.e(TAG, "❌ Ошибка $responseCode повторилась ${TRANSIENT_RETRY_DELAYS_MS.size} раза подряд, откладываем до планового опроса через ${NOTIFICATION_POLL_INTERVAL_MS / 1000} сек")
                                keepPolling = false
                                continue
                            }
                        }

                        // сбрасываем счётчик быстрых повторов после любого не-502/503/504 ответа
                        transientRetryIndex = 0

                        if (responseCode != 200) {
                            val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
                            Log.e(TAG, "❌ receiveNotification вернул код $responseCode, тело: $err")
                            keepPolling = false
                            continue
                        }

                        val bodyText = connection.inputStream.bufferedReader().readText()
                        Log.d(TAG, "📄 pollOutgoingStatuses: тело ответа: $bodyText")
                        if (bodyText.isBlank() || bodyText == "null") {
                            Log.d(TAG, "📭 pollOutgoingStatuses: уведомлений в очереди больше нет")
                            keepPolling = false
                            continue
                        }

                        val notification = JSONObject(bodyText)
                        val receiptId = notification.optLong("receiptId", -1L)
                        val body = notification.optJSONObject("body")
                        val typeWebhook = body?.optString("typeWebhook", "")
                        Log.d(TAG, "🏷️ pollOutgoingStatuses: receiptId=$receiptId typeWebhook=$typeWebhook")

                        if (typeWebhook == "outgoingMessageStatus") {
                            val idMessage = body.optString("idMessage", "")
                            val status = body.optString("status", "")
                            Log.d(TAG, "🏷️ outgoingMessageStatus: idMessage=$idMessage status=$status (ожидаем: ${pendingStatusChecks.containsKey(idMessage)})")
                            val pending = pendingStatusChecks[idMessage]

                            if (pending != null) {
                                when (status) {
                                    "delivered", "read", "sent" -> {
                                        Log.d(TAG, "✅ Подтверждена доставка $idMessage, статус=$status")
                                        pendingStatusChecks.remove(idMessage)
                                    }
                                    "failed", "noAccount" -> {
                                        Log.e(TAG, "❌ Сообщение $idMessage реально не доставлено (статус=$status), кладём в очередь на повтор")
                                        val dbHelper = DatabaseHelper(context)
                                        dbHelper.saveMessageToQueue(
                                            sender = "(повтор после failed статуса)",
                                            messageText = pending.formattedText,
                                            chatId = pending.chatId,
                                            idInstance = pending.idInstance,
                                            apiToken = pending.apiToken
                                        )
                                        pendingStatusChecks.remove(idMessage)
                                    }
                                    else -> {
                                        Log.d(TAG, "ℹ️ Промежуточный статус $idMessage: $status")
                                    }
                                }
                            } else {
                                Log.d(TAG, "ℹ️ Статус пришёл для idMessage, который мы не ждали (отправлено из другого места/сессии): $idMessage")
                            }
                        } else if (!typeWebhook.isNullOrEmpty()) {
                            Log.d(TAG, "ℹ️ Пропускаем уведомление другого типа: $typeWebhook")
                        }

                        // Обязательно подтверждаем получение уведомления
                        if (receiptId >= 0) {
                            val deleteUrl = "$host/waInstance${sample.idInstance}/deleteNotification/${sample.apiToken}/$receiptId"
                            val deleteConnection = URL(deleteUrl).openConnection() as HttpURLConnection
                            deleteConnection.requestMethod = "DELETE"
                            deleteConnection.connectTimeout = 10000
                            deleteConnection.readTimeout = 10000
                            val deleteCode = deleteConnection.responseCode
                            Log.d(TAG, "🗑️ deleteNotification receiptId=$receiptId код=$deleteCode")
                        }
                    }
                    Log.d(TAG, "🔍 pollOutgoingStatuses: цикл завершён (итераций: $safetyCounter)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Исключение при опросе статусов: ${e.message}", e)
                }
            }
        } // конец withWakeLock
        Log.d(TAG, "🏁 pollOutgoingStatuses: завершено, осталось в ожидании: ${pendingStatusChecks.size}")
    }

    private fun ensureNotificationPollingStarted(context: Context) {
        Log.d(TAG, "🕐 ensureNotificationPollingStarted вызван (уже запущен ранее: ${notificationPollRunnable != null})")
        if (notificationPollRunnable != null) return
        notificationPollRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "⏰ Сработал плановый опрос статусов (каждые ${NOTIFICATION_POLL_INTERVAL_MS / 1000} сек)")
                pollOutgoingStatuses(context)
                notificationPollHandler.postDelayed(this, NOTIFICATION_POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "🕐 Планирую первый опрос через 20 сек и далее каждые ${NOTIFICATION_POLL_INTERVAL_MS / 1000} сек")
        // Первая проверка — через 20 секунд после отправки (быстрая обратная связь),
        // дальше — по расписанию каждые NOTIFICATION_POLL_INTERVAL_MS
        notificationPollHandler.postDelayed({
            Log.d(TAG, "⏰ Сработал первый быстрый опрос (20 сек после отправки)")
            pollOutgoingStatuses(context)
        }, 20_000L)
        notificationPollHandler.postDelayed(notificationPollRunnable!!, NOTIFICATION_POLL_INTERVAL_MS)
    }

    /**
     * Обработчик очереди отложенных сообщений
     * - Отправляет сообщения по одному (FIFO)
     * - Между сообщениями пауза 30 секунд
     * - Если какое-то сообщение не отправилось — останавливаем всю отправку
     * - Проверяем очередь каждые 10 минут (через Handler)
     */
    private fun startQueueProcessor(context: Context) {
        if (isProcessingQueue) {
            Log.d(TAG, "Обработчик очереди уже запущен, пропускаем")
            return
        }
        isProcessingQueue = true
        processQueue(context)
    }

    private fun processQueue(context: Context) {
        val dbHelper = DatabaseHelper(context)
        val pendingMessages = dbHelper.getAllQueuedMessages()

        if (pendingMessages.isEmpty()) {
            Log.d(TAG, "📭 Очередь пуста, обработчик останавливается")
            isProcessingQueue = false
            queueCheckRunnable?.let { queueCheckHandler.removeCallbacks(it) }
            queueCheckRunnable = null
            return
        }

        Log.d(TAG, "📨 Найдено ${pendingMessages.size} сообщений в очереди")

        Thread {
            var shouldContinue = true

            for ((index, message) in pendingMessages.withIndex()) {
                if (!shouldContinue) break

                val messageWithTime = message.getMessageWithTimestamp()

                Log.d(TAG, "Отправка сообщения #${message.id} (попытка ${message.retryCount + 1})")

                val result = sendToMax(
                    context = context,
                    message = messageWithTime,
                    chatId = message.chatId,
                    idInstance = message.idInstance,
                    apiToken = message.apiToken
                )

                when (result) {
                    is SendResult.Success -> {
                        dbHelper.removeMessageFromQueue(message.id)
                        Log.d(TAG, "✅ Сообщение #${message.id} принято GREEN-API [${message.getFormattedTime()}]")
                        if (result.idMessage != null) {
                            pendingStatusChecks[result.idMessage] = PendingStatusCheck(
                                chatId = message.chatId,
                                idInstance = message.idInstance,
                                apiToken = message.apiToken,
                                formattedText = messageWithTime
                            )
                            ensureNotificationPollingStarted(context)
                        }

                        if (index < pendingMessages.size - 1) {
                            Thread.sleep(30000)
                        }
                    }
                    is SendResult.Failed -> {
                        dbHelper.incrementRetryCount(message.id)
                        Log.e(TAG, "❌ Ошибка отправки сообщения #${message.id} (${result.reason}), останавливаем отправку очереди")
                        shouldContinue = false
                    }
                }
            }

            queueCheckRunnable = Runnable {
                isProcessingQueue = false
                processQueue(context)
            }
            queueCheckHandler.postDelayed(queueCheckRunnable!!, 10 * 60 * 1000)

            isProcessingQueue = false
        }.start()
    }
}












//package com.example.forwardsmstomax
//
//import android.annotation.SuppressLint
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.net.Uri
//import android.os.Handler
//import android.os.Looper
//import android.provider.ContactsContract
//import android.telephony.SmsMessage
//import android.util.Log
//import java.net.HttpURLConnection
//import java.net.URL
//
//class SMSReceiver : BroadcastReceiver() {
//
//    // Буфер для хранения частей длинных SMS (мультичастные сообщения)
//    private val messageBuffer = mutableMapOf<String, MutableList<String>>()
//
//    // Handler для отложенной отправки (ждём все части сообщения)
//    private val handler = Handler(Looper.getMainLooper())
//
//    // Карта для хранения Runnable, чтобы отменить их при необходимости
//    private val pendingRunnables = mutableMapOf<String, Runnable>()
//
//    // Флаг, чтобы не запускать несколько обработчиков очереди одновременно
//    private var isProcessingQueue = false
//
//    // Handler для периодической проверки очереди (каждые 10 минут)
//    private val queueCheckHandler = Handler(Looper.getMainLooper())
//
//    // Runnable для периодической проверки очереди
//    private var queueCheckRunnable: Runnable? = null
//
//    @SuppressLint("UnspecifiedImmutableFlag")
//    override fun onReceive(context: Context, intent: Intent?) {
//        if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
//            val bundle = intent.extras
//            if (bundle != null) {
//                val pdus = bundle.get("pdus") as? Array<*>
//                if (pdus.isNullOrEmpty()) {
//                    Log.e("aaa", "Ошибка: pdus пустой или null.")
//                    return
//                }
//
//                for (pdu in pdus) {
//                    val sms = SmsMessage.createFromPdu(pdu as ByteArray)
//                    val sender = sms.originatingAddress ?: "Неизвестный номер"
//                    val messageBody = sms.messageBody ?: "Нет текста"
//
//                    // Получаем имя контакта, если номер есть в адресной книге
//                    var contactName = getContactName(context, sender)
//                    if (contactName == "bot") contactName = "Узел 3 (вор. 36-37)" // 🟢🟢🟢🟢🟢🟢
//                    val senderLabel = if (!contactName.isNullOrEmpty()) "$sender\n➖ $contactName ➖" else sender
//
//                    // Добавляем часть сообщения в буфер (для длинных SMS)
//                    messageBuffer.getOrPut(senderLabel) { mutableListOf() }.add(messageBody)
//
//                    // Отменяем предыдущий таймер, чтобы перезапустить
//                    pendingRunnables[senderLabel]?.let {
//                        handler.removeCallbacks(it)
//                    }
//
//                    // Запускаем новый таймер на 3 секунды после последней части
//                    val runnable = Runnable {
//                        sendCompleteMessage(context, sender, senderLabel)
//                        pendingRunnables.remove(senderLabel)
//                    }
//                    pendingRunnables[senderLabel] = runnable
//                    handler.postDelayed(runnable, 3000)
//                }
//            } else {
//                Log.e("SMSReceiver", "Ошибка: Bundle пустой.")
//            }
//        }
//    }
//
//    // Получает имя контакта по номеру телефона из адресной книги
//    private fun getContactName(context: Context, rawNumber: String): String? {
//        val normalizedNumber = Functions.normalizePhoneNumber(rawNumber)
//        val contentResolver = context.contentResolver
//        val uri = Uri.withAppendedPath(
//            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
//            Uri.encode(normalizedNumber)
//        )
//        val cursor = contentResolver.query(
//            uri,
//            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
//            null,
//            null,
//            null
//        )
//        var contactName: String? = null
//        cursor?.use {
//            if (it.moveToFirst()) {
//                contactName = it.getString(
//                    it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
//                )
//            }
//        }
//        return contactName
//    }
//
//    // Собирает полное сообщение из частей и отправляет
//    private fun sendCompleteMessage(context: Context, sender: String, senderLabel: String) {
//        val fullMessage = messageBuffer[senderLabel]?.joinToString("") ?: ""
//        if (fullMessage.isNotEmpty()) {
//            processAndSendMessage(context, sender, fullMessage, senderLabel)
//            messageBuffer.remove(senderLabel)
//        }
//    }
//
//    // Основная логика: форматирование, отправка, работа с очередью
//    private fun processAndSendMessage(context: Context, sender: String, message: String, senderLabel: String) {
//
//        val dbHelper = DatabaseHelper(context)
//        val phone = Functions.normalizePhoneNumber(sender)
//
//        // Получаем все ID фильтров, связанных с этим номером телефона
//        val userIds: List<Long> = dbHelper.getUserIdsByPhoneNumber(phone)
//
//        // Форматируем текст сообщения в зависимости от отправителя
//        val formattedText = if (sender in listPhoneBoilerRooms) {
//            "📬 $senderLabel\n${BoilerRooms.changeText(sender, message)}"
//        } else if (sender in listPhoneGenerators) {
//            "📬 $senderLabel\n${Generators.changeText(sender, message)}"
//        } else {
//            "📬 $senderLabel\n$message"
//        }
//
//        // Отправляем сообщение для каждого активного фильтра
//        for (userId in userIds) {
//            // Проверяем, активен ли фильтр
//            val userData = dbHelper.getTelegramDataByUserId(userId)
//            val isActive = userData?.third == 1L
//
//            if (isActive) {
//                val maxConfig = dbHelper.getMaxConfigByUserId(userId)
//                if (maxConfig != null) {
//                    val success = sendToMax(
//                        context = context,
//                        message = formattedText,
//                        chatId = maxConfig.chatId,
//                        idInstance = maxConfig.idInstance,
//                        apiToken = maxConfig.apiToken
//                    )
//
//
//                    if (success) {
//                        Log.d("aaa", "✅ SMS отправлено в MAX: ${maxConfig.chatId}")
//                    } else {
//                        // Если отправка не удалась — сохраняем в очередь
//                        Log.d("aaa", "💾 Сохраняем в очередь (не удалось отправить)")
//                        dbHelper.saveMessageToQueue(
//                            sender = senderLabel,
//                            messageText = formattedText,
//                            chatId = maxConfig.chatId,
//                            idInstance = maxConfig.idInstance,
//                            apiToken = maxConfig.apiToken
//                        )
//                    }
//                }
//            }
//        }
//
//        // После обработки текущего SMS проверяем и отправляем отложенные сообщения из очереди
////        startQueueProcessor(context)
//
//        // Запускаем обработчик очереди через 1 минуту (не сразу)
//        // Чтобы новое SMS и отложенные сообщения не шли подряд
//        handler.postDelayed({
//            startQueueProcessor(context)
//        }, 60 * 1000)  // 60 секунд
//    }
//
//    /**
//     * 📤 Отправка сообщения в MAX через GREEN-API
//     * @return true если отправка успешна (код ответа 200), false в противном случае
//     */
//    private fun sendToMax(
//        context: Context,
//        message: String,
//        chatId: String,
//        idInstance: String,
//        apiToken: String
//    ): Boolean {
//        val url = "https://api.green-api.com/waInstance$idInstance/sendMessage/$apiToken"
//
//        // Экранируем спецсимволы для JSON
//        val escapedText = message
//            .replace("\\", "\\\\")
//            .replace("\"", "\\\"")
//            .replace("\n", "\\n")
//            .replace("\r", "\\r")
//            .replace("\t", "\\t")
//
//        val jsonBody = """
//        {
//            "chatId": "$chatId",
//            "message": "$escapedText"
//        }
//    """.trimIndent()
//
//        // runBlocking заставляет функцию ждать завершения запроса
//        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
//            try {
//                val connection = URL(url).openConnection() as HttpURLConnection
//                connection.requestMethod = "POST"
//                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
//                connection.doOutput = true
//                connection.connectTimeout = 15000      // 15 секунд на подключение
//                connection.readTimeout = 15000         // 15 секунд на чтение ответа
//
//                // Отправляем JSON
//                connection.outputStream.use { os ->
//                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
//                }
//
//                val responseCode = connection.responseCode
//                val success = responseCode == 200
//
//                if (success) {
//                    Log.d("aaa", "✅ Сообщение отправлено в MAX: $chatId")
//                } else {
//                    val errorText = connection.errorStream?.bufferedReader()?.readText() ?: ""
//                    Log.e("aaa", "❌ Ошибка отправки. Код: $responseCode, $errorText")
//                }
//
//                success
//            } catch (e: Exception) {
//                Log.e("aaa", "❌ Исключение при отправке: ${e.message}")
//                false
//            }
//        }
//    }
//
//    /**
//     * Обработчик очереди отложенных сообщений
//     * - Отправляет сообщения по одному (FIFO)
//     * - Между сообщениями пауза 10 секунд
//     * - Если какое-то сообщение не отправилось — останавливаем всю отправку
//     * - Проверяем очередь каждые 10 минут (через Handler)
//     */
//    private fun startQueueProcessor(context: Context) {
//        // Если уже обрабатываем очередь — не запускаем новый
//        if (isProcessingQueue) {
//            Log.d("aaa", "Обработчик очереди уже запущен, пропускаем")
//            return // Выходим, если уже обрабатываем очередь
//        }
//
//        isProcessingQueue = true  // Устанавливаем флаг, чтобы не запустить второй обработчик
//        processQueue(context) // Запускаем обработчик
//    }
//
//    private fun processQueue(context: Context) {
//        val dbHelper = DatabaseHelper(context)
//        val pendingMessages = dbHelper.getAllQueuedMessages()
//
//        if (pendingMessages.isEmpty()) {
//            // Очередь пуста — останавливаем обработчик
//            Log.d("aaa", "📭 Очередь пуста, обработчик останавливается")
//            isProcessingQueue = false
//            // Отменяем периодическую проверку
//            queueCheckRunnable?.let { queueCheckHandler.removeCallbacks(it) }
//            queueCheckRunnable = null
//            return
//        }
//
//        Log.d("aaa", "📨 Найдено ${pendingMessages.size} сообщений в очереди")
//
//        // Запускаем отправку в отдельном потоке, чтобы не блокировать приём SMS
//        Thread {
//            var shouldContinue = true
//
//            for ((index, message) in pendingMessages.withIndex()) {
//                if (!shouldContinue) break
//
//                // Форматируем сообщение с добавлением времени получения исходного SMS
//                val messageWithTime = message.getMessageWithTimestamp()
//
//                Log.d("aaa", "Отправка сообщения #${message.id} (попытка ${message.retryCount + 1})")
//
//                // Пытаемся отправить
//                val success = sendToMax(
//                    context = context,
//                    message = messageWithTime,
//                    chatId = message.chatId,
//                    idInstance = message.idInstance,
//                    apiToken = message.apiToken
//                )
//
//                if (success) {
//                    // Успешно — удаляем из очереди
//                    dbHelper.removeMessageFromQueue(message.id)
//                    Log.d("aaa", "✅ Сообщение #${message.id} отправлено [${message.getFormattedTime()}]")
//
//                    // Если это не последнее сообщение — ждём 30 секунд
//                    if (index < pendingMessages.size - 1) {
//                        Thread.sleep(30000)  // 30 секунд между сообщениями
//                    }
//                } else {
//                    // Не удалось отправить — увеличиваем счётчик попыток и останавливаем отправку
//                    dbHelper.incrementRetryCount(message.id)
//                    Log.e("aaa", "❌ Ошибка отправки сообщения #${message.id}, останавливаем отправку очереди")
//                    shouldContinue = false
//                }
//            }
//
//            // Планируем следующую проверку очереди через 10 минут
//            // (даже если отправка остановилась из-за ошибки)
//            queueCheckRunnable = Runnable {
//                isProcessingQueue = false  // сбрасываем флаг, чтобы можно было запустить заново
//                processQueue(context)
//            }
//            queueCheckHandler.postDelayed(queueCheckRunnable!!, 10 * 60 * 1000)  // 10 минут в миллисекундах
////            queueCheckHandler.postDelayed(queueCheckRunnable!!, 1 * 60 * 1000)  // 1 минута в миллисекундах
//
//            isProcessingQueue = false
//
//        }.start()
//    }
//}