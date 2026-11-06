# U1.4: integrated evidence, 6 ноября

Это промежуточная visual-волна, **не full quality gate / U1.4 PASS**.

- Integrated focused JVM XML: **140 tests,0 failures/errors/skips** (UI, authoring,
  новый kind serialization). Первый aggregate остановился позже на лишней скобке
  только в новом Android instrumented test; исправлен и пересобран только этот target.
- Integrated focused Native на owned iOS17: **158/158,1259ms**, обычный test link
  без исключения common tests; оба frameworks собраны. SHA test executable
  `dd146a55626ac762acabf32a7d6ae09172219f8761e43f00cb9f109702bab7b3`.
- Новый Android ingress control flow: **2/2 API35,48ms**, без UI/auth/provider fake
  authority. Это Intent/URI checks, не положительный SAF/owner proof.
- App APK SHA `3700456b8e5ab931d73a15a073881daf57265ba2a0904f2ae9cd92415deae1f2`:
  только `.development`, plain Application, без FirebaseInitProvider; узкий debug
  FileProvider экспортирован=false, только `cache/u14-fixtures/`. Нормальный APK1.4
  на tablet не обновлялся, его SHA остался `8f33a334…882f04`.
- Android UI automation пока **RED**: SAF selection/return, затем ошибочная
  предпосылка пустого sandbox после уже выполненной публикации. Cleanup test ранее
  маскировал primary ошибку, теперь failures сохраняются со suppressed cleanup.
  Эти прогоны не объявляются PASS по факту появления доски.
- Manual API35: normal development app переоткрыла реально импортированную «ХОЧУ»;
  девять карточек и выбор ХОЧУ→КУШАТЬ проверены по pixels. Native KEYCODE_BACK →
  библиотека → повторное открытие сохранили фразу, без автоматической речи.
  [Парные PNG](../../../progress-screenshots/2026-11-06-ui14/) просмотрены primary,
  пользовательского одобрения пока нет.
- iOS unsigned app сначала падала: actual console назвала отсутствующий
  `CADisableMinimumFrameDurationOnPhone`. После добавления=true в source/packaged
  plist обычная unsigned сборка17.0 остаётся запущенной. Sanity check не отключён.
  Затем actual UI честно показал Keychain **−34018**: библиотека не открыта, защиты
  не обходили. Нужен легитимный signing/host; compile/158 tests не закрывают этот gate.

Полные логи и frozen artifact metadata рядом. Секреты, личные данные и production
credentials не использовались. Старые full257/361 не выдаются за нынешний full total.
