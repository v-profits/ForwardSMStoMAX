package com.semenenko.smstomax.ui

import com.semenenko.smstomax.R
import com.semenenko.smstomax.util.AppLog
import com.semenenko.smstomax.util.LogEntry
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «Журнал событий»: путь каждого SMS по этапам.
 * Записи с одинаковым кодом (#abc) относятся к одному сообщению.
 */
class LogActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SAVE_LOG = 301
    }

    private lateinit var adapter: LogAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var switchProblems: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        tvEmpty = findViewById(R.id.tv_empty)
        switchProblems = findViewById(R.id.switch_problems)

        adapter = LogAdapter()
        val recycler = findViewById<RecyclerView>(R.id.recycler_log)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        switchProblems.setOnCheckedChangeListener { _, _ -> load() }

        findViewById<View>(R.id.btn_clear_log).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Очистить журнал?")
                .setMessage("Все записи будут удалены.")
                .setPositiveButton("Очистить") { _, _ ->
                    AppLog.clear(this)
                    load()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        findViewById<View>(R.id.btn_save_log).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TITLE,
                    "smstomax-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".txt"
                )
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SAVE_LOG)
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val onlyProblems = switchProblems.isChecked
        val appContext = applicationContext
        Thread {
            val list = AppLog.query(appContext, onlyProblems)
            runOnUiThread {
                adapter.submit(list)
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE_LOG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = AppLog.exportText(this)
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Журнал сохранён в файл", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось сохранить файл: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
        private var items: List<LogEntry> = emptyList()
        private val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

        fun submit(list: List<LogEntry>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val strip: View = view.findViewById(R.id.log_strip)
            val head: TextView = view.findViewById(R.id.log_head)
            val text: TextView = view.findViewById(R.id.log_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            val sb = StringBuilder(timeFormat.format(Date(e.time))).append("  ").append(e.stage)
            if (e.ref.isNotEmpty()) sb.append("  #").append(e.ref)
            if (e.sender.isNotEmpty()) sb.append("  ").append(e.sender)
            holder.head.text = sb.toString()
            holder.text.text = e.text
            holder.strip.setBackgroundColor(
                when (e.level) {
                    AppLog.ERROR -> Color.parseColor("#D32F2F")
                    AppLog.WARN -> Color.parseColor("#FFAB00")
                    else -> Color.parseColor("#2E9E5B")
                }
            )
        }
    }
}
