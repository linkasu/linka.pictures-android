# LINKa «Покажи»: журнал исполнения

Дата начала: 4 ноября 2026 года. Основание: `/Users/aacidov/.opencode/plan/linka-pokazhi-implementation.md` и три соседних приложения contracts/work-packages/verification. Этот журнал отражает выполненное, не заменяет нормативный план.

## Актуально: адаптивная фраза, редактор и перенос — 6–7 ноября 2026

**К выпуску не готово.** Пользователь поручил довести весь согласованный продукт,
но разрешил пока только симуляторы. Production-подпись, push/PR, store upload и
сама публикация не выполнялись и требуют отдельного разрешения.

### Последний технический gate и принятые визуальные решения

- **509/509 shared JVM и633/633 Native iOS17 PASS**, Native70.134s, SHA
  `a24283840da90cbb4a7404c31f27758d70b58ba0b34df644900fb9c48177d1f9`.
  Android APK/tests, оба iOS frameworks и app-host framework собраны;
  lint0errors/46app+9shared warnings. Логи `large-font-current-quality-gate.log`
  и `large-font-full-native17.log`.
- Дополнительно **70/70 Android debug-unit и70/70 release-unit PASS**,
  `verifyReleasePrivacy` PASS. Первоначальный полный debug-unit был69/3 RED:
  legacy privacy fixtures не задавали свой Application после намеренной изоляции
  development manifest. Исправлены только fixtures, добавлены emission assertions
  и проверка отсутствия Firebase initializer/5 legacy writers в debug. Production
  privacy-код, signing и APK release assembly не менялись. [История и логи](evidence/privacy-unit-2026-11-07/).
- Пользователь принял обычный portrait/landscape срез (`6ae5a47`) и затем выбрал
  **«Прокрутка (Recommended)»** для крупного текста (`5830be2`, `2cceba7`).
  Полная подпись доступна в карточке, картинка остаётся; шрифт не уменьшается,
  структура сетки и принятая1.0 геометрия сохранены. Это не whole-U1.4 approval.
- Native portable2.0/contrast/save/read/scroll/restore: **iPhone17 1/1,152.591s;
  iPad17 1/1,122.404s; Android21 ручной bounded roundtrip PASS**. На iPhone
  реальные жесты раскрыли конец заголовка и КАКАТЬ, без изменения фразы/audio state;
  на Android suffix/неподвижная картинка/доступность9 карточек просмотрены. Исходные
  1.0/contrastOff восстановлены через UI и cold reopen. [PNG и полные ограничения](evidence/settings-native-2026-11-06/).
- Штатный iOS17 accessibility audit без issue filter: **iPhone1/1,17.530s;
  iPad1/1,15.757s**. Contrast/elementDetection/hitRegion/description/textClipped/
  trait проверены на обычной доске. Dynamic Type не запускался, системные настройки
  не менялись; это не actual VoiceOver/TalkBack или physical/AAC acceptance.
- **API35 точные `board.linka` и `library.linkabackup` теперь подтверждены**:
  уже открытый picker → Save → fresh access → отдельный confirm → terminal success.
  Export SHA `e382a3190165fabf7dc4dcf54bb42770a3c76bd2cd375dd0d5828f36c4d6d722`,
  backup SHA `a87a9c98d3555179d3e9e8c11b8ce7f8d43ada6aae170091ad656d56cac7a523`;
  CRC/размеры/SHA media проверены. Архивы совпадают с прежними ZIP-байтами, но
  больше не получают `.zip`. Исторические BLOCKED/custom-name attempts сохранены,
  причина старых input timeouts не объявлена доказанной. Финальное UI-сообщение/
  черновики после backup не засчитаны из-за отдельного hierarchy timeout.
  [Native default-name proofs](evidence/portability-native-2026-11-06/).
- iOS17 изолированный **restore → ReturnToRecovery → DeleteRecovery → cold launch:
  1/1 PASS,226.584s**. Sentinel создан через UI, backup выбран настоящим Files;
  возвращён sentinel, тестовая recovery удалена. Десять оригинальных PNG и guard
  сохранены, исходный/скопированный backup SHA неизменны. Это не настоящий upgrade
  или Android restore. [Native recovery proof](evidence/recovery-native-2026-11-07/).
- Dark theme → штатный6-type audit → light restore: **iPhone1/1,58.446s;
  iPad1/1,55.305s**, кадры просмотрены; persisted settings не менялись.
- Настоящий system Dynamic Type на iPad17: `large` → максимальный
  `accessibility-extra-extra-extra-large` → исходный `large`, **3/3 native PASS**.
  Текст реально увеличился на кадрах; значения body17→53→17pt относятся к test
  runner, не измерению шрифта продукта. Отдельно максимальный размер + чтение
  окончания КАКАТЬ жестами: **1/1 PASS,28.262s**, без изменения фразы/audio state;
  восстановленный system size + cold app: **1/1 PASS,20.705s**. Portable scale
  оставлен1.0, сочетание system maximum × portable2.0 и iPhone system maximum
  **не засчитаны**. [System-size evidence](evidence/settings-native-2026-11-06/system-text-ipad-scroll-evidence.json).
- Existing-card Cancel: resume того же изменённого черновика → Cancel → SAVED →
  опубликованная исходная карточка/фраза: **1/1 PASS,30.349s**. Отдельный
  whole-text replacement остаётся **RED**: `NANCEL-CHECK-17ATIVE-CARD-17` вместо
  `CANCEL-CHECK-17`. Два probes выделения0/2, с ошибочными guards/assertions;
  три shortcut-команды исполнены, но ранние кадры не установили диапазон.
  Позднее double tap → публичное Select All дали видимое полное выделение;
  `app.typeText` всё равно воспроизвёл дефект. Дифференциальная проверка с настоящей
  клавишей экранной клавиатуры **ф** корректно заменила всё название, затем
  Cancel/SAVED/исходная опубликованная карточка/фраза: **1/1 PASS,42.857s**.
  Отдельная последовательность настоящих клавиш **ф → фы → фыв** с проверкой
  каждого промежуточного значения и тем же Cancel: **1/1 PASS,45.529s**.
  Product-код не менялся; отказ специфичен для injected-input пути в этой паре,
  его точный внутренний механизм и external keyboard остаются OPEN. Исправленные
  assertions ранних probes не объявляются native PASS.
  [История редактора](evidence/editor-native-2026-11-06/).
- Новые XCTest key-event probes остаются **RED**: raw1/2 дали1→12,
  raw Backspace не удалил2; настоящая экранная Backspace дала12→1,
  но raw Cmd+A/3 дали13 вместо3. Это не physical hardware proof и не product fix.
  После RED выполнен Cancel/SAVED/исходная опубликованная карточка/фраза:
  **1/1 PASS,29.406s**; повтор экранного ввода после выделения общего helper:
  **1/1 PASS,45.619s**. В конце форма закрыта, клавиатура скрыта, исходный
  сохранённый черновик оставлен. Core/runtime не менялись;509/633 и app70/70
  выше остаются прежним техническим gate, не полным UI PASS.

**Ещё OPEN:** полный font/system-scale/landscape/keyboard/screen-reader atlas,
весь U1.4 approval, Android native restore/recovery, Electron runtime exchange, codecs/
mixed/offline/mic/routes, production startup/migration/старые writers/privacy/G4,
ARMv7 runtime, Play signing/max versionCode/реальный upgrade, legal/store/physical/AAC.
Положительный iOS Files export/backup1/1 (102.570s) тоже сохранён ниже.
Production пока остаётся legacy startup, не новым runtime.

### История крупного текста: исходный дефект и первые проверки

Native iPhone Settings: read-only1/1 (23.115s), первоначальный save macro RED
(60.837s) на неверном ожидании дополнительного prompt, затем resume-only
save/read/cold-open/restore1.0: **1/1 PASS,102.725s**. Фактические2.0/contrastOn
перечитаны, исходные1.0/contrastOff возвращены через UI и подтверждены cold reopen.
Общая фраза и9 карточек сохранены. Системный fontScale не менялся.

При этом реальные2.0 PNG выявили **visual RED**: обрезание заголовка и длинных
подписей, исчезновение картинки при слишком длинной подписи. Common caption
исправлен стандартной прокруткой с сохранённым image viewport; toolbar height/
full-title исправлены сразу во всех5 callers, без уменьшения пользовательского
fontScale. Caption commit `5830be2` прошёл42 focused JVM после2 RED; интегрированный
focused срез **76/76 JVM PASS**, Simulator framework и Android APK собраны.
Новый native save2.0/scroll/restore1.0 сценарий подтверждён выше. Полные505/629 ниже относятся к состоянию
**до этих новых font-layout изменений**, не к новому текущему full gate.
[Оригинальные Settings PASS/RED/PASS и кадры](evidence/settings-native-2026-11-06/).

### История технического gate: короткий landscape505/629

