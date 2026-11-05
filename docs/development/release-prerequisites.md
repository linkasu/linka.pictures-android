# R0: release prerequisites — Android / iOS

Срез **2026-11-05**. Это ledger внешних prerequisites, а не разрешение на выпуск,
юридическое заключение или утверждение, что R0/R1 закрыты. Назначение конкретных
ответственных, security/legal/product решения остаются за primary и владельцем продукта.
Локально изменяемый код и зелёная сборка не заменяют runtime/store/legal gates.

## CI: явно разрешаемые действия

`.github/workflows/android.yml` сохраняет build на push/PR в main и ручном запуске:
workflow selfcheck, `lint test verifyReleasePrivacy`, debug APK и androidTest APK.
`permissions: contents: read` достаточно для checkout; upload/download artifacts
используют штатный Actions artifact service, дополнительных repository write scopes нет.

| Событие / inputs | Build | Firebase | Signed AAB |
|---|---|---|---|
| Push / PR main | да | нет | нет |
| Manual, defaults false/false | да | нет | нет |
| Manual, cloud=true, sign=false | да | после build PASS | нет |
| Manual, cloud=false, sign=true | да, summary BLOCKED | нет | **нет** |
| Manual, оба true, ref не main | да | после build PASS | **нет** |
| Manual, оба true, main | да | после build PASS | только build и Firebase PASS |
| Build/Firebase failed, cancelled или skipped | не обходится | без успешного build не запускается | **нет** |

`run_cloud_tests` — отдельное разрешение на потенциально платный Firebase Test Lab.
`sign_release` не включает его автоматически. Заблокированный/skipped bundle job
не означает completed release, даже если workflow в целом зелёный. Конкурирующий
запуск на том же ref по существующему concurrency policy может отменить текущий.
Подпись создаёт только artifact, **не публикует** приложение. Собственный
`${GITHUB_WORKSPACE}/ci-release.jks` удаляется отдельным `always()` step; принудительная
потеря runner не даёт гарантии исполнения cleanup (используется ephemeral hosted runner).

Воспроизведение без credentials/сети/облака:

```sh
python3 -B scripts/check-release-workflow.py
```

Selfcheck читает настоящий workflow, проверяет его ограниченную структуру и actual
job expressions для 384 сочетаний event/ref/boolean inputs/dependency results.
Это **offline regression check**, не YAML/GitHub expression engine и не доказательство
реального Actions run. GitHub validation/runtime остаётся отдельным gate после разрешения.
Существующие secret **имена**, не значения: `PROJECT_ID`, `FIREBASE_KEY`,
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. Наличие/доступ/валидность здесь не проверялись.

Локальное evidence этой правки: RED на отсутствующем `workflow_dispatch` до изменения;
GREEN 384 combinations после. Дополнительно Python compile и системный Ruby Psych
YAML parse PASS; шесть in-memory mutations (automatic cloud, wrong ref, no cloud
approval, failed cloud/build allowed, unsafe default) отклонены selfcheck.

## Действующие требования магазинов (официальные источники)

- **Google Play target API:** для новых обычных phone/tablet apps и обновлений
  **Android 16 / API 36+ с 2026-08-31**. API35 для уже опубликованного приложения —
  иной порог discoverability, не разрешение отправить update с API35.
  Указанное Google продление до **2026-11-01 уже истекло** на дату среза;
  наличие индивидуального exception не предполагается.
  [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en).
