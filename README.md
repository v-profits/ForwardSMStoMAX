# Итоговое обновление 2: пакеты по папкам и оформление настроек

Пакет заменяет `update_final`. Он содержит полный набор изменений по сравнению с исходным репозиторием.

## Что изменено

### 1. Код разложен по пакетам (папкам)
```
app/src/main/java/com/semenenko/smstomax/
├── ui/     MainActivity, SettingsActivity, LogActivity, FilterItemAdapter, ContactAdapter,
│           ContactDialogFragment, ManualDialogFragment, FilterDialogHelper
├── data/   DatabaseHelper, DataClass (FilterData, ContactData), MaxConfigData, QueuedMessage,
│           SettingsStore, FiltersBackup
├── sms/    SMSReceiver, ForegroundService, BoilerRooms, Generators
└── util/   AppLog, Functions, PermissionHelper
```
- Поменялись строки `package` и импорты, а также пути классов в `AndroidManifest.xml` (`.ui.MainActivity`, `.sms.SMSReceiver` и так далее). Логика программы не менялась.
- Файл `txt` (заметки разработчика) остался в корне пакета.

### 2. Настройки
Каждый пункт теперь оформлен как кнопка: белая карточка со скруглённой рамкой и эффектом нажатия.

### 3. Всё, что вошло ранее
Пакет `com.semenenko.smstomax`, удаление упоминаний Telegram, миграция БД до версии 6, разрешения INTERNET/WAKE_LOCK/POST_NOTIFICATIONS, журнал событий, экспорт и импорт фильтров. Отладочных вставок нет.

## Установка (важен порядок)
1. Закройте Android Studio.
2. Скопируйте папку `app` из `ForwardSMStoMAX/` в корень проекта с заменой.
3. Скопируйте `delete_old_files.bat` в корень проекта (рядом с папкой `app`) и запустите двойным щелчком. Он удалит старые файлы из корня пакета: они переехали в подпапки, а при копировании остаются.
4. Откройте проект в Android Studio и выполните Sync, затем Run. Если Gradle сообщает `UP-TO-DATE`, удалите папки `app\build` и `.gradle` и повторите.

**Если пропустить шаг 3,** сборка не пройдёт: в проекте окажутся два одинаковых класса, например `MainActivity` в корне пакета и в `ui`.

## Коммит
```
git status
git add .
git commit -m "Пакет com.semenenko.smstomax, удалены упоминания Telegram, настройки, журнал событий, импорт/экспорт фильтров, код разложен по пакетам ui/data/sms/util"
git push
```
Если предыдущий коммит из `update_final` вы уже сделали, используйте короткое сообщение: «Код разложен по пакетам ui/data/sms/util, пункты настроек оформлены как кнопки».
Git покажет перемещение файлов как переименование, история сохранится.
