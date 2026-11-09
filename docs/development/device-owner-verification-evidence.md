# P08: evidence для fresh device-owner verification

Дата исследования: 2026-11-05. Только публичные первичные источники: Android SDK documentation, исходники AOSP конкретных Lollipop tags, AndroidX source, Apple documentation и Apple sample. Это evidence report, **не security/product decision и не реализация**. Минимумы Android 21 / iOS 17 неизменны.

## Краткий ответ primary

- **Android 21/22: схема `isKeyguardSecure() → non-null createConfirmDeviceCredentialIntent() → RESULT_OK` НЕ доказывает fresh device-credential authentication во всех допустимых состояниях.** В AOSP 5.0/5.1 подтверждён полный путь `locked SIM + no device credential → non-null Intent → RESULT_OK` без ввода device credential. Это подтверждение по исходникам, не выполненный device exploit/test. Нельзя переносить исправляющую предпосылку `isDeviceSecure()` с API 23 на API 21.
- Public API для system credential challenge существует с API 21 и при реально настроенном credential ведёт к проверке PIN/password/pattern. Однако **public prerequisite API 21, достоверно подтверждающий именно настроенный device credential без SIM false positive, в исследованных контрактах не установлен**. Универсальная реализация требуемой гарантии для 21/22 данным рецептом несовместима с evidence; общий тезис «никакое другое public API никогда не поможет» этим исследованием не доказан. Дальнейшее решение принадлежит parent.
- **iOS 17: public `LAContext` + `.deviceOwnerAuthentication` поддерживают реальную OS authentication**, с новым context на попытку и без включения Touch ID reuse. Но `canEvaluatePolicy` — не proof; success зависит от passcode, biometrics, конфигурации и доступности UI. Face ID требует app-local usage description и пользовательского разрешения на Face ID; обещать одновременно Face ID и «никаких новых privacy authorizations» без проверки текущей конфигурации нельзя. Не нужны hidden API, изменение passcode или global settings для самого вызова API. [I1–I7]
- Fresh OS evaluation не равна авторизации бизнес-операции: ни Android result, ни Apple callback не кодируют app `ownerId`, актуальность `requestId`, foreground или отсутствие предшествующей отмены. Lifecycle acceptance — отдельная обязанность приложения; этот report не выбирает её реализацию.

## Статусы доказательств

**CONFIRMED** — непосредственно прочитанный контракт/исходник. **HYPOTHESIS** — вывод, который источник не подтверждает непосредственно. **UNVERIFIED** — не исследовано или не выполнено runtime-подтверждение. Source-confirmed путь не означает, что он воспроизведён на каждом OEM.

## Android: SDK availability и пределы контрактов

| Public API | Точная availability / deprecation | Что действительно означает |
|---|---|---|
| `createConfirmDeviceCredentialIntent(title, description)` | API 21; deprecated API 29 | Создаёт запрос; SDK ожидает `RESULT_OK` после challenge, `null`, когда password не требуется. Современный текст включает biometrics; в исследованном Lollipop source путь именно PIN/password/pattern. Создание Intent не аутентификация. [A1, A2] |
| `isKeyguardSecure()` | API 16; без deprecation в текущем reference | Secure lock screen **или locked SIM**. Не проверяет, показывается ли сейчас lock screen. [A1] |
| `isDeviceSecure()` | API 23; без deprecation в текущем reference | PIN/pattern/password, без locked SIM. На 21/22 отсутствует. [A1] |
| `isDeviceLocked()` | API 22; без deprecation в текущем reference | Текущее locked state, а не наличие настроенного credential. Даже на 22 unlocked устройство может иметь credential. Не замена `isDeviceSecure`. [A1] |
| Framework `startActivityForResult` / `onActivityResult` | API 1; deprecated API 30 | Современная рекомендация — AndroidX Activity Result; замена транспорта не исправляет Lollipop Settings semantics. [A9, A10] |

