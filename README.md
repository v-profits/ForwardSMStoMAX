# Обновление ForwardSMStoMAX: пакет com.semenenko.smstomax

Папка `ForwardSMStoMAX/` повторяет дерево проекта. В ней только изменённые и перенесённые файлы.
Скопируйте её содержимое в корень проекта с заменой, затем удалите три старые папки и файл из раздела «Что удалить».

## Что изменено

1. **Пространство имён.** `com.example.forwardsmstomax` → `com.semenenko.smstomax`.
   - Изменены `namespace` и `applicationId` в `app/build.gradle.kts`.
   - Все исходники перенесены в `app/src/{main,test,androidTest}/java/com/semenenko/smstomax/`.
   - Заменены строки `package` и импорты. Лишние комментарии в строке `package` убраны.
2. **Упоминания Telegram убраны.**
   - Колонка БД `users.telega_user_id` стала `users.max_chat_id`.
   - Поле `FilterData.telegramUserId` стало `maxChatId`.
   - Метод `getTelegramDataByUserId()` стал `getFilterDataByUserId()`.
   - Удалены функция `openTelegramBot()` и неиспользуемые поля `idUserEditText` и `apiTockenEditText` из `MainActivity`.
   - Стили `Theme.ForwardSMStoTelegram.*` стали `Theme.ForwardSMStoMAX.*`.
   - Удалён неиспользуемый ресурс `drawable/telegram.xml`.
   - Очищены комментарии в `strings.xml`, `dialog_filter.xml` и исходниках.
   - Единственное оставшееся упоминание — константа `COLUMN_LEGACY_CHAT_ID = "telega_user_id"` в `DatabaseHelper.kt`. Она нужна только миграции, которая читает старую колонку.
3. **Миграция БД 5 → 6** (`DATABASE_VERSION = 6`).
   - Таблица `users` пересоздаётся с колонкой `max_chat_id`, данные копируются.
   - Так сделано потому, что `RENAME COLUMN` не работает на Android 8–10 (minSdk = 26).
   - Цепочка миграций 4 → 5 → 6 теперь работает последовательно. Раньше на версии 5 срабатывала ветка «пересоздать БД».
   - SQL проверен на SQLite, строки и связанные таблицы сохраняются.
4. **Манифест.** Явно объявлены `INTERNET` и `WAKE_LOCK`. Теперь приложение не зависит от того, что разрешения приходят транзитивно через `firebase-messaging`.
   - Дополнительно объявлено `POST_NOTIFICATIONS`. Код запрашивает его в рантайме (Android 13+), но в манифесте оно не было указано, и система отклоняла запрос.
   - Зависимость `firebase-messaging-ktx` не удалялась. Теперь её можно убрать отдельным коммитом.

## Что удалить вручную (старые файлы остаются при копировании)

```
app/src/main/java/com/example/
app/src/test/java/com/example/
app/src/androidTest/java/com/example/
app/src/main/res/drawable/telegram.xml
```

Если после этого каталог `com/example` пуст, удалите его целиком.

## Применение

1. Закройте Android Studio и скопируйте содержимое `ForwardSMStoMAX/` в корень проекта с заменой.
2. Удалите старые файлы из списка выше.
3. Откройте проект и выполните **File → Sync Project with Gradle Files**, затем **Build → Clean Project** и **Rebuild Project**.
4. Удалите со смартфона старую версию приложения (`com.example.forwardsmstomax`).
   - Теперь это другое приложение с новым `applicationId`.
   - Фильтры и настройки GREEN-API придётся ввести заново, потому что БД старого приложения не переносится.
5. Коммит:

```
git status
git add .
git commit -m "Пакет com.semenenko.smstomax, удалены упоминания Telegram (БД v6), явные INTERNET/WAKE_LOCK/POST_NOTIFICATIONS в манифесте"
git push
```

`git add .` увидит переименования и удаления как перемещения файлов, и история сохранится.

## Проверка

- В проекте не осталось `com.example` и `telegram` (без учёта регистра), кроме `COLUMN_LEGACY_CHAT_ID` для миграции.
- Миграция SQL проверена на тестовой БД.
- Полную сборку в Android Studio я не запускал: в моём окружении нет Android SDK. Если после синхронизации появится ошибка, пришлите её текст.
