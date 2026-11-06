# Заполненная доска iOS через настоящий Files picker

Статус: **test-only сценарий, runtime NOT RUN; U1.4 OPEN**. Компиляция или статическая
проверка не доказывают импорт, изображения, звук, native UI PASS или визуальный паритет.
Нужны реальные снимки `12-board-empty` и `13-filled-message`, проверка повторного
открытия `15-reopened-filled-message`, `.xcresult` и отдельное визуальное одобрение.
Нормативные документы: [UI 1.4](ui-1.4-parity.md),
[изолированный XCTest host](ios-keychain-xctest-host.md).

## Изоляция и подготовка — только primary session

`iosAppHost/LinkaKeychainHost.xcodeproj` получает ровно два generated Info ключа
**только application target**: `UIFileSharingEnabled=YES` и
`LSSupportsOpeningDocumentsInPlace=YES`. Его `Documents` — исключительно sandbox
fixture vending для локального Files. Host не запускает product root, библиотеку
или аудио; Keychain не хранится в Documents. Публикуется только публичный fixture,
не Keychain/приватные данные. Production file sharing **не включается**; реальный
`iosApp`, entitlements, группы, signing policy и существующие тесты не меняются.
Нового File Provider, UTI, URL/delegate hook или source authority нет.

Primary после ревью собирает/устанавливает host на уже разрешённый Simulator с
согласованной Xcode Simulator подписью и уникальным **own** `LINKA_HOST_BUNDLE_ID`.
Без production, profiles, physical devices, account/identity/certificate изменений.
Через app data container **этого host** primary кладёт неизменённый
`app/src/androidTest/assets/u14/ХОЧУ.linka` в
`Documents/u14-fixtures/ХОЧУ.linka`, проверив SHA256 источника и результата:

`a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b`

Это публичный архив version 1.2: 3×3, девять изображений, девять записей, одна
страница. Не создавать substitute JSON/SQL, не перепаковывать архив. Documents host
содержит только staged public fixture. Фактический generated `CFBundleName` должен
остаться `LinkaKeychainHost`; unique bundle ID не меняет имя папки Files.

Реальное development-приложение `su.linka.pictures.development` уже установлено
primary с own group и синтетическими данными. Тест требует пустую читаемую библиотеку
и пустую фразу, не очищает их. Если доска уже импортирована либо библиотека неизвестна,
это **FAIL / reopen-only required**, а не разрешение удалить данные или импортировать
дубликат. После первого импорта повторять этот import-тест нельзя без отдельно
согласованной подготовки; проверка reopen-only — отдельная задача.

## Единственный новый сценарий

`LinkaStartupUITests/FilledBoardImportTests/testImportOwnedFixtureThroughFilesAndCompose`
в существующем standalone UI target (без app dependency и Kotlin runtime):

1. «Ваши наборы» и точный текст пустой библиотеки → «Меню библиотеки» →
   «К сообщению» → `Общее сообщение: пусто` → «Открыть библиотеку».
2. Меню → «Импорт, резервная копия и восстановление» → «Файлы и восстановление».
   В native text field/text view «Название импортируемой доски» проверить default
   «Импортированная доска», выбрать его через стандартное edit menu
   «Выбрать всё» / «Выделить всё» / `Select All` / `Select all` и ввести «ХОЧУ».
   Никаких clipboard, InsertText сообщения, clear-message или app-state hooks.
3. «Выбрать .linka для импорта (без записи в библиотеку)» → настоящий
   `UIDocumentPickerViewController`: «Обзор» / `Browse` → «На моём iPhone» /
   `On My iPhone` → `LinkaKeychainHost` → `u14-fixtures` → `ХОЧУ.linka`.
   Допускается стандартное скрытие расширения (`ХОЧУ`) внутри этой папки. Не открывать
   iCloud, аккаунты, Download, запросы разрешений или другой provider.
4. Вернуться в настоящий preview с `Доска: «ХОЧУ»` и префиксом
   `Страниц: 1; карточек: 9; изображений: 9; записей: 9;` → отдельное нажатие
   «Разрешить команды в этом экране» → «Подтверждаю импорт «ХОЧУ» как новой доски» →
   только `Операция завершена.`. Возврат из picker не выдаёт capability.
   UNCONFIRMED/failed/conflict не считаются успехом; повторного импорта нет.