В AOSP Lollipop `ConfirmDeviceCredentialActivity` экспортирована с action `android.app.action.CONFIRM_DEVICE_CREDENTIAL`, без activity-specific permission; framework Intent ограничен package `com.android.settings`. Это штатный public call path без новой permission, но не достаточная гарантия proof. [A2, A8]

### Полная цепочка SIM-only: CONFIRMED в обеих версиях

Предпосылка здесь точная: **SIM сейчас находится в PIN_REQUIRED / PUK_REQUIRED / PERM_DISABLED**, а device PIN/password/pattern отсутствует. Это не просто включённая когда-то SIM PIN protection после уже успешного SIM unlock. [A5]

1. `KeyguardManager.createConfirmDeviceCredentialIntent`: единственный guard `if (!isKeyguardSecure()) return null;`, затем создаётся Intent и `setPackage("com.android.settings")`. Строки 66–73 в 5.0; 71–78 в 5.1. [A2]
2. `KeyguardManager.isKeyguardSecure()` вызывает `IWindowManager.isKeyguardSecure()`; `WindowManagerService` → `PhoneWindowManager.isKeyguardSecure()` → `KeyguardServiceDelegate.isSecure()`. [A2, A3]
3. **Android 5.0:** delegate → `KeyguardServiceWrapper.isSecure()` → binder `IKeyguardService.isSecure()` → SystemUI `KeyguardService` → `KeyguardViewMediator.isSecure()` → `mLockPatternUtils.isSecure() || KeyguardUpdateMonitor.getInstance(mContext).isSimPinSecure()`. Поэтому SIM alone достаточна. [A3, A4, A5]
4. **Android 5.1:** wrapper больше не делает тот же synchronous `isSecure` binder call: `KeyguardServiceWrapper.isSecure()` → `KeyguardStateMonitor.isSecure()` → `mLockPatternUtils.isSecure() || mSimSecure`. `mSimSecure` приходит через binder `IKeyguardStateCallback.onSimSecureStateChanged`; mediator сообщает начальное значение в `addStateMonitorCallback` и обновления SIM state. Таким образом SIM false positive сохранён, хотя транспорт отличается. [A3, A4, A5]
5. Settings `ConfirmDeviceCredentialActivity.onCreate` вызывает `ChooseLockSettingsHelper.launchConfirmationActivity(0, title, details)`. Helper смотрит **stored device password quality**, запускает pattern/password activity лишь для соответствующих modes; при unspecified/no credential возвращает `false`. Pattern дополнительно требует enabled + saved pattern, password — enabled password. [A6, A7]
6. **Обе версии**, `ConfirmDeviceCredentialActivity` строки 50–55: если helper вернул `false`, логирует `No pattern, password or PIN set.`, выполняет **`setResult(Activity.RESULT_OK); finish();`**. Ни password UI, ни credential check в этой ветке нет. [A6]
7. Следовательно, возможны `isKeyguardSecure == true`, non-null package-bound Intent и OS-delivered `RESULT_OK`, при этом **никакой fresh device-credential reauthentication не было**. Не нужно предполагать поддельное приложение или произвольную подмену result. Это логическое следствие непосредственно прочитанных веток.

Дополнительная граница: `ConfirmLockPattern` в обеих версиях строки 147–154 тоже возвращает `RESULT_OK`, если сохранённого pattern больше нет. Поэтому даже доказанное ранее наличие pattern не является атомарной гарантией его сохранности до результата. [A7]

### Когда в Lollipop действительно проверяется credential

`ConfirmLockPassword.handleNext` вызывает `mLockPatternUtils.checkPassword(pin)` и только успешная ветка ставит `RESULT_OK`; wrong credential показывает ошибку/lockout, не является успешной проверкой. `ConfirmLockPattern.onPatternDetected` аналогично вызывает `checkPattern(pattern)`. Нет вывода «устройство уже unlocked, поэтому автоматически authenticated» в этих нормальных ветках. Public caller получает результат wrapper activity, **без различения normal verified branch и no-credential success branch**. Password extras создаются только для internal activities, не являются public proof payload. [A6, A7]

