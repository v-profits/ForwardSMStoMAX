package com.semenenko.smstomax.util

import com.semenenko.smstomax.data.SettingsStore
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Одна запись журнала */
data class LogEntry(
    val id: Long,
    val time: Long,
    val level: Int,      // AppLog.INFO / WARN / ERROR
    val stage: String,   // этап: ПРИЁМ, ФИЛЬТР, ОТПРАВКА, ОЧЕРЕДЬ, СТАТУС, СИСТЕМА
    val ref: String,     // короткий код сообщения: по нему видно весь путь одного SMS
    val sender: String,  // номер отправителя SMS
    val text: String
)

/** Отдельная БД журнала (app_log.db), чтобы не смешивать её с настройками фильтров */
private class LogDbHelper(context: Context) :
    SQLiteOpenHelper(context, "app_log.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE event_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                level INTEGER NOT NULL,
                stage TEXT NOT NULL,
                ref TEXT NOT NULL,
                sender TEXT NOT NULL,
                text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_event_log_ts ON event_log(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/**
 * Журнал событий пересылки.
 *
 * Пишутся только ключевые этапы пути сообщения (одна строка на этап),
 * без подробностей сетевого опроса — чтобы журнал не разрастался:
 *   ПРИЁМ → (СБОРКА) → ФИЛЬТР → ОТПРАВКА → СТАТУС   или   ОТПРАВКА → ОЧЕРЕДЬ → СТАТУС
 * Записи хранятся столько дней, сколько задано в настройках, и не более MAX_ROWS штук.
 * Ошибки записи в журнал никогда не прерывают пересылку SMS.
 */
object AppLog {
    const val INFO = 0
    const val WARN = 1
    const val ERROR = 2

    private const val MAX_ROWS = 3000
    private const val PURGE_INTERVAL_MS = 60 * 60 * 1000L
    private const val MAX_TEXT = 300

    @Volatile private var helper: LogDbHelper? = null
    @Volatile private var lastPurge = 0L

    // токен GREEN-API может оказаться в тексте исключения (в адресе запроса) — маскируем
    private val tokenRegex = Regex("(/waInstance\\d+/\\w+/)[^\\s/\"?]+")

    private fun db(context: Context): SQLiteDatabase {
        var h = helper
        if (h == null) {
            synchronized(this) {
                h = helper
                if (h == null) {
                    h = LogDbHelper(context.applicationContext)
                    helper = h
                }
            }
        }
        return h!!.writableDatabase
    }

    /** Короткий код сообщения (3 символа), связывает записи одного SMS */
    fun newRef(): String {
        val v = (System.currentTimeMillis() % 46656L).toInt() // 36^3
        return Integer.toString(v, 36).padStart(3, '0')
    }

    fun i(context: Context, stage: String, text: String, ref: String = "", sender: String = "") =
        add(context, INFO, stage, text, ref, sender)

    fun w(context: Context, stage: String, text: String, ref: String = "", sender: String = "") =
        add(context, WARN, stage, text, ref, sender)

    fun e(context: Context, stage: String, text: String, ref: String = "", sender: String = "") =
        add(context, ERROR, stage, text, ref, sender)

    private fun add(context: Context, level: Int, stage: String, text: String, ref: String, sender: String) {
        try {
            if (level == INFO && SettingsStore.isLogErrorsOnly(context)) return
            val clean = tokenRegex.replace(text, "$1***").replace('\n', ' ').take(MAX_TEXT)
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("ts", now)
                put("level", level)
                put("stage", stage)
                put("ref", ref)
                put("sender", sender)
                put("text", clean)
            }
            synchronized(this) {
                db(context).insert("event_log", null, values)
            }
            if (now - lastPurge > PURGE_INTERVAL_MS) purge(context)
        } catch (e: Exception) {
            Log.e("AppLog", "Не удалось записать в журнал: ${e.message}")
        }
    }

    /** Удаляет записи старше срока хранения и лишние сверх лимита */
    fun purge(context: Context) {
        try {
            lastPurge = System.currentTimeMillis()
            val days = SettingsStore.getRetentionDays(context)
            val border = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            synchronized(this) {
                val d = db(context)
                d.delete("event_log", "ts < ?", arrayOf(border.toString()))
                d.execSQL(
                    "DELETE FROM event_log WHERE id <= " +
                        "(SELECT id FROM event_log ORDER BY id DESC LIMIT 1 OFFSET $MAX_ROWS)"
                )
            }
        } catch (e: Exception) {
            Log.e("AppLog", "Не удалось очистить журнал: ${e.message}")
        }
    }

    /** Записи от новых к старым. onlyProblems — только предупреждения и ошибки */
    fun query(context: Context, onlyProblems: Boolean, limit: Int = 1000): List<LogEntry> {
        val result = mutableListOf<LogEntry>()
        try {
            synchronized(this) {
                val cursor = db(context).query(
                    "event_log", null,
                    if (onlyProblems) "level >= 1" else null, null,
                    null, null, "id DESC", limit.toString()
                )
                cursor.use {
                    while (it.moveToNext()) {
                        result.add(
                            LogEntry(
                                it.getLong(it.getColumnIndexOrThrow("id")),
                                it.getLong(it.getColumnIndexOrThrow("ts")),
                                it.getInt(it.getColumnIndexOrThrow("level")),
                                it.getString(it.getColumnIndexOrThrow("stage")),
                                it.getString(it.getColumnIndexOrThrow("ref")),
                                it.getString(it.getColumnIndexOrThrow("sender")),
                                it.getString(it.getColumnIndexOrThrow("text"))
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AppLog", "Не удалось прочитать журнал: ${e.message}")
        }
        return result
    }

    fun clear(context: Context) {
        try {
            synchronized(this) { db(context).delete("event_log", null, null) }
        } catch (e: Exception) {
            Log.e("AppLog", "Не удалось очистить журнал: ${e.message}")
        }
    }

    /** Весь журнал текстом (от старых к новым) — для сохранения в файл */
    fun exportText(context: Context): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val levels = arrayOf("INFO ", "WARN ", "ERROR")
        val sb = StringBuilder()
        for (e in query(context, false, MAX_ROWS).reversed()) {
            sb.append(fmt.format(Date(e.time))).append(' ')
                .append(levels.getOrElse(e.level) { "?    " }).append(' ')
                .append(e.stage)
            if (e.ref.isNotEmpty()) sb.append(" #").append(e.ref)
            if (e.sender.isNotEmpty()) sb.append(" [").append(e.sender).append(']')
            sb.append(": ").append(e.text).append('\n')
        }
        return sb.toString()
    }
}
