# Единый runtime: объединение Android и общего ядра

8 ноября 2026. Это журнал локальной реализации и проверок, не разрешение на выпуск.
Production signing, установка на пользовательскую библиотеку и store publication
не выполнялись. Физические устройства и устойчивость при потере питания не доказаны.

## Принятые решения

Один Android launcher и один product runtime для debug/release. Legacy UI/writers
удаляются; остаётся только read-only перенос старых архивов/preferences. Analytics
удаляется полностью. RU/EN, семь встроенных Android-примеров и существующая
`.development`-библиотека сохраняются. Никакого скрытого legacy fallback.

## Хранение и совместимость

`AndroidLibraryAdmission` допускает существующую development SQLite на прежнем
месте либо связывает `LegacyUpgrade` с реальными native files, media inspection,
`LinkaCodec.readLegacyUpgrade` и `LegacyCredentialImporter/FileCredentialStore`.
При действующем `active` legacy preferences даже не открываются. Credential record
обязателен, в том числе при выключенной защите.

SQLite остаётся schema1. Admission schema2 добавляет явный `sourceKind` для
LEGACY_FILES/BUNDLED. Schema1 active читается без переписывания; schema1 journal
продолжается в том же поколении/с теми же BoardIds. Неизвестная схема отклоняется.
Новая работа/credentials не обновляются из legacy при следующем запуске.

Ранний INVENTORY может продолжить создание отсутствующей БД только в доказанно
своём отсутствующем/пустом поколении с совпавшим источником. Неизвестные файлы
не удаляются. Interrupted BUNDLED не игнорирует появившиеся legacy-данные.

Семь bundled-архивов извлечены из исходных Git blobs без изменения байтов.
Уникальные ASCII filenames убирают конфликт `Цифры`/`цифры` на case-insensitive
checkout; отображаемые имена и разные IDs сохраняются. На существующей библиотеке
seed не запускается. Одинаковые байты архивов не склеивают доски.

Process startup переживает пересоздание Activity и учитывает все started-окна.
Остановка одного окна при другом видимом окне не отменяет общую миграцию.
Настоящий уход в фон до получения runtime отменяет подготовку; resume явный.
Неуспешное закрытие writer требует перезапуска, без второго writer.

## Выполненные focused-проверки

- Ранний journal regression сначала RED, после исправления GREEN.
- JVM migration/access, затем schema1 journal/active compatibility и startup
  multi-window/configuration regressions прошли focused-проверки.
- Независимое read-only ревью миграции: принято. Android review выявил открытие
  preferences при active и отмену startup одним из двух окон; оба исправлены.
- Отдельный агент реализовал общий RU/EN UI; независимое ревью выявило русское
  default-имя новой доски в EN. Оба UI entry points теперь передают локализованное
  имя, пользовательские названия не переводятся. Resource parity/placeholder check PASS.
- Android debug/release unit, release-safety gate и app/shared test APK compilation
  прошли focused-проверку. До скачивания нового штатного CMP Android artifact
  offline build был BLOCKED отсутствием cache; это не подавлялось.
- API21: четыре реальных admission-теста прошли на отдельном shared test package:
  flat1.3 + настоящий legacy-password KDF/store, independent active reopen без prefs,
  существующие dev IDs/message/credentials, seed/delete-no-reseed, missing credential.
  Повтор suite превысил MCP deadline; native TestRunner подтвердил 4 tests/0 failed.
  Усиленный отдельный bundled-тест затем PASS: imported=7, retained=0, все BOARD.
- API21: три настоящих force-stop/reopen сценария PASS после первого journal,
  публикации credential.json и публикации active. Только test package; библиотека
  `.development` и production package не затрагивались. Это process-death proof,
  не power-loss/directory-fsync proof.
- Offline workflow selfcheck: 384 сочетания event/ref/inputs/results PASS.

## Итоговый технический срез

- Shared JVM: **528/528**, failures/errors/skips=0.
- Android debug-unit и release-unit: **26/26 каждый**, failures/errors/skips=0.
  Шаблонный `2+2` тест удалён; проверку namespace заменяют реальные manifest и
  launcher/alias проверки, а не старый неверный production package assertion.
- App/shared lint: **0 errors,7 app/5 shared warnings**. Остались предупреждения toolchain, намеренного
  landscape, debug icon duplicates и существующих native API/KTX рекомендаций;
  подавлений/baseline ради PASS не добавлялось.
