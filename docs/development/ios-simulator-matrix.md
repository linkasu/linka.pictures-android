# iOS Simulator: baseline и fixed matrix

## Режим после остановки пользователем

### Возобновление: только исходное падение, без fixed-очереди

**Результат нового внешнего воспроизведения:** native `launch --console` поймал
исходный UIKit trap за8.1s; затем проверена доставка той же сигнатуры через
`launch --stdout=... --stderr=...` в файлы output. Это отдельная проверка нового
способа capture, не автоматический retry теста. Доказательство прямого stderr:
`external-infrastructure-debug/redirect-check/stderr.log` внутри evidence root.
Изменений ownership тома, глобальных permissions или OS runtime не потребовалось.

После нового поручения «чини до конца» расследована блокировка внешнего Simulator.
Глубокий child TMPDIR дал137-byte Unix socket path и воспроизводимый
`AF_UNIX path too long`. Короткий внешний temp дал72-byte socket path и успешный bind.
Системные настройки ownership тома не менялись. Диагностический внешний
Air11 M3/27.0 без глубокого TMPDIR загрузился за39.3s, установил исходный Release
за89.1s и запустил его. `log show` внутри этой среды зависал; вместо него
нативный `simctl launch --console` с process-local `OS_ACTIVITY_DT_MODE=YES`
за8.1s зафиксировал точный UIKit assertion из Apple crash.

Свежее доказательство **старой**, не исправленной сборки:
`build/ios-matrix-20261112/external-infrastructure-debug/original-console.log`
и `original-result.json`. Это новая одна модель27.0, не полная матрица и не27.0.1.
Runner исправляется на короткий внешний `.linka-tmp/<run-hash>` и прямой stderr
при первом запуске, без повторного запуска для классификации crash.
Автостоп unexpected failures, исходные stop markers и запрет выпуска сохраняются.

Исправление runner проверено52 mocked unit и11 pipeline unit. При первом launch
задаются stdout/stderr-файлы и process-local `OS_ACTIVITY_DT_MODE=YES`; ранняя
смерть процесса больше не требует повторного launch или зависающего `log show`.
Контрольный оригинальный pilot и следующий полный **только baseline Release**
находятся в `build/ios-matrix-20261112/original-red-after-ipc-fix/`.
`baseline-queue.json` разрешает полный обход лишь после exact lifecycle trap в
pilot; fixed Debug/Release из этой очереди не запускаются. Состояние running/queued
не означает выполненную матрицу. Ownership тома, permissions и runtime не менялись.

### Перенос на внешний APFS: фактический результат

Общая пользовательская библиотека скопирована через `ditto` с metadata/ACL.
До переключения сопоставлены относительные пути, symlinks, размеры и SHA-256
каждого файла. После переключения read-only inventory подтвердил те же53 UDID,
все Shutdown; ни одного boot или тестового запуска не выполнялось.

- `~/Library/Developer/CoreSimulator` → `/Volumes/data/developer-state/apple/CoreSimulator`
- `~/Library/Developer/Xcode/DerivedData` → `/Volumes/data/developer-state/apple/Xcode/DerivedData`
- `~/Library/Logs/CoreSimulator` → `/Volumes/data/developer-state/apple/Logs/CoreSimulator`
- `~/Library/Caches/com.apple.dt.Xcode` → `/Volumes/data/developer-state/apple/Caches/com.apple.dt.Xcode`

Старые пути — symlinks для совместимости Xcode. Не отключать внешний диск при
работе Xcode; при его отсутствии runner отказывается работать. Первое удаление
проверенной исходной копии остановилось на EACCES у служебной thumbnail.
После отдельного явного поручения пользователя «удаляй с судо» выполнен `sudo -n`
только для четырёх точных `.before-external-20261112` копий. Все они удалены;
права/SIP не менялись, runtime assets и рабочие внешние данные не затронуты.
Повторная read-only проверка подтвердила те же53 выключенных устройства.
Свободно после очистки: внутренний том41GiB, `/Volumes/data`72GiB.

**Runtime-образы не перенесены:** шесть образов суммарно45.31GiB, пять обслуживаются
системным MobileAsset/Cryptex. `simctl runtime add` и `xcodebuild -importPlatform`
не предоставляют destination установки; `-exportPath` — лишь каталог пакета загрузки.
Ручного перемещения assets/mounts, подмены ссылками и изменения подписей не было.
Все6 runtime после переноса данных остаются зарегистрированы и Ready.

