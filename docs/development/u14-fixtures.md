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

## Исторический incoming blocker; положительный путь — штатный SAF

В dependency `a9fc68c` incoming принимал file/content, но document reader —
только content; archive routing не учитывал реальный picker MIME `*/*`.
**Primary исправил этот контракт в своём root WIP:** incoming теперь только
content URI, archive routing учитывает `*/*`; negative helper tests принадлежат
primary. Здесь production root/reader не менялись. Устаревший app-level
`incomingFixtureRequiresExplicitUiImport`, ожидавший notice от file URI, удалён,
а не оставлен заведомо падающим или замаскированным тестом.

**API35: настоящий SAF, без подмены incoming URI.**
Primary через MCP заранее копирует **только этот архив** в Downloads собственного
API35 под уникальным именем **LINKa-U14-HOCHU-cfb199d.linka**, измеряет SHA/размер,
проверяет read state и фиксирует provenance. Тест выбирает этот файл в настоящем
DocumentsUI, приложение получает штатный SAF content URI/read grant. Этот сценарий
не использует fixture FileProvider, owner bootstrap, StrictMode relax, runtime
permission grants, guessed URI или SQL и не заменён API21-сценарием ниже.

**API21: отдельный owned incoming-content путь, не SAF proof.** У выбранного AVD
нет mounted Downloads/external storage; reset/recreate ради visual slice не нужен.
Primary добавил в **target debug APK** стандартный `androidx.core.content.FileProvider`:
authority `${applicationId}.parity-fixtures`, exported=false, grantUriPermissions=true,
XML только `<cache-path name="u14-fixtures" path="u14-fixtures/"/>`.
Это provider target UID, не test-APK provider и не custom framework; в этой lane
manifest/XML не менялись и foreign WIP не копировался.

`filledBoardThroughOwnedIncomingContent` проверяет PackageManager providerInfo
(authority/class/package/UID/enabled/exported/grantUriPermissions), debuggable
target и canonical containment cache/u14-fixtures. После общей проверки пустого UI
создаёт **одну уникальную** cache-копию неизменённого asset, получает URI через
`FileProvider.getUriForFile`, проверяет точные URI path segments и SHA через
настоящий ContentResolver. Затем same-UID native
`Instrumentation.callActivityOnNewIntent(ACTION_VIEW, application/octet-stream)`
доставляет URI в реальный DevelopmentActivity. Никаких URI grants другому UID.
Notice и пустая библиотека проверяются до выбора import; дальше общие реальные
UI prepare → preview → новая capability через UI → ImportNew → grid/phrase.
После постановки incoming все UI waits разрешают **только target package**:
DocumentsUI в этом варианте — ошибка, не fallback на другой способ ввода.
Копия сохраняется до terminal import/проверки фразы; в finally проверяются SHA
источника и копии, удаляется только эта копия. На ошибке Activity/owner закрывается
перед удалением; directory/чужие handles/пользовательские файлы не удаляются.
Это incoming-content + visual/data-state fixture path; **API21 SAF picker и
cross-UID OS read-grant остаются OPEN**.

Реализованный путь `filledBoardThroughRealSafImport`: **Ваши наборы** → проверка
пустой библиотеки → **Меню библиотеки → К сообщению** → каноническое
`Общее сообщение: пусто` → **Открыть библиотеку** → меню →
`Импорт` (текущий baseline до UI-интеграции:
`Импорт, резервная копия и восстановление`) → ввод имени **ХОЧУ** через настоящее
поле `Название импортируемой доски` вместо default `Импортированная доска` →
prepare без обязательного initial unlock в Unprotected → настоящий SAF picker →
Downloads/Загрузки → точное имя fixture (при необходимости штатный Search/Найти) →
preview 1 страница/9 карточек/9 изображений/9 записей →
явный foreground UI unlock для новой capability перед confirm →
`Подтверждаю импорт «ХОЧУ» как новой доски` → completed → библиотека → ХОЧУ →
проверка 9 видимых named card semantics в collection 3×3 и пустого сообщения →
ХОЧУ, КУШАТЬ → канонические content-description `Общее сообщение: ХОЧУ`, затем
`Общее сообщение: ХОЧУ КУШАТЬ`, действие Произнести и отсутствие Остановить.
Тест **не** доказывает пиксели изображений или отсутствие акустического transient:
UI no-autoplay проверяется по состоянию, реальное audio/PNG проверяет primary.
Не нажимать replace/restore, не подтверждать native auth/permissions/settings.
Нельзя незаметно открыть пустой startup вместо этой заполненной доски.

