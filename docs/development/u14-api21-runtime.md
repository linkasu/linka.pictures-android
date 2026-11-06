# U1.4: API21 owned-content runtime, 6 ноября 2026

**RED: 1 test / 1 failure. UI-паритет и filled-board screenshot не получены.**
Контракт: [ui-1.4-parity.md](ui-1.4-parity.md); визуальное одобрение остаётся OPEN.

## Идентичность и границы

- Только `emulator-5554`, AVD `linka_platform_probe_api21_arm64`, Android 5.0.2 / API21,
  `arm64-v8a`, fingerprint `generic_arm64/sdk_phone_arm64/generic_arm64:5.0.2/LSY66K/8789838:eng/test-keys`.
- Portrait, 1080×1920 px, 480 dpi (360×640 dp); runtime config `1.0` font scale,
  `w360dp h567dp`. `settings get system font_scale` возвращает `null`, не явную настройку.
- До старта MCP видел только 5556. 5554/5555 свободны; существующий API21 AVD проверен.
  Один запуск: `emulator -avd linka_platform_probe_api21_arm64 -port 5554 -no-snapshot-load -no-snapshot-save`.
  Readiness: `sys.boot_completed=1`, API/ABI/AVD проверены через MCP.
- SDK `/Volumes/data/developer-state/android-sdk`, AVD home `/Volumes/data/developer-state/android-avd`.
  Host log оставлен в approved temp: `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/u14-api21-owned-emulator.log`.
- 5556, iOS, production package, snapshots, permissions, системные настройки и source не изменялись.
  `su.linka.pictures` отсутствовал; установлены только frozen development/test APK с `-r`, без downgrade/clear.
- Worktree base `5f173f346e882c14bc9f8bc04a8ee1dae8fc95a1`; APK identity определяется
  SHA ниже, а не предположением, что APK собран из этого base. Сборка не выполнялась.

| Frozen input | Проверенная host SHA-256 |
|---|---|
| `app.apk` | `3700456b8e5ab931d73a15a073881daf57265ba2a0904f2ae9cd92415deae1f2` |
| `appTest-diagnostic.apk` | `edffb0191c97c737d430dc692d9a94308f056367e13129e7ae2dab48d6aa75da` |
| `LINKa-U14-HOCHU-cfb199d.linka` | `a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b` |

APK source directory: `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/u14-visual-artifacts-8se7fno1/`.

## Единственный выполненный тест

```text
am instrument -w -r \
  -e class su.linka.pictures.activity.DevelopmentParityTest#filledBoardThroughOwnedIncomingContent \
  -e u14FixtureName LINKa-U14-HOCHU-cfb199d.linka \
  -e u14FixtureSha256 a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b \
  su.linka.pictures.development.test/androidx.test.runner.AndroidJUnitRunner
```

Предварительный `nohup` через MCP не создал output-файл; instrumentation/process не наблюдались.
Затем прямой MCP raw request с redirect в `/data/local/tmp/linka-u14-api21-owned.txt`
получил client timeout, но тест продолжился. Terminal проверен bounded polling файла,
а не возвратом MCP/am или промежуточным `STATUS_CODE: 0`. Повторного теста не было.

Подтверждены empty-library/message UI preconditions, чтение SHA-verified same-UID
debug FileProvider content URI, native incoming intent и preview
`Страниц: 1; карточек: 9; изображений: 9; записей: 9;`.
Последний stage: `import-preview-observed`; последний await:
`click Разрешить команды в этом экране`.

Первичная ошибка:

```text
java.lang.AssertionError: Timed out at click Разрешить команды в этом экране;
activePackage=su.linka.pictures.development (no text/password dump)
DevelopmentParityTest.await:341 → clickNamed:411 → confirmImportAndCompose:162
→ filledBoardThroughOwnedIncomingContent:92
Time: 111.811
FAILURES!!! Tests run: 1, Failures: 1
INSTRUMENTATION_CODE: -1
```

Два `ActivityScenario.close` failure (`DESTROYED`, last `RESUMED`) сохранены как
**suppressed**, не первичная причина. Конкретная причина недоступности named action
не установлена; guards не ослаблялись, состояние вручную не исправлялось.
Explicit import confirm/SQLite commit, девять видимых карточек, составленная фраза
и `u14State=board-filled` **не достигнуты**. SAF picker и cross-UID OS grant не проверяются этим маршрутом.

## Evidence

- [Terminal instrumentation log](evidence/u14-api21/2026-11-06-owned-content-instrumentation.txt).
- [Device metadata](evidence/u14-api21/2026-11-06-device-metadata.txt).
- [Raw PNG, 07:34:10 UTC](evidence/u14-api21/2026-11-06-owned-content-073410Z.png),
  1080×1920 portrait: снят `screencap -p /data/local/tmp/...` и MCP pull, просмотрен.
  Показывает **Android launcher onboarding после закрытия target activity**, не доску
  и не preview. В этот момент terminal ещё ожидался; активный UIAutomation не трогали.
- Terminal получен к 07:35:26 UTC. PNG не является визуальным доказательством parity;
  paired macro не создавался.

Остановлено на конкретном RED без новых APK, повторного импорта, reset/дубликатов,
SQL-инспекции, native PIN/mic/system prompt automation. AVD оставлен работающим;
следующий шаг primary — исследовать named action/видимость в preview на frozen build,
не считать этот прогон подтверждением успешного импорта.
