# Native portability evidence — 2026-11-06

Архив исходных свидетельств: 5 JSON и 17 PNG скопированы побайтно без изменения исторических результатов RED/BLOCKED. Это архивирование отчётов, не новая проверка устройства или файлов на нём.

## Последовательность попыток

1. [android5556-report.json](android5556-report.json): исходные export и backup дошли до terminal success; отчёт фиксирует проверку содержимого архивов, но DocumentsUI добавил `.zip`: итоговые имена заканчиваются на `.linka.zip` и `.linkabackup.zip`. Строгая проверка расширений не пройдена. Сохранены `android5556-export-provider.png`, `android5556-export-fresh-guard.png`, `android5556-export-confirm.png`, `android5556-export-success.png`, `android5556-backup-provider.png`, `android5556-backup-fresh-guard.png`, `android5556-backup-confirm.png`, `android5556-backup-success.png`, [android5556-provider-appended-zip.png](android5556-provider-appended-zip.png), [android5556-existing-drafts-preserved.png](android5556-existing-drafts-preserved.png), [android5556-message-preserved.png](android5556-message-preserved.png), [android5556-final-library.png](android5556-final-library.png).
2. [android5556-postfix-MIME-blocked-report.json](android5556-postfix-MIME-blocked-report.json): повтор после MIME-исправления на API 35 заблокирован до первого UI-действия: null root, hierarchy timeout и activity dump timeout. Успешный новый скриншот не заявлен; export/backup не начаты.
3. [android5556-postreboot-report.json](android5556-postreboot-report.json): после перезагрузки дошли до реального picker с предложением `board.linka`; `type_text` завершился таймаутом, поле осталось `L`, Save не нажат. Backup не начат. Сохранены [android5556-postreboot-initial-library.png](android5556-postreboot-initial-library.png), [android5556-postreboot-initial-message.png](android5556-postreboot-initial-message.png), [android5556-postreboot-export-picker-open.png](android5556-postreboot-export-picker-open.png), [android5556-postreboot-timeout.png](android5556-postreboot-timeout.png).
4. [android5556-default-name-report.json](android5556-default-name-report.json): последняя попытка проверить имена по умолчанию остановлена после ровно одного Back и следующего hierarchy timeout. Результат скрытия клавиатуры/отмены picker не наблюдался; Save не нажат, новые export/backup не начаты, новых PNG нет.

Дополнительно сохранён исходный PNG [android5556-backup-return.png](android5556-backup-return.png); основной отчёт не перечисляет его в массиве `evidence`.

## Хеши и границы доказательств

[android5556-artifact-manifest.json](android5556-artifact-manifest.json) содержит исторические SHA256 архивов и их записей, CRC/структурные сведения. Хешей PNG в исходном манифесте нет: проверено наличие фактически вычисленных SHA256 всех 17 PNG, совпадений в манифесте — 0. При копировании SHA256 всех 22 исходных файлов совпали с назначением; это проверка локальных копий, не независимая проверка файлов на устройстве. Сами ZIP, backup и APK сюда не включены.

Идентичность runtime приводится только по отчётам: исходный `bb73c51df6082c553244a1dc7e822b6ba8d0997f79adce74ed72a9baabd7acf3` указан как parent-supplied, без самостоятельного rehash; в последующих отчётах `d7a0eedaaa4c790da221c46d468f52ac7babc23331cde690448bf5b7b1988558` указан как проверенный родительской сессией, не этой архивной работой.

**Исторический OPEN до продолжения 7 ноября:** на исправленном APK не были доказаны фактические новые файлы с точными `.linka` / `.linkabackup`, завершение записи, новые архивные/hash/CRC-проверки; отдельно не был проверен ввод пользовательского имени. Та попытка не доказывала возврат в библиотеку. Ниже записано отдельное последующее подтверждение стандартных имён; старые отчёты сохранены.

## Дополнение: последняя попытка API 35

