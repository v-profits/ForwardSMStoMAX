
package com.semenenko.smstomax.sms

import com.semenenko.smstomax.data.DatabaseHelper
import com.semenenko.smstomax.data.QueuedMessage
import com.semenenko.smstomax.util.AppLog
import com.semenenko.smstomax.util.Functions
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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

        // Короткие коды сообщений для журнала (ключ — senderLabel) и счётчик частей
        private val messageRefs = mutableMapOf<String, String>()
        private val partCounts = mutableMapOf<String, Int>()

        // true после первой записи «процесс запущен» — чтобы видеть в журнале перезапуски процесса
        private var processMarked = false

        // Через сколько мс без финального статуса запись о сообщении считается «не подтверждённой»
        private const val STATUS_TIMEOUT_MS = 15 * 60 * 1000L

        @Volatile private var isProcessingQueue = false
        private val queueCheckHandler = Handler(Looper.getMainLooper())
        private var queueCheckRunnable: Runnable? = null

        // --- Защита от дублей входящих SMS ---
        // Ключ: "sender::messageBody", значение: время получения (мс)
        private val recentlyProcessed = mutableMapOf<String, Long>()
        private const val DEDUP_WINDOW_MS = 15_000L // 15 секунд

        // --- Объединение сообщений в одно ---
        // Первое пришедшее SMS открывает окно BATCH_WINDOW_MS. Все сообщения, которые
        // успели обработаться за это время, группируются по получателю (чат + инстанс +
        // токен) и уходят ОДНИМ сообщением, между исходными — пустая строка.
        // В то же отправление добавляются и сообщения этого получателя из очереди.
        private const val BATCH_WINDOW_MS = 30_000L
        private const val BATCH_SEPARATOR = "\n\n"
        private const val MAX_BATCH_CHARS = 15_000 // лимит GREEN-API на сообщение — 20000 символов
        private var batchFlushRunnable: Runnable? = null
        private var batchWindowStart = 0L

        // Сообщения пакета сразу кладутся в БД (таблица очереди), поэтому не теряются, даже если
        // процесс выгрузят до закрытия окна. Закрытие окна страхуется будильником (AlarmManager)
        // и wake lock'ом, а сама отправка идёт в фоновом потоке, а не на главном.
        const val ACTION_FLUSH_BATCH = "com.semenenko.smstomax.FLUSH_BATCH"
        private const val FLUSH_ALARM_REQUEST = 7001
        private const val FLUSH_ALARM_EXTRA_DELAY_MS = 8_000L // будильник срабатывает позже штатного таймера
        private const val BATCH_WAKELOCK_MS = 120_000L
        private const val OLD_MESSAGE_MS = 120_000L // старше — считается «из очереди» и получает пометку 🔄
        private const val QUEUE_SKIP_YOUNG_MS = 45_000L // свежие записи фоновая очередь не трогает: их отправит пакет
        private const val FLUSH_MAX_DEFERRALS = 6
        private val sendWorker = Executors.newSingleThreadExecutor()
        private val pollWorker = Executors.newSingleThreadExecutor()
        private val batchRefs = ConcurrentHashMap<Long, String>() // id записи очереди -> код сообщения в журнале
        private var batchWakeLock: PowerManager.WakeLock? = null
        // Записи очереди, которые прямо сейчас отправляются (пакетом или фоновой очередью).
        // Не даёт двум потокам отправить одну и ту же запись дважды.
        private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        internal data class Destination(val chatId: String, val idInstance: String, val apiToken: String)

        // Одна запись в отправляемом пакете: новое SMS (queued == null) или сообщение из очереди
        internal data class Entry(
            val text: String,
            val ref: String,
            val sender: String,
            val senderLabel: String,
            val queued: QueuedMessage?
        )

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
            val formattedText: String,
            val ref: String = "",
            val createdAt: Long = System.currentTimeMillis(),
            var lastStatus: String = ""
        )
        private val pendingStatusChecks = ConcurrentHashMap<String, PendingStatusCheck>()

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
            if (!processMarked) {
                processMarked = true
                AppLog.i(context, "СИСТЕМА", "Процесс приложения запущен системой (память очищена)")
            }
            val bundle = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as? Array<*>
                if (pdus.isNullOrEmpty()) {
                    Log.e(TAG, "Ошибка: pdus пустой или null.")
                    AppLog.e(context, "ПРИЁМ", "SMS без данных (pdus пустой)")
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
                        AppLog.w(context, "ДУБЛЬ", "Повтор SMS проигнорирован (через ${now - lastSeen} мс), текст: ${messageBody.take(40)}", sender = sender)
                        continue
                    }
                    recentlyProcessed[dedupKey] = now
                    // лёгкая чистка старых записей, чтобы карта не росла бесконечно
                    if (recentlyProcessed.size > 200) {
                        val cutoff = now - DEDUP_WINDOW_MS
                        recentlyProcessed.entries.removeAll { it.value < cutoff }
                    }

                    // Первое SMS открывает окно объединения сообщений
                    openBatchWindow(context)

                    // Получаем имя контакта, если номер есть в адресной книге
                    var contactName = getContactName(context, sender)
                    if (contactName == "bot") contactName = "Узел 3 (вор. 36-37)" // 🟢🟢🟢🟢🟢🟢
                    val senderLabel = if (!contactName.isNullOrEmpty()) "$sender\n➖ $contactName ➖" else sender

                    // Добавляем часть сообщения в буфер (для длинных SMS)
                    messageBuffer.getOrPut(senderLabel) { mutableListOf() }.add(messageBody)
                    val ref = messageRefs.getOrPut(senderLabel) { AppLog.newRef() }
                    partCounts[senderLabel] = (partCounts[senderLabel] ?: 0) + 1
                    AppLog.i(context, "ПРИЁМ", "Получено SMS (${messageBody.length} симв.): ${messageBody.take(40)}", ref, sender)

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
        val ref = messageRefs.remove(senderLabel) ?: AppLog.newRef()
        val parts = partCounts.remove(senderLabel) ?: 1
        if (fullMessage.isNotEmpty()) {
            if (parts > 1) {
                AppLog.i(context, "СБОРКА", "Сообщение собрано из $parts частей, ${fullMessage.length} симв.", ref, sender)
            }
            processAndSendMessage(context, sender, fullMessage, senderLabel, ref)
            messageBuffer.remove(senderLabel)
        } else {
            AppLog.w(context, "СБОРКА", "Пустое сообщение, пересылка пропущена", ref, sender)
        }
    }

    // Основная логика: форматирование, отправка, работа с очередью
    private fun processAndSendMessage(context: Context, sender: String, message: String, senderLabel: String, ref: String) {

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

        if (userIds.isEmpty()) {
            AppLog.w(context, "ФИЛЬТР", "Нет фильтра для номера отправителя — сообщение не пересылается", ref, sender)
        }

        // Сообщение не отправляется сразу: для каждого активного фильтра оно
        // кладётся в пакет получателя и уйдёт вместе с остальными при закрытии окна
        for (userId in userIds) {
            val userData = dbHelper.getFilterDataByUserId(userId)
            val isActive = userData?.third == 1L

            if (!isActive) {
                AppLog.w(context, "ФИЛЬТР", "Фильтр отключён (id $userId) — сообщение не пересылается", ref, sender)
            }

            if (isActive) {
                val maxConfig = dbHelper.getMaxConfigByUserId(userId)
                if (maxConfig == null) {
                    AppLog.e(context, "ФИЛЬТР", "У фильтра (id $userId) нет настроек MAX/GREEN-API", ref, sender)
                } else {
                    val dest = Destination(maxConfig.chatId, maxConfig.idInstance, maxConfig.apiToken)
                    // Сначала в БД — только потом ждём окно: так сообщение не пропадёт при выгрузке процесса
                    val qid = dbHelper.saveMessageToQueue(
                        sender = senderLabel,
                        messageText = formattedText,
                        chatId = dest.chatId,
                        idInstance = dest.idInstance,
                        apiToken = dest.apiToken
                    )
                    batchRefs[qid] = ref
                    AppLog.i(context, "ПАКЕТ", "Сохранено в БД и добавлено в пакет для чата ${dest.chatId} (запись $qid)", ref, sender)
                }
            }
        }

        // Окно должно быть открыто (на случай, если его закрыли раньше, чем собралась последняя часть SMS)
        openBatchWindow(context, delayMs = 1_000L)
    }

    /** Открывает окно объединения, если оно ещё не открыто. Закрытие — через delayMs от первого SMS. */
    private fun openBatchWindow(context: Context, delayMs: Long = BATCH_WINDOW_MS) {
        if (batchFlushRunnable != null) return
        batchWindowStart = System.currentTimeMillis()
        acquireBatchWakeLock(context)
        scheduleFlushAlarm(context, delayMs)
        val runnable = object : Runnable {
            override fun run() {
                // Если какое-то SMS ещё собирается из частей — подождём, чтобы оно попало в пакет
                val waited = System.currentTimeMillis() - batchWindowStart
                if (pendingRunnables.isNotEmpty() && waited < BATCH_WINDOW_MS + 10_000L) {
                    handler.postDelayed(this, 2_000L)
                    return
                }
                batchFlushRunnable = null
                requestFlush(context)
            }
        }
        batchFlushRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun acquireBatchWakeLock(context: Context) {
        try {
            if (batchWakeLock?.isHeld == true) return
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            batchWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForwardSmsToMax:batch")?.also {
                it.setReferenceCounted(false)
                it.acquire(BATCH_WAKELOCK_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось взять WakeLock для пакета: ${e.message}")
        }
    }

    private fun releaseBatchWakeLock() {
        try {
            if (batchWakeLock?.isHeld == true) batchWakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось освободить WakeLock пакета: ${e.message}")
        }
    }

    private fun flushPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BatchAlarmReceiver::class.java).setAction(ACTION_FLUSH_BATCH)
        return PendingIntent.getBroadcast(
            context.applicationContext, FLUSH_ALARM_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Резервное закрытие окна: сработает, даже если таймер Handler «заснул» или процесс был выгружен. */
    private fun scheduleFlushAlarm(context: Context, delayMs: Long) {
        try {
            val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs + FLUSH_ALARM_EXTRA_DELAY_MS,
                flushPendingIntent(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось поставить резервный будильник: ${e.message}")
            AppLog.w(context, "СИСТЕМА", "Резервный будильник отправки не поставлен: ${e.message}")
        }
    }

    private fun cancelFlushAlarm(context: Context) {
        try {
            (context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.cancel(flushPendingIntent(context))
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось снять будильник: ${e.message}")
        }
    }

    /** Вызывается из BatchAlarmReceiver, когда штатный таймер не успел. Безопасно вызывать повторно. */
    fun flushFromAlarm(context: Context) {
        val app = context.applicationContext
        if (batchFlushRunnable == null && DatabaseHelper(app).getAllQueuedMessages().isEmpty()) return // отправлять нечего
        val timer = batchFlushRunnable
        if (timer != null) {
            handler.removeCallbacks(timer)
            batchFlushRunnable = null
            AppLog.w(app, "ПАКЕТ", "Окно закрыто резервным будильником (таймер не сработал вовремя)")
        } else {
            AppLog.w(app, "ПАКЕТ", "Отправка запущена будильником (процесс был перезапущен или таймер потерян)")
        }
        acquireBatchWakeLock(app)
        requestFlush(app, deferrals = FLUSH_MAX_DEFERRALS)
    }

    /** Передаёт закрытие окна фоновому потоку: сеть не должна блокировать главный поток. */
    private fun requestFlush(context: Context, deferrals: Int = 0) {
        val app = context.applicationContext
        cancelFlushAlarm(app)
        sendWorker.execute {
            try {
                flushBatches(app, deferrals)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки пакета: ${e.message}", e)
                AppLog.e(app, "ОТПРАВКА", "Ошибка при отправке пакета: ${e.message}")
            } finally {
                releaseBatchWakeLock()
            }
        }
    }

    /** Закрывает окно: берёт сообщения из БД и отправляет по одному сообщению на каждого получателя. */
    private fun flushBatches(context: Context, deferrals: Int) {
        val dbHelper = DatabaseHelper(context)

        // Очередь обрабатывается фоновым потоком — подождём, чтобы не отправить одно и то же дважды
        var waited = 0
        while (isProcessingQueue && waited < FLUSH_MAX_DEFERRALS && deferrals < FLUSH_MAX_DEFERRALS) {
            Thread.sleep(15_000L)
            waited++
        }

        val queued = dbHelper.getAllQueuedMessages().filter { inFlight.add(it.id) }
        try {
        if (queued.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val groups = queued.groupBy { Destination(it.chatId, it.idInstance, it.apiToken) }
            for ((dest, list) in groups) {
                val entries = list.sortedBy { it.id }.map {
                    // Сообщения, которые уже пытались отправить или лежат давно, помечаем 🔄 со временем получения
                    val old = it.retryCount > 0 || now - it.createdAt > OLD_MESSAGE_MS
                    Entry(
                        if (old) it.getMessageWithTimestamp() else it.messageText,
                        batchRefs[it.id] ?: "q${it.id}", it.sender, it.sender, it
                    )
                }
                sendEntries(context, dbHelper, dest, entries)
            }
        }
        } finally {
            queued.forEach { inFlight.remove(it.id) }
        }

        // Остальную очередь проверяем через минуту
        handler.postDelayed({
            startQueueProcessor(context)
        }, 60 * 1000)
    }

    /** Режет список на пакеты так, чтобы текст каждого не превышал MAX_BATCH_CHARS. */
    private fun chunkEntries(entries: List<Entry>): List<List<Entry>> {
        val chunks = mutableListOf<MutableList<Entry>>()
        var len = 0
        for (e in entries) {
            val add = e.text.length + BATCH_SEPARATOR.length
            if (chunks.isEmpty() || (len + add > MAX_BATCH_CHARS && chunks.last().isNotEmpty())) {
                chunks.add(mutableListOf())
                len = 0
            }
            chunks.last().add(e)
            len += add
        }
        return chunks
    }

    /** Отправляет записи одного получателя одним сообщением (или несколькими, если очень длинно). */
    private fun sendEntries(context: Context, dbHelper: DatabaseHelper, dest: Destination, entries: List<Entry>) {
        var failed = false
        for (chunk in chunkEntries(entries)) {
            val fromQueue = chunk.count { it.queued != null }
            if (!failed) {
                val combined = chunk.joinToString(BATCH_SEPARATOR) { it.text }
                val result = sendToMax(
                    context = context,
                    message = combined,
                    chatId = dest.chatId,
                    idInstance = dest.idInstance,
                    apiToken = dest.apiToken
                )
                when (result) {
                    is SendResult.Success -> {
                        Log.d(TAG, "✅ Пакет из ${chunk.size} сообщ. принят GREEN-API: ${dest.chatId}, idMessage=${result.idMessage}")
                        for (e in chunk) {
                            AppLog.i(
                                context, "ОТПРАВКА",
                                "Принято GREEN-API (idMessage ${result.idMessage}), чат ${dest.chatId}, в пакете сообщений: ${chunk.size}" +
                                        (if (e.text.startsWith("🔄")) " [из очереди]" else ""),
                                e.ref, e.sender
                            )
                            e.queued?.let { dbHelper.removeMessageFromQueue(it.id); batchRefs.remove(it.id) }
                        }
                        if (result.idMessage != null) {
                            pendingStatusChecks[result.idMessage] = PendingStatusCheck(
                                chatId = dest.chatId,
                                idInstance = dest.idInstance,
                                apiToken = dest.apiToken,
                                formattedText = combined,
                                ref = chunk.first().ref
                            )
                        }
                        ensureNotificationPollingStarted(context)
                        continue
                    }
                    is SendResult.Failed -> {
                        failed = true
                        AppLog.w(
                            context, "ОТПРАВКА",
                            "Пакет из ${chunk.size} сообщ. не отправлен: ${result.reason} (из очереди: $fromQueue)",
                            chunk.first().ref, chunk.first().sender
                        )
                    }
                }
            }
            // Не отправлено: новые сообщения сохраняем в очередь (по одному), у старых растёт счётчик попыток
            for (e in chunk) {
                if (e.queued != null) {
                    dbHelper.incrementRetryCount(e.queued.id)
                } else {
                    dbHelper.saveMessageToQueue(
                        sender = e.senderLabel,
                        messageText = e.text,
                        chatId = dest.chatId,
                        idInstance = dest.idInstance,
                        apiToken = dest.apiToken
                    )
                    AppLog.w(context, "ОТПРАВКА", "Сохранено в очередь, будет отправлено позже", e.ref, e.sender)
                }
            }
        }
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
    /**
     * Ищет среди исходящих сообщений инстанса за последние 15 минут сообщение с таким же текстом.
     * Нужно, чтобы после таймаута не отправить то, что GREEN-API уже принял. Возвращает idMessage или null.
     */
    private fun findRecentOutgoing(host: String, idInstance: String, apiToken: String, chatId: String, text: String): String? {
        return try {
            Thread.sleep(3_000L)
            val c = URL("$host/waInstance$idInstance/lastOutgoingMessages/$apiToken?minutes=15")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (c.responseCode !in 200..299) return null
            val arr = org.json.JSONArray(c.inputStream.bufferedReader().readText())
            val want = text.trim()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("chatId") == chatId && o.optString("textMessage").trim() == want) {
                    return o.optString("idMessage", "").ifEmpty { null }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось проверить последние исходящие: ${e.message}")
            null
        }
    }

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

        withWakeLock(context, "send", 90_000L) {
            result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                var requestWritten = false
                try {
                    Log.d(TAG, "🌐 sendToMax: открываю соединение $url")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.doOutput = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000

                    connection.outputStream.use { os ->
                        os.write(jsonBody.toByteArray(Charsets.UTF_8))
                    }
                    requestWritten = true

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
                    if (requestWritten) {
                        // Запрос ушёл, а ответа нет: сервер мог принять сообщение. Прежде чем отправлять
                        // повторно (и получить дубль), смотрим в последние исходящие сообщения инстанса.
                        val found = findRecentOutgoing(host, idInstance, apiToken, chatId, message)
                        if (found != null) {
                            AppLog.w(context, "ОТПРАВКА", "Ответ GREEN-API не получен (${e.message}), но сообщение найдено среди отправленных (idMessage $found) — повтор не нужен")
                            SendResult.Success(found)
                        } else {
                            AppLog.w(context, "ОТПРАВКА", "Ответ GREEN-API не получен (${e.message}), в отправленных сообщение не найдено — будет повтор")
                            SendResult.Failed(e.message ?: "unknown exception")
                        }
                    } else {
                        SendResult.Failed(e.message ?: "unknown exception")
                    }
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
        // Сообщения без финального статуса дольше STATUS_TIMEOUT_MS — пишем в журнал и перестаём ждать
        try {
            val nowMs = System.currentTimeMillis()
            val expired = pendingStatusChecks.filter { nowMs - it.value.createdAt > STATUS_TIMEOUT_MS }
            for ((expiredId, p) in expired) {
                AppLog.w(
                    context, "СТАТУС",
                    "Доставка не подтверждена за ${STATUS_TIMEOUT_MS / 60000} мин (последний статус: ${p.lastStatus.ifEmpty { "нет" }}), idMessage $expiredId",
                    p.ref
                )
                pendingStatusChecks.remove(expiredId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки просроченных статусов: ${e.message}")
        }

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
                                        AppLog.i(context, "СТАТУС", "Статус «$status» — сообщение дошло до MAX", pending.ref)
                                        pendingStatusChecks.remove(idMessage)
                                    }
                                    "notInGroup" -> {
                                        Log.e(TAG, "❌ Сообщение $idMessage: аккаунт GREEN-API не состоит в чате (notInGroup)")
                                        AppLog.e(context, "СТАТУС", "notInGroup — аккаунт GREEN-API не состоит в чате, сообщение НЕ доставлено", pending.ref)
                                        pendingStatusChecks.remove(idMessage)
                                    }
                                    "failed", "noAccount" -> {
                                        Log.e(TAG, "❌ Сообщение $idMessage реально не доставлено (статус=$status), кладём в очередь на повтор")
                                        AppLog.e(context, "СТАТУС", "Статус «$status» — не доставлено, возвращено в очередь на повтор", pending.ref)
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
                                        pending.lastStatus = status
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
                pollWorker.execute { try { pollOutgoingStatuses(context) } catch (e: Exception) { Log.e(TAG, "Ошибка опроса статусов: ${e.message}") } }
                notificationPollHandler.postDelayed(this, NOTIFICATION_POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "🕐 Планирую первый опрос через 20 сек и далее каждые ${NOTIFICATION_POLL_INTERVAL_MS / 1000} сек")
        // Первая проверка — через 20 секунд после отправки (быстрая обратная связь),
        // дальше — по расписанию каждые NOTIFICATION_POLL_INTERVAL_MS
        notificationPollHandler.postDelayed({
            Log.d(TAG, "⏰ Сработал первый быстрый опрос (20 сек после отправки)")
            pollWorker.execute { try { pollOutgoingStatuses(context) } catch (e: Exception) { Log.e(TAG, "Ошибка опроса статусов: ${e.message}") } }
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
        val nowMs = System.currentTimeMillis()
        // Свежие записи (только что принятые SMS) отправит пакет — фоновая очередь их не трогает
        val pendingMessages = dbHelper.getAllQueuedMessages().filter { nowMs - it.createdAt > QUEUE_SKIP_YOUNG_MS && inFlight.add(it.id) }

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

            // Сообщения одного получателя отправляем одним сообщением (пустая строка между ними)
            val groups = pendingMessages.groupBy { Destination(it.chatId, it.idInstance, it.apiToken) }
            val chunks = groups.flatMap { (dest, list) ->
                chunkEntries(list.map { Entry(it.getMessageWithTimestamp(), "q${it.id}", it.sender, it.sender, it) })
                    .map { dest to it }
            }

            for ((index, pair) in chunks.withIndex()) {
                if (!shouldContinue) break
                val (dest, chunk) = pair
                val combined = chunk.joinToString(BATCH_SEPARATOR) { it.text }

                Log.d(TAG, "Отправка из очереди: ${chunk.size} сообщ. одним пакетом")

                val result = sendToMax(
                    context = context,
                    message = combined,
                    chatId = dest.chatId,
                    idInstance = dest.idInstance,
                    apiToken = dest.apiToken
                )

                when (result) {
                    is SendResult.Success -> {
                        for (e in chunk) {
                            val q = e.queued!!
                            AppLog.i(context, "ОЧЕРЕДЬ", "Отправлено из очереди (создано ${q.getFormattedTime()}, idMessage ${result.idMessage}, в пакете сообщений: ${chunk.size})", e.ref)
                            dbHelper.removeMessageFromQueue(q.id)
                        }
                        Log.d(TAG, "✅ Пакет из очереди (${chunk.size} сообщ.) принят GREEN-API")
                        if (result.idMessage != null) {
                            pendingStatusChecks[result.idMessage] = PendingStatusCheck(
                                chatId = dest.chatId,
                                idInstance = dest.idInstance,
                                apiToken = dest.apiToken,
                                formattedText = combined,
                                ref = chunk.first().ref
                            )
                            ensureNotificationPollingStarted(context)
                        }

                        if (index < chunks.size - 1) {
                            Thread.sleep(30000)
                        }
                    }
                    is SendResult.Failed -> {
                        for (e in chunk) {
                            val q = e.queued!!
                            dbHelper.incrementRetryCount(q.id)
                            AppLog.w(context, "ОЧЕРЕДЬ", "Не отправлено из очереди: ${result.reason} (попытка ${q.retryCount + 1}), следующая проверка через 10 мин", e.ref)
                        }
                        Log.e(TAG, "❌ Ошибка отправки пакета из очереди (${result.reason}), останавливаем отправку очереди")
                        shouldContinue = false
                    }
                }
            }

            pendingMessages.forEach { inFlight.remove(it.id) }
            queueCheckRunnable = Runnable {
                isProcessingQueue = false
                processQueue(context)
            }
            queueCheckHandler.postDelayed(queueCheckRunnable!!, 10 * 60 * 1000)

            isProcessingQueue = false
        }.start()
    }
}

/**
 * Резервное срабатывание отправки пакета. Нужен отдельный не экспортируемый приёмник:
 * SMSReceiver защищён разрешением BROADCAST_SMS, и своё же сообщение от будильника ему не пройти.
 */
class BatchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SMSReceiver.ACTION_FLUSH_BATCH) {
            SMSReceiver().flushFromAlarm(context)
        }
    }
}

//package com.semenenko.smstomax
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
//            val userData = dbHelper.getFilterDataByUserId(userId)
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