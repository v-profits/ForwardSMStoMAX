package com.example.forwardsmstomax  // или forwardsmstotelegram

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Адаптер для списка контактов в диалоге выбора
 * Поддерживает:
 * - Отображение имени и номера
 * - Чекбоксы для выбора
 * - Фильтрацию по имени (поиск)
 * - Выбор всех контактов
 */
class ContactAdapter(
    private var contacts: List<ContactData>,
    private val context: Context,
    private val rootView: View
) : RecyclerView.Adapter<ContactAdapter.ViewHolder>(), Filterable {

    private var onSelectionChanged: (() -> Unit)? = null
    private var filteredContacts: List<ContactData> = contacts

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkBox: CheckBox = itemView.findViewById(R.id.contactCheckBox)      // чекбокс
        val name: TextView = itemView.findViewById(R.id.contactName)              // имя контакта
        val phoneNumber: TextView = itemView.findViewById(R.id.contactNumber)     // номер телефона
        val linearLayout: LinearLayout = itemView.findViewById(R.id.linearLayout) // кликабельная область
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = filteredContacts.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = filteredContacts[position]
        holder.name.text = contact.nameContact
        holder.phoneNumber.text = contact.phoneNumber

        // Сбрасываем слушатель, чтобы не было глюков при переиспользовании
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = contact.isSelected

        // Клик по строке — скрываем клавиатуру
        holder.linearLayout.setOnClickListener { hideKeyboardAndClearFocus() }

        // Обработка изменения чекбокса
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            contact.isSelected = isChecked
            hideKeyboardAndClearFocus()
            onSelectionChanged?.invoke()  // уведомляем о смене выбора
        }
    }

    /**
     * Выбрать/снять все контакты
     */
    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(select: Boolean) {
        contacts.forEach { it.isSelected = select }
        filteredContacts = contacts
        notifyDataSetChanged()
    }

    /**
     * Коллбек при изменении выбора (обновляет чекбокс "выбрать все")
     */
    fun setOnSelectionChangedListener(listener: () -> Unit) {
        onSelectionChanged = listener
    }

    /**
     * Скрыть клавиатуру и убрать фокус
     */
    private fun hideKeyboardAndClearFocus() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
        rootView.clearFocus()
    }

    /**
     * Получить список выбранных контактов
     */
    fun getSelectedContacts(): List<ContactData> {
        return contacts.filter { it.isSelected }
    }

    /**
     * Фильтр для поиска по имени контакта
     */
    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(query: CharSequence?): FilterResults {
                val filterResults = FilterResults()
                val q = query.toString().lowercase()
                filterResults.values = if (q.isEmpty()) contacts
                else contacts.filter { it.nameContact.lowercase().contains(q) }
                return filterResults
            }

            @SuppressLint("NotifyDataSetChanged")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                filteredContacts = results?.values as List<ContactData>
                notifyDataSetChanged()
            }
        }
    }
}