Evidence: `build/ios-matrix-20261112/storage-migration/` — inventory до/после,
content verification, пути и отдельный `runtime-relocation-limit.json`.
После доработки выполнены только offline mock/self-tests: matrix44,
pipeline11, scene4. Debug/Release `--validate-only` проверены с запретом native
command callbacks:8 отдельных методов, output не создан. Реальные тесты,
Simulator и сборки после пользовательской остановки не запускались.

12 ноября пользователь остановил исполнение и разрешил только перенос хранения
на внешний диск и доработку скриптов. **До нового явного разрешения не запускать
матрицу, Simulator, runtime-пилот, сборку или повторный выпуск.** Mock/unit-проверки
самих Python-скриптов не запускают Xcode/устройства.

Актуальный runner требует explicit `--device-set` на смонтированном `/Volumes/data`;
app, xctestrun, build manifest, runner artifacts и output тоже должны разрешаться
на этот том. TMPDIR дочерних процессов находится в output на внешнем диске.
Fallback на внутренний том запрещён. CoreSimulator/ОС всё ещё могут записывать
системные caches внутри: запас системного тома проверяется отдельно.

Каждый из восьми методов XCTest выполняется отдельным вызовом, editor последний.
Первый unexpected failure прекращает остальные методы/модели и атомарно сохраняет
`matrix-stop.json` с первой причиной. Marker не очищается автоматически и блокирует
дальнейшие этапы. В baseline только исходный lifecycle trap считается ожидаемым
RED для продолжения матрицы; другие crash/инфраструктурные ошибки останавливают её.

`scripts/test-ios-verification-pipeline.py` по умолчанию/с `--prepare` лишь печатает
план без subprocess, inventory и записей. Только `--run` запускает baseline Release,
затем fixed Debug и fixed Release. Неполный baseline или failure прекращает всю
очередь, а не переключает конфигурацию. `--status` и matrix `--progress` читают ETA;
для не начатых этапов ETA неизвестен. `--validate-only` matrix проверяет замороженные
входы без inventory, запуска и создания файлов.

Для custom-set XCTest скрипт использует per-command
`-DVTSimulatorSetLocation=<device-set>`. Destination discovery в Xcode подтвердил
видимость custom set, но реальное XCTest на нём ещё не подтверждено: последний
пилот остановился до создания устройства из-за отсутствующей директории set.
Runner теперь создаёт её только при разрешённом исполнении, не при prepare/validate.

Ниже сохранена история разработки и прежних запусков; описания7+1 или6+1+1
не являются текущей политикой — сейчас это8 отдельных вызовов с общим fail-fast.

## Область и текущий статус

`scripts/test-ios-simulator-matrix.py` последовательно проверяет пары доступного
iOS runtime и поддерживаемого им типа iPhone/iPad. Скрипт не собирает приложение,
не делает релиз и не меняет версию. App Store, физические устройства и изменения
подписей, разрешений или credentials не входят в этот сценарий.

На 12 ноября 2026 baseline Debug и Release выполняются; полная матрица **не объявлена
завершённой или PASS**. До исправления scene lifecycle baseline ожидаемо RED:
родительская сессия подтвердила SIGTRAP на iOS 27 с
`UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption`. Отсутствие этого
trap на отдельной паре само по себе не доказывает функциональность.

## Фактические пилотные результаты

- Исходный Debug на iPad Air11 M3 /27.0 завершился; device log подтверждает
  `Application failed to launch: UIScene life cycle is required for apps built with this SDK`.
  Evidence: `build/ios-matrix-20261112/pilot-red.json` и `pilot-red-lifecycle.log`.
- После переноса окна в SceneDelegate cold launch/relaunch на той же модели/ОС
  прошёл **1/1**, без failed/skipped: `pilot-fixed-cold-native.xcresult`.
- Первые попытки XCTest невалидны: текущий Xcode отвергает `-test-iterations 1`;
  созданный им result path повторно не используется, исходные ошибки сохранены.
- Расширенный ручной пилот дал6/7 с `signal kill`, параллельный Release pilot
  потерял runner ещё до тестов. Ошибка организации: два XCTest одновременно
  работали на одном UDID. Это не доказательство product crash. Повтор запущен
  через runner на новом собственном Simulator, без перекрытия XCTest на одном UDID.