- **505/505 JVM и629/629 Native iOS17 PASS**, без failures/errors/skips;
  Native71.181s, SHA `7fed165ca3877afac26f49e12a8becfeb7b0211527762d61818202893f6b472a`.
  Android APK/tests, оба iOS frameworks и app-host framework собраны, lint0errors/
  46app+9shared warnings. Логи `landscape-phrase-current-quality-gate.log` и
  `landscape-phrase-full-native17.log`. Это заменяет более ранние504/628 ниже.
- Native portrait/landscape: **iPhone1/1,35.866s; iPad1/1,33.346s;
  API21 landscape1/1,2.485s**. Девять карточек и прежняя фраза фактически проверены;
  миниатюры не обрезают подписи, minimum12sp/fontScale сохранены. Предпочитаемая
  ширина96dp и min-height64dp действуют только в ограниченном пространстве;
  обычная пропорция1:4 и half-viewport cap первых двух элементов сохранены.
- Первоначальный API21 screenshot захватил анимацию поворота и **не принят по
  пикселям**. Тестовый захват ждёт неизменный native raster не менее250ms при
  deadline20s, без fixed sleep или отключения анимаций. Отдельный стабильный PNG
  просмотрен; данные, AVD и настройки анимаций не менялись. После полного gate
  менялся только этот Android test harness, его сборка и focused native повторены.
- Product APK `6e8dfe3edb1eba434210b78e0d9461cb8f6dbe3d475d9793c4b96f3a113b4cdd`;
  iOS executable `619d34467cfef18fc91e4f4af2a8de41e0260d956aaaccea1563c05b4aff2307`.
  [Оригинальные кадры и manifest](../progress-screenshots/2026-11-06-caption-gif/atlas-orientation/height64-native-manifest.json).
  После просмотра пользователь отдельно ответил **«Принимаю срез»**: одобрены
  доска/фраза portrait/landscape commit `6ae5a47`. Весь U1.4, полная large-font/
  contrast/keyboard/screen-reader матрица и physical/AAC этим не закрыты.

- После явного выбора **адаптивности**, а не исключения маленьких устройств,
  первые две миниатюры фразы помещаются целиком. Поддержка Android21+/iOS17+,
  минимум12sp, пользовательский fontScale и прокрутка длинной фразы сохранены.
  iPhone native existing-board/reopen: **1/1 PASS,32.108s**; API21 после обычного
  запуска существующего AVD: **1/1 PASS,1.544s**. Оригинальные просмотренные PNG:
  [Android21](../progress-screenshots/2026-11-06-caption-gif/android21-adaptive.png),
  [iPhone17](../progress-screenshots/2026-11-06-caption-gif/iphone17-adaptive.png).
  Это проверка данного среза, не одобрение полного U1.4 пользователем.
- Исправлены GIF alpha на API21 и одинокая последняя буква подписи. Bounded
  first-frame decode использует уже имеющийся Glide, а не Movie; native tests
  **5/5 API21 и5/5 API35** сохраняют исходные байты. Полная font/contrast/physical
  матрица не выводится из этих результатов.
- Исправлен воспроизводимый native-input дефект редактора: синхронный локальный
  whole-payload buffer, token ACK и RAM editVersion. ACK означает обработку или
  отказ, **не durability**. Тот же сохранённый API35-черновик прошёл 2×2 → карточка
  `NATIVE-CARD-35`/NONE → «Из текста» → SaveCard → SaveBoard → rename → cold reopen;
  итог `LINKa-EDITOR-6F9C-SAVED`. ХОЧУ, её старые черновики v6/v3 и общая фраза
  сохранены. [RED и последующие native proofs](evidence/editor-native-2026-11-06/).
- iPhone: карточка `NATIVE-CARD-17`/NONE и доска опубликованы; отдельный
  rename/cold-reopen **1/1 PASS,30.400s**, итог
  `LINKa-EDITOR-iOS17-20261106-RENAMED`. Старые macro failures на очистке/выделении
  и прокрутке остаются RED; продолжения использовали существующий draft/card/board,
  не повторный fresh-create. Выделение — публичные Cmd+Down/Cmd+Shift+Up.
- Новый выделенный iPad17 прошёл настоящий Files → preview1/9/9/9 → явные
  unlock/ImportNew → compose/reopen: **1/1 PASS,85.940s**. Fixture уже опубликована;
  дальнейшие проверки только existing/reopen, без повторного fresh-import.
- На owned iOS26.5 настоящий clip и непустой TTS callback: **2/2 PASS,2.480s**,
  без установки голосов или изменения сети/громкости. iOS17 Simulator TTS остаётся
  environment RED; минимум physical/offline/Russian AAC этим не принят.
- Официальный publisher AAB1.4 найден, SHA/JAR signature проверены, все7 bundled
  `.linka` совпадают с tag blobs. Это **не Play-delivered APK**; Play certificate,
  max versionCode по трекам и настоящий store upgrade по-прежнему OPEN.
- Реальные API35 export/backup завершились через DocumentsUI, fresh authorization
  и отдельное подтверждение. CRC/размеры/SHA проверены, но provider дописал `.zip`.
  Исправлен общий destination MIME на `application/octet-stream`: **2 JVM RED →
  57 focused PASS**. Последующие попытки были BLOCKED на system UI/input timeout;
  точные новые `.linka`/`.linkabackup` пока не засчитаны.
  [Все исходные отчёты, включая BLOCKED](evidence/portability-native-2026-11-06/).
- Последующий **iPad17 export+backup:1/1 PASS,102.570s**, настоящий Files folder
  picker → fresh authorization → отдельные confirmations → terminal success.
  Точные `board.linka`/`library.linkabackup` созданы,19/21 ZIP entries, CRC/размеры/
  SHA всех18 media проверены. Доска и фраза наблюдались после записи. Это positive
  iOS proof, не закрытие Android provider failures или restore/recovery.
- После отдельного разрешения пользователя API35 штатно перезагружен и существующий
  API21 AVD запущен без wipe/удаления данных или snapshots. Повторное зависание
  API35 не объявлено доказанным app bug. Последний Back отменил незавершённый picker:
  это отдельно подтверждено foreground и надписью «Операция отменена.».

**Предыдущая граница quality gate до short-landscape коррекции:** полный **504/504 JVM PASS**, Android APK/tests,
оба iOS frameworks, app-host framework и lint0errors/46app+9shared warnings.
Лог `adaptive-mime-current-quality-gate.log`,7m42s; APK по-прежнему
`d7a0eedaaa4c790da221c46d468f52ac7babc23331cde690448bf5b7b1988558`.
На этом же исходном срезе полный новый Native runtime: **628/628 PASS,72.463s**;
binary SHA `bafe174b79e805a692ad58ef5ff6cb41eefc755b97c40a2a35f7db8831acd6c4`.
MIME-исправление и его regression expectation сохранены commit `0c3036e`;
runtime native-записи остаются отдельным OPEN, не заменены compile/JVM PASS.

Предыдущие полные **501 JVM /625 Native iOS17 PASS**
(79.216s, Native SHA `3b372001d0b29c0d98c576879890ee79d79d82fbf26cb83f751f4db1ff83733b`)
предшествуют adaptive-preview/MIME изменениям. Android APK/tests, оба frameworks,
app-host framework и lint тогда прошли; lint0errors/46app+9shared warnings.
После изменений сначала прошли57 focused JVM, затем описанный выше полный JVM/build/
lint/628 Native gate. Прежние625 не обозначают этот новый Native runtime PASS.

Дополнение native-проверок: API21 показал исходное правильное имя `board.linka`
без `.zip`, но системный Downloads ответил `Failed to save document` до возврата
и app confirmation. При read-only диагностике `/sdcard/Download` и
`/storage/emulated/0` отсутствовали; mount/permissions/AVD не исправлялись ради PASS.
Частичный файл не удалён, фактическое создание не подтверждено. Первый orientation
сценарий iPhone/iPad дал AX1/1 на каждом, но landscape app captures содержат чёрные
области/обрезание: **визуально не принят**, проверяется полным native screen capture.

Полный XCUIScreen capture отделил ошибку app-scoped XCTest crop от настоящего UI.
Чёрная область iPhone была capture-дефектом; реальные landscape подписи миниатюр
обрезались из-за слишком узкого квадрата. Новая предпочитаемая ширина96dp с cap
на половине viewport дала3 JVM RED →40 focused PASS и iPhone orientation1/1 PASS,
35.052s; подписи просмотрены, но картинкам над ними не хватало высоты. Минимум
области фразы поднят с48 до64dp только там, где1/5 окна меньше минимума; обычная
пропорция и min12sp/fontScale сохранены. Эта последующая небольшая UI-коррекция
проверена отдельным полным505/629 gate и native кадрами9 карточек, описанными в
самом начале журнала. Более ранние504/628 и screenshots с обрезанием сохранены.

**Оставшиеся release-блокеры:** полный UI atlas/landscape/large-font/contrast/IME/
keyboard/TalkBack/VoiceOver и пользовательское U1.4 approval; production startup
всё ещё legacy `LinkaPicturesApp`/`MainActivity`/`SetsManager`, без native migration
admission; G4/fresh OS verification, старые writers/privacy SDK, безопасный настоящий
upgrade, ARMv7 runtime, restore/recovery/Electron runtime exchange, codecs/mixed TTS/
offline/mic/routes/interruptions, physical/AAC, legal/store gates. Только synthetic
или copied data до G4, никакой production установки на настоящую библиотеку.