5. «Назад» → библиотека → уникальная кнопка меню плитки
   «Действия с набором «ХОЧУ»» → «Открыть». Это UI самой плитки, не совпадение
   с заголовком toolbar/поиском. Проверить исходный COMPOSE через пункт
   «Перейти в прямой режим» (не нажимать его), закрыть меню тапом по toolbar.
   Все девять **button-role** карточек должны одновременно быть hittable:
   КУШАТЬ, ПИТЬ, СПАТЬ, КУПАТЬСЯ, ХОЧУ, ПИСАТЬ КАКАТЬ, ГУЛЯТЬ, ИГРАТЬ,
   ОБНИМАТЬСЯ. Тапнуть ХОЧУ, КУШАТЬ, дождаться
   `Общее сообщение: ХОЧУ КУШАТЬ`. Через «Открыть библиотеку» вернуться и открыть
   ту же доску: фраза и девять карточек сохраняются. Не переключать DIRECT и не
   вызывать TTS/Play/Record/Mic/SaveCard/Grid/settings/permissions.

`IosFileSelection.kt` выбирает `UTTypeData`, `asCopy=false`, single URL. Настоящий
picker и NSFileCoordinator копируют файл в зарегистрированный temporary источник;
direct source port не читает Documents host. Тест не подменяет URL/callback/lease.
Каждый известный этап имеет фиксированный ID и screenshot attachment. Ошибка даёт
текущий stage и screenshot без AX tree/debugDescription, URL или password dump.
Ожидания bounded predicate/hittable; прокрутка только реального ScrollView на
transfer-экране. Нет fixed sleep, skips, expected failures, reset и auto-retry.

## Непроверенные runtime-предпосылки / BLOCKED при несовпадении

- Files в remote picker действительно экспонируется через XCUIApplication продукта;
  Browse допускает переход из Recents/вложенного места через известную RU/EN кнопку.
- Локальный provider показывает `LinkaKeychainHost` и папку/архив с точными labels;
  стандартный скрытый extension допускается, иные AX labels ещё не подтверждены.
  Нет folder/fixture — остановиться и проверить staging, не обращаться к аккаунтам.
- Compose Field экспонируется как textField/textView с указанным label и обычным
  value; стандартный long press / Select All / typeText заменяет default без private
  keyboard API. Если edit menu не экспонировано — FAIL, а не обход через clipboard.
- Compose карточки и уникальное меню плитки имеют ожидаемый button role/identifier;
  закрытие dropdown тапом на видимую кнопку toolbar работает на разрешённом iPhone.
- Preview и операции попадают в реальный ScrollView; все девять карточек помещаются
  в текущем viewport. Плохая плотность не маскируется прокруткой сетки.

При несовпадении — FAIL и возврат primary с конкретным stage, не новая product
authority/настройка, обход защит или правка shared source ради зелёного теста.

## Проверки implementation lane

Только `plutil -lint` обоих PBX, `swiftc -frontend -parse` нового Swift, XML parse
существующей схемы и read-only `xcodebuild -showBuildSettings CODE_SIGNING_ALLOWED=NO`.
Никаких build/run, devices, staging, signing или Simulator операций в этой lane.
Primary отдельно проверяет actual generated product/host Info и подписанные артефакты,
затем staging hash, реальный import-run и снимки. Ранее полученные Keychain 2/2 и
startup/relaunch 1/1 этим сценарием не заменяются; полный UI1.4 gate остаётся OPEN.

Статические проверки implementation lane выполнены 6 ноября 2026: оба PBX lint,
Swift parse, XML схемы, оба showBuildSettings и проверка host-only flags / Swift
build references — **PASS**. ShowBuildSettings подтверждает оба ключа `YES`, имя
`LinkaKeychainHost` и `SUPPORTED_PLATFORMS=iphonesimulator`; это не проверка
собранного Info.plist. Runtime, staging и снимки в этой lane не выполнялись.
