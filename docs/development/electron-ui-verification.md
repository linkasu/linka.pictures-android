# Electron UI: проверка и оставшаяся приёмка

7 ноября2026. Код V00–V13 реализован отдельными commits. Это **не утверждение,
что весь V00–V15 закрыт**: V14 требует оставшейся native/accessibility/user matrix,
V15 заблокирован production migration admission.

## Источники и воспроизведение

- Electron reference `318a969973412e6affb13f24b456cd47b8ea1476`, renderer из source,
  существующий test main, изолированный временный home; реальные1280×800/375×667.
- В обоих приложениях показан public `app/src/androidTest/assets/u14/ХОЧУ.linka`,
  SHA `a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b`;
  порядок9 карточек и общая фраза ХОЧУ КУШАТЬ. Новые3.0 generator fixtures отдельно:
  генерация не означает выполненный native import каждого варианта.
- Локальные оригиналы, hashes/build identity и проверочный атлас:
  `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/electron-ui-build/atlas.html`,
  `evidence.json`. PNG остаются в tmp по пожеланию пользователя.
- iPad editor screenshots сохранены XCTest в portrait raster с landscape UI;
  атлас содержит отдельные повёрнутые270° viewing copies, original PNG/SHA не меняются.
  Logical full-screen sizes derived из density/resolution; это не измерение content rect.

## Технический gate

Полный aggregate до последней визуальной калибровки: **BUILD SUCCESSFUL,6m20s**:
shared JVM **511/511**, Android debug-unit **70/70**, release-unit **70/70**,
verifyReleasePrivacy, app/shared lint, debug APK/instrumentation, оба iOS frameworks
и Native test link. Нет failed/errors/skips в JVM XML.

После удаления legacy grid insets и исправления IME повторены только invalidated
UI/tests/build/lint/framework links: PASS4m17s. После inspector-focus fix:
focused WindowPresentation/CardEditorInput + Simulator framework PASS1m17s;
shared full JVM/APK/lint/device framework/Native test link PASS3m39s.
На том срезе actual Native executable: **635/635,0 skips,73612ms**, owned iOS17
`DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`.

После compact action-row и normal-preview104 калибровки (`f4ad2c6`, `0d9ad01`):
**512/512 JVM**, APK, Simulator/device frameworks и Native test link — PASS4m30s;
actual Native **636/636,0 skips,93859ms**. Это последние totals; предыдущие635/511
оставлены как история, не складываются с ними. Новая проверка — бюджет pinned row.

Deprecated platform/expect-actual и известный ICU17.2→min17 linker diagnostics не
подавлялись; lint PASS не означает отсутствие warnings. App-host security/physical
gates не выводятся из core Native totals.

## Native UI и исправления, найденные по PNG

### Android21

Промежуточный read-only atlas **1/1 PASS,5.416s**: библиотека →9 карточек/прежняя фраза →
параметры →настройки →scale dialog/Cancel →та же доска/фраза. Persisted settings не
сохранялись. Экран1080×1920/density480; `font_scale` setting отсутствует (system default),
portable1.0/contrastOff наблюдались в UI.

После финальных compact/preview104 изменений тот же atlas повторён: **1/1 PASS,
5.224s**. Все9 карточек, фраза, настоящая IME и доступная Cancel проверены снова.

Первоначальный scale PNG показал Cancel за клавиатурой, хотя AX-click проходил.
Исправлена Android dialog window policy (`decorFitsSystemWindows=false`), без
смены secure policy/permissions. Новый native assertion проверяет реальные bounds
Cancel относительно TYPE_INPUT_METHOD, а не только AX-наличие. Он PASS;
Cancel поднялся с y1153..1213 до818..878, свежий PNG просмотрен.

Legacy btn_default insets и вложенные grid margins удалены после сравнения PNG:
сетка теперь full-bleed, миниатюры сохраняют больше места, rows/columns/order не меняются.

### iPhone17 / iPad17

До калибровки phone startup/relaunch+editor/keyboard+atlas+orientation+audit:
**5/5 PASS**. iPad первый набор: **3 PASS/1 RED**, editor не удерживал клавиатуру.
RED не удалён: `ipad-atlas.xcresult`, keyboard отсутствует после focus tap.

