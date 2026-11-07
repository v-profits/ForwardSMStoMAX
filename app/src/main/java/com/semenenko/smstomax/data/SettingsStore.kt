package com.semenenko.smstomax.data
import android.content.Context

/**
 * Настройки приложения (SharedPreferences "settings").
 * Сейчас хранит только параметры журнала событий.
 */
object SettingsStore {
    private const val PREFS = "settings"
    private const val KEY_RETENTION_DAYS = "log_retention_days"
    private const val KEY_ERRORS_ONLY = "log_errors_only"

    /** Допустимые сроки хранения записей журнала, дней */
    val RETENTION_OPTIONS = intArrayOf(1, 3, 7, 14, 30)
    const val DEFAULT_RETENTION_DAYS = 7

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getRetentionDays(context: Context): Int =
        prefs(context).getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

    fun setRetentionDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }

    /** true — писать в журнал только предупреждения и ошибки */
    fun isLogErrorsOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ERRORS_ONLY, false)

    fun setLogErrorsOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ERRORS_ONLY, value).apply()
    }
}