- Дополнительный `:shared:testDebugUnitTest` (не target действующего CI) дал
  590 tests/227 failures: Android SQLite JNI отсутствует на macOS host, Android
  Trace API не mocked; отдельный caption assertion требует Android-среды.
  Это не исправлено подавлением тестов. Штатные app unit/lint/safety/selectors прошли;
  повторный JVM execution запущен отдельно, старые cached XML не считаются новым run.
- Первые массовые прогоны остановлены после подтверждённого ENOSPC на системном
  диске Simulator. Debug baseline успел записать48 результатов, Release48;
  это **не полная матрица**. Native17 получил188 прямых ENOSPC failures, поэтому
  остальные его assertions нельзя считать независимой регрессией до чистого прогона.
  Native26.5 дал720/722: два runtimeEvidence жёстко требовали старый UDID; assertions
  заменены проверкой валидного executing Simulator UUID и принадлежности temp-пути
  этому устройству. Hosted suite26.5 прошёл5/5. Все старые результаты сохранены.
- Для восстановления места удалены только точные UDID собственных временных
  устройств по ledger. Чужие устройства не удалялись/не перемещались; системный
  диск после завершения удаления вернулся к13GiB свободного места. Новые прогоны
  выполняются с контролем свободного места и без конкурирующих тяжёлых матриц.
- Последовательный healthy pilot/AirM3/27.0 после восстановления места дал
  **7/7 smoke** без failed/skipped (`pilot-serial-healthy/debug-smoke.xcresult`).
  Отдельный editor case не прошёл: helper искал «Готово»/Done внутри iPad keyboard
  после успешного ввода названия; native keyboard dismissal требует уточнения
  по AX. Это не скрытый skip и не8/8. Исходный draft оставлен на собственном pilot.
- По фактическому AX найдено «Скрыть клавиатуру»; helper исправлен без изменения
  продукта. Новый runner V2 прошёл editor **1/1** на том же own pilot
  (`pilot-editor-keyboard-fixed.xcresult`). В процессе отдельный Release pilot
  с6+1+1 и native Dynamic Type readback. Повторный Native26.5 после удаления
  UDID-привязки прошёл **722/722**, hosted suite снова5/5.
- Свежий JVM gate дал592/593 и timeout60s в одном credential-import тесте при
  параллельной нагрузке. Изолированный повтор этого exact-теста прошёл; полный
  новый JVM execution затем прошёл **593/593** с timestamp12 ноября,
  failures/errors/skipped0 (`jvm-fresh-summary.json`). Первый failure не удаляется.

Все файлы evidence этого этапа находятся под `build/ios-matrix-20261112/`.
Номера версий не менялись; uploads, store actions и релиз запрещены пользователем.
`verification-pipeline.json` описывает очередь полных fixed Debug/Release прогонов
после завершения baseline. Перед стартом проверяются сохранённые Release pilot
6+1+1 summaries; очередь не делает upload/export/signing для магазина. Пока
процессы не закончились, их запуск не означает выполненную матрицу.

## Inventory

Из корня репозитория, без создания и загрузки устройств:

```sh
python3 scripts/test-ios-simulator-matrix.py --list
```

Прочитанный inventory на 12 ноября 2026 содержит **238 пар**:

| Runtime | Build из inventory | Пар |
| --- | --- | ---: |
| iOS 17.0 | 21A328 | 46 |
| iOS 26.5 | 23F77 | 65 |
| iOS 27.0 | 24A434 | 62 |
| iOS 27.1 | 24A94425 | 1 |
| iOS 27.2 | 24B8117, 24B91016s | 64 |
| Всего | | 238 |

Это динамический inventory, не список всех когда-либо выпущенных устройств.
Идентичность пары — `runtime identifier + device type identifier`; имя версии
не заменяет runtime identifier. Оба build iOS 27.2 входят в его суммарный счётчик.
Перед новым прогоном снова сохраняют inventory; изменение установленного набора
runtime может изменить знаменатель.

iOS 27.0.1 отсутствует среди доступных runtime. По диагностике родительской
сессии попытка нативной загрузки через `xcodebuild` завершилась ошибкой
unavailable, **exit code 70**. Это инфраструктурное ограничение, не PASS этой
версии и не доказательство её проверки; исходный лог загрузки сохраняют вместе
с evidence. Команду загрузки здесь не повторяем.

## Замороженные входы и provenance