## История: возобновление с visual slice — 6 ноября 2026

Ниже сохранены прежние срезы и их результаты. Старые слова «текущий» относятся к
моменту соответствующей записи; authoritative status и границы gate — выше.

### Актуальный срез: настоящие записи, Stop и повторное воспроизведение

- Полные suites после caption и media-lease исправлений: **479/479 JVM** и
  **603/603 Native iOS17,62.103s**, без failures/errors/skips. Android APK/test APK,
  Simulator/device frameworks и app-host framework собраны офлайн.
- Реальные UI-проверки существующей фразы: **API21 1/1 PASS,5.046s**,
  **API35 1/1 PASS,28.809s**, **iOS17 1/1 PASS,45.232s**. Последовательность:
  recording-only фраза завершилась → повторный запуск → Stop из PLAYING →
  остановлено → повторный запуск завершился → library/Back и повторное открытие
  с прежней фразой. Нет переимпорта/clear/reset, микрофон и голоса не менялись.
- Исправлен настоящий product defect: составной MessageLease удерживал корректные
  pins, но StoragePins отвергал wrapper как чужой proof. Внутреннее разрешение до
  подлинного leaf сохраняет owner/closed/asset проверки, GC counters и lock order.
  Регрессия actual SQLite/media: **2 RED до исправления**, затем focused212 PASS;
  закрытые wrappers после cleanup failure также отклоняются.
  [Контракт и причина](media-lease-read-proofs.md).
- Отдельный hosted clip callback: **1/1 PASS,0.672s** после явного CInterop input
  для custom compilation; до исправления `tone: IrLinkageError,0.242s`. Это
  отдельная причина первого host failure, не дефект AVAudioPlayer.
- **TTS остаётся OPEN/RED**, как и physical audio quality/mic/routes/offline proof,
  GIF21, остаточные caption/UI1.4, migration/G4/ARMv7/AAC/legal/release. UI Stop
  проверен по настоящему product state, не является измерением физического
  output latency/тишины на аудиовыходе.

Это текущий срез; записи ниже — история и не заменяют актуальные verdicts.

### Текущий результат: реальная заполненная iOS-доска

- После UIKit native-identity fix полный Native gate: **595/595 PASS,52.545s**;
  device ARM64 framework тоже собран. JVM471 относится к неизменённому common
  product code; последующие новые audio app-host checks — отдельный compartment.
- iOS: настоящий UIDocumentPicker → локальный host Documents → preview1/9/9/9 →
  явные unlock/ImportNew → публикация. Для отделения picker от text-input проблем
  оставлено штатное имя «Импортированная доска». Это не финальная title parity.
- Первый полный import macro остался RED уже после публикации при закрытии меню;
  он не перезапускался ради дубля/очистки. Отдельный **existing board compose/reopen
  1/1 PASS,37.200s** подтвердил9 card buttons, ХОЧУ→КУШАТЬ и сохранение после возврата.
  [Actual iOS PNG и xcresult summary](../progress-screenshots/2026-11-06-ios-filled/).
- API21: опубликованная доска и сообщение сохранены. Отдельные warm-intent cleanup
  и native Back/reopen **2/2 PASS,2.408s**, без повторного импорта.
  [Actual phone кадры](../progress-screenshots/2026-11-06-ui14-phone/).
- GIF transparency measured: реальный API21 thumbnail decoder теряет alpha,
  API35 сохраняет; стандартный Movie на21 сохраняет. Storage bytes не менялись.
  Production fallback пока не принят: bounds/memory требуют проверки.
- Положительные audio app-host checks созданы без fake Main/foreground/AV delegates.
  Первый runtime **2 failures/0 PASS**: clip дал диагностируемую ошибку, TTS —
  INTERRUPTED с OS voice/audio-unit errors. Ни skip, ни установка голосов/микрофон,
  ни изменение системной громкости не использовались. Звук/Stop ещё не закрыты.

Полный U1.4/G4/release по-прежнему OPEN. В частности, на portrait видны разрывы
подписей; соответствующее исправление проверяется отдельно от test automation.

### Последняя проверенная вертикаль: обе платформы живые, не release PASS

**Полные shared suites:471 JVM /593 Native iOS17 PASS**, без failures/skips;
оба Native frameworks собраны. Отдельно **2/2 настоящий hosted Keychain XCTest**
и **1/1 XCUI startup/relaunch**: iOS development app теперь открывает библиотеку
«Ваши наборы», Keychain−34018 не блокирует startup. Проверены exact own Simulator
group, min17, strict signatures/actual payload и installed hashes. Пользователь
отдельно разрешил development UI; production/physical signing не затрагивается.
[Кадры и XCUI attachments](../progress-screenshots/2026-11-06-ios-development/).

Android calibrated Back/reopen **1/1 PASS,39.949s**,10 actual PNG; после отдельных
permission/manifest поправок последний APK ещё раз **1/1 PASS,30.448s**,9 AX карточек
и ХОЧУ КУШАТЬ сохранены. [Кадры](../progress-screenshots/2026-11-06-ui14-calibrated/device-resume/).
Toolbar64dp совпал; остаётся измеренное6dp отличие границы фразы/сетки и другие
незакрытые phone/font/IME/pixel acceptance. Пользователь не принимал эти кадры
как полный U1.4; наличие выделения/focus не удалялось ради копии1.4.

Lint запущен с намеренной загрузкой уже закреплённого lint-gradle31.10.1 после
отсутствия офлайн-кэша. Исправлены RECORD_AUDIO ownership/recheck и неработавшие
pathPattern без authority старого content filter, без baseline/suppression.
Native negative permission tests **3/3 PASS** (микрофон не разрешался/не записывал),
resolution-only legacy filter test **1/1 PASS** (writer disabled/не запущен).
Теперь lint **0 errors**,7 shared+46 app warnings явно остаются.
[Полная проверенная сводка/границы](verification-2026-11-06.md).

Ни filled iOS board/import, ни API21 unlock/phone matrix, ни positive mic/TTS/
provider/ARMv7/physical/AAC/migration/legal/store gates этим не закрыты. Full release
и переключение production roots по-прежнему только после соответствующей приёмки.

### Новый срез: настоящий Keychain host и объединённые UI-коррекции

- **2/2 actual app-hosted XCTest PASS на owned iOS17.0**, 0 skips/expected failures.
  Подтверждены индивидуальные тесты и `.xcresult` summary; до install проверены
  strict signatures и exact own-group Mach-O Simulator payload, после запуска —
  совпадение installed executable SHA с проверенной сборкой.
- Valid Development identity подготовлена пользователем вручную. Принудительно
  Development-подписанный host был отклонён AMFI−413/no matching profile до тестов.
  После этого пользователь **явно разрешил штатную Xcode Simulator-подпись нового
  UIKit app-hosted XCTest**; именно этот путь дал2 PASS. Profiles/trust/ACL/AMFI/SIP
  не менялись. [Границы и actual evidence](ios-keychain-xctest-host.md).
- Исправлен Gradle framework seam: compilation задаётся при создании binary,
  а не после замораживания main link inputs. Экспорт двух bridge методов проверяет
  `:shared:verifyAppHostTestFramework`; unlinked-symbol warnings устранены.
- После review интегрированы inline-grid `5b021c9` и контраст functional selection
  `93b4262`: persisted form первым вводом, resize только SaveGrid, серый/teal
  функциональный UI и сохранённый жёлтый FAB. Объединённые focused JVM **158/158 PASS**,
  оба Android development/test APK собраны отдельно; device-проверка и новые PNG
  пока не приняты — MCP увидел пустой список, эмуляторы оказались остановлены.

Это закрывает только Simulator Keychain test compartment. Продуктовый iOS root,
физическая security/audio/AAC/large-font матрица, UI approval и полный G4/release
не объявляются PASS. Более ранние BLOCKED/counts ниже сохранены как история.

### Последний delta: реальная заполненная доска, не U1.4 approval

Восемь изолированных исполнителей подготовили и проверили UI, связки, atomic card
kind, fixtures и API21 evidence; primary выполнил connected-code review/интеграцию.
На main **140 focused JVM и158 focused Native iOS17 PASS**, оба frameworks и
development APK; новый ingress **2/2 API35**. [Полные границы/логи](evidence/u14-2026-11-06/).

Реально импортированная «ХОЧУ» доступна на API35: 9 карточек, фраза ХОЧУ→КУШАТЬ,
Native Back→library→open сохраняет фразу. [Baseline/new PNG](../progress-screenshots/2026-11-06-ui14/)
просмотрены primary. U1.4/user approval ещё OPEN: не все различия/экраны приняты,
автоматические UI-прогоны RED, их counts не заменены ручной проверкой.