## Что уже делает instrumentation

`DevelopmentParityTest.baselineFixtureHasUnmodifiedNineCardMedia` проверяет SHA,
версию/сетку/порядок и наличие ненулевых байтов всех 18 медиа.

Оба import-метода и reopen до запуска Activity проверяют development
package/UID/debuggable, SHA упакованной fixture и обязательные
instrumentation arguments:

```text
u14FixtureName=LINKa-U14-HOCHU-cfb199d.linka
u14FixtureSha256=a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b
```

Для SAF SHA-argument — аттестация **измеренного primary** Downloads-файла, не вычисление
тестом через чужую файловую систему: до выдачи SAF grant файл напрямую не читается.
Его доступность подтверждается точным filename в реальном DocumentsUI **до выбора**,
preview/counts и карточки проверяются до и после явного commit соответственно.
Отсутствие source не приводит к импортированию другого файла. Primary проверяет
SHA Downloads-копии до/после; тест её не удаляет и не переименовывает.
До подготовки импорта тест проверяет **семантически пустую библиотеку и фразу
через UI**. Наличие SQLite после startup regression не является признаком
импортированной fixture и не блокирует тест. Непустая библиотека/фраза дают ясный
failure с инструкцией reopen-only; никаких дубликатов, удаления, очистки или reset.
Запускать **один выбранный метод**: full import и reopen — разные исходные состояния.

`reopenFilledBoardWithoutImportOrMessageEdits` требует те же два arguments,
существующую development-библиотеку, открывает ХОЧУ и проверяет grid/filled phrase;
затем отправляет настоящий `KEYCODE_BACK`, ждёт Ваши наборы, повторно открывает
ХОЧУ и проверяет те же 9 карточек и сохранённую фразу. Это actual Back dispatcher
regression, не pure navigation calculation. Native `Window.setBackHandler` /
`LinkaApp.onBackHandlerChanged` связывает primary; до интеграции и device-run PASS нет.
Не импортирует, не редактирует фразу, не произносит и не очищает. UI открытия доски
может штатно сохранить cursor. При отсутствии нужного состояния тест останавливается.

Успешный full/reopen test отправляет только passive instrumentation status
`u14State=board-filled`, fixture SHA, message parts, `UI_STATE_READY_ONLY` и
`u14InputPath`: `real-saf`, `owned-incoming-content; SAF-picker/OS-grant NOT evaluated`
или `reopen-native-back-regression`. В capture-журнале сохранять фактический путь
получения, не выдавать incoming-content за SAF evidence.
Никаких PNG и visual verdict. ActivityScenario штатно закрывается в конце:
primary после instrumentation снова открывает приложение/доску через MCP и
проверяет сохранённую фразу перед съёмкой, а не снимает закрывшуюся Activity.

Library/message selectors сверены с UI source `ad8ed7c`: `Ваши наборы`,
`Меню библиотеки`, `К сообщению`, `Открыть библиотеку`, content-description
`Общее сообщение: …`. Старые служебные Text `Сообщение пусто.` и единая строка
`ХОЧУ КУШАТЬ` не требуются; merge-dependent countNamed удалён.

