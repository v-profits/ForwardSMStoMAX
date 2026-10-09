package com.example.forwardsmstomax  // или forwardsmstotelegram

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * Диалог для выбора контактов из адресной книги
 * @param contactList полный список контактов
 * @param onSave коллбек с выбранными контактами
 */
class ContactDialogFragment(
    private val contactList: List<ContactData>,
    private val onSave: (List<ContactData>) -> Unit
) : DialogFragment() {

    private lateinit var adapter: ContactAdapter
    private lateinit var globalCheckboxListener: (CompoundButton, Boolean) -> Unit

    @SuppressLint("MissingInflatedId", "UseGetLayoutInflater")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_contacts, null)

        // Элементы UI
        val recyclerView = view.findViewById<RecyclerView>(R.id.contactsRecyclerView)
        val searchEditText = view.findViewById<EditText>(R.id.searchEditText)      // поиск
        val saveButton = view.findViewById<TextView>(R.id.saveButton)              // сохранить
        val cancelButton = view.findViewById<TextView>(R.id.cancelButton)          // отмена
        val imageClear = view.findViewById<ImageView>(R.id.image_clear)            // кнопка очистки поиска
        val contactsCheckBox = view.findViewById<MaterialCheckBox>(R.id.contactsCheckBox) // "выбрать все"

        imageClear.visibility = View.GONE

        // Чекбокс "выбрать все"
        globalCheckboxListener = { _, isChecked ->
            adapter.selectAll(isChecked)
        }
        contactsCheckBox.setOnCheckedChangeListener(globalCheckboxListener)

        // Убираем дубликаты по номеру телефона
        val uniqueContacts = contactList.distinctBy { normalizePhoneNumber(it.phoneNumber) }
        adapter = ContactAdapter(uniqueContacts, requireContext(), view)

        // Обновляем состояние чекбокса "выбрать все" при изменении выбора
        adapter.setOnSelectionChangedListener {
            val allSelected = adapter.getSelectedContacts().size == uniqueContacts.size
            contactsCheckBox.setOnCheckedChangeListener(null)
            contactsCheckBox.isChecked = allSelected
            contactsCheckBox.setOnCheckedChangeListener(globalCheckboxListener)
        }

        // Настройка RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        // Поиск по контактам
        searchEditText.addTextChangedListener {
            adapter.filter.filter(it.toString())
            imageClear.visibility = View.VISIBLE
            contactsCheckBox.visibility = View.GONE
        }

        // Кнопка очистки поиска
        imageClear.setOnClickListener {
            searchEditText.clearFocus()
            searchEditText.setText("")
            imageClear.visibility = View.GONE
            contactsCheckBox.visibility = View.VISIBLE
        }

        // Кнопка "Отмена"
        cancelButton.setOnClickListener { dismiss() }

        // Кнопка "Сохранить" — возвращаем выбранные контакты
        saveButton.setOnClickListener {
            onSave(adapter.getSelectedContacts())
            dismiss()
        }

        builder.setView(view)
        return builder.create()
    }

    /**
     * Нормализация номера для сравнения при удалении дубликатов
     */
    private fun normalizePhoneNumber(number: String): String {
        return number
            .lowercase()
            .replace("[^a-zа-я0-9+]".toRegex(), "")  // оставляем только буквы, цифры, +
            .replace("^\\+7".toRegex(), "8")         // +7 → 8
            .replace("^8(?=\\d{10})".toRegex(), "8") // оставляем 8 если 11 цифр
    }
}