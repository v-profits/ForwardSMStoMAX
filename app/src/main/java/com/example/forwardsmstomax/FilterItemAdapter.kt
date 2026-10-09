package com.example.forwardsmstomax

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView

/**
 * Адаптер для списка фильтров
 * Поддерживает:
 * - Свайп влево для открытия кнопок (удалить, редактировать, информация, вкл/выкл)
 * - Перетаскивание для изменения порядка
 * - Отображение активности фильтра (цветной минус)
 */
class FilterItemAdapter(
    private val dbHelper: DatabaseHelper,
    var items: MutableList<FilterData>,
    private val activity: Activity,
    private val onToggle: (position: Int, isActive: Boolean) -> Unit
) : RecyclerView.Adapter<FilterItemAdapter.FilterViewHolder>() {

    private var openedPosition: Int = RecyclerView.NO_POSITION  // какой элемент сейчас открыт (свайпнут)

    inner class FilterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val userIdText: TextView = itemView.findViewById(R.id.userIdText)           // ID чата
        val userNameText: TextView = itemView.findViewById(R.id.filterNameText)     // имя фильтра
        val minusImageView: ShapeableImageView = itemView.findViewById(R.id.minus)  // цветной индикатор

        private val powerIcon: ShapeableImageView = itemView.findViewById(R.id.powerIcon)   // вкл/выкл
        private val editIcon: ShapeableImageView = itemView.findViewById(R.id.editIcon)     // редактировать
        private val infoIcon: ShapeableImageView = itemView.findViewById(R.id.infoIcon)     // информация
        private val deleteIcon: ShapeableImageView = itemView.findViewById(R.id.deleteIcon) // удалить

        private val card = itemView.findViewById<LinearLayout>(R.id.foregroundItem)   // передняя панель
        private val drag = itemView.findViewById<ImageView>(R.id.drag)                // иконка перетаскивания
        val backgroundLayout = itemView.findViewById<LinearLayout>(R.id.backgroundLayout) // задняя панель (с кнопками)
        var backgroundWidth = 500f

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: FilterData, position: Int) {
            userNameText.text = item.filterName
            userIdText.text = formatNumberWithSpaces(item.telegramUserId)

            // Получаем ширину задней панели для свайпа
            backgroundLayout.post { backgroundWidth = backgroundLayout.width.toFloat() }

            // Обработка свайпа влево/вправо
            drag.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        dX = card.translationX
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawX - startX
                        val newX = (dX + delta).coerceIn(-backgroundWidth, 0f)
                        card.translationX = newX
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val currentX = card.translationX

                        // Если элемент не открыт
                        if (!item.isOpen) {
                            if (currentX < -50f) {
                                // Закрываем предыдущий открытый элемент (если есть)
                                if (openedPosition != RecyclerView.NO_POSITION && openedPosition != bindingAdapterPosition) {
                                    val previousItem = items[openedPosition]
                                    previousItem.isOpen = false
                                    notifyItemChanged(openedPosition)
                                }
                                // Открываем текущий
                                openedPosition = bindingAdapterPosition
                                card.animate().translationX(-backgroundWidth).setDuration(300).start()
                                item.isOpen = true
                            } else {
                                // Возвращаем на место
                                card.animate().translationX(0f).setDuration(300).start()
                                item.isOpen = false
                                // Если текущий элемент был открыт, сбрасываем позицию
                                if (openedPosition == bindingAdapterPosition) {
                                    openedPosition = RecyclerView.NO_POSITION
                                }
                            }
                        }
                        // Если элемент уже открыт
                        else {
                            if (currentX > -backgroundWidth + 50f) {
                                // Закрываем
                                card.animate().translationX(0f).setDuration(300).start()
                                item.isOpen = false
                                openedPosition = RecyclerView.NO_POSITION
                            } else {
                                // Возвращаем в открытое состояние
                                card.animate().translationX(-backgroundWidth).setDuration(300).start()
                                item.isOpen = true
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            colorMinus(item, position)

            // Кнопка вкл/выкл фильтра
            powerIcon.setOnClickListener {
                item.isActive = !item.isActive
                dbHelper.updateUserActiveStatus(item.id, item.isActive)
                colorMinus(item, position)
                onToggle(position, item.isActive)
                Toast.makeText(activity, if (item.isActive) "✅ Фильтр активен" else "⚠️ Фильтр не активен", Toast.LENGTH_SHORT).show()
            }

            // Кнопка редактирования
            editIcon.setOnClickListener {
                val mainActivity = activity as MainActivity
                mainActivity.selectedContacts.clear()

                val angle = if (mainActivity.isRotated) 0f else 45f
                mainActivity.buttonAdd.animate().rotation(angle).setDuration(1000).start()
                mainActivity.isRotated = !mainActivity.isRotated

                mainActivity.editId = item.id
                FilterDialogHelper.showDialogFilter(mainActivity)
                mainActivity.nameFilterEditText.setText(item.filterName)

                val maxConfig = dbHelper.getMaxConfigByUserId(item.id)
                if (maxConfig != null) {
                    mainActivity.maxChatIdEditText.setText(maxConfig.chatId)
                    mainActivity.maxIdInstanceEditText.setText(maxConfig.idInstance)
                    mainActivity.maxApiTokenEditText.setText(maxConfig.apiToken)
                }
            }

            // Кнопка информации
            infoIcon.setOnClickListener { showInfoDialog(item) }

            // Кнопка удаления
            deleteIcon.setOnClickListener { showDeleteDialog(item) }
        }

        /**
         * Диалог с подробной информацией о фильтре
         */
        @SuppressLint("MissingInflatedId")
        fun showInfoDialog(item: FilterData) {
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_info, null)

            val filterActivation = view.findViewById<TextView>(R.id.filterActivation)
            val filterName = view.findViewById<TextView>(R.id.filterName)
            val chatId = view.findViewById<TextView>(R.id.chatId)
            val idInstanceView = view.findViewById<TextView>(R.id.idInstance)        // ← MAX: ID Instance
            val apiToken = view.findViewById<TextView>(R.id.apiToken)
            val contactList = view.findViewById<TextView>(R.id.contactList)
            val idInstanceCardView = view.findViewById<androidx.cardview.widget.CardView>(R.id.idInstanceCardView)
            val apiCardView = view.findViewById<androidx.cardview.widget.CardView>(R.id.apiCardView)
            val cancelButton = view.findViewById<TextView>(R.id.button_cansel)

            filterActivation.text = if (item.isActive) "Пересылает сообщения" else "Не пересылает сообщения"
            filterName.text = item.filterName

            // MAX версия: получаем данные из max_config
            val maxConfig = dbHelper.getMaxConfigByUserId(item.id)
            if (maxConfig != null) {
                chatId.text = maxConfig.chatId
                idInstanceView.text = maxConfig.idInstance
                apiToken.text = maxConfig.apiToken
                idInstanceCardView.visibility = View.VISIBLE
                apiCardView.visibility = View.VISIBLE
            } else {
                chatId.text = "не задан"
                idInstanceView.text = "не задан"
                apiToken.text = "не задан"
            }

            // Список номеров телефонов (с выделением цветом)
            val listContactData = dbHelper.getPhoneNumbersByUserId(item.id)
            val spannable = SpannableStringBuilder("")
            for ((index, contact) in listContactData.withIndex()) {
                if (contact.nameContact != "") spannable.append("${contact.nameContact}\n")
                val start = spannable.length
                spannable.append(contact.phoneNumber)
                if (index != listContactData.size - 1) spannable.append("\n\n")
                val end = spannable.length
                spannable.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(activity, R.color.number)),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            contactList.setText(spannable, TextView.BufferType.SPANNABLE)

            val dialog = AlertDialog.Builder(activity).setView(view).create()
            cancelButton.setOnClickListener { dialog.dismiss() }
            dialog.window?.setBackgroundDrawableResource(R.color.white)
            dialog.show()
        }

        /**
         * Диалог подтверждения удаления фильтра
         */
        fun showDeleteDialog(item: FilterData) {
            AlertDialog.Builder(activity)
                .setIcon(R.drawable.outline_delete_outline_24)
                .setTitle("Подтверждение")
                .setMessage("Вы уверены, что хотите удалить этот фильтр?")
                .setPositiveButton("Да") { _, _ ->
                    if (dbHelper.deleteUserById(item.id)) {
                        (activity as? MainActivity)?.refreshFilterList()
                        Toast.makeText(activity, "Фильтр удален", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Ошибка удаления", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Нет", null)
                .create()
                .apply {
                    setOnShowListener {
                        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(activity, R.color.minusColor3))
                        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(activity, R.color.minusColor3))
                        val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
                        findViewById<TextView>(titleId)?.setTextColor(ContextCompat.getColor(activity, R.color.black))
                        findViewById<TextView>(android.R.id.message)?.setTextColor(ContextCompat.getColor(activity, R.color.black))
                    }
                    window?.setBackgroundDrawableResource(R.color.fonDialogFilter)
                    show()
                }
        }

        // Цвет индикатора активности (разный цвет для каждого фильтра)
        fun colorMinus(item: FilterData, position: Int) {
            val colors = listOf(
                R.color.minusColor0, R.color.minusColor1, R.color.minusColor2,
                R.color.minusColor3, R.color.minusColor4, R.color.minusColor5,
                R.color.minusColor6, R.color.minusColor7
            )
            val colorRes = if (item.isActive) colors[position % colors.size] else R.color.transparent
            minusImageView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, colorRes))
        }

        private var dX = 0f
        private var startX = 0f
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
        return FilterViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_filter, parent, false))
    }

    override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
        val item = items[position]

        // Синхронизируем визуальное состояние свайпа при привязке
        if (position != openedPosition) {
            if (item.isOpen) {
                // Если элемент должен быть открыт, но позиция не совпадает — закрываем
                item.isOpen = false
            }
            // Сбрасываем перевод
            holder.itemView.findViewById<LinearLayout>(R.id.foregroundItem).apply {
                translationX = 0f
            }
        } else {
            // Если это открытый элемент, устанавливаем правильную позицию
            if (item.isOpen) {
                holder.itemView.findViewById<LinearLayout>(R.id.foregroundItem).apply {
                    translationX = -holder.backgroundWidth
                }
            }
        }

        holder.bind(item, position)
    }

    override fun getItemCount(): Int = items.size

    // Форматирование чисел с пробелами (например, "-1001234567890/26" → "-100 123 456 7890/26")
    private fun formatNumberWithSpaces(numberStr: String): String {
        val (mainPart, suffix) = if (numberStr.contains("/")) {
            val parts = numberStr.split("/")
            Pair(parts[0], "/" + parts.getOrNull(1).orEmpty())
        } else Pair(numberStr, "")
        val isNegative = mainPart.startsWith("-")
        val digits = mainPart.removePrefix("-")
        val formatted = digits.reversed().chunked(3).joinToString(" ").reversed()
        return (if (isNegative) "-$formatted" else formatted) + suffix
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setItems_(newItems: MutableList<FilterData>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }
}