package com.example.forwardsmstomax

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Объект для управления диалогом создания/редактирования фильтра
 * Содержит логику:
 * - Ввода имени фильтра
 * - Ввода данных MAX (chatId, idInstance, apiToken)
 * - Выбора контактов из адресной книги
 * - Сохранения в БД
 */
object FilterDialogHelper {

    /**
     * Показывает диалог для создания или редактирования фильтра
     * @param activity MainActivity
     */
    @SuppressLint("MissingInflatedId", "InflateParams")
    fun showDialogFilter(activity: MainActivity) {
        // Загружаем layout диалога
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_filter, null)

        // Настройка диалога (прозрачный фон, отступы)
        val dialog = Dialog(activity)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val margin = activity.resources.getDimensionPixelSize(R.dimen.dialog_margin)
        val width = Resources.getSystem().displayMetrics.widthPixels - 2 * margin
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Получаем элементы layout
        val switchContact = dialogView.findViewById<SwitchMaterial>(R.id.switch_contact)  // переключатель "ввести номер/выбрать контакты"
        val cardContact = dialogView.findViewById<CardView>(R.id.card_contact)            // кнопка "Открыть контакты"

        val buttonManual = dialogView.findViewById<ImageView>(R.id.manualIcon)    // кнопка "?"
        val buttonNo = dialogView.findViewById<TextView>(R.id.button_no)          // кнопка "Отмена"
        val buttonYes = dialogView.findViewById<TextView>(R.id.button_yes)        // кнопка "Сохранить"

        // Поля ввода
        activity.nameFilterEditText = dialogView.findViewById(R.id.nameFilterEditText)               // имя фильтра
        val nameFilterTextView = dialogView.findViewById<TextView>(R.id.nameFilterTextView)          // плавающая подпись

        activity.maxChatIdEditText = dialogView.findViewById(R.id.maxChatIdEditText)                 // ID чата в MAX
        val maxChatIdTextView = dialogView.findViewById<TextView>(R.id.maxChatIdTextView)            // плавающая подпись

        activity.maxIdInstanceEditText = dialogView.findViewById(R.id.maxIdInstanceEditText)         // ID Instance (GREEN-API)
        val maxIdInstanceTextView = dialogView.findViewById<TextView>(R.id.maxIdInstanceTextView)    // плавающая подпись

        activity.maxApiTokenEditText = dialogView.findViewById(R.id.maxApiTokenEditText)             // API Token (GREEN-API)
        val maxApiTokenTextView = dialogView.findViewById<TextView>(R.id.maxApiTokenTextView)        // плавающая подпись

        activity.phoneNumberEditText = dialogView.findViewById(R.id.phoneNumberEditText)             // номер телефона (ручной ввод)
        val phoneNumberTextView = dialogView.findViewById<TextView>(R.id.phoneNumberTextView)        // плавающая подпись

        // Скрываем/показываем плавающие подписи при фокусе
        activity.nameFilterEditText.setOnFocusChangeListener { _, hasFocus ->
            nameFilterTextView.visibility = if (hasFocus) View.GONE else View.VISIBLE
        }
        activity.maxChatIdEditText.setOnFocusChangeListener { _, hasFocus ->
            maxChatIdTextView.visibility = if (hasFocus) View.GONE else View.VISIBLE
        }
        activity.maxIdInstanceEditText.setOnFocusChangeListener { _, hasFocus ->
            maxIdInstanceTextView.visibility = if (hasFocus) View.GONE else View.VISIBLE
        }
        activity.maxApiTokenEditText.setOnFocusChangeListener { _, hasFocus ->
            maxApiTokenTextView.visibility = if (hasFocus) View.GONE else View.VISIBLE
        }
        activity.phoneNumberEditText.setOnFocusChangeListener { _, hasFocus ->
            phoneNumberTextView.visibility = if (hasFocus) View.GONE else View.VISIBLE
        }

        // Переключатель "Ввести номер вручную / Выбрать контакты"
        activity.phoneNumberEditText.visibility = if (switchContact.isChecked) View.VISIBLE else View.INVISIBLE
        cardContact.visibility = if (switchContact.isChecked) View.INVISIBLE else View.VISIBLE

        switchContact.setOnCheckedChangeListener { _, isChecked ->
            activity.phoneNumberEditText.visibility = if (isChecked) View.VISIBLE else View.INVISIBLE
            cardContact.visibility = if (isChecked) View.INVISIBLE else View.VISIBLE
            switchContact.text = if (isChecked) "Выбрать номера контактов" else "Ввести номер вручную"
        }