Последующий отдельный native reopen/Back прогон: **1/1 PASS,22.919s API35**, включая
explicit AX3×3/9 карточек и сохранение ХОЧУ→КУШАТЬ после Back/reopen. Исправлен тестовый
поиск clickable ancestor вместо требования clickable на named child. Import/SAF
не пересчитаны в PASS, pixel/user approval остаются отдельным незакрытым gate.

API21 восстановлен одним обычным boot без wipe; frozen incoming test дал preview,
но затем **1/1 failure** на кнопке unlock. Данные/snapshots не сброшены. iOS startup
crash исправлен required plist flag без отключения sanity-check; actual unsigned
app теперь остаётся запущенной и показывает **Keychain−34018, fail closed**.
Дальнейшая iOS visual/security приёмка BLOCKED legitimate signing/host; identity0.
Ни store/signing/real data, ни full release/physical/AAC gates этим не закрыты.

### Возобновление и исходный handoff волны

После документирования STOP пользователь явно поручил «делай всё … весь план …
в сабагентах, максимально асинхронно до30». Реализация возобновлена с U1.4:
независимые visual lanes, один writer на файл, isolated development sandbox.
Число30 — предел, не требование запускать зависимые/дублирующиеся задачи.
Пользовательское визуальное одобрение, safety/release gates сохраняются; production
roots/signing/stores не переключаются ради более быстрого отчёта. Ниже — срез STOP,
который сохраняет состояние/evidence до этого возобновления.

Текущая visual-волна: `a008cd8` сохраняет UI-контракт; `21bbbeb` интегрирует ранее
готовый composed-image/pager seam; `a9fc68c` сохраняет отдельный development runtime
и startup regression (не production acceptance). После connected-code review iOS
root/corrections интегрированы как `d738472/a03d971/bbd0204/f7bd55a/690f762/b08a2c5`
для изолированной visual-вертикали; normal Keychain/prompt/physical gates остаются
открыты. Новый focused Native compile/link запущен, результат пока не принят.

Асинхронные writers: `u14-communication` — только экран общения; `u14-library` —
библиотека/настройки; `u14-editor` — editor/card UI; `u14-theme` — tokens/primitives;
`u14-fixtures` — test-only данные/harness; `u14-navigation` — shared UI binding.
У каждого отдельный worktree и непересекающиеся пути. Devices/интеграция и финальный
safety review принадлежат COORD. Новые картинки/старые PASS заранее не принимаются.

Пользователь остановил реализацию после визуального review и подтвердил
**«UI 1.4 + согласованные функции»**. Код/builds/исполнители/device scenarios на
паузе; этот шаг меняет только планы и Markdown. Main HEAD `f6e2979`, ветка
`implementation/linka-pokazhi`; незакоммиченный Android runtime/debug/startup fix,
Native `import okio.use`, `.kotlin/` и screenshots сохранены. Все lanes завершены;
iOS lane подтвердила STOP, последний known SHA `68a2299`, активных задач нет.

**UI не принят.** Просмотр живой библиотеки/общения выявил техническую access-панель,
колонку одинаковых команд, лишние статусы/пустоту вместо приоритета сетки. Это не
соответствует 1.4, даже при зелёных core tests. Нормативный [атлас/контракт 1.4](ui-1.4-parity.md)
задаёт библиотеку-плитки, навигацию/иконки, компактную фразу, плотность доски,
editor grid и card dialog. Общая фраза, drafts, перенос/backup/recovery и защита
сохраняются, а не откатываются до старого функционального объёма.

Первый шаг после отдельной команды продолжить — одинаковая заполненная доска
1.4/новая, paired PNG и одобрение пользователя; затем весь атлас обеих платформ
и U1.4. Оставшаяся интеграция ждёт визуального gate. Compilation/empty startup не
закрывают U1.4, native security, G4 или physical/release gates.

## Разрешения и ограничения

- **Условный coding разрешён:** пользователь явно выбрал «Продолжать ядро (Recommended)» после iOS17/Android21 ARM64 smoke. 5 ноября зафиксировано исключение к ранним G0/G1→P02: можно реализовывать P02–P14, не объявляя неполные gates PASS. Native codecs должны пройти приёмку до их интеграции G3/G5, ARMv7/оставшийся baseline — до соответствующей safety/финальной приёмки, G4 — до настоящей библиотеки, physical/AAC и все store/release gates неизменны. Это не MVP и не исключение ABI/форматов.

- **Новая явная поправка R15:** после объяснения SDK/runtime пользователь выбрал «Поднять минимум до iOS17». Текущий минимум iOS/iPadOS теперь17+, не14. Android21/armv7/arm64 неизменны. Xcode остаётся stable27.2, не beta. Основной план и три приложения в `/Users/aacidov/.opencode/plan/` обновлены точечно; история прежних сборок14 сохранена. Это не согласие пропустить gates или установить дополнительные SDK/runtime.
- **Отдельное разрешение на среды:** следующим ответом «Да, Android21 и iOS17» пользователь разрешил скачать/установить соответствующие образы и создать отдельные тестовые среды. Существующие AVD/simulators не сбрасывать; новые лицензии, вход Apple и native permissions не подтверждать автоматически. Android21 ARM64 установку ведёт V, iOS runtime — COORD. `xcodebuild -license check` успешен без изменения лицензии; загрузка iOS17.0 ARM64 запрошена штатным `xcodebuild -downloadPlatform` с export на data-диск.

- Пользователь поручил выполнить план с параллельными агентами, worktrees и локальными ветками/коммитами. Основная ветка: `implementation/linka-pokazhi` от `v1.4`.
- На вопрос о недоступных minimum-runtime средах пользователь ответил «реши проблему». Это не разрешение поднять minimum OS, убрать ABI или объявить G1 пройденным без доказательств. Проверяется альтернативный совместимый кандидат; порядок stop-gates пока сохраняется.
- Явно разрешена штатная отмена только двух несохранённых тестовых состояний: `Audit14д` на `linka_audit_phone` и новый набор на `linka_audit_tablet`. Сохранённые наборы не удалять. Для продолжения аудитора передано это ограничение.
- Push/PR, загрузка в магазины, публикация, production signing/data, личные устройства, установка Xcode/SDK и принятие лицензий не выполнялись. Android state-changing ввод ограничен указанными тестовыми AVD и выданным разрешением.

## Пакеты и gates

### Последняя Build-волна перед UI STOP — 5 ноября

Пользователь переключился из Plan в Build и поручил выполнить все шаги до готовых Android/iOS. Затем отдельно разрешил дальнейшие действия на **выделенных LINKa симуляторах**, включая development runtime, синтетические данные и screenshots. Это не разрешение production signing/store upload, новых Apple аккаунтов/сертификатов или обхода системных прав macOS. Старые AVD/user data не сбрасываются ради успешного теста.

Приняты в main после primary connected-code review:

- R0 `aa94682/e60b9d8`: cloud/signing только manual opt-in,384 offline scenarios, no push/cloud/sign run. Official policy требует API36 для update и16KB проверки; min21 сохраняется. Identity lookup разрешён для своего test-host, **0 valid identities**, Keychain2 runtime всё ещё BLOCKED.
- P08 `9548d04/92ce9de/82e852e`: ephemeral proof FSM, owner-specific Android/iOS native mechanics, controlled prompt отзывает старые guards/caps без оживления stale request. Root failure может BLOCK access без удаления credential record; поздний known publish не открывает blocked gate. **17/17 JVM+Native focused**, затем **18/18 JVM** (15 Access+3 thumbnails), обе Native main compilations. Positive auth/modal/process-death/root binding не объявляются принятыми.
- P11 `11baf9e/840a0a5/68c11f2/036b102`: AuthoringSession и board/card editor, real SQLite/media tests **43/43**, cleanup/queued lease/dedup/re-auth adoption fixes. Prepared picker result не публикуется до явного Resume с новой capability; real background его отзывает.
- P12 `883d775/3ba73f7/406500d`: guarded import/export/backup/restore/recovery, bounded operation state, captured export report и owner-bound safe cancel после capability revoke; **18/18 lane JVM**. Fresh guards confirm неизменны, external partial не удаляется.
- P13 `54e703a/a4ec58a/1b305fd/0ca55ad`: library/settings/access/transfer и shared app navigation. **26/26 lane JVM**, обе Native/Android main compilation. Revoke до fallible cleanup; browse/смена досок сохраняет compose playback, protected entry останавливает. Перед external confirm проверяется настоящая fresh capability, не non-null token.
- P09 `4c7d33f/f6e2979` + COORD importer/codec `92ce9de`: synthetic admission, pure atomic SQL guards, credential-before-activation, strict resume/readback, close-failure blocks следующего writer; **21 migration tests** в lane. Exact legacy credential importer5 и internal-only flat1.3 mapping проверены; ordinary external reader whitelist прежний. Actual native binding/upgrade/signing/G4 ещё OPEN.
- Один integrated focused main gate до последующих root-only изменений: **116/116 JVM и116/116 Native iOS17,17650ms**, включая real SQLite authoring/transfer, credential importer и flat1.3 boundary. Не повторялись неизменённые63/54 device suites; не складывать focused totals в выдуманный full count.

