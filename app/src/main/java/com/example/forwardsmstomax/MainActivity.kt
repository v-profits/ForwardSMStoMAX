package com.example.forwardsmstomax  // ← MAX версия

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
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

    // Telegram версия: были эти поля (в MAX они не используются, но оставлены для совместимости)
    lateinit var idUserEditText: TextInputEditText          // ID чата Telegram
    lateinit var apiTockenEditText: TextInputEditText       // токен Telegram бота

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

        // Настройка RecyclerView
        recyclerView = findViewById(R.id.recyclerViewUsers)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Инициализация БД и загрузка списка фильтров
        dbHelper = DatabaseHelper(this)
        val rawUsers = dbHelper.getAllUsers()
        userList = rawUsers.map { FilterData(it.id, it.telegramUserId, it.filterName, it.apiTokenBot, it.isActive) }.toMutableList()
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

    // ==================== ТОЛЬКО ДЛЯ TELEGRAM ВЕРСИИ ====================
    // В MAX версии эта функция не нужна, но пока оставлена
    @SuppressLint("QueryPermissionsNeeded")
    fun openTelegramBot(context: Context) {
        val botUsername = "forward_sms_to_telegram_bot"
        val uri = Uri.parse("https://t.me/$botUsername")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("org.telegram.messenger")
        }
        if (intent.resolveActivity(packageManager) != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
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