Причина: host инспектора выбирался по высоте после IME-padding; клавиатура меняла
host на dialog, уничтожая focused field. Policy перенесена до IME insets; буфер
identity и прикладные команды не изменены. **Тот же тест, без ослабления ожидания
Keyboard, теперь PASS**. Native loops после fix: phone **3/3**, tablet **3/3**
(atlas, editor с настоящей keyboard, штатный accessibility audit без issue filters).
`iphone-calibrated.xcresult` / `ipad-calibrated.xcresult`, последние PNG просмотрены.
Side inspector и pinned Cancel/Done действительно видны вместе с iPad keyboard.

Дополнительно existing dark/session restore и portable2.0 + high contrast +
save/read/cold-open/caption gestures/restore прошли **2/2 на phone и2/2 на tablet**,
`iphone-accessibility.xcresult` / `ipad-accessibility.xcresult`. Исходные1.0/contrastOff
перечитаны после восстановления; фраза и audio state сохранены. System font category
этими сценариями не менялась. На PNG крупного текста выявлен лишний унаследованный
button line-height у12sp подписей действий; последняя типографическая правка задаёт14sp
явно. Её focused build/runtime evidence фиксируется отдельно от предшествующего full gate.

Следующий PNG-review выявил, что узкая колонка действий не годится при2.0 даже
с правильным interline. В compact/large-text окне действия выделены в отдельный
закреплённый ряд с тремя равными целями; широкое обычное окно сохраняет inline
Electron-композицию. Callback/guards общие для обоих размещений. Новый pure budget
test проверяет резерв этого ряда и отсутствие роста viewport; это UI-layout,
не новый persisted mode и не уменьшение accessibility scale.

Последний compact row: phone atlas+portable2.0 roundtrip **2/2 PASS**.
Tablet первый replay получил **RED до применения и сохранения**: injected
Cmd+Down/Cmd+Shift+Up/typeText не заменил1.0 на2; новой геометрии этот путь ещё
не достиг. Сохранение не повторялось вслепую: app cold reopen, read-only inspection,
затем qualified serial replay с guard исходных1.0/contrastOff — **2/2 PASS**,
`ipad-action-row-serial.xcresult`. Исходные настройки восстановлены/перечитаны.
Точный механизм injected keyboard failure не объявляется исправленным; initial
`ipad-action-row.xcresult` сохраняется как RED, hardware keyboard gate OPEN.

Финальный build `0d9ad01`: phone read-only atlas + настоящее recording-only
Speak → Stop → replay → reopen — **2/2 PASS**, `iphone-delivery.xcresult`.
Это UI/controller/callback proof на Simulator, не physical audio quality/TTS proof.
Предпочтительная высота compact preview104 проверена отдельным layout assert и
свежим Android PNG: стандартная двухстрочная подпись миниатюры больше не срезается.

Dark audit проверяет native semantics, но не произвольные pixels пользовательского
медиа: чёрный текст внутри transparent bitmap «ХОЧУ» теряет контраст на тёмной
поверхности. Исходные изображения не перекрашивались/не заменялись; этот известный
визуальный limit остаётся в приёмке dark media, не скрывается за audit PASS.

App/test signatures, min17 и actual Simulator entitlements проверены: прежняя
isolated development group у продукта, только generated runner-own group у теста.
Install обновляет development app без удаления sandbox. Production signing нет.

### Android35 environment

Первый install timeout; `cmd: Can't find service: package`. Пользователь разрешил
reboot без wipe и подтвердил отсутствие важных данных в эмуляторах — [AGENTS](../../AGENTS.md).
После reboot и штатного restart того же AVD с **прежними** args PM снова отвечает,
но Google boot animation сохраняется, install sessions sealed/committed90% без
Applied/Failed. Screenshot `api35-install-state.png` — boot, не приложение.
MCP timeout не считается ни успехом, ни доказанным отказом install. Проверенный
старый APK hash отличается от build; API35 product UI PASS не заявлен.
Не обходились verification/permissions, не удалялись данные или AVD locks.

## Незакрытые пункты V14/V15

- User visual approval всех экранов и мобильных отличий.
- Полный atlas редких empty/error/conflict/UNCONFIRMED/transfer/access состояний.
- Вся system × portable font-scale/short landscape/window-size matrix.
- Dark-media contrast для transparent raster symbols независимо от caption contrast.
- Реальные TalkBack/VoiceOver и physical/AAC, не только automated audit.
- Android35 healthy boot/install и повтор native product matrix.
- Production root/migration/upgrade/rollback admission: [V15](electron-production-admission.md).

Публикация, store upload, push/PR и production-подпись не выполнялись. Код и
технические PASS не выдаются за выполненные пункты из этого списка.
