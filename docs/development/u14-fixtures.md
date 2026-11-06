# U1.4: одинаковая fixture и граница harness

**OPEN.** Здесь нет снимков заполненной доски, визуального PASS или одобрения
пользователя. Device lease остаётся у primary; агент не использовал ADB, устройство,
эмулятор, picker, credentials, SQL, второй StorageRuntime или пользовательские данные.

## Замороженный источник

`app/src/androidTest/assets/u14/ХОЧУ.linka` — побайтовая копия
`2073d912f6c70ab0165823f37e4b5704cf72e953:app/src/main/assets/sets/ХОЧУ.linka`.
Размер **2 866 142** байта; SHA-256:

```text
a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b
```

В `config.json` действительно **version="1.2"**, не 1.3, directSet=false,
withoutSpace=false; одна страница, 3×3, 9 content-карточек. Публичный
`LinkaWire.parse(..., legacyUpgrade=false)` разрешает 1.2. Версию не меняли,
private upgrade не используется. В ZIP 19 записей: config + 9 изображений
(GIF/PNG/JPEG) + 9 OGG-записей. Повторяющийся исходный card id=4 не исправляется:
импорт создаёт внутренние идентификаторы и сохраняет порядок массива.

Порядок слева направо, сверху вниз:

1. КУШАТЬ
2. ПИТЬ
3. СПАТЬ
4. КУПАТЬСЯ
5. ХОЧУ
6. ПИСАТЬ КАКАТЬ
7. ГУЛЯТЬ
8. ИГРАТЬ
9. ОБНИМАТЬСЯ

Первая обязательная пара: `board-filled`, страница **0** (zero-based), сообщение
из двух частей **ХОЧУ → КУШАТЬ**, без запуска воспроизведения. Исходный архив
baseline и новый test asset должны иметь указанный SHA до/после импорта.
Статическая проверка ZIP не доказывает native decode/отрисовку/воспроизведение.

## Подтверждённый blocker полноценного guarded import

Реальный путь:

1. `DevelopmentActivity.acceptIntent(ACTION_VIEW)` → `Window.incomingDocument(uri)`:
   сохраняет incoming, приостанавливает доступ, показывает уведомление; не импортирует.
2. `LinkaApp` → стартовая библиотека (принятый navigation binding) → меню импорта →
   `TransferScreen`. Для Unprotected prepare допускается с null capability;
   перед confirm readiness требует новую capability через явную кнопку
   `Разрешить команды в этом экране`. Это настоящий UI `access.unlock`,
   не поддельная capability и не direct API с пустым паролем.
3. `Выбрать .linka для импорта (без записи в библиотеку)` → gate.check →
   `TransferCoordinator.prepareImport` → `files.pick(LINKA)`.
4. `AndroidPlatformFilesPort.pick` создаёт ACTION_OPEN_DOCUMENT с **type="*/*"**,
   EXTRA_MIME_TYPES=[application/zip, application/octet-stream].
5. `AndroidProductRuntime.Window.document` считает запрос архивом только при
   **intent.type in {application/octet-stream, application/zip}**. Поэтому incoming
   **не забирается**, вызывается настоящий системный picker.
6. Даже исправленный routing сам по себе не делает file:// подходящим:
   `AndroidPlatformFilesPort.present` требует **content:// с authority**.
   В текущих manifest нет FileProvider, публикующего test-owned cache.

Это mismatch production-root/document-port и отсутствующий transport seam, не
основание обходить guard или менять storage. В этой ветке они **не исправлены**.
Primary дополнительно разрешил `app/src/androidTest/AndroidManifest.xml` и
`ParityFixtureProvider.kt` с non-exported/read-only/nonce scope, если file URI
непригоден. Но test APK provider и instrumentation target живут под разными UID:
`instrumentation.context` не меняет UID вызывающего процесса. Для записи файла
в cache **test APK** и выдачи grant от его владельца нужен owner-side test
bootstrap component. Одних Context/grantUriPermission из target процесса
недостаточно. Такой bootstrap не добавлен, native UID/grant поведение не проверено
на устройстве. Нельзя молча делать provider exported, выдавать глобальные URI
или подменять resolver. Нужен отдельно согласованный owner-side test bootstrap
либо явно замороженный child-only instrumentation entry через настоящий runtime.
Общая защита, foreground и повторная авторизация после system presentation
должны сохраниться. Нельзя назвать private legacy-upgrade публичным импортом.

После согласования seam ожидаемый путь: стартовая **Библиотека** → меню →
`Импорт` (текущий baseline до UI-интеграции:
`Импорт, резервная копия и восстановление`) → ввод имени **ХОЧУ** через настоящее
поле `Название импортируемой доски` вместо default `Импортированная доска` →
prepare без обязательного initial unlock в Unprotected →
preview 1 страница/9 карточек/9 изображений/9 записей →
явный foreground UI unlock для новой capability перед confirm →
`Подтверждаю импорт «ХОЧУ» как новой доски` → completed → библиотека → ХОЧУ →
проверка всех 9 изображений и пустого сообщения → ХОЧУ, КУШАТЬ → filled screenshot.
Не нажимать replace/restore, не подтверждать native auth/providers автоматически.
Нельзя незаметно открыть пустой startup вместо этой заполненной доски.