- App/shared test APK, debug APK и `verifyReleaseSafety`: PASS.
- Оба iOS frameworks, Native test link и app-host framework verification: PASS.
  Первоначальный aggregate был RED на дублировании ресурсов: CMP1.9.3 конфигурировал
  одну Copy-задачу для двух test executables. Отдельный исправляющий commit сравнивает
  байты дубликатов, отвергает конфликтующие данные и доставляет ресурсы в оба tracked
  output. Самостоятельный regression script прошёл и в основном checkout.
- Actual Native core на явно выбранном owned iOS17: **652/652 PASS,75173ms**.
  Лог: `T/opencode/unified-core-native-ios17.log`. App-host security tests в этот
  total не включены, исполнение Keychain/OS prompts этим не заявляется.
- Unsigned generic Simulator **и device** iOS app builds: PASS. В Simulator `.app`
  сверены все шесть CMP resource-файлов, RU/EN InfoPlist strings, development identity,
  minimum17 и landscape. Это packaging/build proof, не установка на physical device.
- API21 существующее `.development` обновлено через install `-r`, без uninstall,
  wipe или reset. Прежние наборы видны на before/after PNG, интерфейс следует EN
  системной локали. Финальные launcher+content-MIME alias tests: **2/2 PASS,1.186s**.
- Отдельный native regression на появление legacy-работы между interrupted bundled
  seed и resume: PASS. Такая работа блокирует активацию, исходные байты сохраняются.
- Удалена разовая cache-dependent reflection-проба внутреннего inspector из app
  tests. Реальный import/fixture SHA-контракт сохранён; типизированный shared native
  inspector corpus на API21: **9/9 PASS,0.339s**. Это общая проверка media, не
  побайтовая замена отдельного диагностического отчёта по repaired sounds-архиву.
- RU/EN native-test selectors: 50 mappings PASS; пользовательский текст и разные
  исходы не нормализуются в одинаковый результат. iOS Russian-сценарии получают
  process-local locale; добавлен read-only EN→RU smoke. Их unsigned build-for-testing
  прошёл, новый native UI smoke пока не исполнялся.
- Все существенные implementation/test changes прошли отдельное read-only ревью
  другого агента; найденные ошибки исправлены. Дополнительная @Volatile публикация
  startup ownership markers обеспечивает IO→Main видимость, после неё Android
  проверки повторены.

API35 остаётся **environment BLOCKED**: package service отсутствовал, разрешённый
reboot временно вернул его, установка отдельного shared test APK превысила deadline,
после чего package service снова исчез. Timeout не считается install success или
продуктовым failure. Повторных install/reboot/clear-data попыток ради PASS не было.
В рамках этой работы production package на этом эмуляторе не устанавливался и не открывался.

Отчёты относятся к проверенным source/artifact-срезам, а не к сумме исторических
результатов. После общего gate повторялись только затронутые исправлениями проверки.

## Воспроизведение native migration

На явно выбранном тестовом эмуляторе после сборки/установки shared test APK:

```sh
adb -s SERIAL shell am instrument -w -r \
  -e class su.linka.pictures.bootstrap.AndroidLibraryAdmissionTest \
  su.linka.pictures.shared.test/su.linka.pictures.bootstrap.LibraryTestRunner
```

Реальное завершение процесса запускается отдельно от обычного JUnit suite:

```sh
adb -s SERIAL shell am instrument -r -e migrationAction pause -e token UNIQUE_TOKEN \
    -e checkpoint journal \
  su.linka.pictures.shared.test/su.linka.pictures.bootstrap.LibraryTestRunner
```

Host ждёт точного содержимого `journal` в принадлежащем тесту
`files/process-migration-UNIQUE_TOKEN/ready` с ограниченным deadline, затем
force-stop **только** `su.linka.pictures.shared.test` и запускает тот же runner
с `migrationAction resume`, прежним token/checkpoint и `-w`. Повторяется для
`credential.json` и `active`. Runner проверяет единственную опубликованную доску,
неизменные исходные байты и отдельный credential record; после PASS удаляет только
собственный fixture. Некорректные tokens/повторное создание fixture отклоняются.

В OpenCode используются соответствующие ADB MCP tools; команды выше описывают
штатный Android-протокол. Никаких sleeps, wipe, скрытых прав или обходов SDK.

## Открытые внешние gates

Подпись/настоящий upgrade с прежней release identity; Play versionCode/target API/
16KB/ARMv7; physical audio/AAC; App Store/privacy/legal/FFmpeg shipping; power-loss
и directory durability. Зелёный локальный код не закрывает эти gates.
