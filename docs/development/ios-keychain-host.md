# P08: Keychain binding и обязательный app-host gate

## Актуальное продолжение — 6 ноября

### Уточнённая граница Simulator после фактического запуска

Framework/link seam исправлен: binary сразу создаётся с appHostTest compilation,
поэтому Native link не замораживает main inputs. Проверены оба exported bridge
метода, Swift ABI и отсутствие unlinked-symbol warnings; добавлен отдельный
`:shared:verifyAppHostTestFramework`.

Xcode Simulator штатно выбрал ad-hoc, несмотря на переданную Development identity.
Явное `AD_HOC_CODE_SIGNING_ALLOWED=NO` отвергнуто SDK. Подписанные штатным codesign
с настоящей valid Development identity host/bundle прошли strict signature и exact
own-group entitlement checks, но actual XCTest runner не запущен: host taskgated /
AMFI **−413, No matching profile found**. Это **0 actual PASS**, не провал двух
Keychain assertions. Profiles не получались, настройки безопасности не менялись.

После сообщения этих фактов пользователь явно выбрал **«Штатный XCTest»**:
разрешена Xcode-managed Simulator-подпись **только нового isolated UIKit app-host
с app-hosted XCTest**. Прежний запрет повторять CLI/ad-hoc AMFI−424 wrapper,
снимать entitlements, отключать AMFI/SIP/ACL или получать profiles автоматически
остаётся. Перед следующим запуском проверяются signature и штатный Simulator
`__entitlements` payload с одной exact own Keychain group. При отказе ОС — STOP.
Результат Simulator не подменяет physical / production signing / P08/G4 gates.

**Итог этого разрешённого пути: 2/2 настоящих XCTest PASS на iOS17.0**, без skip
или expected failure. Runtime подтвердил bytes/reopen/update/revoked guard и
size/service validation. До install проверены strict signatures и фактический
Mach-O Simulator entitlement payload; после тестов installed executable hashes
совпали с проверенной сборкой. Product library/основная credential service не
открывались. [Команды/границы и evidence](ios-keychain-xctest-host.md).

После ручной настройки пользователем создана Apple Development identity. Read-only
проверка сначала обнаружила MissingIntermediate; с официальным Apple WWDR G3
(`dcf21878c77f4198e4b4614f03d696d89c66c66008d4244e1b99161aac91601f`)
проверка цепочки прошла без импорта/изменения trust агентом. Пользователь добавил
intermediate вручную со стандартным trust; теперь найдено **1 valid identity**.
Ни private key, ни пароли/токены не экспортировались. Прежние BLOCKED записи ниже
остаются историческим evidence, не разрешением ad-hoc retry.

Принятая граница нового host: отдельный UIKit Xcode application + **app-hosted
XCTest bundle**, не CLI kexe в самодельной app. Kotlin appHostTest compilation
экспортируется в static `SharedAppHostTests.framework`, загружаемый только XCTest
bundle (host не линкует второй Kotlin runtime). Два Swift теста вызывают именно
существующие Native Keychain tests через bounded result facade, без fake/skip.

Владельцы/writers: подписанный development host имеет только собственную
`TEAMID.bundleID` Keychain group; два теста создают service с новым UUID и удаляют
только его. Normal product root/library/credential record не инициализируются.
Секреты не входят в argv/JSON/Git; Team/identity выбираются только локально при
разрешённой подписи. Gradle/host config не содержит персональный Team ID.
Schema/миграция/release signing не меняются. Нет profile fetch, снятия entitlements,
AMFI/SIP/ACL обхода или автоматического подтверждения системных запросов.

Первоначальная матрица (Simulator signing уточнён явным решением выше): нет valid identity/нужен prompt → BLOCKED; local signature
проверяется как non-ad-hoc + exact own entitlements до install; выполняются ровно
2 настоящих XCTest cases на explicit owned iOS17; native Keychain error не считается
пустым store/успехом. Только фактические2 PASS закрывают этот compartment, не весь
P08/G4, LAContext, migration или release/physical gates.

5 ноября 2026. **Runtime BLOCKED; не G4 PASS.** `KeychainCredentialStore` скомпилирован для обеих ARM Native-целей, но реальное чтение/запись ещё не подтверждены. Normal app/root не подключены.

