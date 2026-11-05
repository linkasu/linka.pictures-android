# P08: native owner mechanics — integration in progress

5 ноября 2026. Это implementation evidence, **не закрытый P08/G4**. Android/iOS roots ещё должны подключить настоящие request/owner/lifecycle callbacks; положительные OS prompts не исполнялись.

## Реализованная граница

- `OwnerVerificationRequest` — in-process FSM AWAITING_PROOF → AWAITING_FOREGROUND → TERMINAL. Success без foreground ожидает возврата; cancel/failed terminal без foreground. Повторный/поздний proof не оживляет запрос, UnconfirmedCommit не является OS proof.
- `LocalAssistantAccess.suspendForSystemPrompt()` отзывает capabilities и старые library guards, но сохраняет контекст принадлежащей owner OS verification. Это **только** controlled system presentation. Реальный background/owner close по-прежнему `revokeAll/revokeOwner`, включая отмену pending proof. Новый guardRevision не меняет persisted schema/пароль.
- `isVerificationCurrent(id, owner)` — thread-safe root predicate из actual Access state. Он нужен native adapter до launch/после callback; stored UI flag его не заменяет. Новая explicit операция того же owner отменяет прошлый native request; после availability reset заново проверяет job и permit до verify.
- `IosDeviceOwnerVerifier`: новый LAContext на попытку, deviceOwnerAuthentication, reuse duration0, реальный canEvaluatePolicy отдельно от proof; callback сериализуется на Main, background отменяет, success ожидает active. NSNotification tokens/LAContext освобождаются на всех terminal paths. Root scope cancellation отзывает pending request. Context/observer operations не держат state lock.
- `AndroidDeviceOwnerVerifier`: на21/22 UNAVAILABLE до любых Keyguard service вызовов и UI. На23+ actual isDeviceSecure и fresh system-credential intent, проверка prerequisites до/после OS result. Actual ActivityResult и resumed/window foreground delivery принадлежат root requester; no default fake callbacks. cancel только своего request. `onForeground` доставляет ожидающий proof один раз.

## Незакрытый Android lifecycle gate

Внешняя Settings credential activity и нажатие Home внутри неё могут не давать root различимых событий. Adapter **не утверждает**, что один pending flag различает эти состояния. Native requester/real lifecycle spike и разрешённая тестовая device-credential среда остаются обязательными до production acceptance23+. Нельзя из compilation или controlled callback получить P08 PASS либо молча распространить исключение21/22 на другие версии.

Собственные file picker/permission presentation также отзывают старые capabilities/guards, но не объявляются автоматически real background. Transfer требует новую foreground confirmation. Authoring должен удержать только готовый leased media result до явной reauthorization/Resume; не публиковать по старому pre-picker guard и не проигрывать запись заново. Real background/close отменяют pending results и освобождают их.

## Focused evidence

`OwnerVerificationRequestTest` сначала RED: отсутствующий production FSM, log `p08-owner-request-red.log`. После реализации **3/3**, вся affected `LocalAssistantAccessTest` **14/14 JVM**,0 failures/errors/skips, log `p08-owner-request-green.log` (44s). Включены suspension guard revocation, замена native request и прежние legacy/unknown-commit regressions.

Обе Native KLIB compilation и core test link PASS, log `p08-native-owner-compile.log` (58s). На повторно проверенном owned iOS17/21A328 **17/17 focused tests PASS,10035ms**: это common FSM/Access tests с real CommonCrypto, а не LAContext prompt execution.

Готовый self-target test APK `su.linka.pictures.shared.test`, min21/target34, SHA `3ebf2268f17abc9cd908b091da1008629f6884f27f1f13e033cba754aad43a7a`, без RECORD_AUDIO/FirebaseInitProvider, установлен MCP только на owned5554/5556. Native Android adapter control-flow tests **2/2 API21 и2/2 API35 (flagged)**: на21 отказ до service/prompt; stale/background не запускают UI; на35 injected service denial даёт UNKNOWN, не AVAILABLE. Это не positive OS proof/SIM exploit/device credential mutation. Native prompts, grants, normal APK и реальные user data не использовались. Старые63/54 suites не повторялись.

Полные [freeze, manifests и summaries](evidence/p08-owner-mechanics-2026-11-05/) сохранены; raw temp logs дополнительно остались в `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/`.

## Keychain signing prerequisite

Пользователь отдельно разрешил поиск/use существующей Apple Development identity **только** для собственного isolated legitimate Xcode host/Keychain group. Read-only `security find-identity -v -p codesigning` вернул **0 valid identities**. Подпись не выполнялась; аккаунты/сертификаты/профили не создавались и не загружались. Keychain2 app-host runtime остаётся BLOCKED до появления разрешённой identity. AMFI−424 не обходится, ad-hoc retries не возобновляются.