[android5556-default-final-report.json](android5556-default-final-report.json) фиксирует отзывчивый начальный экран с подтверждённой отменой предыдущей операции, возврат в библиотеку, выбор «Экспортировать» именно для `LINKa-EDITOR-6F9C-SAVED`, разрешение команд и запрос подготовки экспорта. [android5556-default-final-export-picker.png](android5556-default-final-export-picker.png), несмотря на имя, показывает **TransferScreen с отключёнными кнопками подготовки, не picker**. Следующий hierarchy вернул null root; Save, внешняя запись и backup не выполнялись. Строгая native MIME-проверка остаётся **OPEN**. Родительская сессия остановила повторы API 35 и перешла к отдельной линии API 21; её результаты это дополнение не оценивает. Два новых файла скопированы без изменений, SHA256 копий совпадают с исходниками; прежние свидетельства сохранены.

## API21: default-name attempt

`android21-default-manifest.json` и `android21-default-*.png` фиксируют отдельную
проверку установленного исправленного APK на API21. Без ввода текста Downloads
предложил **board.linka** (без `.zip`), но после единственного Save показал
`Failed to save document`. App preview/fresh authorization/final confirmation
не достигнуты, backup не запускался. Это **не** positive export/backup proof;
возможный частичный файл не проверялся и не удалялся. После остановки primary
read-only проверил отсутствие `/sdcard/Download` и `/storage/emulated/0`; mount,
permissions и AVD не менялись. Исходная фраза ХОЧУ КУШАТЬ наблюдалась до операции.

## iPad17: положительный native export и backup

[ipad17-native-export-backup.json](ipad17-native-export-backup.json) и семь
`ipad17-*.png` фиксируют настоящий Files folder picker → свежую авторизацию →
отдельное подтверждение → terminal success для обоих файлов: **1/1 PASS,102.570s**.
Получены ровно `board.linka` и `library.linkabackup`, без `.zip`; они оставлены в
выбранной локальной папке изолированного fixture host, не помещены в Git.

- Export: pages3.0,1 страница3×3,9 карточек,9 изображений/9 записей,19 ZIP entries;
  SHA256 `a8dca172b5c09183583b540d569b8ced9ffd74165ae19ceff4298f96b0a61211`.
- Backup: format1.0,1 доска/0 черновиков/18 assets,21 ZIP entries;
  SHA256 `1c198000ed5be181f8d8102b8f800ec69cb94adc7af3d40c279f383f8ad0baed`.
- CRC, размеры и SHA всех18 media проверены, после чтения оба файла неизменны.
  Доска,9 карточек и фраза ХОЧУ КУШАТЬ фактически наблюдались после записи.

Это закрывает данный положительный iOS picker/write сценарий, **не** Android
provider acceptance, restore/recovery, physical/AAC или full release. Отсутствие
структурных password/current-message полей в manifest/settings не объявляется
общей гарантией отсутствия личного содержимого в открытых досках/медиа.

## Актуально 7 ноября: точные стандартные имена подтверждены на API35

Read-only snapshot нашёл уже открытый сфокусированный DocumentsUI picker без
нагрузки/ошибок. Вместо нового prepare/type_text продолжен **тот же** запрос:
ожидание native resumed Activity перед чтением UI, Save один раз, свежий доступ
и отдельное подтверждение. Новых перезагрузок/переустановок/сбросов не было.

- [android35-resumed-default-export.json](android35-resumed-default-export.json):
  `board.linka`,11576 байт, SHA
  `e382a3190165fabf7dc4dcf54bb42770a3c76bd2cd375dd0d5828f36c4d6d722`;
  собственная доска2×2/NATIVE-CARD-35/NONE,2 ZIP entries, CRC/размер/SHA изображения
  проверены. ZIP-байты совпадают с прежним `.linka.zip`; изменено именно расширение.
- [android5556-backup-resumed-report.json](android5556-backup-resumed-report.json):
  `library.linkabackup`,3196647 байт, SHA
  `a87a9c98d3555179d3e9e8c11b8ce7f8d43ada6aae170091ad656d56cac7a523`;
  2 доски/2 черновика/19 assets/25 ZIP entries, CRC/размеры/SHA проверены исходной
  runtime-линией. Локальные копии и их хеши дополнительно проверены в
  `android35-native-backup-archive-index.json`.

Оба имени без `.zip`/`.bin`, оба terminal success, файлы оставлены в выбранном
Downloads. После backup обе доски фактически наблюдались; финальный список
черновиков/сообщение через UI не засчитаны из-за последующего hierarchy timeout.
Это закрывает стандартные имена/запись, **не** custom-name input, причину прежних
таймаутов, restore/recovery, реальный upgrade, physical/AAC или весь release.