UI определяется actual text/content-description. `namedHitBounds` проверяет
visible/enabled/non-password/**non-editable** именованный узел и ближайший enabled visible clickable
ancestor, затем нажимается центр **именованного** bounds после пересечения с
ancestor и активным окном. Пустое пересечение запрещает tap; fallback на центр
широкой строки, doubletap или ослабление SAF-return predicate отсутствуют.
Finite aliases: app Ваши наборы/Библиотека,
Меню библиотеки/Меню/Ещё, Импорт/старый
onTransfer entry; picker Show roots/Показать корневые папки/Открыть панель навигации,
Downloads/Загрузки, Search/Найти/Поиск. Новые неизвестные labels не угадываются.
Ожидание каждого состояния ограничено deadline 20 секунд; поиск offscreen
transfer controls использует максимум 12 реальных semantic scroll actions,
без fixed sleep. Разрешены только target package и на SAF-шаге
com.android.documentsui/com.google.android.documentsui. Неожиданное системное
окно немедленно прерывает тест; нет XML/password dumps или random coordinates.
**Parent API35 RED:** настоящий DocumentsUI показал fixture, но старый helper
нажал центр row [0,512–2560,640] = (1280,576), вне filename label
[160,554–1017,597], и получил `Timed out at SAF returned to target`.
Это подтверждённый дефект automation, не product failure/visual verdict.
Исправление использует label hotspot; небольшой Rect regression покрывает эти
bounds, clipping и пустое пересечение. Эта lane проверяет compile, не device:
повторный настоящий SAF run и визуальная приёмка остаются за primary, **PASS нет**.
Второй parent RED после point correction: matcher выбрал editable
`search_src_text` (AutoCompleteTextView) с тем же basename вместо non-editable
`android:id/title` результата. Общий `isNamedClickCandidate` теперь исключает
editable как для availability (`visibleNamed`), так и для tap; flags regression
различает query/result с одинаковым именем. Search/Field изменяются только через
отдельный `replaceText`/`setField`; IME, SAF-return и point clipping не обходятся.

Следующий parent RED выявил маскировку исходной ошибки исключением
`Activity never becomes requested state [DESTROYED]` из cleanup `scenario.close()`.
Теперь исходное исключение сохраняется, close/SHA/delete ошибки добавляются как
suppressed; обе SHA-проверки и удаление **только своей копии** предпринимаются
независимо. Regression намеренно портит только собственную cache-копию и проверяет
сохранение primary + suppressed SHA failure + удаление копии.
В начале каждого await однократно отправляется passive `u14Await` (до loop),
а `u14Stage` отмечает проверенный owned content, native delivery, prepare click и
наблюдённый preview. Это фиксированные synthetic stage IDs/ожидаемые labels:
нет URI, фактического UI text/password dump или private runtime access.
Tap policy не менялась; первичная ошибка/root cause ещё требует нового device run.
Снимок baseline после закрытия test caller не является снимком нового продукта.
Новые начальные route/toolbar selectors должны быть интегрированы из UI lane:
исходный runtime dependency этого worktree ещё начинает с сообщения и поэтому
UI tests в одиночку до интеграции navigation lane ожидаемо не пройдут.

После одобрения primary запускает методы на known-owned API35/21 через Android
MCP/instrumentation runner `androidx.test.runner.AndroidJUnitRunner`, один selector:

- API35: `su.linka.pictures.activity.DevelopmentParityTest#filledBoardThroughRealSafImport`;
- API21: `su.linka.pictures.activity.DevelopmentParityTest#filledBoardThroughOwnedIncomingContent`;
- уже импортированная fixture, actual Back: `su.linka.pictures.activity.DevelopmentParityTest#reopenFilledBoardWithoutImportOrMessageEdits`.

API21 требует интегрированные primary debug provider/XML и incoming-root fix;
reopen/Back требует интегрированный native Back binding. Ничего из этого тест не
подменяет и не включает сам.
Два аргумента выше передаются runner как instrumentation arguments.
Пустой owned sandbox после `DevelopmentStartupTest` допустим: файл SQLite не
удалять. Существующий startup test в этой lane не менялся.

Локальная проверка без устройства (JDK17, один worker, только test APK,
без обычного APK/release/full gate):

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=/Volumes/data/developer-state/android-sdk ./gradlew :app:assembleDebugAndroidTest --max-workers=1 --console=plain
python3 scripts/check-ui-parity-evidence.py --self-test
```

Артефакт: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
Сборка test APK на `544ec0f` и SHA его упакованного asset проверены локально;
последняя selector/precondition correction проверена Kotlin compile и static
UI-source signatures. Перед device-run пересобрать интегрированный test APK:
старый локальный APK не содержит correction. Установка и instrumentation/device
execution не выполнялись. Production files/Gradle/manifest в этой lane не менялись;
provider используется только уже добавленный primary, без собственного компонента.

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
не с выводом о похожести. Размеры reference брать из raw PNG (`sips`), не из
viewer-scaled 2000px: actual raw references primary имеют ширины 2400/2560px,
а не размер preview. При system bars указывать viewport полного screenshot,
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
