package com.semenenko.smstomax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import java.util.Collections

val REQUEST_CODE_ALL_PERMISSIONS = 101

class MainActivity : AppCompatActivity() {
    // ==================== ПОЛЯ ВВОДА (из диалога фильтра) ====================
    lateinit var nameFilterEditText: TextInputEditText      // имя фильтра
    lateinit var phoneNumberEditText: TextInputEditText     // номер телефона (ручной ввод)

    // MAX версия: новые поля для GREEN-API
    lateinit var maxChatIdEditText: TextInputEditText       // ID чата в MAX
    lateinit var maxIdInstanceEditText: TextInputEditText   // ID Instance (GREEN-API)
    lateinit var maxApiTokenEditText: TextInputEditText     // API Token (GREEN-API)

    lateinit var dbHelper: DatabaseHelper                   // работа с БД
    private var permissionJob: Job? = null                  // для ожидания разрешений
    var selectedContacts: MutableList<ContactData> = mutableListOf()  // выбранные контакты

    // Список разрешений, необходимых для работы приложения
    val requiredPermissions = mutableListOf(
        Manifest.permission.RECEIVE_SMS,    // приём SMS
        Manifest.permission.READ_SMS,       // чтение SMS
        Manifest.permission.READ_CONTACTS   // чтение контактов
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)    // уведомления (Android 13+)
    }.toTypedArray()

    lateinit var buttonAdd: CardView         // кнопка "+" (добавить фильтр)
    var isRotated = false                   // флаг поворота кнопки
    var editId = 0L                         // ID редактируемого фильтра (0 = новый)

    // RecyclerView для списка фильтров
    private lateinit var recyclerView: RecyclerView
    private lateinit var userAdapter: FilterItemAdapter
    private var userList: MutableList<FilterData> = mutableListOf()

    lateinit var textViewManual: TextView   // кнопка инструкции (не используется)

    @SuppressLint("BatteryLife", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15+ рисует приложение под статус-баром: сдвигаем содержимое вниз на высоту статус-бара,
        // иначе шапка (и шестерёнка) оказываются под ним и не получают нажатий
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root_main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        // Шестерёнка в шапке — экран «Настройки» (журнал, экспорт/импорт фильтров)
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Удаляем устаревшие записи журнала (срок хранения задаётся в настройках)
        Thread { AppLog.purge(applicationContext) }.start()

        // Настройка RecyclerView
        recyclerView = findViewById(R.id.recyclerViewUsers)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Инициализация БД и загрузка списка фильтров
        dbHelper = DatabaseHelper(this)
        val rawUsers = dbHelper.getAllUsers()
        userList = rawUsers.map { FilterData(it.id, it.maxChatId, it.filterName, it.apiTokenBot, it.isActive) }.toMutableList()
        userAdapter = FilterItemAdapter(dbHelper, userList, this) { position, isActive ->
            userList[position].isActive = isActive
            dbHelper.updateUserActiveStatus(userList[position].id, isActive)
        }
        recyclerView.adapter = userAdapter

        // Запуск фонового сервиса (если есть разрешения)
        if (PermissionHelper.checkPermissions(this))
            Functions.startForegroundServiceManually(this)

        // Кнопка "+" — открывает диалог создания/редактирования фильтра
        buttonAdd = findViewById(R.id.button_add)
        buttonAdd.setOnClickListener {
            if (!permissions()) {
                // Нет разрешений — показываем диалог запроса
                PermissionHelper.showPermissionDialog(this)
                permissionJob = CoroutineScope(Dispatchers.Default).launch {
                    while (isActive) {
                        withContext(Dispatchers.Main) {
                            if (permissions()) this@launch.cancel()
                        }
                        delay(500)
                    }
                }
            }
        }

        // Перетаскивание фильтров (изменение порядка)
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            var fromPosition = -1
            var toPosition = -1

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (fromPosition == -1) fromPosition = viewHolder.adapterPosition
                toPosition = target.adapterPosition
                Collections.swap(userAdapter.items, viewHolder.adapterPosition, target.adapterPosition)
                userAdapter.notifyItemMoved(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Сохраняем новый порядок в БД
                userAdapter.items.forEachIndexed { index, item ->
                    dbHelper.updateUserOrder(item.id, index)
                }
                val updatedList = dbHelper.getAllUsers()
                userAdapter.setItems_(updatedList)
                fromPosition = -1
                toPosition = -1
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    // После возврата с экрана настроек список мог измениться (импорт фильтров)
    override fun onResume() {
        super.onResume()
        if (::dbHelper.isInitialized && ::recyclerView.isInitialized) refreshFilterList()
    }

    // Обновление списка фильтров после добавления/удаления/редактирования
    @SuppressLint("NotifyDataSetChanged")
    fun refreshFilterList() {
        val newList = dbHelper.getAllUsers()
        userList = newList
        userAdapter = FilterItemAdapter(dbHelper, newList, this) { position, isActive ->
            userList[position].isActive = isActive
            dbHelper.updateUserActiveStatus(userList[position].id, isActive)
        }
        recyclerView.adapter = userAdapter
        userAdapter.notifyDataSetChanged()
    }

    // Проверка разрешений и открытие диалога фильтра
    private fun permissions(): Boolean {
        if (PermissionHelper.checkPermissions(this)) {
            Functions.startForegroundServiceManually(this)
            // Анимация поворота кнопки
            val angle = if (isRotated) 0f else 45f
            buttonAdd.animate().rotation(angle).setDuration(1000).start()
            isRotated = !isRotated
            // Открываем диалог создания/редактирования фильтра
            FilterDialogHelper.showDialogFilter(this)
            return true
        }
        return false
    }

    // Запрос на отключение оптимизации батареи (для работы в фоне)
    @SuppressLint("BatteryLife")
    fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}