R2 Android: отдельный debug ID `su.linka.pictures.development`, plain Application/Firebase provider removed и legacy activities disabled **только в development manifest**. Root/launcher — WIP, production startup не переключён. Thumbnails/owner callback **4/4 API21 и4/4 API35 flagged** ([evidence](evidence/r2-thumbnails-2026-11-05/)). Первый startup дал `INVALID_DATA`: caller создал media до fresh-root admission. `DevelopmentStartupTest` доказал RED на старом APK; после исправления порядка **1/1 GREEN API35 и1/1 API21**. Storage guard не ослаблен; удалён только доказанно пустой media-dir нового dev sandbox. Логи `r2-development-startup-{red,green,api21}.txt` в approved temp. Реальные empty communication PNG21/35 просмотрены в `docs/progress-screenshots/2026-11-05-product-r2/`; они доказывают запуск, **не визуальную приёмку**. Production APK и настоящая библиотека1.4 не обновлялись.

R2 iOS root: готовые lane commits `9be7e7f/5a72b3f/1212d8b` и review corrections `9f3fa7b/16f7137/68a2299` (modal-safe window, post-READY error/cleanup/foreign handle), **ещё не интегрированы и не приняты целиком primary**. Lane сообщила18/18 focused Native и unsigned builds, не main product acceptance. ICU17.2 warning: member `libicu.icudtl_dat.o` содержит только6.3MB const data,0 code/imports; metadata не доказала использование17.2 API, но full17.0 product runtime остаётся обязательным. Заголовки/minimum не переписываются. Keychain identity lookup дал0 valid identities, app-host BLOCKED.

P10/P11 visible-demand+pager seam `c9f6629` готов отдельно, **не интегрирован**; caller bind в LinkaApp не сделан. U1.4, product roots/migration binding/legacy writers/privacy removal/physical/AAC/legal/signing/release gates открыты. **Весь продукт готовым не называется; код остановлен.**

| Пакет | Фактический статус |
|---|---|
| P00 / G0 | Частично: source/APK/fixtures сохранены, карты legacy writers и Electron подготовлены. UI-follow-up дал 30 новых PNG и tablet save; phone отключился, оставшиеся сценарии UNKNOWN/BLOCKED. Play provenance и физические устройства не доказаны |
| P01 / G1 | Не принят целиком. Android21 ARM64 и API35 прошли SQL/callback/focus/IME на final APK886 после root-focus fix и согласования D8. iOS17.0 через Chrome/MCP установлен, native SQLite и public UI XCTest прошли. ARMv7 guest не загрузился в текущем Emulator даже с classic; codec candidates проверяются отдельно |
| P02 / G2 | **Зафиксирован:** normal bootstrap `bb4f920`→`a371877`, `iosApp/`, модели/команды/ports, streaming snapshots, schema/epoch/ownership handoff, C4. JVM14/14 и Native iOS17 14/14, Unicode16 1093 вектора, Android/оба frameworks/unsigned iOS builds успешны. Это не реализация P03–P14 и не G1 PASS |
| P03–P14 / G3–G5 | Core/application и UI-кандидаты интегрированы частично, см. последнюю волну выше. Исторический full257/361 и focused116/116 не текущая полная приёмка. UI1.4 не принят; Android root WIP, iOS root отдельно, native P08/Keychain/G4/production binding OPEN/BLOCKED. Код на паузе |
| P15 / G6 | Не начат: физические minimum/current устройства и AAC-участники недоступны в текущей проверке |
| P16 / G7 | Не начат; внешние review/signing/store permissions отдельно, публикация не разрешена |

## Выполненные независимые lanes

| Lane | Worktree suffix / ветка результата | Результат |
|---|---|---|
| Android P00 | `pokazhi-p00-android` / `implementation/p00-android-inventory` | Коммит `ce26641`, интегрирован как `890abe6`: [карта writers/readers/settings](../redesign-audit/legacy-storage-inventory.md) |
| Electron P00 | `pokazhi-p00-peer` / `implementation/p00-electron-contract` | Коммит `49f0a1d`, интегрирован как `3dc46d6`: [standard/audio/ZIP/fixtures evidence](../redesign-audit/electron-standard-contract-evidence.md) |
| UI P00 | `pokazhi-p00-ui-audit` / `implementation/p00-ui-followup` | `2604991`→`d7c1532`, `71b17cd`→`b2a15a4`, `47c6c79`→`74246db`: [evidence](../redesign-audit/p00-ui-audit.md), 30 просмотренных новых PNG; tablet types/TTS-submit/Tab+Enter/save проверены, phone disconnected |
| Platform preflight | `pokazhi-p01-platform-smoke` / `implementation/p01-platform-probe` | `15dad5d`→`8d14b6d`→`595061a`→`c7eb3a2`: отклонённые кандидаты и успешные final builds; `dedb578`: native iOS runtime. Эксперимент не интегрирован, minimum-runtime proof отсутствует |
| Android runtime | `pokazhi-p00-android` / `implementation/p01-android-runtime` | `a4dd51d`→`13bb635`: [API35 ARM64 UI/focus/SQLite/callback evidence](../redesign-audit/android-platform-runtime.md), 5 просмотренных PNG; baseline не затронут |
| Foundation P02 | `pokazhi-p02-foundation` / `implementation/p02-foundation` | `bb4f920`→`a371877`, normal bootstrap, результаты расширены G2 `0816394` |
| D / P03 | `pokazhi-p03-communication` / `implementation/p03-communication` | `ce76a873`→`38a34d6`, review corrections `d54acecb`→`9cd6a0d`; integrated common state machine, bounded saves/quarantine, JVM и iOS17 41/41 |
| S / P04 | `pokazhi-p04-storage` / `implementation/p04-storage` | `04ec421`→`8484377`, `fa87e237`→`70c0c2a`, `0c719e57`→`152cbb9`; integrated82 storage regressions с actual SQLite/probe/pins/epoch/uncertain/text guards, Native213 main gate PASS. Не G4/migration |
| P / P05 | `pokazhi-p05-linka-codec` / `implementation/p05-linka-codec` | `974d8752`→`22fffdb`, review fixes `9c000326`→`ad9ceaa`; integrated bounded codec, Native/JVM common suite и pure peer oracle PASS; native inspector/полный runtime round-trip OPEN |
| Shared audio prerequisite | `pokazhi-p06-audio-coordinator` / `implementation/p06-audio-coordinator` | `db767de7`→`a8d857c`, review fixes `b4b172cb`→`0d5e8ba`; общий coordinator принят,102/102 JVM+iOS17 вместе с P03/P05. Не native P06/P07 codec/permissions acceptance |
| Android audio/inspection | `pokazhi-p06-android-audio` / `implementation/p06-android-audio` | `64a4f9c`/`5875143`→`9c90297`/`728f1da`, fixtures8a5→28baf09. Owned25/25 обаAPI; expanded31/31 API35,30/31 API21: rawAMRExtractor0tracks. Direct raw-frame native decoder fix в работе; без mic/grants |
| Backup codec prerequisite | `pokazhi-p12-backup-codec` / `implementation/p12-backup-codec` | `1a5d951`→`99cd405`; общий LINKA/BACKUP engine, core Native213 PASS, не external save/authorization/product restore/UI/G4 |
| Joint core integration | `pokazhi-p14-core-integration` / `implementation/p14-core-integration` | `382421a`→`c6efd1d`: real SQLite/FS/StorageRuntime/BackupCodec→restore/reopen/recovery/GC, fake только synthetic media inspector. JVM214 в lane, main gate pending; не полныйP14/G4 |
| iOS FFmpeg candidate | `pokazhi-p07-ffmpeg-preflight` / `implementation/p07-ffmpeg-preflight` | Пользователь явно разрешил isolated minimal LGPL2.1+ candidate preflight, без GPL/nonfree/network. Не shipping integration; source/notices/relink/AppStore compliance остаются release gates |
| C / P10 early | `/Volumes/data/linka/linka-pokazhi-p10-shared-ui` / `implementation/p10-shared-ui` | `8dc9824`→`6052a06`: четыре palettes, font scaling, accessible house primitives и pure tests; следующий экран общения не подменяется tokens |

### История: принятый native integration срез после переноса

Review/integration завершены для Android transient files/guarded destinations, iOS files/ImageIO/image preparation/audio backend и development FFmpeg CInterop. Guard/admission исправления не держат IO под FSM-lock; nonempty/foreign audio allocations не присваиваются cleanup. Общий ImageEnvelope/Crc32 заменил Android image walker; native pixel decode остаётся обязательным, полной compressed-bitstream canonicality не обещаем.

