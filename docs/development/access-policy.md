# P08: политика защиты помощника

## Уточнение iOS backup — 9 ноября 2026

Пользователь явно выбрал перенос защиты помощника вместе с библиотекой через
защищённый системный backup iOS. Только assistant Keychain item меняется на
`kSecAttrAccessibleWhenUnlocked`, `kSecAttrSynchronizable=false`; формат/bytes
verifier и access group не меняются. Старый `ThisDeviceOnly` item переводится
после validation, с последним root guard и readback атрибутов/неизменных bytes.
Ни delete/add, ни reset отсутствующего/повреждённого record не допускаются.
Identity email, receipt и account/installation tokens остаются `ThisDeviceOnly`.
Portable `.linka`/backup/recovery по-прежнему не переносит credential record.

Ниже сохранён исторический срез 5 ноября; его упоминания `ThisDeviceOnly` для
assistant store superseded этим решением. Это изменение eligibility Keychain,
не утверждение, что фактический restore на втором физическом устройстве уже проверен.

COORD, 5 ноября 2026. Угроза — случайные/нежелательные изменения и выдача данных в локальном приложении, не владелец/root разблокированного устройства. Общение всегда вне gate. Этот документ фиксирует решения, не закрывает native/security/G4 gates.

## Верификатор и совместимость

Один version1 credential record вне library/backup, никакого plaintext password. PBKDF2-HMAC-SHA1 **1300000 iterations**, random salt16 bytes, output32 bytes, стандартные Java SecretKeyFactory/CommonCrypto, не собственный PBKDF2. Причина SHA1 PRF — встроенный PBKDF2WithHmacSHA1 на Android21; SHA256 Java factory требует более нового API. Это не применение быстрого SHA1 password hash и не утверждение FIPS. [OWASP work factors](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2). Calibration измеряет стоимость на целевых устройствах, не снижает нижнюю границу ради быстрого PASS; выполняется вне Main, один KDF одновременно. ARMv7/performance ещё OPEN.

Вход KDF — ASCII lowercase hex **каждой UTF16 code unit (4 digits)**, без trim/normalization/transcoding replacement. Это injective encoding, не новый crypto algorithm: одинаково сохраняет Unicode, NUL и даже legacy lone surrogate без C-string truncation. Encoding явно versioned вместе с record. Пароль<=65536 UTF16 units, без тихого усечения; новое значение непустое. Secret byte/char buffers очищаются best-effort, исходный UI String нельзя обещать стереть из managed memory.

Legacy record хранит legacy=true и verifier **точного сохранённого** значения. При unlock только legacy input trim-ится по старому Kotlin правилу, пустой результат отвергается; stored password не trim-ится. Missing/null прежнего parent_password мигрируется как empty, а не выключает lock. Поэтому прежние unreachable empty/space-edge states остаются защищёнными до свежего owner reset. Новые пароли сравниваются точно, без trim. P09 ещё должен атомарно перенести record; этот модуль не читает реальные preferences.

Android store: app-private noBackupFilesDir, один atomic versioned verifier record, private permissions; verifier не plaintext и не требует API23 symmetric Keystore. iOS: отдельный Keychain item, WhenUnlockedThisDeviceOnly. Оба исключены из portable snapshot и library replacement. Коррупция/неизвестная schema/read error — fail closed, не Unprotected. Store проверяет guard непосредственно перед publish; сомнительный durable исход — UnconfirmedCommit и quarantine до reopen, не ложный rollback.

## Матрица операций

| Состояние | Обычные protected commands | Unlock | Configure/disable | Reset |
|---|---|---|---|---|
| Не защищено | Разрешены, owner-bound guard всё равно отзывается | Создаёт ephemeral owner session | Новый непустой пароль только при AVAILABLE реальной OS verification | Только fresh OS proof |
| Protected modern/legacy | Только актуальная capability того же owner | Проверка точной modern / прежней legacy semantics | Нужна текущая owner capability; новый пароль требует AVAILABLE | Только fresh OS proof, не capability |
| Ошибка чтения/unknown commit | Отказ | Отказ | Отказ | До explicit reopen отказ |

Capability — in-process identity+random token, один текущий context на owner, максимум64 одновременных owner contexts. Не сериализуется. Guard захватывает owner context и credential generation; revokeOwner/revokeAll/изменение пароля отзывают уже созданные guards даже без lock. Повторный unlock того же owner заменяет его прошлую capability. Старый success после cancellation/revoke не создаёт новую capability. Никаких SQL/FS/OS prompts внутри синхронного guard и обратного storage lock order.