- **Android native ABI:** каждой поставляемой 32-bit архитектуре соответствует
  64-bit: `armeabi-v7a` → `arm64-v8a`, `x86` → `x86_64`. Не требуется добавлять
  все ABI, но нельзя считать ARMv7-only native bundle достаточным для Play.
  Проверяется фактический release AAB/APK и все native зависимости, не только abiFilters.
  [64-bit requirement](https://developer.android.com/google/play/requirements/64-bit).
- **16 KB:** новые apps и updates, направленные на Android15/API35+ устройства,
  должны поддерживать 16 KB pages на 64-bit устройствах с **2025-11-01**.
  Нужны ELF/packaging checks и запуск в 16 KB окружении; один успешный arm64 build
  этого не доказывает.
  [Android page sizes](https://developer.android.com/guide/practices/page-sizes).
- **App Store Connect:** с **2026-04-28 Xcode26+ и iOS/iPadOS26 SDK+** для upload;
  с **2026-09-09 deployment target iOS13+**. SDK minimum и deployment minimum —
  разные требования; существующий project minimum iOS17 нельзя механически поднять до26.
  [Apple enforced requirements](https://developer.apple.com/news/upcoming-requirements/).
- **Apple privacy:** с **2024-05-01** требуются approved reasons для listed
  required-reason APIs, в том числе внутри dependencies. Нужен bundled
  `PrivacyInfo.xcprivacy` с правдивыми API/data/tracking declarations, сверка с
  actual archive и App Privacy answers. Для SDK из официального списка обязательны
  manifests, а для соответствующих binary dependencies — signatures.
  [Manifest specification](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files),
  [SDK requirements](https://developer.apple.com/support/third-party-SDK-requirements/),
  [App Privacy details](https://developer.apple.com/app-store/app-privacy-details/).

Все перечисленные даты — уже действующие cutoff, не новые deadlines проекта.
Будущие SDK/target-policy версии не выдумываются; release owner повторно сверяет
живые официальные страницы непосредственно перед submission.

## FFmpeg: точный development baseline, shipping OPEN

Текущая интеграция — **FFmpeg8.1.2**, официальный source archive SHA256
`464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`, проверенный
PGP fingerprint `FCF986EA15E6E293A5644F10B4322F04D67658D8`.
Это не утверждение «последняя версия». Development approval не означает shipping approval.
Источник доказательств: [current bridge](ios-audio-decoder-bridge.md#build-correspondence--compile-boundary),
[original preflight/config](ios-ffmpeg-preflight.md#exact-assembly--toolchain),
producer `scripts/build-ios-audio-decoder.py` (FLAGS derived from preflight).

Текущий LGPL-only config: `--disable-autodetect --disable-everything --disable-gpl
--disable-nonfree --disable-version3 --disable-network`, static libraries,
`--disable-shared`, no programs/CLI, avfilter/filters/encoders/muxers removed from
preflight configuration, `--disable-avfilter --disable-pthreads --disable-w32threads
--disable-os2threads`. Остальные ограничения preflight (devices/hwaccels,
AudioToolbox/VideoToolbox/SecureTransport, external libraries) сохраняются.
Включены только demuxers `ogg,amr,mov,wav,mp3`, decoders
`vorbis,amrnb,pcm_s16le,mp3`, protocol `file`, swresample; линкуются
avformat/avcodec/avutil/swresample. GPL/NONFREE/VERSION3/NETWORK и внешние
LIBVORBIS/LIBMP3LAME/LIBX264 выключены. **AAC decoder не включён**; AAC приёмку
нельзя закрыть успешным AMR-in-3GP тестом или этим baseline.

Qualified legal/shipping owner должен письменно закрыть exact corresponding source
и build recipe delivery, copyright/license notices (включая UI/distribution),
static linking/relink/replacement materials, EULA/reverse-engineering terms,
patent obligations для выбранных codecs/юрисдикций и App Store distribution compatibility.
«LGPL-only» не разрешает автоматически статическую поставку и не решает патенты.
Официальный [FFmpeg legal checklist](https://ffmpeg.org/legal.html) сам не является legal advice.

## Ownership / permission / evidence ledger

Все OPEN/BLOCKED строки требуют именованного ответственного; роли ниже — routing,
не назначение реального человека. Неизвестные store IDs/versionCode не угадываются.

| Gate | Owner / status | Нужное разрешение | Шаги воспроизведения / evidence для закрытия |
|---|---|---|---|
| Offline CI policy | CI maintainer / local check available | нет внешних прав | Команда selfcheck выше; diff review и YAML parse; не runtime proof |
| Hosted Actions / artifacts | CI maintainer / NOT EXECUTED | Разрешить push и обычный Actions run | Проверить push/PR и manual defaults: build PASS, Firebase/bundle skipped; сохранить run URL и job conclusions |
| Firebase / billing | Cloud account owner / OPEN | Явно разрешить project, credentials и расходы | Manual cloud=true, sign=false; обе existing instrumentation/Robo команды PASS; сохранить run/result IDs без secrets |
| Android signing | Release custodian / OPEN | Отдельное разрешение использовать существующий release key | Main, оба флага true, успешные build+cloud; проверить AAB signature/identity и cleanup; не upload в магазин |
| Android store policy | Android release owner / OPEN | Store access только после разрешения | Проверить target в release manifest, ABI inventory, 16 KB ELF/zip/runtime evidence и Play validation; подтвердить actual package/versionCode из store history |
| ARMv7 physical / AAC | Android device + audio owner / OPEN | Авторизованный physical device и scoped test launch; любые нужные runtime permissions отдельно | Реальное выполнение согласованной ARMv7/audio fixture matrix с AAC; записать device/API/ABI и decoded duration/output; emulator/AMR proof не заменяют |
| iOS Keychain2 | iOS signing + security owner / BLOCKED | **Сначала** разрешение на legitimate signed Xcode test-host, app identity/profiles/credentials | Выполнить оба настоящих app-host теста по [Keychain host report](ios-keychain-host.md); сохранить 2/2 runtime evidence и exact entitlements; core/compile/ad-hoc AMFI failure не PASS |
| iOS archive/privacy | iOS release + privacy owner / OPEN | Разрешение на signing/archive, затем отдельно store access/upload | Штатный archive, SDK/deployment metadata, actual bundled privacy manifest/report, dependency inventory, review approved reasons и App Privacy answers |
| FFmpeg shipping/legal | Qualified legal owner + release owner / OPEN | Продуктовое решение о поставке после legal review | Сверить exact source/config/libs с archive; обеспечить source/notices/relink/EULA/patent/AppStore checklist и записать approval scope |

В этой lane не выполнялись cloud, signing, install, credential lookup, store access,
physical runtime или Keychain host execution. Root composition/access/security и R1
ведёт primary отдельно; этот документ не подменяет его acceptance evidence.
