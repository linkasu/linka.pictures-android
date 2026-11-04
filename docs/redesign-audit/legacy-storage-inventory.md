# P00: инвентарь хранения Android 1.4 (только факты legacy)

## Область и достоверность

- Проверено 4 ноября 2026, только worktree `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p00-android`.
- Начальное состояние: чистый worktree, **detached HEAD**, base `3deade7a9a9d82e6478b0a34d19c99ef204e5add`. Это документационный коммит поверх `v1.4` = `2073d912f6c70ab0165823f37e4b5704cf72e953`. `git diff v1.4 HEAD -- app/src/main shared/src/androidMain app/build.gradle` пуст: исследуемые исходники соответствуют тегу, не позднейшим исправлениям main.
- `app/build.gradle:28-36`: applicationId `su.linka.pictures`, minSdk 21, targetSdk 35, versionCode 5, versionName `1.4`. Это версия приложения, **не версия JSON**.
- Локальных/родительских `AGENTS.md` на проверенной цепочке каталогов не найдено; действуют переданные инструкции. `graphify-out/graph.json` отсутствует, graphify не запускался.
- **Факт** ниже означает прочитанный код или прочитанный Git blob встроенного архива. **Следствие** означает вывод из конкретной цепочки кода, а не воспроизведение на устройстве. **Не проверено** явно отделено. Не читались данные установленных приложений, реальные пароли, production, ключи, credentials; сборки, тесты с устройствами и изменения legacy-кода не выполнялись.
- В коде нет отдельного собственного класса `Preferences`: настройки реализованы AndroidX Preferences, `Cookie` и `TelemetryPrivacyPreferences`. Внешние SDK/ОС могут иметь своё внутреннее хранилище; это не перечисление всех внутренних writers Android/Firebase/Glide.
- Ниже используются точные сокращения путей: **S/** = `shared/src/androidMain/kotlin/su/linka/pictures/`, **A/** = `app/src/main/java/su/linka/pictures/`, **R/** = `app/src/main/res/`. Все номера строк относятся к base; исходники этим аудитом не меняются.

## 1. Физические источники и владельцы

| Данные | Реальный путь / источник | Кто создаёт / читает; время жизни |
|---|---|---|
| Библиотека `.linka` | `Context.filesDir/sets/<имя>`; не external storage | `S/SetsManager.kt:42-50,137`; только здесь менеджер хранит архивы. Обычно Android отображает filesDir как `/data/user/0/su.linka.pictures/files`, но конкретный путь устройства **не получен** |
| Встроенные наборы | `app/src/main/assets/sets/*` → `filesDir/sets` | `S/SetsManager.kt:24-40`: копируется всё из assets `sets`, по каждому исходному имени; флаг `ASSETS_LOADER` ставится после всего цикла |
| Импорт извне | `Intent.ACTION_VIEW`, `intent.data`, `ContentResolver.openInputStream(uri)` | `A/activity/BroadcastReceiverActivity.kt:19-67`; после подтверждения копирует байты непосредственно в библиотеку |
| Рабочая распаковка | `Context.cacheDir/workspace/<UUID>/` | `S/SetsManager.kt:97-105,124-145,238-243`; каждый open/create получает новый workspace. Не стабильный ID и не отдельный сохранённый черновик |
| Manifest и картинки preview | `Context.cacheDir/previews/<archive.nameWithoutExtension>/config.json`, пути картинок и `.source` | `S/SetsManager.kt:181-243`; `.source` содержит строку `archive.lastModified()`, не digest |
| TTS audio до вставки в набор | `Context.cacheDir/<UUID>.wav` | `A/TTS.kt:23-35`, Android TextToSpeech пишет файл; `EditCardDialog` затем копирует его в workspace |
| Запись микрофона до вставки | `File.createTempFile(<UUID>, ".3gpp")`, каталог явно не задан | `A/components/RecordButton.kt:83-109`; runtime default temp directory, фактический путь **не установлен**; формат THREE_GPP / AMR_NB |
| Общие UI-настройки | `PreferenceManager.getDefaultSharedPreferences(context)` | `A/components/ParentPasswordDialog.kt:11-18`, `A/activity/SettingsActivity.kt:38-40`, `R/xml/root_preferences.xml:4-15`. Имя файла не задаётся приложением; обычная AndroidX-конвенция `<package>_preferences.xml` не подтверждалась чтением устройства/исходников зависимости |
| Cookie / настройки конкретного файла | SharedPreferences с именем `my` (обычно `shared_prefs/my.xml`) | `S/Cookie.kt:14-31`: private preferences, `apply()`; ASSETS_LOADER Boolean и произвольные ключи имени файла Int в **одном** namespace |
| Выбор аналитики | SharedPreferences `telemetry_privacy`, key `analytics_state` String | `S/TelemetryPrivacy.kt:22-49`; не default preferences. UI `telemetry_analytics` не persistent |
| RAM-only | Карточки/редактируемый manifest, выбранная фраза, страница, текущие bitmap/audio refs, preview bitmap LRU | `A/activity/SetEditActivity.kt:27-35`, `A/components/EditCardDialog.kt:43-52`, `A/components/OutputLine.kt:35-48`, `CardGrid.kt:18-27`, `A/SetsAdapter.kt:23-27`. Это не ещё один формат резервной копии |

Cache не является гарантированным долговременным источником: сохранённый набор живёт в `filesDir/sets`, а несохранённая модель в RAM и её медиа в cache/temp. У кода нет общей операции очистки/освобождения workspace при закрытии, успешном save или ошибке распаковки; UUID-коллизия удаляется `createWorkspace:100-102`. Это не утверждение, что оставшийся workspace полностью восстанавливает черновик.

## 2. Полная цепочка собственных writers и их product-callers

Таблица перечисляет операции, создающие, меняющие, переименовывающие или удаляющие рассматриваемые данные. Внутренние private writers указаны вместе с вызывающим public API; тестовые вызовы отделены ниже.

| Writer (точные строки) | Все найденные вызывающие цепочки в product-коде | Эффект / граница |
|---|---|---|
| `SetsManager.loadDefaultSets` `S/SetsManager.kt:24-40`; `copyStream:246-252` | `A/activity/MainActivity.kt:67,186-193` | `FileOutputStream` по существующему имени перезаписывает файл; нет transactional batch. Затем `Cookie.set(ASSETS_LOADER,true)` |
| `getSetsDirectory:42-47`, `createWorkspace:97-105`, `getWorkspaceRoot:238-243` | Библиотека: loadDefaultSets/getSets/getSetFile/save, плюс importer `A/activity/BroadcastReceiverActivity.kt:52`; workspace: getSet и createSet | `mkdirs` результаты не проверяются; UUID workspace создаётся до чтения ZIP |
| `ZipFile.extractAll` `S/SetsManager.kt:124-134` | `getSet(String):121` ← `A/activity/GridActivity.kt:69`, `A/activity/SetEditActivity.kt:172` | Распаковывает **весь** ZIP, не только медиа из manifest |
| `createSet` `S/SetsManager.kt:139-145` | `A/activity/SetEditActivity.kt:156-161` | Создаёт папку и объект manifest; сам `config.json` ещё не записывает |
| `Set.addCard` `S/Set.kt:56-65` (RAM) | `A/activity/SetEditActivity.kt:133-153` ← callback EditCardDialog | Замена позиции или расширение с `Card(0,3)`; не append-only журнал |
| Изменение manifest (RAM) `A/activity/SetEditActivity.kt:65-80,196` | Resize button; checkbox; callback подтверждения save | rows/columns из `toIntOrNull` (без проверки положительности/верхней границы); withoutSpace. Не пишет архив до save |
| Изменение Card (RAM), `A/components/EditCardDialog.kt:70-75,123-131,257-294` | OK/delete диалога → callback → Set.addCard выше | Клонирует существующую карточку; новая имеет id 0. Удаление создаёт тип 3; типы не-standard обнуляют media refs, но не удаляют сами файлы |
| `Set.saveBitmap` `S/Set.kt:45-53` → `Utils.saveBitmapToFile` `S/Utils.kt:75-98` | **Единственный** product-caller `A/components/EditCardDialog.kt:271-278` | UUID `.png`, PNG quality 100; ошибка записи печатается, возвращается File; Boolean от compress не проверяется |
| `Set.copyAudioFile` `S/Set.kt:29-43,87-99` → `Utils.copy(File,File)` `S/Utils.kt:52-56` → `copy(InputStream,File):23-50` | **Единственный** product-caller `A/components/EditCardDialog.kt:281-287` | Тот же absolute path возвращается без копии; при коллизии имени `-1`, `-2`…; сохраняется только `file.name` в Card |
| `Utils.copy(InputStream,File)` `S/Utils.kt:23-50` | Предыдущая аудио-цепочка и **единственный import-handler** `A/activity/BroadcastReceiverActivity.kt:46-61` | Прямой FileOutputStream; ловит Exception, закрывает оба stream, возвращает destination даже после ошибки |
| `Set.writeConfig` `S/Set.kt:68-83` ← `SetManifest.toJSONObject:63-74` ← `Card.toJSONObject` `S/Card.kt:16-24` | **Единственный** product-caller `S/SetsManager.kt:148` ← save editor ниже | FileWriter перезаписывает `config.json`; JSONException приводит к return, остальные ошибки записи подавляются. Сериализаторы сами пишут только JSON-объекты в RAM |
| `SetsManager.save` `S/SetsManager.kt:147-159` → `Utils.zipFolder` `S/Utils.kt:109-130` | **Единственный** product-caller `A/activity/SetEditActivity.kt:191-211` (save menu `119-123`) | Прямо открывает целевой архив с усечением; ZIP всех верхнеуровневых файлов workspace; не рекурсивно, не reference-filtered. Обновляет archiveFile в RAM, clearPreview, callback |
| `SetsManager.rename` `S/SetsManager.kt:170-179` | **Единственный** caller `A/activity/MainActivity.kt:129-138` из long-click RENAME `76-85` | `File(parent,"$result.linka")`, `renameTo`; при false молчит. Не переписывает manifest и preference keys |
| `SetsManager.delete` `S/SetsManager.kt:161-168` | **Единственный** caller `A/activity/MainActivity.kt:140-149` из long-click DELETE `76-85` | `archiveFile.delete()` результат игнорируется; затем clearPreview. Не удаляет Cookie, workspace или исходный документ провайдера |
| `extractManifestConfig:194-203`, `ensurePreviewDir:205-221`, `getPreviewRoot:230-236` в `S/SetsManager.kt` | `getSets:54-74` → `getSetManifest(File):80-88` → extractManifestConfig; также overload getSetManifest(String):77 (внешних product-callers не найдено). ensurePreviewDir также вызывается getSetImage | Повторно удаляет и распаковывает config; смена mtime удаляет весь preview directory и пишет `.source`. cacheKey может совпасть у файлов с одинаковым stem |
| `getSetImage` `S/SetsManager.kt:181-191` | **Единственный** caller `A/SetsAdapter.kt:62-82` | Распаковывает картинку по manifest path, только если её ещё нет в preview |
| `clearPreview` `S/SetsManager.kt:223-228` | Только save/delete/успешный rename (`153,167,176`) | Рекурсивно удаляет preview. Импорт clearPreview не вызывает; проверка mtime выполняется при следующем чтении |
| `Cookie.set` `S/Cookie.kt:19-21` | Только `S/SetsManager.kt:39` | Boolean ASSETS_LOADER, apply; нет сброса через product UI |
| `Cookie.setSetSettings` `S/Cookie.kt:25-27` | Только `A/activity/GridActivity.kt:123-141`, строка 134 | Int по полному fileName, apply |
| AndroidX SwitchPreferenceCompat/EditTextPreference `R/xml/root_preferences.xml:6-14` | `A/activity/SettingsActivity.kt:38-40` устанавливает экран | Framework writer ключей parent / parent_password. Собственного обработчика записи/валидации/хеширования нет |
| `TelemetryPrivacyPreferences.writeState` `S/TelemetryPrivacy.kt:37-44` | setAnalyticsEnabled `30-32` ← `S/Telemetry.kt:31-35` ← `A/activity/MainActivity.kt:209-211` и `A/activity/SettingsActivity.kt:43-48` | Пишет enabled/disabled с apply; нет product-writer для unknown |
| `TTS.speakToBuffer` `A/TTS.kt:23-35` | Только `A/components/EditCardDialog.kt:209-231`, строка 216 | synthesizeToFile возвращает до callback завершения синтеза; код не ожидает готовность перед копированием |
| `RecordButton.startRecording` `A/components/RecordButton.kt:83-109`, удаления `105,128` | onClick `35-40` → permission callback `60-81`; stop `112-138` → listener в `EditCardDialog.kt:140-152` | MediaRecorder пишет temp; ошибки start/stop могут удалить файл. stopRecording передаёт сохранённую File-ссылку и после внутренней ошибки stop. onDestroy `47-58` не имеет product-callers, найденных поиском |

Прочие setters не являются disk writers: `SetManifest.withArchiveFile` (`S/SetManifest.kt:46-48`) не имеет product-callers; archiveFile меняется непосредственно в save/rename. `SetManifest.name/cacheKey:40-44` вычисляются из имени архива (до save — config.json), а не из стабильного ID. `GridSettingsView.commit` (`A/activity/GridSettingsView.kt:33-36`) меняет объект; disk writer — GridActivity → Cookie выше. Добавление/очистка выбранной фразы в OutputLine/OutputGrid не пишет диск.

## 3. Все собственные readers и дальнейшие потребители

| Reader | Callers / потребители |
|---|---|
| `S/SetsManager.kt:54-95,107-118,194-203`: listFiles, сортировка по mtime убыванию, config.json из ZIP, FileReader → JSONObject → SetManifest | `A/activity/MainActivity.kt:179-184`; перечисляет **каждый обычный файл**, без фильтра `.linka`. IOException на отдельном файле печатается и файл пропускается, из библиотеки не удаляется. `getSetManifest(String)` не имеет иных product-callers; File overload вызывается getSets |
| `S/SetsManager.kt:121-137`: getSetFile → распаковка ZIP → config.json | `A/activity/GridActivity.kt:60-82`; `A/activity/SetEditActivity.kt:101-106,170-189`. Ошибка ZIP завершает экран с сообщением. Заголовок/extra использует имя файла |
| `S/SetManifest.kt:19-38`: JSON defaults и массив cards; `40-57`: name/cacheKey/первая ненулевая imagePath | `SetsManager.parseManifest:92-95`; `A/SetsAdapter.kt:29-50` читает имя и default bitmap; `A/activity/MainActivity.kt:76-89,129-164` использует manifest для действий/extra |
| `S/SetsManager.kt:181-220`: marker и изображение preview; `S/Utils.kt:132-136`: decodeFile | `A/SetsAdapter.kt:62-82`; bitmap LRU key `<cacheKey>/<path>`, RAM очищается clear (`57-60`), executor shutdown (`86-88`) |
| `S/Set.kt:18-27`: bitmap и audio file относительно workspace | Изображение: `A/components/CardGrid.kt:107-123`, `EditCardGrid.kt:16-31`, `OutputGrid.kt:42-49`, `EditCardDialog.kt:159-194`. Audio: `EditCardDialog.kt:178-181,196-206`, `OutputLine.kt:176-193` (MediaPlayer) |
| Manifest rows/columns/cards | `A/components/CardGrid.kt:33-87,107-123`, `EditCardGrid.kt:16-31`; `A/activity/SetEditActivity.kt:65-80,182-189`. CardGrid pageIndex сбрасывается при setSet. `GridButton.kt:28-55` различает 0 standard / 1 space / 2 invisible / 3 create |
| Manifest withoutSpace, Card title/type/audio refs | `A/components/OutputLine.kt:70-95,136-208`: без пробелов собирает title/space в текст и TTS, иначе последовательное audio; `OutputGrid.kt:42-49` рисует только standard. `EditCardDialog.kt:103-111,159-194,257-294` читает/валидирует/редактирует карточки |
| `Cookie.get` `S/Cookie.kt:17` | Только `S/SetsManager.kt:25`, default false |
| `Cookie.getSetSettings` `S/Cookie.kt:23` | Только `A/activity/GridActivity.kt:76`, default 3; интерпретирует `A/activity/GridSettings.kt:8-25`, применяет GridActivity `144-157` |
| Default preferences parent / parent_password | Только `A/components/ParentPasswordDialog.kt:11-37`. Вызовы dialog: `A/activity/MainActivity.kt:109-117,167-176` (настройки / создать); `A/activity/GridActivity.kt:96-104,160-168` (настройки сетки / выход). Long-click edit/rename/delete и importer этот reader не вызывают |
| TelemetryPrivacyPreferences.state/readState `S/TelemetryPrivacy.kt:28,34-35`, mapping `5-19` | `S/Telemetry.kt:18-29` init/restore; UI `A/activity/MainActivity.kt:51-52,195-217`, `A/activity/SettingsActivity.kt:42-48`. Init вызывается `A/LinkaPicturesApp.kt:5-9`. Build разрешает collection только release (`app/build.gradle:52-58`); enabled не означает collection в debug |

Связанные временные readers: bitmap chooser `A/components/EditCardDialog.kt:81-101,249-255` читает `image/*` URI через ImageDecoder (API 28+) / MediaStore; generated image `233-246` использует `Utils.textAsBitmap` (`S/Utils.kt:58-73`), не внешний файл. Record/TTS temp читаются `Utils.copy` при commit карточки. Полной фразы, текущей страницы, несохранённых Card/manifest и picker-state persistence в соответствующих Activity/компонентах нет; MainActivity сохраняет в Bundle лишь pending analytics choice (`103-106`), не пользовательскую библиотеку.

## 4. Legacy schema и проверенные архивы

**Читаемый/записываемый контракт кода**, не новая спецификация (`S/SetManifest.kt:9-38,63-74`, `S/Card.kt:6-24`):

| Поле | Чтение | Запись |
|---|---|---|
| version | optString, default `1.0`; проверки поддерживаемой версии нет | Сохраняется прочитанная строка, у нового набора `1.0` |
| columns / rows | optInt, defaults 4 / 3; own validation диапазона отсутствует | Числа из модели |
| withoutSpace | optBoolean, default false | Boolean |
| cards | optJSONArray, нет массива → пустой список; non-object элементы пропускаются | Массив всех Card по порядку списка |
| Card.id / cardType | optInt без явного default (JSON API default 0); уникальность id не проверяется | id как есть; cardType `<3` как есть, `>=3` → **2** (включая удалённый/create placeholder 3) |
| Card.title / imagePath / audioPath | isNull → Kotlin null, иначе optString; отсутствующие поля также isNull | JSONObject.put с Kotlin null удаляет ключ, не пишет JSON null; пустая строка остаётся строкой |
| Любые другие поля | Не помещаются в модель | Не сериализуются обратно |

Manifest title/name не хранится отдельным полем контракта: имя — filesystem archive filename. Позиция — порядок в cards, не id. Config читается FileReader/пишется FileWriter без явно выбранного charset; точный runtime charset здесь не измерен. ZIP writer — `java.util.zip.ZipOutputStream`; reader — zip4j **2.11.5** (`shared/build.gradle:25-30`). Приложение не передаёт пароль ZIP, не реализует шифрование `.linka` и не связывает пароль родителя с ZIP.

Проверка **Git blobs**, а не пользовательских документов: Python zipfile/json прочитал `config.json` для каждого `git ls-tree` пути `app/src/main/assets/sets/`. Значения текстов, медиа и любых паролей в отчёт не выводились. Бинарный архив не имеет осмысленных номеров строк; источник схемы — entry `config.json` в точном пути ниже.

| Asset (точный путь под `app/src/main/assets/sets/`) | version | Card count / ZIP entries | Дополнительные поля относительно writer |
|---|---|---|---|
| `Болит.linka` | `1.3` | 12 / 25 | manifest description, directSet; Card height, width |
| `Еда.linka` | `1.2` | 9 / 19 | manifest description, directSet |
| `Клавиатура.linka` | `1.0` | 96 / 95 | Нет |
| `ХОЧУ.linka` | `1.2` | 9 / 19 | manifest description, directSet |
| `Цифры.linka` | `1.0` | 24 / 31 | Нет |
| `ШУМЫ ДОМА.linka` | `1.0` | 9 / 19 | manifest description |
| `цифры.linka` | `1.0` | 24 / 31 | Нет |

У всех семи tracked archives: ноль вложенных entry paths, ноль отсутствующих ненулевых media refs. Это не проверка декодирования медиа/валидности аудио. `Цифры.linka` и `цифры.linka` имеют один blob `9c23167b6ab189a28ba12610a04844050425e134`; в текущем worktree `Path.iterdir()` показывает только `цифры.linka`, всего шесть файлов. **Факт наблюдения**, совместимый с case-insensitive FS; настройки FS и содержимое доставленного APK не проверялись. Нельзя из этого выводить, сколько наборов лежит у конкретного пользователя.

## 5. Настройки: что переносится существующим кодом

«Переносится» здесь означает только попадание в `.linka` writer или механизм хранения 1.4. Это **не** решение о будущей миграции.

| Настройка / данные | Stored type / default / смысл | Переносимость в существующем `.linka` |
|---|---|---|
| rows, columns, withoutSpace | Manifest выше; без пробелов относится к самому набору | Да, сериализатор config.json |
| Card order, id, title, imagePath, audioPath, cardType | Модель выше; медиа из верхнего уровня workspace | Да, с указанными потерями/преобразованием cardType |
| description, directSet, width/height и неизвестные JSON fields | Присутствуют в исходных assets, модель их игнорирует | Сырые импортированные байты их сохраняют; **после editor save теряются**. Из одного имени directSet нельзя делать вывод о настройках GridActivity |
| `my` / ASSETS_LOADER | Boolean default false; одноразовая загрузка assets | Нет; локальный установочный флаг, не часть набора |
| `my` / `<полный fileName>` | Int default 3; 0 = оба off, 1 = output on, 2 = page buttons on, 3 = оба on. Для иных Int fromInt выставляет оба false, не bitmask любых старших битов | Нет. UI commit пишет 0..3; rename/delete не переносят/чистят ключ; повторный импорт под тем же именем использует старое значение |
| default prefs / `parent` | Boolean, reader default false; XML без explicit default | Нет |
| default prefs / `parent_password` | String, reader default empty, null → empty; не hash, не encrypted preference в own code | Нет; только локальное default preferences, не Card/Set JSON |
| `telemetry_privacy` / `analytics_state` | String `unknown`/`enabled`/`disabled`; отсутствующее/неизвестное → UNKNOWN, notice required | Нет; `telemetry_analytics` — UI key с persistent=false, не отдельная сохранённая копия выбора |
| Current page / selected phrase / unsaved draft / pending media | RAM/cache/temp, нет документированного export state | Нет. Pending analytics choice сохраняется только в Activity Bundle |

`app/src/main/AndroidManifest.xml:20-24` указывает allowBackup=true **вместе с** explicit rules: `R/xml/backup_rules.xml:2-12` исключает root/file/database/sharedpref/external/device_*; `R/xml/data_extraction_rules.xml:2-25` исключает их в cloud-backup и device-transfer. Следовательно, правила приложения исключают рассматриваемую библиотеку/настройки из системных backup/transfer; это не доказательство поведения каждого OEM. Собственного экспорта/шаринга `.linka`, SAF create-document или backup writer в product-коде 1.4 не найдено. Не следует обещать пользователю перенос локальных preferences через `.linka` или automatic restore.

## 6. Пароль: пустой, отсутствующий и Unicode

Источник — только имена ключей и логика; **никаких реальных значений пароля не читалось**.

1. `ParentPasswordDialog.kt:12-18`: если parent отсутствует/false, callback success без чтения пароля и без диалога. Сам пароль при выключении parent приложением не очищается; `dependency="parent"` в XML отключает UI редактирования, не является собственной операцией удаления ключа.
2. Если parent=true, отсутствующий parent_password читается как пустая строка; `getString(..., "").orEmpty()` также сводит null к empty. Проверки «пароль настроен» нет.
3. `InputDialog.kt:38-63` делает `text.toString().trim().orEmpty()`; **пустой после trim ввод вызывает onFail и не передаётся на сравнение**. Поэтому parent=true + missing/empty password не может успешно пройти этот диалог, даже при пустом вводе. Это статически подтверждённое следствие, не новый recovery/security policy.
4. При непустом вводе `ParentPasswordDialog.kt:25-30` сравнивает Kotlin String `==` с сохранённой строкой; приложение не вызывает NFC/NFD normalization, case folding, hashing, transcoding. Значит, Unicode принимается как строки, но канонически эквивалентные разные последовательности не объявляются равными автоматически. Emoji/кириллица не запрещены own code; фактическая клавиатура/IME не исследовалась.
5. Trim применяется **только к вводимой проверке**. Значение preferences перед сравнением не trim’ится. Если сохранённое значение имеет обрезаемые края или целиком состоит из обрезаемых пробельных символов, совпадение через этот InputDialog невозможно. Точный набор Unicode-пробелов определяется Kotlin trim/runtime, не отдельной таблицей приложения; конкретные code points тестами здесь не проверялись.
6. Writer пароля — AndroidX EditTextPreference из XML. В приложении нет собственного listener, задающего trimming, normalization, запрет пустого значения или recovery. Точное поведение диалога записи для empty/null/пробельных краёв и обработка отдельных IME/Unicode edge cases **не установлены** без проверки зависимости/runtime. Нельзя переносить trim из InputDialog на этот writer по аналогии.
7. Контроль применяется только к четырём цепочкам reader-table. Из факта локального хранения строки не следует запрет чтения её ОС, гарантия шифрования, политика будущего переноса или достаточность контроля для всех действий.

## 7. Подтверждённые границы потерь и неполной валидации

- **Импорт:** Manifest intent filters (`app/src/main/AndroidManifest.xml:79-119`) объявляют content MIME `application/vnd.linka`, `application/linkaapp`, `application/octet-stream` и file scheme. Код обрабатывает только ACTION_VIEW. `BroadcastReceiverActivity.kt:34-61` подтверждает «добавить», но не отдельно замену существующего файла; имя берётся из `uri.lastPathSegment`, не DISPLAY_NAME. Нет own sanitization/canonical-root check, pre-parse/schema/media validation или атомарной staging-публикации. Это факт отсутствия проверок, **не** доказанный exploit/поведение любого провайдера.
- **Прямые перезаписи:** import, assets-copy и save могут затронуть существующее имя. `Utils.copy` скрывает ошибки; import после этого способен логировать успех/открывать библиотеку с частичным файлом. Assets loader при исключении до конца не ставит флаг и следующий запуск повторяет копирование, потенциально перезаписывая уже существующие одноимённые файлы.
- **Save:** `writeConfig` не сообщает об ошибке; zipFolder может упаковать старый/повреждённый config и вызвать success. Writer открывает destination до чтения workspace, не использует temporary + atomic replace/rollback. При I/O failure прежний архив уже может быть усечён; IOException приводит к callback failure, но восстановления старых байтов нет (`S/SetsManager.kt:147-159`, `S/Utils.kt:109-130`).
- **Вложенные медиа:** reader распаковывает все директории ZIP, но writer перечисляет только верхний уровень и пытается открыть каждый элемент как FileInputStream. Вложенный каталог не обходится рекурсивно и может прервать save после усечения destination. Проверенные assets плоские; пользовательские архивы неизвестны.
- **JSON roundtrip:** неизвестные поля теряются, non-object cards пропускаются, missing fields заполняются defaults, null-key не сохраняется, cardType>=3 становится 2. Версия 1.2/1.3 может остаться в version при уже потерянных description/directSet/height/width. Нет validation «поддерживаемая версия» или полного сохранения raw JSON.
- **Медиа:** удаление/замена карточки не чистит старые файлы workspace; zipFolder включает и неиспользуемые файлы. Ошибки bitmap/audio записи могут дать ссылку на отсутствующий/неполный файл. TTS не ожидает завершения синтеза; recorder способен отдать File после stop-error и удаления. Конкретная частота/воспроизведение этих сбоев неизвестны.
- **Cache:** preview stamp только mtime; файлы с одинаковым stem и mtime могут использовать общий preview, нет own locking между config/preview extraction и clearPreview. Это риск stale/race, не воспроизведённая порча сохранённого архива. Cache eviction может лишить текущий editor медиа; reopening читает сохранённый ZIP, но не RAM-черновик.
- **Идентичность:** rename не меняет grid preferences key; delete оставляет его. File names из create/rename идут через общий InputDialog (trim/nonempty), но без own проверки разделителей/коллизии. renameTo outcome при существующем target зависит от runtime/filesystem и здесь не измерялся; overwrite/отказ не следует объявлять универсальным фактом.
- **Lifecycle:** `SetEditActivity.onCreate` заново create/load по extra, не восстанавливает модель/путь workspace; у него нет onSaveInstanceState для draft. `GridActivity` загружает set/settings заново; фраза и страница не сериализуются. Закрытие editor после confirm выставляет RESULT_OK даже без save (`213-218`); это не подтверждение сохранённого draft.
- **Backups:** явные exclusions означают отсутствие заявленного приложением автоматического переноса этих данных; деинсталляция/очистка app data и реальный device-transfer не проводились.

## 8. Проверки, тестовые callers и открытые пробелы

### Выполнено

- `pwd` → ровно заданный worktree; `git status --short --branch` → `## HEAD (no branch)`, без изменений; `git rev-parse HEAD` → base выше; `git branch -vv` подтвердил detached HEAD. Не создавалась/не переключалась ветка родительской сессии.
- Проверены AGENTS на цепочке родителей и `glob **/AGENTS.md` внутри worktree → не найдены; `test -f graphify-out/graph.json` → отсутствует.
- `git rev-parse v1.4` → `2073d912f6c70ab0165823f37e4b5704cf72e953`; `git diff v1.4 HEAD -- app/src/main shared/src/androidMain app/build.gradle` → пусто.
- `git ls-files` / `git ls-tree -r --name-only -z HEAD app/src/main/assets/sets` → проинвентаризированы Android product sources и семь tracked assets. Отдельный Python zipfile/json scan файловой системы показал шесть, scan Git blobs через `git show HEAD:<path>` — семь, с результатами schema-table выше.
- Dedicated grep с **абсолютным корнем этого worktree**, затем чтение найденных файлов: disk primitives (`FileOutputStream`, `FileWriter`, `writeText`, extract/zip/delete/rename), preferences API и callers (`save`, `copy`, `writeConfig`, saveBitmap/copyAudioFile, Cookie setters, setAnalyticsEnabled). Дополнительный `rg -n` выполнялся с cwd этого worktree по `app/src/main/java`, `shared/src`, `app/src/main/res/xml` и подтвердил приведённые product-цепочки. fff индекс основного проекта не использовался.
- Тестовые прямые callers не являются дополнительными product writers: `app/src/test/java/su/linka/pictures/SetTest.kt:33-81` — addCard/copyAudioFile; `app/src/androidTest/java/su/linka/pictures/SetInstrumentedTest.kt:40-89` — copyAudioFile/writeConfig; `app/src/test/java/su/linka/pictures/components/ParentPasswordDialogTest.kt:28-87` — очистка/запись synthetic default prefs и проверки enabled/correct/incorrect; `InputDialogTest.kt:31-87` — nonempty/empty/cancel. Они **не запускались**, реальные значения в инвентарь не включены.
- Telemetry test writers preferences: `app/src/test/java/su/linka/pictures/TelemetryPrivacyPreferencesTest.kt:19-49`, `TelemetryProcessInitializationTest.kt:21-55`, `activity/MainActivityPrivacyNoticeTest.kt:29-68`, `activity/SettingsActivityTelemetryRestoreTest.kt:24-49`. Fixtures `components/ComponentTestUtils.kt:22-38` и setup/teardown `SetTest`, `CardGridTest`, `EditCardDialogTest` пишут/чистят тестовые cache/temp. Эти операции не вызваны product-кодом. `BackupExclusionPolicyTest.kt:18-45` проверяет XML-политику, не реальный restore.
- `git diff --check` → без вывода; после добавления единственного нового файла `git diff --cached --check` → без ошибок (важно: unstaged diff сам по себе не проверяет untracked файл). `git diff --cached --stat` → только этот файл; `git diff --name-only` → пусто. Итоговый commit возвращается сопровождающей сессии, а не вписывается сам в себя. Никаких build/CI/source/других docs изменений не делалось.

### Открыто / не выдавать за установленные факты

- **G0/G4:** определения этих gate-ID не обнаружены в существующих `docs/c4` и `docs/redesign-audit`; этот инвентарь не переопределяет их и не объявляет закрытыми. Родительской сессии требуется сопоставить факты ниже со своим acceptance checklist.
- Не получены Play-delivered APK, его assets/merged manifest и доказательство побайтового соответствия установленной 1.4 тегу. Source baseline подтверждён; Play provenance — отдельный пробел (см. также `docs/c4/README.md:30-34`).
- Нет корпуса пользовательских `.linka` и private preferences из реальных установок: фактические версии/неизвестные поля/paths/коррупция/дубликаты имён/пустые или Unicode password states и распределение значений **недоступны**. Assets не заменяют такой корпус.
- Не проверены AndroidX EditTextPreference writer semantics для empty/Unicode, реальный runtime charset/temp path, Unicode code-point trim matrix, OEM backup/transfer, provider-specific lastPathSegment и renameTo collision behavior. Нет прибора/эмулятора в рамках этой задачи, не выполнялись fault-injection и process-death проверки.
- Не сделаны новые migration/security/product решения: не выбирались новый формат, normalization/хеширование пароля, recovery, новый backup policy, правила разрешения коллизий, приоритет cache или маппинг старых настроек в новую систему. Наличие старого writer/reader описывает данные, но само по себе не доказывает совместимость будущей реализации.