Все credential writers сериализованы отдельным mutex. KDF/OS prompt вне storage writer; credential store publish имеет собственный последний guard. Revoke синхронный и не ждёт KDF/IO. После подтверждённого credential publish sessions отзываются, state обновляется даже если caller coroutine уже отменена. Failed/cancel до publish сохраняет старую защиту; unknown исход блокирует mutations. Restore/recovery никогда не меняют credential record. Старый Android1.4 не понимает новый record; downgrade не обещается, исходные legacy данные сохраняет migration recovery, не credential reset.

## Native owner verification и lifecycle

**Явное решение пользователя, 5 ноября: на Android21/22 OS verification UNAVAILABLE.** Прежний кандидат `isKeyguardSecure + createConfirmDeviceCredentialIntent + RESULT_OK` недостаточен: AOSP5.0/5.1 допускает locked SIM без device credential → Intent → RESULT_OK без аутентификации. Надёжный public prerequisite API21/22 без SIM false positive не установлен; unsafe success не подключать. Пользователь выбрал «Ограничить OS-проверку»: минимум21 сохраняется, новая блокировка/замена пароля и forgotten-password reset через ОС на21/22 недоступны. Legacy lock и точный password unlock сохраняются; невозможные ранее empty/space-edge пароли не превращаются в unlocked. Известная прежняя capability может выключить lock по существующему контракту, это не OS reset. Забытый пароль на21/22 нельзя обходить очисткой данных/библиотеки. [Исходники и полный call path](device-owner-verification-evidence.md).

| Платформа | Новый lock/замена пароля | Unlock существующего | Сброс через ОС |
|---|---|---|---|
| Android21/22 | Недоступны: OWNER_VERIFICATION_UNAVAILABLE | Точный legacy/modern verifier; отсутствие OS proof не отключает lock | Недоступен, библиотека и verifier сохраняются |
| Android23+ | Только AVAILABLE; доступность recheck, не cached boolean | По существующей policy | Только fresh verified challenge + живой request/foreground/guard |
| iOS17+ | Только AVAILABLE; signed Keychain/runtime gate отдельно | По существующей policy | Новый LAContext + живой request/foreground/guard |

Это согласованное ограничение R14 для21/22, не повышение минимума/сокращение остального продукта. `isDeviceSecure` с API23 — доступный predicate, не готовый lifecycle/reauth implementation. Configure/reset проходят общий serialized credential writer; UNAVAILABLE не запускает prompt/KDF нового пароля и не пишет credential record. Credentials остаются вне library/backup, schema не меняется. UI/root должны отображать недоступность, не предлагать unsafe fallback. Regression `unavailableOsVerificationPreservesLegacyUnlockAndCannotResetOrReplaceItsPassword` использует synthetic verifier; actual native availability/root binding ещё не реализован.

Focused после решения: **12/12 LocalAssistantAccessTest JVM и12/12 core Native iOS17 (9762ms)**, включая новую byte-preservation/no-prompt/no-write regression и прежние cancellation/unknown-commit/legacy tests. Native test link PASS. Logs в approved temp: `p08-api21-compatibility-policy.log`, `p08-api21-compatibility-native-ios17.log`. Это pure/common policy с реальным platform KDF, **не actual Android OS verifier**. Предыдущие полные257/361 — отдельный завершённый gate до новой regression, не новый полный258/362 run.

iOS17 — новый LAContext/evaluatePolicy deviceOwnerAuthentication на каждую попытку, не cached boolean; canEvaluatePolicy не proof. Availability проверяется заново после Settings. Cancel/denial/unavailable различаются, raw exceptions/пароли/paths не логируются. Face ID usage description/privacy authorization и реальный prompt требуют отдельной app-host проверки.

Shell обязан различать принадлежащий запросу системный prompt от реального ухода пользователя в фон: existing assistant capabilities отзываются в обоих случаях, а terminal owner proof принимается только после возврата foreground и при живом explicit request/owner. Close/real background/cancel отзывают pending request; поздний success не принимается. Ни capability, ни proof, ни ожидающий request не восстанавливаются после process death. Точная native activity/LAContext wiring и реальные UI tests ещё OPEN; core fake proof не является OS proof.

## Проверенное ядро и оставшиеся bindings