Это локальный OS result, не подписанная аттестация, не timestamp последнего ввода и не доказательство конкретного юридического владельца. В частности, знание device credential позволяет пройти проверку независимо от app owner identity.

### Какие public prerequisites API 21 проверены

| Кандидат | CONFIRMED ограничение |
|---|---|
| `isKeyguardSecure` + non-null Intent | Подтверждённый выше SIM false positive. |
| `isKeyguardLocked` / restricted-input state | Показывается ли keyguard, не наличие PIN/password/pattern. [A1] |
| `isDeviceLocked` | Только с 22; state, не configured-credential predicate. [A1] |
| `isDeviceSecure` | Нужная документированная семантика, но только с 23. [A1] |
| `DevicePolicyManager.getPasswordQuality(null)` | Возвращает **минимальное требование политики admin(s)**, не фактическую configured credential quality; API 8, deprecated 31. [A11] |
| `DevicePolicyManager.isActivePasswordSufficient` | Соответствие admin policy, требует active device admin + `USES_POLICY_LIMIT_PASSWORD`; не разрешённый permission-free predicate для обычного приложения. API 8. [A11] |
| `Settings.Secure.LOCK_PATTERN_ENABLED` | API 8, deprecated 23; pattern-related setting, не общий PIN/password/pattern predicate. Текущий reference дополнительно предупреждает о `SecurityException` для target M+. Не подтверждает все виды device credential. [A12] |
| `LockPatternUtils.isSecure()` / stored password quality | Внутренний framework class, не public SDK API. Его использование через reflection не является допустимым ответом. [A5, A7] |

**UNVERIFIED:** иной permission-free public API 21/22, который даёт требуемое надёжное положительное подтверждение configured device credential, здесь не установлен. Никакие SIM/тайминговые/UI-эвристики, hidden reflection, повышение minSdk или отключение legacy lock не предлагаются. Публичный `FEATURE_SECURE_LOCK_SCREEN` в современном reference описывает возможность устройства, а не то, что пользователь настроил credential; переносить новые feature/API semantics на 21 нельзя. [A1]

## Android: result delivery, freshness и lifecycle

- **CONFIRMED, framework Lollipop:** Javadoc `Activity.onActivityResult` прямо говорит, что при restart callback приходит **непосредственно перед `onResume()`**. `RESULT_CANCELED` означает explicit cancel, отсутствие возвращённого result **или crash**, а не обязательно сознательный отказ пользователя. Settings wrapper нормализует любой не-OK дочерний результат в CANCELED. [A6, A9]
- **CONFIRMED, AndroidX:** lifecycle-aware `ActivityResultRegistry.register` активирует callback и доставляет pending results на **`ON_START`**, снимает callback на `ON_STOP`, unregister на `ON_DESTROY`; pending results и launched keys сохраняются/восстанавливаются. Поэтому callback не обещает `RESUMED`, а transport может пережить recreation. Исследован upstream `androidx-main`, не установленная версия зависимостей проекта. [A10]
- **CONFIRMED:** официальная инструкция Activity Result требует регистрировать callback при каждом создании и предупреждает о смерти процесса/activity между launch и callback; дополнительное состояние сохраняется отдельно. Возможность получить восстановленный result — свойство транспорта, **не разрешение восстанавливать owner proof/session**. [A10]
- **CONFIRMED:** открытие другой activity вызывает lifecycle transitions; при полном закрытии исходной activity — `onStop`, при частичном перекрытии возможен только `onPause`. Credential activity принадлежит Settings, поэтому собственный разрешённый системный prompt тоже может убрать app activity из foreground. Один `onPause`/`onStop` не сообщает причину ухода. [A6, A8, A13]
- **Предел гарантии:** result API не сообщает историю «пользователь нажал Home/перешёл в другую app внутри credential flow». Официальный контракт не даёт отдельного public события, которое надёжно различает такое действие и обычное ожидание собственной Settings activity. Сценарий prompt → Home → возврат → OK не проверен runtime; **не утверждается**, что один pending-prompt flag способен безопасно его различить.
- **Следствие для существующей policy, не новое решение:** требование `access-policy.md` принимать terminal proof только после foreground и при живом request нельзя заменить проверкой lifecycle прямо внутри callback. Callback может прийти раньше resume; late OK может существовать уже после app cancellation/revoke. OS OK не отменяет локальную отмену и не восстанавливает request. Механизм согласования с foreground и реальным уходом пользователя требует отдельного native design/runtime evidence.