        // Кнопка "Открыть контакты" — показывает диалог выбора контактов
        cardContact.setOnClickListener {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 1)
            } else {
                val contacts = loadContacts(activity)
                activity.selectedContacts.clear()
                ContactDialogFragment(contacts) { selected ->
                    selected.forEach { activity.selectedContacts.add(it) }
                }.show(activity.supportFragmentManager, "contactDialog")
            }
        }

        // Кнопка "Сохранить"
        buttonYes.setOnClickListener {
            var ok = false
            val inputTextNameFilter = activity.nameFilterEditText.text.toString()
            val inputTextChatId = activity.maxChatIdEditText.text.toString()
            val inputTextIdInstance = activity.maxIdInstanceEditText.text.toString()
            val inputTextApiToken = activity.maxApiTokenEditText.text.toString()
            val inputTextContact = activity.phoneNumberEditText.text.toString()

            // Если редактируем и номера не меняли — загружаем из БД
            if (inputTextContact == "" && activity.selectedContacts.isEmpty() && activity.editId != 0L) {
                activity.selectedContacts = activity.dbHelper.getPhoneNumbersByUserId(activity.editId).toMutableList()
            }

            // Проверка заполнения полей
            if (inputTextNameFilter.isNotEmpty() &&
                inputTextChatId.isNotEmpty() &&
                inputTextIdInstance.isNotEmpty() &&
                inputTextApiToken.isNotEmpty() &&
                (inputTextContact.isNotEmpty() || activity.selectedContacts.isNotEmpty())) {

                ok = true
                dialog.dismiss()

                if (activity.editId == 0L) {
                    // ===== СОЗДАНИЕ НОВОГО ФИЛЬТРА =====
                    // 1. Добавляем пользователя в таблицу users
                    val userId = activity.dbHelper.addUser(
                        inputTextChatId,      // chatId сохраняется как telegramUserId
                        inputTextNameFilter,
                        inputTextApiToken,
                        isActive = true
                    )
                    // 2. Добавляем конфигурацию MAX в таблицу max_config
                    activity.dbHelper.addMaxConfig(
                        userId,
                        inputTextChatId,
                        inputTextIdInstance,
                        inputTextApiToken
                    )
                    // 3. Добавляем номера телефонов в таблицу phone_numbers
                    if (activity.selectedContacts.isNotEmpty()) {
                        for (contact in activity.selectedContacts) {
                            activity.dbHelper.addPhoneNumber(userId, contact.phoneNumber, contact.nameContact)
                        }
                    } else {
                        activity.dbHelper.addPhoneNumber(userId, inputTextContact, "")
                    }
                } else {
                    // ===== РЕДАКТИРОВАНИЕ ФИЛЬТРА =====
                    // 1. Обновляем данные в таблице users
                    val updated = activity.dbHelper.updateUserAndPhoneNumbers(
                        activity.editId,
                        inputTextChatId,
                        inputTextNameFilter,
                        inputTextApiToken,
                        isActive = true,
                        inputTextContact,
                        activity.selectedContacts
                    )
                    // 2. Обновляем конфигурацию MAX
                    activity.dbHelper.updateMaxConfig(
                        activity.editId,
                        inputTextChatId,
                        inputTextIdInstance,
                        inputTextApiToken
                    )
                    if (updated) {
                        Toast.makeText(activity, "✅ Данные успешно обновлены", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "❌ Ошибка при обновлении", Toast.LENGTH_SHORT).show()
                    }
                    activity.editId = 0L
                }
                // Обновляем список фильтров
                activity.refreshFilterList()
            } else {
                Toast.makeText(activity, "⚠️ Заполните все поля", Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка "Отмена"
        buttonNo.setOnClickListener { dialog.dismiss() }

        // Кнопка "?" — показывает инструкцию
        buttonManual.setOnClickListener {
            ManualDialogFragment().show(activity.supportFragmentManager, "manualDialog")
        }

        // Если редактируем — заполняем поля существующими данными
        if (activity.editId != 0L) {
            val maxConfig = activity.dbHelper.getMaxConfigByUserId(activity.editId)
            if (maxConfig != null) {
                activity.maxChatIdEditText.setText(maxConfig.chatId)
                activity.maxIdInstanceEditText.setText(maxConfig.idInstance)
                activity.maxApiTokenEditText.setText(maxConfig.apiToken)
            }
            val filterData = activity.dbHelper.getAllUsers().find { it.id == activity.editId }
            filterData?.let {
                activity.nameFilterEditText.setText(it.filterName)
            }
        }

        dialog.show()

        // Анимация поворота кнопки "+" при закрытии
        dialog.setOnDismissListener {
            val angle = if (activity.isRotated) 0f else 45f
            activity.buttonAdd.animate().rotation(angle).setDuration(1000).start()
            activity.isRotated = !activity.isRotated
        }
    }

    /**
     * Загружает список контактов из адресной книги телефона
     */
    fun loadContacts(activity: MainActivity): List<ContactData> {
        val contactList = mutableListOf<ContactData>()
        val cursor = activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                val number = it.getString(numberIndex)
                contactList.add(ContactData(name, number))
            }
        }
        return contactList
    }
}