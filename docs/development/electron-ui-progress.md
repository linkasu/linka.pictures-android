# Electron UI: исполнение V00–V15

Основание: [действующий контракт](electron-ui-parity.md), подробный C4 coding plan
в разговоре от 7 ноября 2026. Базовый mobile commit `0f6d62d`.
Результаты ниже не означают user approval или production release readiness.

| Шаг | Состояние | Evidence |
|---|---|---|
| V00 | DONE | Новый контракт; прежнее1.4 требование помечено историческим |
| V01 | DONE | Детерминированный генератор6 public fixtures, source SHA, self-check; native import остаётся проверкой runtime |
| V02 | CODE DONE | `fd6fc82`, `038bdca`: semantic palette, typography, metrics,4 themes |
| V03 | CODE DONE | `d7eea72`: flat primitives, explicit kind/action appearance |
| V04 | CODE DONE | `148470b`, `16fa894`: shared toolbar, body-scroll/pinned actions, все callers |
| V05 | CODE DONE | `f76b172`, `8d8afb8`:24sp bold/full caption scroll/image viewport; dead shrinking helper удалён |
| V06 | CODE DONE | `97b9966`:2 focused RED →15 layout tests PASS; pinned phrase/actions |
| V07 | CODE DONE | `f6de49a`, `40b6065`: labelled speech/Stop/Undo, flat previews, secondary actions |
| V08 | CODE DONE | `72f736e`: adaptive flat tiles/create toolbar/stable listing |
| V09 | CODE DONE | `923a1b7`: form identity/input buffer выше dialog/inspector |
| V10 | CODE DONE | `e67327f`: adaptive inspector, page footer, explicit SaveGrid/CancelGrid, honest Apply label |
| V11 | CODE DONE | `c4bb8fa`: semantic panels/inert preview/manual save unchanged |
| V12 | CODE DONE | `b34b546`: grouped transfer/access; unchanged guards/counters/outcomes |
| V13 | CODE DONE | `4664a5a`: iOS startup/error theme; readable Android21 system strip |
| V14 | PARTIAL / OPEN | `d27d23f`, `744ea3b`: [фактические native/quality результаты и gaps](electron-ui-verification.md), не whole approval |
| V15 | BLOCKED | `6b15212`: [actual call path и prerequisites](electron-production-admission.md), release legacy |

## Проверки по ходу реализации

- V02/V03/V05 focused theme/components:29 JVM PASS.
- V08/V11 focused library/controller/settings PASS.
- V09/V10 focused editor/card/window:86 JVM PASS.
- V06 pinned budget:2 воспроизводимых RED до исправления, затем15 layout PASS.
- Integration communication/app/library/settings focused JVM PASS.
- V12 transfer/access focused JVM PASS; BoardSettings/ProductBack focused PASS.
- Первый native slice: Android APK и iOS Simulator framework собраны; iOS app
  штатно подписана только для Simulator с прежним isolated development group,
  strict signature/min17/actual Mach-O entitlements проверены до install.
  Development APK/app обновлены без удаления данных.
- Android/iPhone stage1 library PNG сняты и просмотрены. Это промежуточный slice,
  не финальный atlas и не user approval.
- Electron снят повторно с точным исходным public u14/ХОЧУ архивом и фразой
  ХОЧУ КУШАТЬ,1280×800 и375×667; desktop/web-phone кадры — не native acceptance.

Временные screenshots/builds/logs: OpenCode temp `electron-ui-build/`.
Полный gate и native acceptance дописываются после фактического выполнения,
не выводятся из результатов отдельных lanes. Source schema/credentials не менялись.

После PNG-review: `6e8ae9a` убирает оставшиеся legacy grid margins;
`6ed38f7` исправляет настоящий Android dialog IME overlap с native bounds assert;
`15543ba` сохраняет iPad keyboard focus, выбирая host до IME insets.
Последние фактические totals/ограничения — [verification](electron-ui-verification.md).

`2acc573` задаёт явный interline действий; `f4ad2c6` переносит их в закреплённый
ряд для compact/large text; `0d9ad01` калибрует normal preview104 без изменения
сохранённой сетки. Последние shared totals512 JVM/636 Native PASS; Android21 atlas
1/1 и iPhone delivery atlas+Speak/Stop/replay2/2 PASS. Это не whole V14/V15 closure.
