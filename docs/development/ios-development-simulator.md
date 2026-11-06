# Изолированный product UI на Simulator

6 ноября пользователь отдельно разрешил штатную Xcode Simulator-подпись
development-приложения после **2/2 actual Keychain app-host XCTest PASS**.
Это расширение только на development UI, не production/profile/store/device signing.

## Границы до запуска

- Bundle остаётся `su.linka.pictures.development`; install обновляет его без
  удаления sandbox. Production bundle/данные не открываются и не сбрасываются.
- `DevelopmentSimulator.entitlements` подключён только для Simulator SDK.
  Team подставляется локально из разрешённого публичного сертификата, не из Git.
  Ровно одна группа: `TEAM.su.linka.pictures.development`; нет общей группы с
  Keychain test-host, production или wildcard. Ни shared Kotlin API, ни выбор
  service/credential format, ни SQL schema этим не меняются.
- Единственный writer прежний IosProductRoot → admitRootCredentials →
  LocalAssistantAccess и StorageRuntime. Приложение не создаёт второй writer.
  Пути Application Support/Caches остаются внутри собственного app sandbox.

| Persisted состояние | Поведение |
|---|---|
| Store пуст, library root ещё не существует | Прежняя атомарная подготовка unprotected credential record, затем открытие библиотеки |
| Credential существует | Прежняя validation, без сброса/перезаписи ради UI |
| Library существует, credential отсутствует | INTEGRITY_FAILURE, не fresh install и не permission обход |
| Keychain read/guard/IO failed или неизвестен commit | Прежний fail-closed, без fallback/авторетрай/другой группы |

До install обязательны strict signature, min17, правильный development bundle и
проверка фактического Mach-O Simulator entitlement payload. После запуска нужен
реальный живой process и кадр «Ваши наборы», а не SpringBoard/ошибка. Только
синтетические fixtures и явные UI-команды; импорт/разрешения не подставляются
прямыми SQL/API-записями. Ошибка ОС останавливает этот путь.

Rollback — предыдущая development сборка без очистки данных; переключение группы
не лечится reset. Physical/native owner verification/audio/AAC и полный G4/UI
approval остаются отдельными gates.

## Actual startup

Обе SDK build settings проверены: min17, default signing=NO; entitlement source
подключён только для Simulator. Development Simulator app собрана штатным Xcode,
до install проверены strict signature, bundle/min17 и фактическая Mach-O секция:
ровно `TEAM.su.linka.pictures.development`, без группы Keychain XCTest host.
Executable SHA256:
`f4acf190d3f7deaa536aebbb09bf7652ca886f5451c9cee3b2c639f3d062a7ad`.

Install обновил тот же development bundle без удаления данных. После launch процесс
проверен как живой; [реальный кадр](../progress-screenshots/2026-11-06-ios-development/startup-library.png)
просмотрен: библиотека «Ваши наборы», пустое состояние и FAB. Прежняя Keychain−34018
больше не блокирует startup. Это первый product UI startup, не filled-fixture
matching и не пользовательское одобрение всего iOS UI.
