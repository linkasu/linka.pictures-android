# API35 после переноса: native netsim/gRPC abort

5 ноября 2026. Это сбой host Emulator, не доказательство повреждения перенесённых AVD и не ошибка приложения.

## Подтверждённые факты

- SDK37.2.12 и userdata находятся на data. Оба owned AVD достигли boot_completed=1; сохранённый test APK совпал с прежним SHA `1c9d835cdcd98ffedcd853ded375445c659f8206c35d68599dec30c7d4f0faae`. [Transfer proof](p06-android-files.md).
- API35 process85100 запущен15:01:15, self-abort15:04:31, SIGABRT/byProc=qemu-system-aarch64. Не shell timeout: обе launch calls имели background=true без timeout. Managed log после рестарта недоступен; реальный crash report сохранён ОС.
- `/Users/aacidov/Library/Logs/DiagnosticReports/qemu-system-aarch64-2026-11-05-150458.ips`: faulting thread70 — abort → `std::__throw_bad_function_call` → `grpc::internal::CallbackWithSuccessTag::Proceed/StaticRun`; thread68 — `netsim::packet::GetChannel/CreateChannel` → `android::qemu2::PacketStreamerTransport::OnDone`.
- Соседние ColorBuffer/texture warnings не были причиной faulting stack. Renderer/config/locks не менялись. Read-only host log также содержит отказы фоновых scanners читать SDK на external volume; это не доказанный отказ самому emulator.
- Один controlled restart с исходными flags/timeout0 вновь дал full boot1, затем self-SIGABRT. Циклические retries не выполняются.

Похожая сигнатура опубликована в [Android issue516836317](https://issuetracker.google.com/issues/516836317), другие Emulator/macOS версии. Это подтверждает известный класс ошибки, но не доказывает одинаковый trigger или исправление в другом patch. Текущий patch37.2.13 release note описывает отдельную graphics-memory leak; SDK автоматически не обновлялся.

## Явно разрешённый ограниченный workaround

Пользователь отдельно разрешил **один проверочный запуск** только owned `linka_audit_tablet` с дополнительным runtime `-feature -WiFiPacketStream`. Данные, AVD config, GPU, SDK и ADB transport не менялись, no wipe/reset/snapshot deletion/grants. Имя feature проверено в локальном SDK `lib/advancedFeatures.ini`; normal command-line override поддерживается help. Это отключает использование netsim для WiFi только в этой проверке, не является Bluetooth/WiFi/сетевой приёмкой продукта.

Controlled process запущен с port5556/no-snapshot-load/no-snapshot-save, без timeout. MCP подтвердил owner name, SDK35, boot1. Только test APK self-target/min21/target34, SHA `5dff891cad6e254a8d6232c7143e2a18d5e70b05e083d113b595cb4ec08f8cfc`, установлен с сохранением test-app data, без RECORD_AUDIO/FirebaseInitProvider и без normal-app install.

Первый actual API35 files run: **17/19 PASS,2 FAIL** в fixture path assertions `/data/user/0` против `/data/data`. Это не скрывается как native PASS; production sandbox guard не ослабляется. A исправляет сравнение physical/canonical identity и повторяет изменённые checks; runtime outcome записывается после чтения. Raw full log `api35-files19-verified-flag.txt` в approved temp. User data/credentials/library не читались.

Correction `6e289f6`→main`81ff8f0` сравнивает paths через один FS canonicalize + containment. **2/2 changed API21 и19/19 API35 PASS**,0 skips; production guards без изменений. [Полный red/green evidence](evidence/p06-files-path-2026-11-05/), frozen APK SHA `2247f5bafc2e359e5ebe6a7b5cfbf87ec98ed6691ecbcdae8c595b1e0604e37b`. Следующий affected image-only run готового main APK также **24/24 API35**, [отдельный artifact/SHA](p06-main-image-envelope-runtime.md); прежние63/54 suites не повторялись.

Даже успешные file tests с flag не доказывают default Emulator stability, radio/route/real SAF/provider UI или physical acceptance. Новый API35 matrix gate закрывается только по соответствующему evidence, не по самому факту boot.