Позднее пользователь явно разрешил проверить/use **существующую Apple Development identity только для собственного isolated legitimate test-host/Keychain group**. Read-only `security find-identity -v -p codesigning` дал **0 valid identities**. Это разрешение не распространяется на создание сертификатов/аккаунтов, profiles fetch, production signing или store upload; они не выполнялись. Отсутствующий identity остаётся prerequisite, а не повод повторять ad-hoc AMFI путь. [Текущие native mechanics](native-owner-verification.md).

## Реализация

Один generic-password record, default service `su.linka.pictures.assistant.v1`, account `credential`. Data Protection Keychain, synchronizable=false, WhenUnlockedThisDeviceOnly, authentication UI запрещён для store operations (fresh-owner verification — другой модуль). Размер1..8192 bytes, CFData передаёт byte length, никаких C-string passwords. CF dictionaries/data/result освобождаются на всех путях. Синхронный последний guard непосредственно перед Add/Update, без delete/add fallback или слепого retry. Known success → Completed, native error/исключение после возможного mutation сверяет exact old/new независимым read; нет доказательства → UnconfirmedCommit. Read failure не становится отсутствующим record.

## Воспроизведённый отказ и evidence

Первый focused запуск двух настоящих Native Keychain tests на owned iOS17.0/21A328:

```sh
xcrun simctl spawn DD0F9A3F-DC32-4373-BA08-400FECE3A1CC \
  "$PWD/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe" \
  '--ktest_filter=su.linka.pictures.access.KeychainCredentialStoreTest.*'
```

**0/2**, initial read: `IO_FAILURE/systemCode=-34018` (`errSecMissingEntitlement`). Тестовый CLI не имеет app identity; ошибка не игнорировалась и защита Data Protection не отключалась.

Пользователь отдельно разрешил ad-hoc isolated test-host с только собственной Keychain group. Копия binary в отдельном `.app` получила только `application-identifier=<random own bundleID>` и `keychain-access-groups=[sameID]`. Exact signed entitlements проверены; original SHA `532a85dc67bbdfb112eaad2eeb248e8fbdd08a6994fc96665e64c1a542399900` не менялся. Signing production identity, доступ к существующим записям и новые права macOS не запрашивались.

Даже после установки на owned simulator spawn/обычный app launch не дошли до тестов. Host AMFI подтвердил **−424: “The file is adhoc signed but contains restricted entitlements”**, затем launchd153. Это не ошибка password algorithm и не основание убрать entitlement, отключить AMFI/SIP или использовать привилегированный чужой runner. Дальнейшие ad-hoc попытки прекращены. Нужен штатно подписанный Xcode test-host с подходящей app identity; доступ к signing credentials/profiles требует отдельного разрешения.

Полные логи в approved temp: `p08-keychain-ios17-focused.log`, `p08-keychain-diagnostic-ios17.log`, `p08-keychain-host-launch-diagnostic.log`, `p08-keychain-installed-launch.log`. Один собственный test-host остался установленным: `su.linka.pictures.nativetests.c18402deff3c4756984399b0debe07fe`. Он не запустился; normal apps/их данные не менялись. Остальные `.app` копии/entitlement manifests — только retained diagnostics в approved temp. Неудачный runner script удалён, не включён в CI и не объявлен рабочим инструментом.

## Отдельный, не отключённый test compartment

Keychain-тестам нужен app host, pure storage/Unicode/session tests — нет. Поэтому два теста находятся в **`shared/src/iosAppHostTest/kotlin/.../KeychainCredentialStoreTest.kt`**, отдельная Gradle compilation `appHostTest` associated с main. Они проверяют actual byte round-trip/reopen/update, отказ guard без потери прежнего record, size/native-text boundaries и cleanup только уникального test service. Код не заменён fake/skip/assumption.

```sh
./gradlew :shared:linkAppHostDebugTestIosSimulatorArm64
```

Это **compile/link check**, не app-host execution. Required runtime gate P08/G4 остаётся BLOCKED до легитимного signed host и выполнения всех2 tests. Обычный `linkDebugTestIosSimulatorArm64` и core suite не включают этот OS-bound compartment, не могут закрыть его и не должны называться полной security/native приёмкой. P14/CI обязаны учитывать оба compartments отдельно, а не только зелёный core total.
