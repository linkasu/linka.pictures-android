# P08: политика защиты помощника

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

Android21 — KeyguardManager.isKeyguardSecure + createConfirmDeviceCredentialIntent, не просто fingerprint flag. iOS17 — новый LAContext/evaluatePolicy deviceOwnerAuthentication на каждый reset, не cached boolean. Availability проверяется заново после Settings. Cancel/denial/unavailable различаются, raw exceptions/пароли/paths не логируются.

Shell обязан различать принадлежащий запросу системный prompt от реального ухода пользователя в фон: existing assistant capabilities отзываются в обоих случаях, а terminal owner proof принимается только после возврата foreground и при живом explicit request/owner. Close/real background/cancel отзывают pending request; поздний success не принимается. Ни capability, ни proof, ни ожидающий request не восстанавливаются после process death. Точная native activity/LAContext wiring и реальные UI tests ещё OPEN; core fake proof не является OS proof.

## Проверенное ядро и оставшиеся bindings

`LocalAssistantAccess` реализует policy поверх существующего `DeviceOwnerVerifier`, а не второго OS-verification interface. `FileCredentialStore` пишет bounded record через own must-create staging+flush+atomic rename, last guard до publish; исключение rename сверяет exact old/new bytes отдельным чтением. Не удалось подтвердить — UnconfirmedCommit. Реальный app-private noBackup root ещё должен передать Android composition root; legacy preferences не читались и не изменялись. iOS Keychain и настоящие OS prompts ещё **не реализованы**.

Focused JVM: **17/17**,0 failures/errors/skips, включая4 actual-FS tests: reopen, late guard, failed/known/unknown rename, foreign staging sentinel/oversized record и неизменность library sentinel. Android и Native compilation PASS. Ранее весь Native suite **230/230** на owned iOS17 (40193ms) включил11 policy tests и2 actual CommonCrypto tests; добавленные4 file-store tests после этого ещё ждут Native runtime. Standard PBKDF2 vectors, embedded NUL/empty input и production1300000 Unicode/lone-surrogate vector совпали с независимым Python hashlib, без нового crypto dependency. Native vector занял428ms на одном simulator — не ARMv7 calibration.

Raw logs в approved temp: `p08-core-review-check.log`, `p08-core-native-ios17.log`, `p08-file-credential-fixed.log`. Новые Android tests проверяют actual API provider и own noBackup root, но runtime до отдельного APK freeze/device execution **не заявляется**. Initial iOS compile failure был неверным именем K/N package (`platform.CommonCrypto` вместо встроенного `platform.CoreCrypto`); временный пустой custom cinterop удалён, не добавлены библиотека/SDK/новый минимум. KDF идёт через штатные Java/CommonCrypto, comparison — MessageDigest.isEqual / timingsafe_bcmp.

Core fakes для owner proof не являются реальной системной проверкой. Никаких новых native permissions, passcode/settings changes, production credential reads, normal-app installs или real-user migration. Полные P08/G4 до native stores/prompts/lifecycle/root review остаются OPEN.

## Внешние файлы: решение до native binding

`save(picker+write)` недостаточен: при picker фон отзывает capability, поэтому старый UI success не разрешает позднюю запись. Требуется двухфазный destination handle: выбрать назначение (может создать пустой файл), затем на foreground явно подтвердить выдачу с новой авторизацией и guard. Содержимое не передаётся provider до второй фазы. Handle scoped к owner/request и закрывается при отмене/закрытии; URI не persisted.

Необратимая точка — первое открытие/truncate внешнего sink после успешного guard. Перед ней ещё раз проверяются owner/request/foreground/guard; guard проверяется и между bounded copy chunks. После неё cancel/revoke/IO могут оставить внешнюю частичную копию, это честный Failed/Cancelled, не обещание rollback чужого provider. Completed только после полного write/flush/close. Существующий provider file не удаляется cleanup-ом; own private temp и lease живут до terminal outcome. Публичный port и все fakes обновлены вместе до реального native adapter: `chooseDestination` → owner-scoped `ExportDestination.write(file, guard)`/`close`, без unsafe default overload. Native binding ещё OPEN.