`LocalAssistantAccess` реализует policy поверх существующего `DeviceOwnerVerifier`, а не второго OS-verification interface. `FileCredentialStore` пишет bounded record через own must-create staging+flush+atomic rename, last guard до publish; исключение rename сверяет exact old/new bytes отдельным чтением. Не удалось подтвердить — UnconfirmedCommit. Реальный app-private noBackup root ещё должен передать Android composition root; legacy preferences не читались и не изменялись. iOS Keychain binding скомпилирован, но [app-host runtime gate](ios-keychain-host.md) **BLOCKED**; настоящие OS owner prompts ещё не реализованы.

Focused JVM: **17/17**,0 failures/errors/skips, включая4 actual-FS tests: reopen, late guard, failed/known/unknown rename, foreign staging sentinel/oversized record и неизменность library sentinel. Android и Native compilation PASS. Ранее весь Native suite **230/230** на owned iOS17 (40193ms) включил11 policy tests и2 actual CommonCrypto tests; добавленные4 file-store tests после этого ещё ждут Native runtime. Standard PBKDF2 vectors, embedded NUL/empty input и production1300000 Unicode/lone-surrogate vector совпали с независимым Python hashlib, без нового crypto dependency. Native vector занял428ms на одном simulator — не ARMv7 calibration.

Raw logs в approved temp: `p08-core-review-check.log`, `p08-core-native-ios17.log`, `p08-file-credential-fixed.log`. Новые Android tests проверяют actual API provider и own noBackup root, но runtime до отдельного APK freeze/device execution **не заявляется**. Initial iOS compile failure был неверным именем K/N package (`platform.CommonCrypto` вместо встроенного `platform.CoreCrypto`); временный пустой custom cinterop удалён, не добавлены библиотека/SDK/новый минимум. KDF идёт через штатные Java/CommonCrypto, comparison — MessageDigest.isEqual / timingsafe_bcmp.

После coherent review main aggregate прошёл **240/240 JVM и240/240 Native iOS17,40570ms**, включая4 real file-store tests и9 UI presentation/palette tests. Оба frameworks, Android normal/test APK собираются, normal APK не устанавливается. Следующие iOS image tests и OS-bound Keychain compartment учитываются отдельно; предыдущий total их не подменяет.

После добавления iOS image22 latest core исполнил **262/262 Native,56147ms**. На Android actual PBKDF2/noBackup reopen подтверждены внутри reviewed63/63 suite API21/35, без настоящего owner prompt. Новые build paths после переноса SDK/cache проверены; повторять эти device suites ради переноса не требуется. App-host Keychain2 по-прежнему BLOCKED отдельно.

Core fakes для owner proof не являются реальной системной проверкой. Никаких passcode/settings changes, production credential reads, normal-app installs или real-user migration. Пользователь отдельно разрешил isolated ad-hoc Keychain test-host; AMFI отказал restricted entitlements, обход не выполнялся. Полные P08/G4 до native stores/prompts/lifecycle/root review остаются OPEN.

## Внешние файлы: решение до native binding

`save(picker+write)` недостаточен: при picker фон отзывает capability, поэтому старый UI success не разрешает позднюю запись. Требуется двухфазный destination handle: выбрать назначение (может создать пустой файл), затем на foreground явно подтвердить выдачу с новой авторизацией и guard. Содержимое не передаётся provider до второй фазы. Handle scoped к owner/request и закрывается при отмене/закрытии; URI не persisted.

Необратимая точка — первое открытие/truncate внешнего sink после успешного guard. Перед ней ещё раз проверяются owner/request/foreground/guard; guard проверяется и между bounded copy chunks. После неё cancel/revoke/IO могут оставить внешнюю частичную копию, это честный Failed/Cancelled, не обещание rollback чужого provider. Completed только после полного write/flush/close. Существующий provider file не удаляется cleanup-ом; own private temp и lease живут до terminal outcome. Публичный port и все fakes обновлены вместе до реального native adapter: `chooseDestination` → owner-scoped `ExportDestination.write(file, guard)`/`close`, без unsafe default overload. Native binding ещё OPEN.

Оба native files adapter реализованы и включены в latest **257 JVM/361 core Native** aggregate; Android files19 и affected images24 на обоих API подтверждены отдельно. Destination IO выполняется вне FSM-lock; close не ждёт уже допущенный syscall/chunk, но запрещает последующие admissions. Реальные ActivityResult/picker/foreground roots, protected workflow и provider UI acceptance остаются OPEN; adapter tests не являются fresh-owner proof и не закрывают G4.