До выполнения отдельно готовят simulator `.app` и, для XCTest, совместимый
`.xctestrun` с runner/test bundle. Пути ниже — абсолютные примеры; их заменяют
на реальные замороженные артефакты. Каталог `--output` должен быть новым и не
находиться внутри `.app`. Нельзя пересобирать эти входы во время прогона.

`--config Debug|Release` не является доказательством конфигурации. Для XCTest
нужна конфигурация в Info.plist (`LinkaBuildConfiguration`, `Configuration` либо
`BuildConfiguration`) или отдельный `--build-manifest` JSON:

```json
{
  "configuration": "Debug",
  "app_sha256": "<digest(app) из scripts/test-ios-simulator-matrix.py>"
}
```

Это digest всего дерева `.app` по алгоритму `digest()` скрипта, с относительными
путями, содержимым и symlink targets, **не** SHA одного executable и не SHA архива.
Манифест формируют по известному результату сборки; произвольная метка Debug
не подтверждает происхождение. Если Info.plist уже сообщает конфигурацию,
она также должна совпадать с `--config`.

В `matrix.json` сохраняются `app_sha256`, конфигурация и её provenance; при
`--build-manifest` — его абсолютный путь и digest. Для XCTest также сохраняются
`xctestrun_sha256` и `test_artifact_hashes` runner/test bundle и указанного
`UITargetAppPath`. Последний должен совпасть с замороженным приложением.
`.xctestrun` обязан явно задавать совпадающий `LINKA_UI_PRODUCT_BUNDLE_ID` в
`TestingEnvironmentVariables`; поддерживаются только bundle IDs, разрешённые
`iosUiTests/LinkaStartupUITests/ProductApplication.swift`.

## Полный baseline: только launch evidence

```sh
python3 scripts/test-ios-simulator-matrix.py \
  --app /absolute/frozen-baseline/LinkaPictures.app \
  --output /absolute/evidence/baseline-debug-launch \
  --phase baseline --config Debug --probe launch \
  --build-manifest /absolute/frozen-baseline/build-manifest.json
```

Без `--only-runtime` и `--limit` выбирается весь inventory. `launch_alive`
означает только проверенную идентичность PID и жизнь процесса в течение
`--stable-seconds` (по умолчанию 3 секунды), **не UI readiness и не functional
PASS**. Launch может работать без build manifest, но без конфигурации в
Info.plist его provenance тогда лишь `declared_only`.

Baseline и любой launch-only прогон возвращают exit code 1 даже при всех
`launch_alive`: их outcome — `evidence_only_or_incomplete`. Ожидаемый RED не
разрешает пропускать пары или называть инфраструктурную ошибку lifecycle trap.
Для последнего нужен указанный lifecycle assertion в crash/log evidence.

## Полный fixed: XCTest без сборки

```sh
python3 scripts/test-ios-simulator-matrix.py \
  --app /absolute/frozen-fixed/LinkaPictures.app \
  --output /absolute/evidence/fixed-debug-xctest \
  --phase fixed --config Debug --probe xctest \
  --xctestrun /absolute/frozen-fixed/LinkaStartupUITests.xctestrun \
  --build-manifest /absolute/frozen-fixed/build-manifest.json
```

Release — отдельный прогон с отдельно подготовленными Release артефактами,
соответствующим build manifest, `--config Release` и новым output. Debug evidence
не заменяет Release evidence. Каждый runner работает последовательно; независимые
прогоны требуют разных output и устройств, а допустимый параллелизм ограничивается
памятью машины. Runner устанавливает приложение и вызывает `xcodebuild
test-without-building` без включения повторов. Для текущего Xcode не передавать
`-test-iterations 1`: эта комбинация отвергается до выполнения тестов.

По умолчанию selector — `LinkaStartupUITests/MatrixSmokeTests`. На каждой паре
выполняются **6 normal + 1 LargeText + 1 editor**, тремя отдельными вызовами XCTest:

- `smoke`: класс без LargeText и `testZZEditorCreatePublishRelaunchAndCancelCardDraft`;
- `large-text`: только LargeText после native set/readback `simctl ui content_size`;
- `editor`: только этот метод, после smoke, поскольку он добавляет доску.

