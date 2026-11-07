package com.semenenko.smstomax.data
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт и импорт фильтров в JSON-файл.
 *
 * ВНИМАНИЕ: файл содержит idInstance и apiToken GREEN-API в открытом виде.
 * Хранить его нужно так же осторожно, как сами токены.
 */
object FiltersBackup {
    private const val FORMAT = "smstomax-filters"
    private const val VERSION = 1

    /** Результат импорта: сколько фильтров добавлено и сколько пропущено (дубли или неполные данные) */
    data class ImportResult(val added: Int, val skipped: Int)

    private data class ParsedFilter(
        val name: String,
        val chatId: String,
        val apiToken: String,
        val idInstance: String,
        val active: Boolean,
        val phones: List<Pair<String, String>> // номер, имя контакта
    ) {
        fun key() = "$name|$idInstance|$chatId"
    }

    fun defaultFileName(): String =
        "smstomax-filters-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".json"

    /** Собирает JSON со всеми фильтрами в том порядке, в котором они показаны в списке */
    fun export(db: DatabaseHelper): String {
        val filters = JSONArray()
        for (f in db.getAllUsers()) {
            val max = db.getMaxConfigByUserId(f.id)
            val phones = JSONArray()
            for (p in db.getPhoneNumbersByUserId(f.id)) {
                phones.put(JSONObject().put("number", p.phoneNumber).put("name", p.nameContact))
            }
            filters.put(
                JSONObject()
                    .put("name", f.filterName)
                    .put("active", f.isActive)
                    .put("chatId", max?.chatId ?: f.maxChatId)
                    .put("idInstance", max?.idInstance ?: "")
                    .put("apiToken", max?.apiToken ?: f.apiTokenBot)
                    .put("phones", phones)
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .put("filters", filters)
            .toString(2)
    }

    /**
     * Загружает фильтры из JSON.
     * replace = true  — все текущие фильтры удаляются и заменяются файлом;
     * replace = false — фильтры из файла добавляются к текущим (одинаковые пропускаются).
     * Файл сначала разбирается целиком: при ошибке формата текущие данные не затрагиваются
     * (выбрасывается IllegalArgumentException).
     */
    fun importFrom(db: DatabaseHelper, json: String, replace: Boolean): ImportResult {
        val parsed = mutableListOf<ParsedFilter>()
        var skipped = 0
        try {
            val root = JSONObject(json)
            require(root.optString("format") == FORMAT) { "Это не файл фильтров smstomax" }
            val arr = root.getJSONArray("filters")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                val chatId = o.optString("chatId").trim()
                val token = o.optString("apiToken").trim()
                val instance = o.optString("idInstance").trim()
                if (name.isEmpty() || chatId.isEmpty() || token.isEmpty() || instance.isEmpty()) {
                    skipped++
                    continue
                }
                val phones = mutableListOf<Pair<String, String>>()
                val pa = o.optJSONArray("phones")
                if (pa != null) {
                    for (j in 0 until pa.length()) {
                        val p = pa.getJSONObject(j)
                        val number = p.optString("number").trim()
                        if (number.isNotEmpty()) phones.add(number to p.optString("name"))
                    }
                }
                parsed.add(ParsedFilter(name, chatId, token, instance, o.optBoolean("active", true), phones))
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Файл повреждён или имеет неверный формат")
        }

        if (replace) db.clearAllData()

        val existing = HashSet<String>()
        for (f in db.getAllUsers()) {
            val m = db.getMaxConfigByUserId(f.id)
            if (m != null) existing.add("${f.filterName}|${m.idInstance}|${m.chatId}")
        }

        var added = 0
        for (f in parsed) {
            if (!existing.add(f.key())) {
                skipped++
                continue
            }
            val userId = db.addUser(f.chatId, f.name, f.apiToken, f.active)
            if (userId <= 0) {
                skipped++
                continue
            }
            db.addMaxConfig(userId, f.chatId, f.idInstance, f.apiToken)
            for ((number, name) in f.phones) db.addPhoneNumber(userId, number, name)
            added++
        }
        return ImportResult(added, skipped)
    }
}