- Aggregate `p07-native-adapters-gate-fixed.log`: **BUILD SUCCESSFUL,3m46s**; JVM XML **257 tests,0 failures/errors/skips**; оба Shared.framework, core/app-host test links, Android normal/test APK. SHA256 лога `ef80027babf7ff814b126b02a2d1b53501661de4e3a30e84e56c224d3f6f1d49`.
- Owned iOS17/21A328 `DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`: весь core **361/361 PASS,52502ms**. Log `p07-integrated-native-ios17.log`, SHA256 `c054eeae168ee013d8b6484aa5732f427f37f508e7b56175e0d1cbd370049e55`. Включены real files/ImageIO/CoreAudio PCM writer и Kotlin→CInterop→FFmpeg→owned WAV integration; **Keychain2 в361 не входят**, обязательный app-host runtime BLOCKED отдельно. Логи находятся в approved temp `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/`.
- Decoder prepare проверяет manifest/provenance обоих targets; fastpath без extract/configure/build/runtime. CInterop явно учитывает static archives. `-pthread` удалён только из ld linkerOpts; finite upgrade распознаёт старый собственный def, произвольные mutations не принимает. Native compiler/link min17 проверен `vtool`, binary headers не переписывались. [Binding/evidence](ios-audio-decoder-bridge.md).
- Android files: API21 **19/19** после guard-race fix; fixture-only canonical-path correction дала **2/2 API21 и19/19 API35**, SHA `2247f5bafc2e359e5ebe6a7b5cfbf87ec98ed6691ecbcdae8c595b1e0604e37b`. Production guards не ослаблены. [Files evidence](evidence/p06-files-path-2026-11-05/).
- Affected main image checks: **24/24 на каждом API21/35**, без failures/skips; APK SHA `683787ceaaba3969b926edcd745d07a8b725e85a7b301cd8ed7e875ffd2785be`. COORD сверил полные summaries/24 starts+24 pass и actual router/helper hashes. `cabfba1`→`6313446`, [отчёт](p06-main-image-envelope-runtime.md). Старые63/54 suites не повторялись.
- API35 crash подтверждён `.ips`: self-abort в empty gRPC callback, `PacketStreamerTransport::OnDone`, не GPU/timeout. Пользователь разрешил один per-run `-feature -WiFiPacketStream`; именно этот flagged AVD дал files19/images24. Данные/config/GPU/SDK/ADB не менялись; default stability, Bluetooth/WiFi не доказаны. [Диагностика](api35-netsim-crash.md).
- P08 research `31994db`→`4c14a6d` reviewed: AOSP5.0/5.1 SIM-only может вернуть системный RESULT_OK без device authentication. Пользователь затем явно выбрал **«Ограничить OS-проверку»**: на21/22 availability UNAVAILABLE, без нового lock/замены пароля/OS reset; legacy verifier/unlock и min21 сохранены. Compatibility decision принят, native implementation/lifecycle ещё OPEN, unsafe fallback не разрешён. [Policy/матрица](access-policy.md), [источники](device-owner-verification-evidence.md).
- Новая common compatibility regression прошла вместе со всей affected class: **12/12 JVM и12/12 Native iOS17,9762ms**, native test link PASS. Проверены unchanged legacy bytes/no writes/no OS prompt при UNAVAILABLE и прежний password unlock. Это не native Android verifier, он ещё впереди; предыдущий full257/361 не пересчитывается как исполненный258/362. Logs `p08-api21-compatibility-{policy,native-ios17}.log` в том же approved temp.

На этом историческом срезе lanes завершились, product roots/workflows оставались следующей работой. Эти pending формулировки не являются текущей очередью: actual состояние и UI STOP указаны в начале документа. Не повторять неизменённые доказательства ради продолжения.

### История: возобновление после паузы — 5 ноября

Пользователь вновь поручил «доделывай весь проект»: Build возобновлён, полный scope сохранён. Старые STOP/Plan пометки во внешнем checkpoint — исторический срез, не новая пауза. Максимум три независимые implementation lanes; безопасность/миграция/интеграция остаются у COORD.

COORD проверил owned iOS17 simulator `DD0F9A3F-DC32-4373-BA08-400FECE3A1CC` / `linka_platform_probe_ios17`, Booted17.0, и выполнил весь существующий main Native executable: **214/214 PASS,30132ms**, без filter/skip. Raw log `resume-native214-ios17.log` в approved temp. Включён actual joint SQLite→backup→restore→reopen→recovery/GC, fake только media inspection; это не decoder или product UI proof.

Raw-AMR delta `fc41a32` reviewed: один frame-size helper, borrowed reader, точные NB packets/timestamps/EOF, cancellation/limits, никаких remux/source writes/dependencies. Интегрирован как `c2b973b`. A выполнил **37/37 без skip/failure на owned API21/35**, rawAMR220ms на обоих, frozen APK SHA `78bf2245103973ac3d952c39f6ef657ca49f15ab5300898bf410ca76baf0d0d7`. COORD прочитал обе terminal summaries/freeze; evidence `5c41929`→`74c26cd` с полными [логами](evidence/p06-c2b973b-2026-11-05/). Это не ARMv7/positiveTTS/mic/physical/production target35 gate. A готовит Android images; F проверяет audio-only native harness, не расширяет whitelist video filters; C реализует shared communication screen. [C4](../c4/README.md) синхронизирован с core и незавершёнными швами, не изображает normal apps готовыми.

Следующий coherent gate main `a0d2315`: full JVM **240/240**,0 failures/errors/skips; Native linked executable целиком **240/240 на owned iOS17,40570ms**. Gradle aggregate (оба frameworks, app debug assembly и test APK) exit0/1m42s. Logs: `resume-ui-access-core-gate.log`, `resume-ui-access-native240-ios17.log` в approved temp. Native исполнил4 новых real file-store tests; core OS-verification fake не стал реальным owner proof. Текущие declarations/native stores/тесты после этого gate проверяются отдельно, старые240 не выдаются за новый набор.

C communication `c8f6183`→`cd80cbf`, review fix `b2de627`→`21e262a`: message и corrections отделены от secondary scroll, Speak/Stop закреплён, существующие session commands/state, обязательный voices, bounded borrowed images. Ни runtime image-loader/cache/leases, ни normal composition root не выдуманы. I-image `3e3f5d3`→`dde3faf`, review `49e94a8`→`58ee2db`: native exact-copy и CoreText/CoreGraphics→PNG,22/22 owned iOS17 tests после failure-safe cleanup/shared native-text reuse; files/inspector реальное wiring OPEN.

FFmpeg preflight `4573655`→`018cf0a` доказал native5-codec PCM и device static build. Пользователь отдельно разрешил **development integration**, не shipping. Bridge negative нашёл external dref flags0, который FFmpeg игнорирует при embeddedAMR. COORD не ослабляет negative: non-self-contained URL/URN/alias references отвергаются bounded structural validator до player/decoder на обеих платформах, supplemental io_open остаётся deny-all. Этот bridge draft ещё не интегрирован; legal/source/notices/relink/store gates OPEN.

Keychain candidate скомпилирован, но первые2 standalone tests получили **errSecMissingEntitlement −34018** при initial read — не «пустой store». Параметры защиты не убирались и тесты не skipped. Пользователь явно разрешил отдельный **ad-hoc isolated test-host с только собственной Keychain group**, без production identities/прав macOS/доступа к прежним записям. AMFI подтвердил **−424 restricted entitlements**, launchd153; дальнейшие ad-hoc попытки прекращены, обход не выполняется. Неудачный runner удалён. Два обязательных OS-bound tests находятся в отдельном `appHostTest` compartment: link/registration подтверждены, runtime **BLOCKED** до штатного signed Xcode host/отдельного разрешения signing. [Evidence и точный gate](ios-keychain-host.md). Настоящие device-owner prompts — отдельная незавершённая область.

### Продолжение после переноса AVD/SDK/Gradle

Пользователь сам приостановил работу ради переноса, затем явно поручил продолжить. Новые paths: SDK `/Volumes/data/developer-state/android-sdk`, AVD `/Volumes/data/developer-state/android-avd`; все5ini обновлены. Старые SDK/AVD aliases, `~/.gradle/caches` и wrapper — валидные symlinks на data. Parent read-only подтвердил free internal57.87GiB/data128.54GiB и отсутствие active emulator/Gradle jobs; MCP ADB devices пуст. Старые данные/каталоги агент не удалял. Полная загрузка двух owned AVD проверяется отдельно без wipe/reset/grants; старые63/63 не повторяются ради переноса.

Reviewed A commits: Android image `93abfed`→`9fe2d16`,54-test evidence `37fde31`→`e983598`, empty-string parity `5f75a8d`→`af74d5a`, bounded external-dref validator `219d46b`→`96fb42a`. Прочитаны production/cases и обе terminal summaries: **63/63 API21/35**,0 failure/skip, frozen test APK SHA `1c9d835cdcd98ffedcd853ded375445c659f8206c35d68599dec30c7d4f0faae`. Genuine3GP220ms/sourceSHA сохранены; raw external URL/URN/alias отвергаются до decoder, mdat opaque. [Доказательства](evidence/p06-dref-2026-11-05/) относятся к исполнению до переноса, не к новым boot checks.