**Что здесь OS proof:** на Android это результат именно запущенного системного challenge, **лишь при установленной гарантии, что произошла реальная credential verification**. На 21/22 один OK такую гарантию не даёт, как доказано выше. `null`, exception, CANCELED и wrong attempts не proof; launch и availability тоже не proof. Точное app mapping failure/cancel/unavailable этот report не выбирает.

## iOS 17: public LocalAuthentication facts

### Availability / deprecation

| API | iOS introduced | Deprecation в прочитанном Apple symbol metadata |
|---|---|---|
| `LAContext` | 8.0 | Не указана |
| `canEvaluatePolicy(_:error:)` | 8.0 | Не указана |
| `evaluatePolicy(_:localizedReason:reply:)` | 8.0 | Не указана |
| `LAPolicy.deviceOwnerAuthentication` | **9.0** | Не указана |
| `invalidate()` | 9.0 | Не указана |
| `touchIDAuthenticationAllowableReuseDuration` | 9.0 | Не указана |
| `NSFaceIDUsageDescription` | 11.0 | Не указана |

Это availability **самих символов**, не минимальная версия современного downloadable sample. Все доступны на фиксированном iOS 17. Источник availability — Apple symbol documentation JSON; соответствующие человекочитаемые ссылки ниже. [I1–I7]

### Проверка, cache и условия настоящей аутентификации: CONFIRMED

1. `.deviceOwnerAuthentication` в iOS разрешает biometry **или device passcode**. Если biometry доступна, enrolled и не disabled, OS использует её первой; иначе passcode fallback. Без enabled passcode evaluation завершается `passcodeNotSet`. Для этой policy отсутствие enrolled biometrics само по себе не означает отсутствие owner authentication. [I1]
2. `canEvaluatePolicy` проверяет prerequisites, не пользователя. Apple запрещает сохранять его значение как неизменное: системные настройки могут измениться после проверки. Нужна отдельная `evaluatePolicy`; её `success == true` означает успешную OS policy evaluation. Не гарантируется, что последующая evaluation тоже успешна. Поведение зависит от policy, устройства и configuration profiles. [I2, I3]
3. **Новый context имеет значение:** официальный Apple sample `Authenticator/ViewController.swift`, строки 65–69, прямо предупреждает: reuse ранее успешного context позволяет следующей policy evaluation успешно завершиться **без повторного тестирования biometry**, и создаёт `context = LAContext()` на каждый login. Это primary-source доказательство, не совет из стороннего блога. [I8]
4. Отдельный механизм `touchIDAuthenticationAllowableReuseDuration`: ненулевой interval разрешает использовать недавний Touch ID device unlock без нового prompt; default **0** означает отсутствие такого reuse. Новый context и default 0 решают разные источники reuse. Документация свойства говорит именно Touch ID; универсальная гарантия «это отключает все возможные OS Face ID/passcode caches» из неё не следует. [I6]
5. **Предел freshness:** источники поддерживают fresh evaluation с новым context и без включения указанного reuse, но API не выдаёт timestamp физического ввода, подписанный proof, identity app owner или гарантию конкретного visual prompt. У Face ID сканирование начинается сразу; отсутствие длительно видимого диалога само по себе не доказывает cache/reuse. Apple не обещает новый ввод passcode при каждом запросе: допустима biometry. [I1, I3, I8]
6. Для Face ID обязателен `NSFaceIDUsageDescription` в app `Info.plist`; Apple sample поясняет, что строка показывается при первом использовании Face ID. Для Touch ID аналогичная usage description не требуется. Это app-local configuration/privacy consent, а не global setting и не Android-style manifest permission. Текущее наличие строки/состояние разрешения в этом исследовании не проверялись; пользовательское согласие не запрашивалось. [I4, I7, I8]