## Что уже делает instrumentation

`DevelopmentParityTest.baselineFixtureHasUnmodifiedNineCardMedia` проверяет SHA,
версию/сетку/порядок и наличие ненулевых байтов всех 18 медиа.

`DevelopmentParityTest.incomingFixtureRequiresExplicitUiImport` — **только
ingress preflight**, не full-import test: свежий собственный development sandbox,
проверка package и UID, копия asset в уникальный target.cacheDir tempfile,
same-UID synthetic `Instrumentation.callActivityOnNewIntent(ACTION_VIEW)` через
настоящий `DevelopmentActivity.onNewIntent`, ожидание стартовой Библиотеки и
incoming notice, проверка отсутствия автоматического импорта.
file:// не передаётся через внешний process boundary и не маскируется под content://.
Удаляется только собственная cache-копия; SHA оригинала проверяется до/после.
Библиотека/credentials не чистятся и не заполняются напрямую.

UI определяется actual text/content-description. Этот preflight не нажимает
кнопок; будущий полный harness должен нажимать центр bounds ближайшего enabled
visible clickable ancestor actual named selector, а не произвольные координаты.
Ожидание ограничено deadline 20 секунд,
без fixed sleep; неожиданный package немедленно прерывает тест. Нет XML-дампов,
паролей, произвольных координат или автонажатий в системных окнах.
Полный harness до filled-board **заблокирован описанным seam**, не отмечен PASS.
Новые начальные route/toolbar selectors должны быть интегрированы из UI lane:
исходный runtime dependency этого worktree ещё начинает с сообщения и поэтому
UI preflight в одиночку до интеграции navigation lane ожидаемо не пройдёт.

После одобрения primary запускает методы на known-owned API35/21 через Android
MCP/instrumentation runner `androidx.test.runner.AndroidJUnitRunner`, class
`su.linka.pictures.activity.DevelopmentParityTest`. UI-ingress method требует
свежего sandbox; не запускать после `DevelopmentStartupTest` в той же уже открытой
библиотеке и не сбрасывать чужие данные ради теста. Существующий startup test не менялся.

Локальная проверка без устройства (JDK17, один worker, не APK/release/full gate):

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Volumes/data/developer-state/android-sdk ./gradlew :app:compileDebugAndroidTestKotlin --max-workers=1 --console=plain
python3 scripts/check-ui-parity-evidence.py --self-test
```

## Manifest настоящих evidence

Primary создаёт **новый** каталог для каждой съёмки; старые снимки не заменять.
Manifest не генерируется до появления настоящих PNG. Скрипт ничего не снимает,
не создаёт placeholders и не меняет review/approval.

JSON: `schema_version: 1`, `pairs: [{state: "board-filled", baseline: {...}, new: {...}}]`.
Обязательные поля каждой стороны:

| Поле | Значение / источник |
|---|---|
| build | Идентичность реально установленного APK/build, не только исходников |
| commit | Полный 40-символьный commit SHA |
| dirty, wip_diff_sha256 | Boolean; для WIP SHA-256 diff реально собранных исходников, иначе null. Включить staged/unstaged и untracked build inputs, а не хеш одного пустого git diff |
| package | baseline `su.linka.pictures`, new `su.linka.pictures.development` |
| os | Реальная OS/API/build, одинаковая у пары |
| viewport | width_dp, height_dp, width_px, height_px, font_scale, orientation (`portrait`/`landscape`); одинаковые у пары |
| fixture_sha256 | Замороженный SHA выше |
| page, card_order, message_parts | 0, список 9 названий выше, ["ХОЧУ", "КУШАТЬ"] |
| png, sha256 | Уникальный относительный путь к настоящему PNG, его SHA-256 |
| visually_reviewed, user_approved | Изначально **false / false**. Менять только после реального просмотра / отдельного решения пользователя |

Размеры pixels должны совпадать с PNG; dp/orientation/fontScale — с устройством,
не с выводом о похожести. При system bars указывать viewport полного screenshot,
не смешивать его с размером content-only crop; content area отдельно описать в
журнале. Нельзя сравнивать landscape с portrait или разные экраны.

```sh
python3 scripts/check-ui-parity-evidence.py /absolute/path/to/new-capture/manifest.json
```

Проверяются required `board-filled`, соответствие метаданных пары, identities,
имена/безопасные пути/уникальность PNG, размеры IHDR и SHA. Сообщение `Integrity OK`
**не означает** visual parity. Скрипт не доказывает содержимое screenshots и не
открывает PNG; каждый снимок primary должен реально просмотреть. Дополнительные
обязательные пары (library, empty message, multipage, editor, card dialog),
phone/tablet, orientation, large text и обе платформы остаются OPEN по
`ui-1.4-parity.md`, даже если первая filled-пара целостна.