F dirty decoder draft сохранён после infra disk-full error; продолжение разрешено после восстановления свободного места, не повтор всего принятого preflight. I-files44/44 result ещё не integrated: review обнаружил provider IO под lock (Main close мог блокироваться) и observer-token cycle; correction8ace115 вернул53/53 и primary принимает конкретный delta отдельно. Root first focused build после переноса остановлен лимитом tool120s при app-host link (daemon подтвердил client-disconnect cancellation, не code failure); незавершённые tasks завершены с подходящим deadline в `resume-after-avd-move-link-complete.log`, exit0/1m42s. Повтор completed native/device suites не выполнялся.

Latest main core Native после image22 исполнен целиком на verified owned iOS17: **262/262,56147ms**, log `resume-native-core262-ios17.log`. Два app-host Keychain tests остаются отдельно зарегистрированными и BLOCKED, в262 не входят; новой полной security/native приёмкой этот core total не называется. Android63/63 из прежних frozen результатов сохранены, не перезапускались.

A подтвердил full boot обоих перенесённых AVD и установленный test APK **16,144,120 bytes/SHA `1c9d835c…0f0faae`** на обоих — данные тестовой установки сохранились. После этого API35 host завершился SIGABRT, причина ещё не установлена; только API21 остаётся connected. Без автоматического restart/renderer/config/lock changes. Parent расследует host lifecycle, A продолжает native files compile+focused API21, API35 runtime OPEN. Signing/real owner/real data/physical/AAC/store gates не сняты.

История Android review: `64a4f9ca` не запускался до nonempty-temp/float/Firebase fixes. После `5875143` aapt подтвердил provider removal/deactivation/no-mic; COORD проверил owned AVD/test UID и исполнил25/25 наAPI21/35, затем31-test corpus. [Raw runtime evidence](../redesign-audit/android-native-audio-evidence.md). API21 single failure traced native probe: actual rawAMR NB header valid, MediaExtractor.trackCount=0, а тот же AMR codec в3GP декодируется. Фикс direct raw frames→platformAMRNBdecoder согласован без новой зависимости/переписывания исходника/skip теста. Target34 self-test не подменяет production target35 foreground/focus, positiveTTS/mic/route/ARMv7/AAC OPEN.

### Первая implementation/review волна после G2

- D вернул `ce76a873`: 31/31 JVM и Native compilation, но **ещё не интегрирован**. COORD прочитал actual state machine/lease/rendering implementation и запросил bounded save coalescing (один in-flight+latest pending вместо неограниченной истории) и поддержку нового неподтверждённого commit исхода. D продолжает ту же ветку; source changes не дублируются в main.
- S остановил незакоммиченный draft после **двух воспроизводимых real SQLite safety-регрессий**: чтение writer-ом своей незакоммиченной revision давало ложный Completed; exception после actual COMMIT и недоступная проверка давали ложный Failed. Draft **не интегрировать**. COORD принял fail-closed протокол `OperationResult.UnconfirmedCommit`/quarantine, независимую bounded reconciliation после закрытия writer, запрет writes/GC/автоповторов до explicit reopen. Exact declarations/семантика: `01e2adb`; S исправляет оба сохранённых теста без сужения fault гарантий.
- P вернул docs-only stop `ba11871d` о классификации active/passive unknown fields. Пользователь явно выбрал **«Да, явные поля функций»**: reserved direct root/page/card action(s)/command(s)/event(s)/script(s)/navigation отвергают whole archive; вложенные opaque metadata не сканируются и не исполняются. COORD зафиксировал конечное правило+UNSUPPORTED_FEATURE в `601291e`; P продолжает реальную codec-реализацию, не docs-only acceptance.
- После additive frozen updates main `:shared:jvmTest :shared:compileKotlinIosSimulatorArm64` прошли; Native execution/full linked artifacts будут перепроверены после интеграции исправленных lane results. Все три исполнителя получили одинаковые updates; конфликтные draft/реальные данные не затрагивались.

S после исправления uncertain-commit тестов нашёл новый read-only blocker: generic driver close checkpoint-ит source schema2 WAL (4096→12288 bytes). Draft снова **не интегрирован**,48 storage tests:46 passed/2 новых red. COORD проверил опубликованный source2.5.2 и официальную SQLite WAL/URI документацию: flags overload публичен, но generic factory его не несёт; `immutable=1` нельзя выдавать за универсальное safe recovery. Принят initial **probe-copy** протокол с single-writer предпосылкой, streaming stable DB/WAL/journal copy, SQLite recovery только копии, неизменными source files и отказом без source-open при ошибке. Точная ownership/fault матрица добавлена в code-contracts; S должен доказать её перед финальным commit, не выключать regression.

Вторая review-волна: P05 вернул реальный codec `974d8752` (35 JVM tests, Native compilation, pure Node peer oracle3.0/2.0), **пока не интегрирован**. COORD прочитал4 production files и запросил central-index name memory budget (4096 UTF8 bytes/path,1MiB aggregate до allocation) и cancellation check после final acquire с late-result cleanup. Новые budgets зафиксированы в handoff, старые tests/peer proof сохраняются. Shared audio вернул `db767de7` (66 JVM tests, Native compilation), **пока не интегрирован**: review всех3 production files потребовал failure-safe stop/finish/lease/temp cleanup/wakeup и явный synthesis maxTextLength отказ до backend. Исполнители исправляют свои branches, COORD не дублирует их implementation; native codec/OS/physical acceptance этими fakes не подменяется.

### P05 integration acceptance

P вернул review fixes `9c000326`: production+13/-2 и4 новые common regressions,39/39 isolated JVM. COORD просмотрел actual delta/предельные UTF8/aggregate tests/noncooperative final-acquire cancellation и интегрировал `974d8752`/`9c000326` как `22fffdb`/`ad9ceaa`. Docs-only stop `ba11871d` перенесён как история `aca90c2`, не как готовый codec.

Main gate после review: JVM + Native test link + оба Shared.framework + Android assembly, **BUILD SUCCESSFUL**,47s. Объединённые tests: **66/66 JVM**,0 failures/errors/skipped; **66/66 Native iOS17.0/21A328**,24506ms на том же owned UDID. Raw native log только в approved temp `p05-integrated-native-ios17.log`. Из main повторно исполнен pure peer oracle на E `c540646…`/Node22.22.2: legacy2-flat, actual Kotlin ZIP3 writer reader/normalizer, sparse/span visibility **PASS**, peer git status unchanged. Не Electron app/storage/TTS runtime и не decoder proof.

COORD явно принимает bounded payload decoding, не canonical-DEFLATE proof: public Okio prefetch может скрыть trailing compressed bytes, при этом полный compressed range учтён, фактический payload ограничен size/CRC/hash и только он передаётся inspector. Лимитация записана в handoff/report; никаких private APIs/собственного deflate/one-byte slow feed. UTF8 flag требуется для non-ASCII ZIP names, non-content media references отвергаются целиком без потери молча. Все оставшиеся native/physical/ABI, real inspector allocation и полный cross-app runtime round-trip gates OPEN.

### P03 integration acceptance

Cross-module поправка перед authoring интегрирована: COORD согласовал actual uniform geometry (1cell/position) с R04. `0d95e42c`→`5b58e2f` нормализует spans к1 в read/write, удаляет старый packing/overlap rejection (+16/-37 production) и лишнее implicit trailing padding. Input sequence/holes/explicit EMPTY metadata и все содержательные карточки остаются, writer не реанимирует opaque geometry после move/resize. Новые typed spans/UI JSON parsing/автосортировка не добавляются.

После review delta main aggregate gate прошёл: **105/105 JVM**, Native test link, оба Shared.framework, Android assembly,49s. Owned iOS17.0/21A328: **105/105 PASS**,26099ms, raw log `p05-uniform-integrated-native-ios17.log` в approved temp. Pure peer oracle E/Node22.22.2 из main:4 actual writer fixtures, uniform1x1 import/edit/sparse visibility, metadata/audio и compact padding PASS. Это не полный Electron runtime/device/decoder proof. P12 codec prerequisite будет переиспользовать один ZIP engine с двумя фиксированными профилями, без второго parser-а/новых dependencies/live replacement; exact handoff в code-contracts.

COORD reviewed точный delta `ce76a873..d54acecb` по save lifetime/quarantine/closing/cursor и regression tests; frozen signatures не менялись исполнителем. Основная ветка интегрирует только собственные implementation commits D, не дублирует cherry-picked COORD updates. Проверено в main `9cd6a0d`: `:shared:jvmTest :shared:linkDebugTestIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64 :app:assembleDebug`, **BUILD SUCCESSFUL**,44s. JVM XML:41 tests,0 failures/errors/skipped. Native executable запущен на подтверждённом owned iOS17.0 UDID: **41/41 PASS**,23009ms;4096-part boundary stress занял22159ms, не скрыт как мгновенный runtime.

Это принимает common communication implementation, включая controlled seam fakes, а не физические audio/storage/UI/device функции. Нормальный APK не устанавливался, sandbox/legacy/защита не подключены. Общие P06/P07 audio sequencing можно реализовать независимо по AudioPort/NativeAudioBackend; codec/OS evidence остаётся OPEN.