### Callback, отмена, foreground

- `evaluatePolicy` асинхронна; reply выполняется на **private framework queue, unspecified threading context**, не гарантированно Main. Из reply нельзя вызывать `canEvaluatePolicy`: Apple предупреждает о deadlock. UI/lifecycle state в callback не становится автоматически потокобезопасным. [I3]
- `invalidate()` останавливает pending evaluations с `appCancel`; invalidated context нельзя использовать снова, повторная invalidate без эффекта. Это documented native cancellation, но не обещание отозвать уже доставленный/конкурирующий success callback. [I5]
- `userCancel` — пользователь нажал Cancel; `appCancel` — приложение отменило evaluation; `systemCancel` — система отменила, например, когда другая app вышла foreground во время dialog. **Это разные причины**, все без successful proof. `authenticationFailed` — неуспешная аутентификация; `passcodeNotSet`, `invalidContext`, `notInteractive` — другие failure conditions, не подтверждение личности. [I1, I5, I9]
- **Inactive не background:** `UIApplication.State.inactive` определён как foreground без получения событий; `UIScene.ActivationState.foregroundInactive` также foreground. `.background` означает background (для scene — не onscreen). Поэтому `willResignActive` нельзя само по себе трактовать как доказанный реальный уход в фон. [I10]
- **UNVERIFIED:** точная последовательность inactive/active событий именно LocalAuthentication prompt на используемом iOS 17 host и гарантированное различение собственного OS prompt от иных interruptions. Прочитанные API docs не обещают конкретную последовательность или отдельный флаг «inactive потому что именно мой LAContext». `systemCancel` описан как возможный исход при смене foreground app, а не universal lifecycle oracle. [I3, I9, I10]
- Успешный callback может быть обработан после локальной отмены/закрытия owner из-за асинхронной доставки и scheduling; API не проверяет `requestId`/app owner и не обещает устранить такую app-level гонку. Отдельная проверка актуальности относится к existing Access policy, а не к OS identity proof. Runtime race tests не запускались.
- Process death уничтожает app context/callback/request; в этих API нет persistable owner-proof token или API восстановления `LAContext` evaluation после запуска нового процесса. Session restoration приложения не является новой OS authentication. Гарантия существующей policy «не восстанавливать pending request/proof» не предоставляется флагом LocalAuthentication и здесь не реализована.

## Hypotheses / unverified / blockers для parent

- **Бывшая гипотеза SIM-only → OK:** переведена в **CONFIRMED по AOSP source** для `android-5.0.0_r1` и `android-5.1.0_r1`. Runtime и OEM variants, другие patch releases не проверены. Для 5.1 отдельно подтверждена изменённая binder/cache цепочка, а не перенесена по аналогии с 5.0.
- **UNVERIFIED:** достаточный public API 21 prerequisite без SIM false positive; одинаковые OEM semantics; end-to-end lifecycle wiring, поздние results, process-death handling и OS prompts приложения. Наличие platform API не закрывает P08/G4.
- **UNVERIFIED:** обещание «каждая успешная iOS evaluation всегда показывает отдельный заметный prompt/требует именно нового ручного passcode». Apple предоставляет policy evaluation, не такой visual/manual-input контракт.
- **BLOCKED отдельно:** app-host Keychain/AMFI **−424** остаётся отдельным известным blocker из задания. Никакие credentials/signing identities/Keychain items не читались; обход entitlements/AMFI не предлагался. LocalAuthentication documentation research не закрывает Keychain runtime gate.
- Ничего не реализовано; `DeviceOwnerVerifier.kt` и `access-policy.md` только прочитаны. Утверждение policy «Android21 — isKeyguardSecure + Intent» само по себе недостаточно для заявленного fresh proof. Исправление policy/compatibility behaviour — решение primary, не этого report.

