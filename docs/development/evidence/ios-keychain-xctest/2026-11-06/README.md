# Реальный iOS Keychain app-host compartment

**2/2 actual XCTest PASS**, owned `linka_platform_probe_ios17`, iOS17.0 arm64.
Ни skip, ни expected failure. Это не product UI / physical / полный G4 PASS.

Путь: настоящий UIKit app-host → hosted XCTest bundle → статический Kotlin
`SharedAppHostTests.framework` → существующие KeychainCredentialStoreTest.
Host не запускает product root/library/audio. Framework линкуется только в test
bundle. Каждая проверка использует новый UUID service и удаляет только его.

Что проверено отдельно:

- Конструктор Framework получает appHostTest compilation сразу. Прежнее позднее
  присваивание замораживало main link inputs и не экспортировало bridge.
- Проверены Swift ABI двух методов и отсутствие unlinked-symbol warnings.
- Valid Apple Development identity появилась после ручных действий пользователя;
  private key не экспортировался, trust/ACL/системные permissions агентом не менялись.
- Принудительная настоящая Development-подпись прошла strict verification, но ОС
  отказала **до тестов**: AMFI−413 / no matching profile. Это сохранённый RED, не
  ошибочный результат самих двух assertions. Provisioning не получался.
- Пользователь после объяснения явно разрешил **штатную Xcode Simulator-подпись
  нового app-hosted XCTest**, не повтор прежней CLI wrapper. Новый отдельный own
  bundle ID/group, без снятия entitlements и обхода AMFI/SIP/ACL.
- До запуска проверены обе strict signatures и фактический Mach-O Simulator
  entitlement payload: ровно TEAM.ownBundleID group, не wildcard/production group.
  После запуска installed host/bundle SHA совпали с проверенными продуктами.
- `.xcresult` summary и отдельные имена тестов подтверждают оба реальных PASS.

`manifest.json`, redacted launch failure, test log и xcresult summary рядом.
Полный `.xcresult` сохранён локально по пути manifest; в Git не добавлен.