Каждый batch обязан иметь точное ожидаемое число passed/total, ноль failed/skipped,
пустые failures и result `Passed`. Для текущих 238 пар это **1904 ожидаемых
выполнения методов на конфигурацию** (1666 smoke + 238 editor), не число уже
успешных тестов. Проверяют оба batch, статусы пар и итоговые счётчики, а не только
exit code. `--selector` отдельного метода, `--only-runtime` и `--limit` не
доказывают полную проверку 6+1+1 на всём inventory: фильтры runtime/limit помечаются
`pilot`; отдельный selector требует самостоятельного контроля полноты.

## Что проверяют UI-тесты и чего они не доказывают

`MatrixSmokeTests.swift` проверяет холодный запуск/relaunch; английский и русский
UI; первую страницу «Общаюсь» с девятью декодированными изображениями и paging;
сохранение/очистку сообщения через background и relaunch; ориентации и hit targets;
библиотеку и общение с запрошенным большим текстом; настройки без записи;
создание/публикацию доски, relaunch и отмену изменения карточки.

Helpers ждут enabled/hittable AX elements, проверяют видимые границы карточек,
минимум 44×44 и AX value «Изображение показано»; библиотека собирается через
scroll для lazy grid. Это содержательнее живого процесса, но не полная ручная
визуальная приёмка. Ориентации iPhone здесь только landscape; iPad — также
portrait и upside-down.

Dynamic Type batch меняет системную категорию через `simctl ui content_size`,
требует точный readback `accessibility-extra-extra-extra-large`, а в `finally`
восстанавливает и проверяет исходную категорию. Unsupported/mismatch —
`capability_blocked`, не skip/PASS. Тест также задаёт process-local override.
Это подтверждает категорию ОС и geometry assertions, но не прямое измерение
Compose font scale. Process-local AppleLanguages/AppleLocale
задаются helper `productApplication`, без изменения системного языка.

Текущие тесты **не покрывают целиком старый filled-fixture flow и backup/restore
flow**. Они также не подтверждают физические устройства, озвучку/микрофон,
provider/network flows или полную миграцию пользовательских данных.

## Evidence, полнота и остановка

Перед каждой новой парой runner проверяет свободное место на томах output и
device set (минимум5GiB). Недостаток места останавливает создание следующих
устройств, а не порождает238 ложных product failures. Один evidence workspace
защищён `.simulator-matrix.lock` от конкурирующих тяжёлых matrix jobs.

Для launch-only доступны собственные device sets на data-диске:

```sh
python3 -B scripts/test-ios-simulator-matrix.py \
  --app /absolute/frozen-baseline/LinkaPictures.app \
  --output /Volumes/data/project/build/matrix/baseline-release \
  --phase baseline --config Release --probe launch \
  --build-manifest /absolute/frozen-baseline/build-manifest.json \
  --device-set /Volumes/data/project/build/matrix/devices --delete-created
```

Используется нативный `simctl --set`, без переноса default set, symlinks или
глобальных preferences. Пилот Release/AirM3/27.0 в таком set поймал исходный
lifecycle trap. Custom-set XCTest пока отклоняется до запуска: поддержка такого
destination в Xcode не доказана. Полный baseline Release на data set запущен
отдельно после ENOSPC; прежние прерванные результаты не подменяются.

`matrix.json` содержит manifest и записи пар; каталоги `0000`, `0001`, … — логи,
crash evidence и для XCTest `smoke.xcresult`, `editor.xcresult` с summaries.
При неуспехе screenshot — best effort, его отсутствие не превращает результат
в PASS. Default cleanup выключает и сохраняет только созданные runner устройства;
`--delete-created` отдельно разрешает удаление точных owned UDID из ledger.
Существующие пользовательские устройства не используются и не стираются.

Количество записей или строк прогресса — не passed count: могут быть `pending`,
`running`, `interrupted`, `infrastructure_blocked`, `unknown_crash`,
`lifecycle_trap`, `tests_failed` и `launch_alive`. Полнота требует всех выбранных
пар с терминальным evidence; functional PASS требует всех `tests_passed`, полного
selector 6+1+1 и full scope. Нельзя называть matrix complete/PASS, пока она идёт.

`--resume` требует идентичного manifest, включая hashes и inventory. Он не
повторяет существующие записи: `running`/`pending` становятся `interrupted`,
очищаются только собственные устройства, далее запускаются ещё не записанные
пары. Это не автоматический retry и не способ скрыть пропуски. Новый доказательный
прогон использует новый output; не подменяют старый ledger или release ledger.