## Первичные источники

### Android

- **A1:** [KeyguardManager SDK reference](https://developer.android.com/reference/android/app/KeyguardManager), разделы `createConfirmDeviceCredentialIntent`, `isKeyguardSecure`, `isDeviceSecure`, `isDeviceLocked`, `isKeyguardLocked`. Современная справка используется для availability/контрактов, не вместо исторического source.
- **A2:** `KeyguardManager.java`: [5.0](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/KeyguardManager.java), [5.1](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/core/java/android/app/KeyguardManager.java).
- **A3:** `WindowManagerService`: [5.0](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/services/core/java/com/android/server/wm/WindowManagerService.java), [5.1](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/services/core/java/com/android/server/wm/WindowManagerService.java); `PhoneWindowManager`: [5.0](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/policy/src/com/android/internal/policy/impl/PhoneWindowManager.java), [5.1](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/policy/src/com/android/internal/policy/impl/PhoneWindowManager.java); `KeyguardServiceDelegate`: [5.0](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/policy/src/com/android/internal/policy/impl/keyguard/KeyguardServiceDelegate.java), [5.1](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/policy/src/com/android/internal/policy/impl/keyguard/KeyguardServiceDelegate.java).
- **A4:** `KeyguardServiceWrapper`: [5.0](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/policy/src/com/android/internal/policy/impl/keyguard/KeyguardServiceWrapper.java#52), [5.1](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/policy/src/com/android/internal/policy/impl/keyguard/KeyguardServiceWrapper.java#199); [5.1 KeyguardStateMonitor](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/policy/src/com/android/internal/policy/impl/keyguard/KeyguardStateMonitor.java#60); [5.0 KeyguardService binder](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardService.java#72).
- **A5:** `KeyguardViewMediator`: [5.0 isSecure](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java#1014), [5.1 isSecure](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java#1077), [5.1 binder callback registration](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java#1540); `KeyguardUpdateMonitor`: [5.0](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/packages/Keyguard/src/com/android/keyguard/KeyguardUpdateMonitor.java#1197), [5.1](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/packages/Keyguard/src/com/android/keyguard/KeyguardUpdateMonitor.java#1233); `LockPatternUtils`: [5.0](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/com/android/internal/widget/LockPatternUtils.java#1486), [5.1](https://android.googlesource.com/platform/frameworks/base/+/android-5.1.0_r1/core/java/com/android/internal/widget/LockPatternUtils.java#1536).
- **A6:** `ConfirmDeviceCredentialActivity`: [5.0, строки 43–63](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.0.0_r1/src/com/android/settings/ConfirmDeviceCredentialActivity.java#43), [5.1, строки 43–63](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.1.0_r1/src/com/android/settings/ConfirmDeviceCredentialActivity.java#43).
- **A7:** `ChooseLockSettingsHelper`: [5.0](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.0.0_r1/src/com/android/settings/ChooseLockSettingsHelper.java#69), [5.1](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.1.0_r1/src/com/android/settings/ChooseLockSettingsHelper.java#69); `ConfirmLockPassword`: [5.0](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.0.0_r1/src/com/android/settings/ConfirmLockPassword.java#193), [5.1](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.1.0_r1/src/com/android/settings/ConfirmLockPassword.java#193); `ConfirmLockPattern`: [5.0](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.0.0_r1/src/com/android/settings/ConfirmLockPattern.java#147), [5.1](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.1.0_r1/src/com/android/settings/ConfirmLockPattern.java#147).
- **A8:** [5.0 Settings AndroidManifest.xml, строки 1114–1120](https://android.googlesource.com/platform/packages/apps/Settings/+/android-5.0.0_r1/AndroidManifest.xml#1114).
- **A9:** [Lollipop Activity.onActivityResult Javadoc, строки 4744–4766](https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/Activity.java#4744); [current Activity reference](https://developer.android.com/reference/android/app/Activity).
- **A10:** [Get a result from an activity](https://developer.android.com/training/basics/intents/result); [AndroidX ActivityResultRegistry.kt](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/activity/activity/src/main/java/androidx/activity/result/ActivityResultRegistry.kt), `register`, `onSaveInstanceState`, `onRestoreInstanceState` (upstream snapshot прочитан 2026-11-05, moving branch).
- **A11:** [DevicePolicyManager reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager), `getPasswordQuality`, `isActivePasswordSufficient`.
- **A12:** [Settings.Secure.LOCK_PATTERN_ENABLED](https://developer.android.com/reference/android/provider/Settings.Secure#LOCK_PATTERN_ENABLED).
- **A13:** [The activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle), `onStart`, `onResume`, `onPause`, `onStop`, process death.

### Apple

- **I1:** [LAPolicy.deviceOwnerAuthentication](https://developer.apple.com/documentation/localauthentication/lapolicy/deviceownerauthentication).
- **I2:** [canEvaluatePolicy(_:error:)](https://developer.apple.com/documentation/localauthentication/lacontext/canevaluatepolicy(_:error:)).
- **I3:** [evaluatePolicy(_:localizedReason:reply:)](https://developer.apple.com/documentation/localauthentication/lacontext/evaluatepolicy(_:localizedreason:reply:)).
- **I4:** [LAContext](https://developer.apple.com/documentation/localauthentication/lacontext).
- **I5:** [invalidate()](https://developer.apple.com/documentation/localauthentication/lacontext/invalidate()).
- **I6:** [touchIDAuthenticationAllowableReuseDuration](https://developer.apple.com/documentation/localauthentication/lacontext/touchidauthenticationallowablereuseduration).
- **I7:** [NSFaceIDUsageDescription](https://developer.apple.com/documentation/bundleresources/information-property-list/nsfaceidusagedescription).
- **I8:** [Logging a User into Your App with Face ID or Touch ID](https://developer.apple.com/documentation/localauthentication/logging-a-user-into-your-app-with-face-id-or-touch-id); [официальный downloadable sample](https://docs-assets.developer.apple.com/published/a6e7eb261400/LoggingAUserIntoYourAppWithFaceIDOrTouchID.zip), `Authenticator/ViewController.swift`, строки 65–75. ZIP прочитан в памяти, не исполнен.
- **I9:** [systemCancel](https://developer.apple.com/documentation/localauthentication/laerror-swift.struct/systemcancel), [appCancel](https://developer.apple.com/documentation/localauthentication/laerror-swift.struct/appcancel), [userCancel](https://developer.apple.com/documentation/localauthentication/laerror-swift.struct/usercancel), [notInteractive](https://developer.apple.com/documentation/localauthentication/laerror-swift.struct/notinteractive).
- **I10:** [UIApplication inactive](https://developer.apple.com/documentation/uikit/uiapplication/state/inactive), [background](https://developer.apple.com/documentation/uikit/uiapplication/state/background); [UIScene foregroundInactive](https://developer.apple.com/documentation/uikit/uiscene/activationstate-swift.enum/foregroundinactive), [background](https://developer.apple.com/documentation/uikit/uiscene/activationstate-swift.enum/background).

Apple availability проверена через официальный documentation JSON endpoint `https://developer.apple.com/tutorials/data/documentation/<symbol-path>.json`, поле `metadata.platforms`; содержание — `abstract` / `primaryContentSections`. Это публичный first-party источник, без login/browser authorization. Отсутствие `deprecatedAt` отмечено как «не указана», не как обещание вечной поддержки.

## Что не делалось

Не запускались prompts, device tests, изменения passcode/SIM/microphone/settings/permissions; не читались реальные credentials, preferences, signing identities или Keychain. Не выполнялись build и app installation. Документ — source/API evidence, **не runtime/security acceptance P08**. Изменён только этот файл; прочие native/policy bindings не тронуты.
