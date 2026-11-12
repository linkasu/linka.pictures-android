# Обновление полосы сообщения: Android2.0.1(7), iOS2.0.0(2)

## Контракт

- Общая `PortableSettings.messageRowPercent`:16/20/24/28/32, default20.
- Старые настройки без поля получают20. Явное неправильное значение отклоняется.
- Выбор в настройках меняет черновик; запись только общим Save с существующим
  CHANGE_SETTINGS, revision/epoch и подтверждением после авторизации.
- Процент — target всей полосы сообщения вместе с кнопками. Безопасный минимум
  и целые карточки важнее точного процента. Фактически подобранная высота не
  перезаписывает выбор пользователя; неподходящее сочетание отклоняется явно.
- Центрируется только сетка, включая остаток после pixel rounding. Картинки,
  подписи, порядок и размерность наборов не менялись.

## Форматы и откат

Storage JSON остаётся schema1; SQL-миграции нет. Новый reader принимает старые
данные, но старый строгий reader не понимает новое поле. Downgrade после записи
новых настроек не поддерживается: не очищать библиотеку для такого отката.
Исправление rollback выпускается новым бинарником, понимающим поле.

Backup writer —1.2; strict readers —1.0/1.1/1.2. Frozen1.1 DTO сохраняет прежние
navigation/drag/organization и добавляет20 при конвертации. Неправильные настройки
отклоняются до создания snapshot builder. Обычные `.linka` не меняются.

## Проверки до CI

- Целевой JVM gate после RED-компиляции новых layout tests прошёл.
- Полный локальный gate:600 JVM,26+26 app Android unit; failures/errors/skipped0.
  App/shared lint, release safety/selectors, compile Android instrumentation PASS.
- RU/EN localization PASS после удаления заменённой неиспользуемой строки ошибки.
- CI/upload скрипты имеют offline mocked tests. Это не подтверждение принятия
  артефактов магазинами или прохождения native E2E.
- Добавлены реальные Android instrumentation и iOS XCUI:20→28 Save/relaunch,
  «Еда и питьё», загрузка изображений, bounds и центрирование сетки; вертикальный
  центр дополнительно проверяется на многотраничном «Общаюсь».

## Публикация только через CI

Пользователь разрешил API-ключи/signing secrets, выпуск и публикацию; отдельно
потребовал убрать Firebase и выполнять E2E в CI. Новых платных Firebase запусков нет.

- Android CI:build→owned API35 emulator E2E→manual/main signed AAB.
  Runtime4KB, не16KB proof. Подпись не обходится при failed/skipped E2E.
- iOS CI:owned phone/tablet Simulator, Native suite и два продуктовых XCUI cases.
  Hosted runner/Xcode фактически фиксируются в evidence; физического устройства нет.
- Publish workflows требуют точный успешный main SHA и конкретный source run.
  Нет manual file upload релизных AAB/IPA с локального Mac.
- Google Play commit отправляет production release на review, не означает публичный rollout.
- RuStore принимает только Google-generated universal APK с правильной цепочкой,
  не APK с upload-ключом. Текущая ожидающая review версия6 — конфликт, который
  нельзя автоматически отменять для новой версии7.
- iOS IPA upload означает processing, не App Review; отдельная CI-подача должна
  подтвердить Waiting for Review. Франция/Mac/Vision остаются выключенными.

Secrets не находятся в репозитории. Существующие сертификаты/ключи не отзывались.
App Store key имеет App Manager, RuStore key ограничен одним приложением и методами
публикации. Private key values не должны появляться в stdout, logs или artifacts.

## Известные пределы

Физическое audio/биометрия и физический Keychain restore не подтверждены.
Полная историческая матрица238 model/runtime pairs не объявляется пройденной.
Точный Simulator27.0.1 недоступен у Apple. Исходный launch crash27.x связан с
UIScene; его исправление и regression входят в кандидат, старые RED доказательства
сохранены отдельно и не подменяются новой сборкой.
