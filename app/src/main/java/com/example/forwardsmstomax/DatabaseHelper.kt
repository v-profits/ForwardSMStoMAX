package com.example.forwardsmstomax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

// Константы для названий колонок
private const val COLUMN_ID = "id"
private const val COLUMN_TELEGA_USER_ID = "telega_user_id"      // В MAX версии это chatId (ID чата в MAX)
private const val COLUMN_FILTER_NAME = "filter_name"            // Имя фильтра (отображается в списке)
private const val COLUMN_API_TOKEN_BOT = "api_token_bot"        // В MAX версии это apiToken (токен GREEN-API)
private const val COLUMN_PHONE_NUMBER = "phone_number"          // Номер телефона, с которого пересылать SMS
private const val COLUMN_CONTACT_NAME = "contact_name"          // Имя контакта (если выбран из адресной книги)
private const val COLUMN_USER_ID_FK = "user_id_fk"              // Внешний ключ на таблицу users
private const val COLUMN_ACTIVE_USER_ID = "active_user_id"      // Активен ли фильтр (1 - активен, 0 - отключён)

// Новые константы для MAX (GREEN-API)
private const val COLUMN_CHAT_ID = "chat_id"                    // ID чата в MAX (куда отправлять)
private const val COLUMN_ID_INSTANCE = "id_instance"            // ID Instance из GREEN-API
private const val COLUMN_API_TOKEN = "api_token"                // API Token из GREEN-API

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "my_database.db"
        private const val DATABASE_VERSION = 5                   // ← ВЕРНУЛ 4 (как в Telegram версии)

        // Константы для таблицы очереди сообщений
        private const val COLUMN_QUEUE_ID = "id"
        private const val COLUMN_QUEUE_SENDER = "sender"           // отправитель (номер или имя)
        private const val COLUMN_QUEUE_MESSAGE_TEXT = "message_text"  // текст сообщения
        private const val COLUMN_QUEUE_CHAT_ID = "chat_id"         // куда отправлять в MAX
        private const val COLUMN_QUEUE_ID_INSTANCE = "id_instance" // ID Instance GREEN-API
        private const val COLUMN_QUEUE_API_TOKEN = "api_token"     // API Token GREEN-API
        private const val COLUMN_QUEUE_CREATED_AT = "created_at"   // время получения SMS (timestamp)
        private const val COLUMN_QUEUE_RETRY_COUNT = "retry_count" // сколько раз пытались отправить
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.d("DatabaseHelper", "onCreate: создаём таблицы")

        // 1. Таблица пользователей (фильтров)
        val createUsersTable = """
            CREATE TABLE IF NOT EXISTS users (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TELEGA_USER_ID TEXT NOT NULL,
                $COLUMN_FILTER_NAME TEXT NOT NULL,
                $COLUMN_API_TOKEN_BOT TEXT NOT NULL,
                $COLUMN_ACTIVE_USER_ID INTEGER NOT NULL DEFAULT 1,
                order_index INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createUsersTable)

        // 2. Таблица номеров телефонов (с каких номеров пересылать SMS)
        val createPhoneNumbersTable = """
            CREATE TABLE IF NOT EXISTS phone_numbers (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_USER_ID_FK INTEGER NOT NULL,
                $COLUMN_PHONE_NUMBER TEXT NOT NULL,
                $COLUMN_CONTACT_NAME TEXT NOT NULL,
                FOREIGN KEY($COLUMN_USER_ID_FK) REFERENCES users($COLUMN_ID) ON DELETE CASCADE
            )
        """.trimIndent()
        db.execSQL(createPhoneNumbersTable)

        // 3. Таблица MAX-конфигураций (данные для GREEN-API)
        val createMaxConfigTable = """
            CREATE TABLE IF NOT EXISTS max_config (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_USER_ID_FK INTEGER NOT NULL,
                $COLUMN_CHAT_ID TEXT NOT NULL,
                $COLUMN_ID_INSTANCE TEXT NOT NULL,
                $COLUMN_API_TOKEN TEXT NOT NULL,
                FOREIGN KEY($COLUMN_USER_ID_FK) REFERENCES users($COLUMN_ID) ON DELETE CASCADE
            )
        """.trimIndent()
        db.execSQL(createMaxConfigTable)

        // Таблица для хранения отложенных сообщений (когда нет интернета или ошибка отправки)
        val createMessageQueueTable = """
            CREATE TABLE IF NOT EXISTS message_queue (
                $COLUMN_QUEUE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_QUEUE_SENDER TEXT NOT NULL,
                $COLUMN_QUEUE_MESSAGE_TEXT TEXT NOT NULL,
                $COLUMN_QUEUE_CHAT_ID TEXT NOT NULL,
                $COLUMN_QUEUE_ID_INSTANCE TEXT NOT NULL,
                $COLUMN_QUEUE_API_TOKEN TEXT NOT NULL,
                $COLUMN_QUEUE_CREATED_AT INTEGER NOT NULL,
                $COLUMN_QUEUE_RETRY_COUNT INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createMessageQueueTable)

        Log.d("DatabaseHelper", "onCreate: таблицы созданы")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d("DatabaseHelper", "onUpgrade: oldVersion=$oldVersion, newVersion=$newVersion")

        // Поэтапные миграции (чтобы можно было обновляться с любой версии)
        when (oldVersion) {
            4 -> {
                // Обновление с версии 4 до 5
                migrateToVersion5(db)
                // Если в будущем будет версия 6, добавим сюда:
                // migrateToVersion6(db)
            }
            // Можно добавить миграции с более старых версий
            else -> {
                // Если версия совсем старая или неизвестная — пересоздаём всё
                Log.w("DatabaseHelper", "Неизвестная версия $oldVersion, пересоздаём БД")
                db.execSQL("DROP TABLE IF EXISTS max_config")
                db.execSQL("DROP TABLE IF EXISTS phone_numbers")
                db.execSQL("DROP TABLE IF EXISTS users")
                db.execSQL("DROP TABLE IF EXISTS message_queue")
                onCreate(db)
            }
        }
    }


    /**
     * Миграция с версии 4 на 5
     * Добавляем таблицу message_queue, сохраняя все существующие данные
     */
    private fun migrateToVersion5(db: SQLiteDatabase) {
        Log.d("DatabaseHelper", "Миграция: версия 4 → 5, добавляем таблицу message_queue")

        // Просто создаём новую таблицу, если её ещё нет
        // IF NOT EXISTS — если таблица уже есть (по какой-то причине), не будем создавать заново
        val createMessageQueueTable = """
            CREATE TABLE IF NOT EXISTS message_queue (
                $COLUMN_QUEUE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_QUEUE_SENDER TEXT NOT NULL,
                $COLUMN_QUEUE_MESSAGE_TEXT TEXT NOT NULL,
                $COLUMN_QUEUE_CHAT_ID TEXT NOT NULL,
                $COLUMN_QUEUE_ID_INSTANCE TEXT NOT NULL,
                $COLUMN_QUEUE_API_TOKEN TEXT NOT NULL,
                $COLUMN_QUEUE_CREATED_AT INTEGER NOT NULL,
                $COLUMN_QUEUE_RETRY_COUNT INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createMessageQueueTable)

        Log.d("DatabaseHelper", "Миграция завершена, данные сохранены")
    }

    // ==================== МЕТОДЫ ДЛЯ MAX ====================

    /**
     * Добавление конфигурации MAX для пользователя
     * @param userId ID пользователя из таблицы users
     * @param chatId ID чата в MAX (куда отправлять сообщения)
     * @param idInstance ID Instance из GREEN-API
     * @param apiToken API Token из GREEN-API
     * @param isActive активен ли фильтр
     */
    fun addMaxConfig(userId: Long, chatId: String, idInstance: String, apiToken: String): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_USER_ID_FK, userId)
            put(COLUMN_CHAT_ID, chatId)
            put(COLUMN_ID_INSTANCE, idInstance)
            put(COLUMN_API_TOKEN, apiToken)
        }
        val id = db.insert("max_config", null, values)
        db.close()
        return id
    }

    // Обновление конфигурации MAX
    fun updateMaxConfig(userId: Long, chatId: String, idInstance: String, apiToken: String): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_CHAT_ID, chatId)
            put(COLUMN_ID_INSTANCE, idInstance)
            put(COLUMN_API_TOKEN, apiToken)
        }
        val rows = db.update("max_config", values, "$COLUMN_USER_ID_FK = ?", arrayOf(userId.toString()))
        db.close()
        return rows > 0
    }

    /**
     * Получение конфигурации MAX по ID пользователя
     * Используется в SMSReceiver для отправки в MAX
     */
    @SuppressLint("Range")
    fun getMaxConfigByUserId(userId: Long): MaxConfigData? {
        val db = this.readableDatabase
        val cursor = db.query(
            "max_config",
            arrayOf(COLUMN_CHAT_ID, COLUMN_ID_INSTANCE, COLUMN_API_TOKEN),
            "$COLUMN_USER_ID_FK = ?",
            arrayOf(userId.toString()),
            null, null, null
        )

        var result: MaxConfigData? = null
        cursor.use {
            if (it.moveToFirst()) {
                val chatId = it.getString(it.getColumnIndexOrThrow(COLUMN_CHAT_ID))
                val idInstance = it.getString(it.getColumnIndexOrThrow(COLUMN_ID_INSTANCE))
                val apiToken = it.getString(it.getColumnIndexOrThrow(COLUMN_API_TOKEN))
                result = MaxConfigData(chatId, idInstance, apiToken)
            }
        }
        db.close()
        return result
    }

    /**
     * Удаление конфигурации MAX по ID пользователя
     * Внутренний метод (не закрывает базу) — используется в deleteUserById
     */
    private fun deleteMaxConfigByUserIdInternal(db: SQLiteDatabase, userId: Long): Boolean {
        val result = db.delete("max_config", "$COLUMN_USER_ID_FK = ?", arrayOf(userId.toString()))
        return result > 0
    }

    // Удаление конфигурации MAX (публичный метод, закрывает базу)
    fun deleteMaxConfigByUserId(userId: Long): Boolean {
        val db = this.writableDatabase
        return try {
            deleteMaxConfigByUserIdInternal(db, userId)
        } finally {
            db.close()
        }
    }

    // ==================== МЕТОДЫ ДЛЯ РАБОТЫ С ФИЛЬТРАМИ ====================

    // Обновление порядка сортировки фильтров (для перетаскивания)
    fun updateUserOrder(userId: Long, orderIndex: Int) {
        val db = writableDatabase
        val values = ContentValues().apply { put("order_index", orderIndex) }
        db.update("users", values, "id = ?", arrayOf(userId.toString()))
        db.close()
    }

    // Обновление данных фильтра и связанных номеров телефонов
    fun updateUserAndPhoneNumbers(
        userId: Long,
        newUserId: String,
        newFilterName: String,
        newBotToken: String,
        isActive: Boolean,
        phoneNumber: String,
        phoneNumbers: MutableList<ContactData>
    ): Boolean {
        val db = this.writableDatabase
        db.beginTransaction()
        return try {
            // Обновляем пользователя
            val userValues = ContentValues().apply {
                put(COLUMN_TELEGA_USER_ID, newUserId)
                put(COLUMN_FILTER_NAME, newFilterName)
                put(COLUMN_API_TOKEN_BOT, newBotToken)
                put(COLUMN_ACTIVE_USER_ID, if (isActive) 1 else 0)
            }
            val updatedUserRows = db.update("users", userValues, "$COLUMN_ID = ?", arrayOf(userId.toString()))

            // Удаляем старые номера
            db.delete("phone_numbers", "$COLUMN_USER_ID_FK = ?", arrayOf(userId.toString()))

            // Вставляем новые номера
            if (phoneNumbers.isNotEmpty()) {
                for (contact in phoneNumbers) {
                    val phoneValues = ContentValues().apply {
                        put(COLUMN_USER_ID_FK, userId)
                        put(COLUMN_PHONE_NUMBER, contact.phoneNumber)
                        put(COLUMN_CONTACT_NAME, contact.nameContact)
                    }
                    db.insert("phone_numbers", null, phoneValues)
                }
            } else {
                val phoneValues = ContentValues().apply {
                    put(COLUMN_USER_ID_FK, userId)
                    put(COLUMN_PHONE_NUMBER, phoneNumber)
                    put(COLUMN_CONTACT_NAME, "")
                }
                db.insert("phone_numbers", null, phoneValues)
            }

            db.setTransactionSuccessful()
            updatedUserRows > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Добавление нового фильтра
     * @return ID созданного фильтра
     */
    fun addUser(userId: String, filterName: String, apiToken: String, isActive: Boolean): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TELEGA_USER_ID, userId)
            put(COLUMN_FILTER_NAME, filterName)
            put(COLUMN_API_TOKEN_BOT, apiToken)
            put(COLUMN_ACTIVE_USER_ID, if (isActive) 1 else 0)
        }
        val currentMaxOrder = getMaxOrderIndex(db) + 1
        values.put("order_index", currentMaxOrder)
        val id = db.insert("users", null, values)
        db.close()
        return id
    }

    // Получение максимального order_index (для порядка сортировки)
    fun getMaxOrderIndex(db: SQLiteDatabase): Int {
        val cursor = db.rawQuery("SELECT MAX(order_index) FROM users", null)
        var max = 0
        if (cursor.moveToFirst()) max = cursor.getInt(0)
        cursor.close()
        return max
    }

    // Логирование всех order_index (для отладки)
    fun logAllOrderIndexes() {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_ID, $COLUMN_FILTER_NAME, order_index FROM users ORDER BY order_index ASC", null)
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FILTER_NAME))
                val orderIndex = cursor.getInt(cursor.getColumnIndexOrThrow("order_index"))
                Log.e("aaa", "ID: $id, Name: $name, OrderIndex: $orderIndex")
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
    }

    // Переиндексация order_index (восстанавливает порядок 0,1,2...)
    fun reassignOrderIndexes() {
        val db = this.writableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_ID FROM users", null)
        var index = 0
        if (cursor.moveToFirst()) {
            do {
                val userId = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val values = ContentValues().apply { put("order_index", index) }
                db.update("users", values, "$COLUMN_ID = ?", arrayOf(userId.toString()))
                index++
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
    }

    // Добавление номера телефона для фильтра
    fun addPhoneNumber(userId: Long, phoneNumber: String, contactName: String): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_USER_ID_FK, userId)
            put(COLUMN_PHONE_NUMBER, phoneNumber)
            put(COLUMN_CONTACT_NAME, contactName)
        }
        val id = db.insert("phone_numbers", null, values)
        db.close()
        return id
    }

    // Получение всех фильтров (для отображения в списке)
    @SuppressLint("Range")
    fun getAllUsers(): MutableList<FilterData> {
        val users = mutableListOf<FilterData>()
        val db = this.readableDatabase
        val cursor: Cursor = db.rawQuery("SELECT * FROM users ORDER BY order_index ASC", null)
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val userId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TELEGA_USER_ID))
                val userName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FILTER_NAME))
                val apiToken = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_API_TOKEN_BOT))
                val isActive = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ACTIVE_USER_ID)).toInt() == 1
                users.add(FilterData(id, userId, userName, apiToken, isActive))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return users
    }

    /**
     * Получение ID фильтров, которые должны получать SMS с указанного номера
     * Используется в SMSReceiver для определения куда отправлять сообщение
     */
    fun getUserIdsByPhoneNumber(inputPhoneNumber: String): List<Long> {
        val db = this.readableDatabase
        val userIds = mutableListOf<Long>()
        val normalizedInput = Functions.normalizePhoneNumber(inputPhoneNumber)
        val cursor = db.query(
            "phone_numbers",
            arrayOf(COLUMN_USER_ID_FK, COLUMN_PHONE_NUMBER),
            null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val dbPhone = it.getString(it.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER))
                val normalizedDbPhone = Functions.normalizePhoneNumber(dbPhone)
                if (normalizedInput == normalizedDbPhone) {
                    val userId = it.getLong(it.getColumnIndexOrThrow(COLUMN_USER_ID_FK))
                    userIds.add(userId)
                }
            }
        }
        db.close()
        return userIds
    }

    // Получение номеров телефонов, привязанных к фильтру
    @SuppressLint("Range")
    fun getPhoneNumbersByUserId(userId: Long): List<ContactData> {
        val contacts = mutableListOf<ContactData>()
        val db = this.readableDatabase
        val cursor: Cursor = db.rawQuery("SELECT * FROM phone_numbers WHERE $COLUMN_USER_ID_FK = ?", arrayOf(userId.toString()))
        if (cursor.moveToFirst()) {
            do {
                val contactName = cursor.getString(cursor.getColumnIndex(COLUMN_CONTACT_NAME))
                val phoneNumber = cursor.getString(cursor.getColumnIndex(COLUMN_PHONE_NUMBER))
                contacts.add(ContactData(contactName, phoneNumber))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return contacts
    }

    /**
     * Получение данных фильтра (для отправки в SMSReceiver)
     * Возвращает: (chatId, apiToken, isActive)
     */
    fun getTelegramDataByUserId(userId: Long): Triple<String, String, Long>? {
        val db = this.readableDatabase
        var result: Triple<String, String, Long>? = null
        val cursor = db.query(
            "users",
            arrayOf(COLUMN_TELEGA_USER_ID, COLUMN_API_TOKEN_BOT, COLUMN_ACTIVE_USER_ID),
            "$COLUMN_ID = ?",
            arrayOf(userId.toString()),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val telegaId = it.getString(it.getColumnIndexOrThrow(COLUMN_TELEGA_USER_ID))
                val token = it.getString(it.getColumnIndexOrThrow(COLUMN_API_TOKEN_BOT))
                val active = it.getLong(it.getColumnIndexOrThrow(COLUMN_ACTIVE_USER_ID))
                result = Triple(telegaId, token, active)
            }
        }
        db.close()
        return result
    }

    /**
     * Обновление статуса активности фильтра (вкл/выкл)
     * Вызывается при нажатии на powerIcon в списке фильтров
     */
    fun updateUserActiveStatus(userId: Long, isActive: Boolean): Int {
        val db = writableDatabase
        val values = ContentValues().apply { put(COLUMN_ACTIVE_USER_ID, if (isActive) 1 else 0) }
        val rows = db.update("users", values, "$COLUMN_ID = ?", arrayOf(userId.toString()))
        db.close()
        return rows
    }

    // Удаление всех номеров телефонов, привязанных к фильтру
    fun deletePhoneNumbersByUserId(userId: Long) {
        val db = writableDatabase
        db.delete("phone_numbers", "$COLUMN_USER_ID_FK = ?", arrayOf(userId.toString()))
        db.close()
    }

    /**
     * Удаление фильтра по ID
     * Сначала удаляем связанные MAX-конфигурации, затем сам фильтр
     * Вызывается при нажатии на deleteIcon в списке фильтров
     */
    fun deleteUserById(userId: Long): Boolean {
        val db = this.writableDatabase
        return try {
            // Сначала удаляем связанные MAX-конфигурации (используем внутренний метод, чтобы не закрывать базу)
            deleteMaxConfigByUserIdInternal(db, userId)
            // Затем удаляем номера телефонов
            db.delete("phone_numbers", "$COLUMN_USER_ID_FK = ?", arrayOf(userId.toString()))
            // И наконец удаляем сам фильтр
            val result = db.delete("users", "$COLUMN_ID = ?", arrayOf(userId.toString()))
            result > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            db.close()
        }
    }

    // Удаление одного номера телефона (не используется в текущей версии)
    fun deletePhoneNumber(phoneId: Long) {
        val db = this.writableDatabase
        db.delete("phone_numbers", "$COLUMN_ID = ?", arrayOf(phoneId.toString()))
        db.close()
    }

    // Очистка всех данных (для отладки)
    fun clearAllData() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM max_config")
        db.execSQL("DELETE FROM phone_numbers")
        db.execSQL("DELETE FROM users")
        db.close()
    }


// ==================== НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ОЧЕРЕДЬЮ ====================

    /**
     * Сохраняет сообщение в очередь (когда отправка не удалась)
     * @param sender отправитель SMS (номер или имя контакта)
     * @param messageText полный отформатированный текст сообщения
     * @param chatId ID чата в MAX
     * @param idInstance ID Instance GREEN-API
     * @param apiToken API Token GREEN-API
     * @return ID сохранённого сообщения в БД
     */
    fun saveMessageToQueue(
        sender: String,
        messageText: String,
        chatId: String,
        idInstance: String,
        apiToken: String
    ): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_QUEUE_SENDER, sender)
            put(COLUMN_QUEUE_MESSAGE_TEXT, messageText)
            put(COLUMN_QUEUE_CHAT_ID, chatId)
            put(COLUMN_QUEUE_ID_INSTANCE, idInstance)
            put(COLUMN_QUEUE_API_TOKEN, apiToken)
            put(COLUMN_QUEUE_CREATED_AT, System.currentTimeMillis())  // запоминаем время получения SMS
            put(COLUMN_QUEUE_RETRY_COUNT, 0)  // начальное количество попыток = 0
        }
        val id = db.insert("message_queue", null, values)
        db.close()
        return id
    }

    /**
     * Получает все сообщения из очереди (неотправленные)
     * Сортировка по времени создания (старые первыми - FIFO)
     */
    @SuppressLint("Range")
    fun getAllQueuedMessages(): List<QueuedMessage> {
        val messages = mutableListOf<QueuedMessage>()
        val db = this.readableDatabase

        val cursor = db.query(
            "message_queue",
            null,
            null,  // все сообщения
            null,
            null, null,
            "$COLUMN_QUEUE_CREATED_AT ASC"  // сортируем по возрастанию (старые первые)
        )

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COLUMN_QUEUE_ID))
                val sender = it.getString(it.getColumnIndexOrThrow(COLUMN_QUEUE_SENDER))
                val messageText = it.getString(it.getColumnIndexOrThrow(COLUMN_QUEUE_MESSAGE_TEXT))
                val chatId = it.getString(it.getColumnIndexOrThrow(COLUMN_QUEUE_CHAT_ID))
                val idInstance = it.getString(it.getColumnIndexOrThrow(COLUMN_QUEUE_ID_INSTANCE))
                val apiToken = it.getString(it.getColumnIndexOrThrow(COLUMN_QUEUE_API_TOKEN))
                val createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_QUEUE_CREATED_AT))
                val retryCount = it.getInt(it.getColumnIndexOrThrow(COLUMN_QUEUE_RETRY_COUNT))

                messages.add(QueuedMessage(
                    id = id,
                    sender = sender,
                    messageText = messageText,
                    chatId = chatId,
                    idInstance = idInstance,
                    apiToken = apiToken,
                    createdAt = createdAt,
                    retryCount = retryCount
                ))
            }
        }
        db.close()
        return messages
    }

    /**
     * Удаляет сообщение из очереди (после успешной отправки)
     * @param messageId ID сообщения в БД
     */
    fun removeMessageFromQueue(messageId: Long): Boolean {
        val db = this.writableDatabase
        val result = db.delete("message_queue", "$COLUMN_QUEUE_ID = ?", arrayOf(messageId.toString()))
        db.close()
        return result > 0
    }

    /**
     * Увеличивает счётчик попыток отправки сообщения
     * @param messageId ID сообщения в БД
     */
    fun incrementRetryCount(messageId: Long) {
        val db = this.writableDatabase
        // сначала получаем текущее значение retry_count
        val cursor = db.query(
            "message_queue",
            arrayOf(COLUMN_QUEUE_RETRY_COUNT),
            "$COLUMN_QUEUE_ID = ?",
            arrayOf(messageId.toString()),
            null, null, null
        )

        var currentRetryCount = 0
        cursor.use {
            if (it.moveToFirst()) {
                currentRetryCount = it.getInt(0)
            }
        }

        // обновляем, увеличивая на 1
        val values = ContentValues().apply {
            put(COLUMN_QUEUE_RETRY_COUNT, currentRetryCount + 1)
        }
        db.update("message_queue", values, "$COLUMN_QUEUE_ID = ?", arrayOf(messageId.toString()))
        db.close()
    }

    /**
     * Очищает всю очередь (для отладки)
     */
    fun clearMessageQueue() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM message_queue")
        db.close()
    }
}