Дополнения Android21: `fa17623d`→`d44723b` сохраняет успешную загрузку SDK и первоначальный tools-root blocker; `c9a32601`→`5a35201` подтверждает его исправление co-located cmdline-tools20.0, новый API21 ARM64 AVD и SQL/callback runtime, но честно фиксирует clearFocus/IME FAIL.

Текущий probe stack: Kotlin/KMP/compiler2.2.21, CMP1.9.3 (Android Compose1.9.4), SQLite2.5.2, **AGP8.10.1 / Gradle8.11.1**, compileSdk35/minSdk21, JDK17.0.19, Xcode27.2, app exact17.0. Actual AGP-loaded R8 **8.10.24** проверен reflection assert против официального minimum8.10.21 для Kotlin2.2; прежний8.6.27 выдавал metadata warning и не оставлен скрытым. Wrapper distribution SHA pinned. `394de1f` исправляет Android21 native focus fallback, `cd78fba` — compiler chain, final APK SHA `886630c576528783bede77bf9385d3c7471b90f87f708524043ac6f415f76c0b`. `d85fcf1`/`67e6a83` дают app17 и passing XCTest17. Это проверенный изолированный кандидат, не G1 PASS и не product runtime, подключённый к библиотеке.

Все эти worktrees созданы штатным OpenCode tool под `/Users/aacidov/.local/share/opencode/worktree/67846f/`. Tool создал P00 worktrees с detached HEAD; завершённые Android/Electron commits дополнительно закреплены именованными ветками. Чужие существовавшие worktrees/ветки не изменяются. Вложенной делегации нет.

## Проверенное координатором

- [Baseline artifact evidence](../redesign-audit/baseline-artifact-evidence.md): snapshot 140 исходных файлов с проверкой хешей; коммит `3deade7` сохраняет 135 неигнорируемых docs/PNG. Проверено, что 138 неизменявшихся оригинальных файлов совпадают со snapshot; два Markdown обновлены явно. Исходные PNG сохранены.
- Локальный APK1.4/5 сохранён; `:app:assembleDebug` успешен, SHA до/после совпадает. Это локальный debug baseline, не магазинная подпись/chain.
- ADB MCP `pm path` + `sha256sum` подтвердили совпадение установленных `base.apk` обоих выделенных AVD с сохранённым локальным baseline. Private library/preferences при этом не читались.
- Старые PNG20/21 визуально прочитаны; phone21 — fullscreen IME, не редактор. В `screens.md` это теперь обозначено явно, без подмены изображения.
- [Платформенный preflight](../redesign-audit/platform-preflight.md): фактические AAR manifests проверены; доступное окружение инвентаризировано. Android21/armv7 и iOS14 runtime не подменены современными эмуляторами.
- Новые characterization tests legacy password writer/verification используют только synthetic Robolectric preferences. Они не меняют защиту приложения или настройки устройств и не являются P08.
- Focused `:app:testDebugUnitTest --tests su.linka.pictures.components.ParentPasswordDialogTest`: итог 5/5, 0 skipped. Первый запуск имел один infrastructure fetch failure (`Connection reset` до application setup); повтор той же команды без code changes прошёл. Сценарии не отключались.
- Та же focused class в `:app:testReleaseUnitTest`: **BUILD SUCCESSFUL**, 5/5, 0 skipped/failures/errors по XML. Production signing/key не использовались.
- Tablet test library намеренно дополнена новым `P00TabletSave` (6→7), исходные шесть наборов не открывались для изменения. COORD просмотрел save confirmation/library PNG; новый набор оставлен, не удаляется. Качество/валидность TTS-аудио и offline-only voice не доказаны UI success.
- Phone `emulator-5554` отключился при вводе имени собственного тестового набора; timeout/`error: closed`, повторный ADB MCP list показывает только tablet. Автоповтора ввода/перезапуска/очистки не было, несохранённое состояние phone неизвестно.
- [iOS runtime evidence](../redesign-audit/ios-platform-runtime.md): COORD создал отдельный собственный iOS26.5 simulator, установил только `.platformprobe`, визуально проверил startup PNG и выполнил native SQLite assert harness. Ни один чужой simulator не сбрасывался; iOS14/полный UI-ввод этим не проверены.
- Android35 ARM64 runtime: installed SHA совпал с probe artifact; реальные Unicode и ASCII/апостроф readback/callback, focus/IME/clearFocus успешны; собственный SQLite-файл 12288 bytes. Probe остановлен и оставлен установленным, baseline app/библиотека не использованы.

## История перехода P01 (до разрешённого условного P02)

После поправки R15 проверяется новая сборка target17 в изолированном probe. Для перехода по прежнему порядку нужны Android21 ARMv7/ARM64 и **iOS17** runtime среды, оставшиеся G0 проверки; они пока не установлены. Официальный API21 ARM64 image доступен для скачивания; совместимость конкретного старого образа с Emulator нужно проверить запуском. ARMv7 runtime — отдельная проверка. Исторический availability-case API15 на iOS14 больше не блокирует новую цель17; общая minimum-runtime приёмка остаётся обязательной. Пользователь изменил минимум, но не разрешил пропустить gates; P02–P13 пока не начаты.

**Обновление после MCP:** Android21 ARM64 и iOS17.0 уже установлены и действительно исполнялись. Прежнее отсутствие этих сред больше не является blocker. User выбрал «Сначала установить iOS17», перенос gates не разрешил. Сейчас завершаются исправление Android21 focus и native iOS17 UI-проверка; ARMv7 runtime, physical/AAC и оставшиеся baseline/codec доказательства не подменяются зелёной отметкой.

**Текущая граница:** исправление focus прошло на21/35, public XCTest17 — 1/1 passed. User дополнительно разрешил ARMv7 image/отдельный AVD; образ установлен и AVD создан, но stock Emulator37.2.12 дважды отказал до boot (auto и документированный classic comparison). Native32/app32 proof отсутствует; это не ошибка APK и не разрешение убрать ABI. История `006371fe`→`3da7463`, final886 quick64 `94e5e5a`→`2b84459`, classic `b0a27efe`→`32a01d9` интегрированы. Отдельно проверяются обязательные codec candidates; P02 не начат, stop-gates не перенесены.

Codec preflight завершён как **bounded diagnostic, не PASS всего набора**: оба public native paths на17.0 реально декодировали WAV/MP3, но не genuine Ogg/Vorbis. AMR-NB/3GPP fixture получена public Android MediaCodec из синтетического PCM без microphone/AudioRecord/новых permissions (`implementation/p01-amr-fixture`, `9f335b1`); host валидировал actual AMR и3GP, не AAC. На17.0 проверенные native paths PCM не выдали, public AMR decoder list пуст; physical не проверен. [Точная матрица/ошибки/границы](../redesign-audit/codec-preflight-summary.md). Локальная decoder dependency пока не выбрана/не интегрирована; R10 не сокращён. Изолированный codec helper находится только в новом `pokazhi-p01-android-codecs`, package `.platformprobe.codecs`, без product/library changes.

Повторная проверка сохранности: **133 исходных неигнорируемых docs/PNG**, кроме двух явно обновлённых README/screens, совпадают со snapshot. Первоначальная проверка138 также включала5 `.DS_Store`; теперь один ignored `docs/redesign-audit/screenshots/.DS_Store` изменился, автор записи не устанавливался, metadata **не откатывались**. Все исходные140 файлов всё ещё сохранены в проверенном preimplementation tar; содержимое исходных PNG/архитектуры не потеряно.

## P02 завершение и текущий переход

5 ноября: приняты [точные code-contracts](code-contracts.md): режимы/команды, SQLite schema1, единственный writer, media/lease/GC, optimistic revisions, monotonic restore epoch, streaming snapshot и отдельный открытый backup schema1. Предварительный aggregate всех документов/медиа в памяти удалён; closure/atomicity доказывают P04/P12 tests, не тест сериализации manifest. COORD review сохранил guard непосредственно перед commit и финализацию media+lease под writer lock.

Unicode16 реализован самостоятельно по официальным правилам/данным, без несовместимого Kotlin-порта и новых dependencies; лицензия/хэши и полный corpus сохранены. C4 механически согласован отдельным агентом по принятым K1–K9. [Фактическая P02 проверка](verification.md#p02-contract-freeze--5-november-2026): JVM14/14, Native iOS17 14/14,1093 Unicode vectors, обе ARM framework links, Android assembly, обе unsigned iOS shell builds. Первый Native запуск ожидал системный Removable Volumes prompt до входа в тесты; пользователь **сам вручную** разрешил доступ, тот же бинарник прошёл, обходов/agent approval не было.

Все исходные C4 теперь намеренно обновлены P02 и сохранены Git-историей; предыдущая сверка133 относится к состоянию P01, не утверждает их неизменность после принятой правки архитектуры. Реальные данные и normal apps не запускались. Следующий пакет — независимые P03 message, P04 storage, P05 `.linka` по frozen handoff; COORD отдельно принимает security/decoder решения. Условный порядок уже разрешён, повторно его не спрашивать.
