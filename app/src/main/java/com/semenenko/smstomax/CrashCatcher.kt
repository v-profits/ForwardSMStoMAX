package com.semenenko.smstomax

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запоминает последнюю аварию приложения (необработанное исключение), чтобы при следующем
 * запуске показать её текст на экране. Нужно для диагностики без подключения к Android Studio.
 */
object CrashCatcher {
    private const val PREFS = "crash"
    private const val KEY = "last"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                val text = "$time, поток ${thread.name}\n${sw.toString().take(4000)}"
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).commit()
            } catch (ex: Throwable) {
                // запись аварии не должна мешать обычному завершению
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Возвращает текст последней аварии и стирает его (показывается один раз) */
    fun takeLast(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, null)
        if (text != null) prefs.edit().remove(KEY).apply()
        return text
    }
}
