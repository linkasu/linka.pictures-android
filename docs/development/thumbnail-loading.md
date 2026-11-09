# R2: leased native thumbnails

Common `ThumbnailLoader(media, decode, scope)` содержит текущий root-owned набор
превью, не канонические медиа/провайдерские URI. Потребность каждого окна регистрируется
`request(owner, imageIds)` и удаляется `remove(owner)`. Native root передаёт только
активные UI assets, не всю библиотеку; visible-demand P10/P11 integration отдельно.

Одна collectLatest worker-операция удерживает media lease до окончания декодирования;
смена demand/окна отменяет старую и закрывает lease. После native decode cancellation
проверяется до публикации; старые pixels не подменяют новую страницу. Decode/read errors
публикуют structured problem, не фиктивный Loaded. Битмапы borrowed UI, без file/SQL в
composables. Только ARGB8888 и реальные dimensions в пределах вычисленной стороны.

Предел — 32 MiB текущих 32-bit preview pixels: максимум256px, при большом current-page+
message наборе сторона уменьшается (8192 IDs→32px). Это **не** обещание process RSS,
немедленного GC старых кадров или физической калибровки; они обязательны до G6.

Android decoder использует реальный inspector до pixel allocation; ImageDecoder28+
с bounded target/SRGB/software либо BitmapFactory sample+штатный ExifInterface на21.
JPEG orientation применяется только к preview; source не переписывается/не нормализуется.
Native handles/непереданный Bitmap освобождаются на ошибке/отмене. iOS decoder
подключается из отдельно reviewed product-root lane, не вторым cache.

Проверки: **3/3 JVM loader policy/lease/cancel tests** и **15/15 affected Access tests**,
оба Native main compile PASS (`r2-root-fail-closed-thumbnail-check.log`,1m10s).
Native Android **4/4 на API21 и4/4 API35 flagged**:2 thumbnails (actual bounded PNG pixels,
JPEG EXIF, original SHA),2 owner-verifier control-flow tests с новым owner-specific
foreground callback. Без permissions, microphone, OS-auth UI или normal package install.

[Frozen artifact/manifests/source hashes/full summaries](evidence/r2-thumbnails-2026-11-05/).
APK SHA `fc6c36dd281711f5befd0cb28c4bf6c73d05ca9f0c8f5d5dce1de10950bcb75b`.
Это не renderer screenshots, physical accessibility, positive TTS/OS proof или G4/G6.
