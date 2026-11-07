package com.semenenko.smstomax.ui

import com.semenenko.smstomax.R
import com.semenenko.smstomax.data.DatabaseHelper
import com.semenenko.smstomax.data.FiltersBackup
import com.semenenko.smstomax.data.SettingsStore
import com.semenenko.smstomax.util.AppLog
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Экран «Настройки»: параметры журнала, экспорт и импорт фильтров.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_EXPORT_FILTERS = 201
        private const val REQ_IMPORT_FILTERS = 202
    }

    private lateinit var tvRetention: TextView
    private lateinit var switchErrorsOnly: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // ----- Журнал -----
        findViewById<View>(R.id.row_open_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        tvRetention = findViewById(R.id.tv_retention_value)
        updateRetentionText()
        findViewById<View>(R.id.row_retention).setOnClickListener { showRetentionDialog() }

        switchErrorsOnly = findViewById(R.id.switch_errors_only)
        switchErrorsOnly.isChecked = SettingsStore.isLogErrorsOnly(this)
        switchErrorsOnly.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setLogErrorsOnly(this, checked)
        }
        findViewById<View>(R.id.row_errors_only).setOnClickListener { switchErrorsOnly.toggle() }

        findViewById<View>(R.id.row_clear_log).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Очистить журнал?")
                .setMessage("Все записи будут удалены.")
                .setPositiveButton("Очистить") { _, _ ->
                    AppLog.clear(this)
                    Toast.makeText(this, "Журнал очищен", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // ----- Фильтры -----
        findViewById<View>(R.id.row_export).setOnClickListener { confirmExport() }
        findViewById<View>(R.id.row_import).setOnClickListener { startImport() }

        // ----- О приложении -----
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tv_version).text = "Пересылка SMS в MAX, версия $version"
        } catch (e: Exception) {
            // версия не критична — остаётся текст по умолчанию
        }
    }

    // ==================== Журнал ====================

    private fun updateRetentionText() {
        tvRetention.text = daysText(SettingsStore.getRetentionDays(this)) +
            " — более старые записи удаляются автоматически"
    }

    private fun daysText(days: Int): String {
        val mod10 = days % 10
        val mod100 = days % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "день"
            mod10 in 2..4 && mod100 !in 12..14 -> "дня"
            else -> "дней"
        }
        return "$days $word"
    }

    private fun showRetentionDialog() {
        val options = SettingsStore.RETENTION_OPTIONS
        val labels = options.map { daysText(it) }.toTypedArray()
        val current = options.indexOf(SettingsStore.getRetentionDays(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Срок хранения записей")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                SettingsStore.setRetentionDays(this, options[which])
                updateRetentionText()
                Thread { AppLog.purge(applicationContext) }.start()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== Экспорт / импорт фильтров ====================

    private fun confirmExport() {
        AlertDialog.Builder(this)
            .setTitle("Экспорт фильтров")
            .setMessage(
                "В файл попадут все фильтры вместе с ID Instance и API Token GREEN-API — " +
                    "в открытом виде. Храните файл в надёжном месте и не отправляйте посторонним."
            )
            .setPositiveButton("Сохранить файл") { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, FiltersBackup.defaultFileName())
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_EXPORT_FILTERS)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_IMPORT_FILTERS)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_EXPORT_FILTERS -> {
                try {
                    val db = DatabaseHelper(this)
                    val json = FiltersBackup.export(db)
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    AppLog.i(this, "СИСТЕМА", "Фильтры экспортированы в файл")
                    Toast.makeText(this, "Фильтры сохранены в файл", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    AppLog.e(this, "СИСТЕМА", "Ошибка экспорта фильтров: ${e.message}")
                    Toast.makeText(this, "Не удалось сохранить файл: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            REQ_IMPORT_FILTERS -> {
                val json = try {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
                if (json == null) {
                    Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show()
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle("Импорт фильтров")
                    .setMessage(
                        "«Добавить» — фильтры из файла будут добавлены к текущим (одинаковые пропускаются).\n\n" +
                            "«Заменить все» — текущие фильтры будут удалены."
                    )
                    .setPositiveButton("Добавить") { _, _ -> runImport(json, false) }
                    .setNeutralButton("Заменить все") { _, _ -> runImport(json, true) }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    private fun runImport(json: String, replace: Boolean) {
        try {
            val result = FiltersBackup.importFrom(DatabaseHelper(this), json, replace)
            val mode = if (replace) "замена" else "добавление"
            AppLog.i(this, "СИСТЕМА", "Импорт фильтров ($mode): добавлено ${result.added}, пропущено ${result.skipped}")
            Toast.makeText(
                this,
                "Добавлено: ${result.added}, пропущено: ${result.skipped}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: IllegalArgumentException) {
            AppLog.e(this, "СИСТЕМА", "Ошибка импорта фильтров: ${e.message}")
            Toast.makeText(this, e.message ?: "Ошибка импорта", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.e(this, "СИСТЕМА", "Ошибка импорта фильтров: ${e.message}")
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
