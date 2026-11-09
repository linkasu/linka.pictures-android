# Небольшие permissive offline-декодеры: Vorbis и AMR-NB

Дата проверки: 2026-11-09. Область: исходники и лицензии C/C++-декодеров;
не Apple API, не изменение приложения. Архитектурное решение остаётся за основной
сессией. Сборки, установка зависимостей и изменения исходников не выполнялись.

## Практический вывод

**Наименьший вариант без заведомого сужения Vorbis-совместимости — upstream
libvorbis 1.3.7 + libvorbisfile + libogg 1.3.6.** Это BSD-3-Clause, без требования
LGPL-relink. Не подключать `libvorbisenc`, примеры и инструменты. Обычный upstream
`libvorbis` всё же содержит часть analysis/encoding-кода: отдельного готового
decoder-only target в проверенном manifest нет. Не начинать собственный fork
ради удаления нескольких translation units. [V1–V4, O1–O3]

**Если потребуется именно программный AMR-NB fallback — opencore-amr 0.1.6,
только `amrnb`, encoder выключен, без `amrwb`.** Его umbrella LICENSE,
маленький wrapper и проверенные PacketVideo decoder-файлы — Apache-2.0, не
LGPL. Это значительно проще переносить, чем свежий Android Stagefright target,
но не обещание активного сопровождения или безопасности. [A1–A7]

**Не выбирать stb_vorbis только ради одного файла:** он меньше, но явно не
поддерживает floor 0 и chained streams, а upstream предупреждает о задержках
security-fix. Minivorbis — удобная упаковка, не новый маленький codec: содержит
старый libogg 1.3.4 и пропускает последующие upstream исправления, включая
allocation/UB fixes в 1.3.6. [S1–S2, M1–M3, O3]

Это **не полная замена FFmpeg само по себе**: ни один из этих codec-декодеров
не demux-ит `.3gp`. Дополнительная проверка minimp4 ниже подтверждает извлечение
raw samples независимо от codec, но stock MP4D недостаточно для определения
`samr` и строгого rejection внешних references. Минимальный кандидат для 3GPP —
узкое расширение существующего minimp4 parser + opencore, **не второй ручной
MP4 parser**, с обязательными проверками до допуска в приложение.

Нельзя оставлять 3GPP на предположении о native passthrough: локальное
`docs/redesign-audit/codec-preflight-summary.md:17–27` фиксирует настоящий
AMR-NB `.3gp`, у которого на проверенном iOS17 native reader видит **0 audio
tracks**. Это уже существующий результат другой сессии; Apple API здесь не
исследовались. До подтверждения bounded demux+decode на exact fixture удаление
FFmpeg не готово.

## Сравнение и происхождение

Размеры ниже — **текст исходников, не размер `.a`, IPA или RAM**. Загруженные
release-архивы анализировались в памяти, без распаковки в проект и без сборки.
Для GitHub зафиксирован SHA HEAD при проверке; лицензии прочитаны из самих
исходников. GitHub MCP в этой сессии не обнаружен; использовались только
публичные read-only исходники и `git ls-remote`, без аккаунтов и mutation.

| Кандидат / pin | Лицензия из первичного источника | Фактический масштаб / зависимости | Решение |
| --- | --- | --- | --- |
| stb_vorbis 1.22, stb `4e9b368bfc071e97206ea5c6adba68dd0cbf8897` | На выбор MIT **или** public-domain/Unlicense; выбрать MIT и сохранить notice | `stb_vorbis.c`: 192 790 байт, 5 584 строки без завершающей пустой; libc, math, по умолчанию malloc/alloca; внешнего libogg нет | Только при явно согласованном исключении старого/chained Vorbis и принятом security-профиле |
| libvorbis 1.3.7 + libvorbisfile | BSD-3-Clause, Xiph.org | Manifest: 20 core `.c` + `vorbisfile.c`, 19 внутренних `.h`: 595 764 байта; дополнительно публичные headers, libogg, libc/math | Предпочтительный совместимый Vorbis fallback |
| libogg 1.3.6 | BSD-3-Clause, Xiph.org | Два implementation `.c` + `crctable.h`: 121 439 байт; публичные headers/config дополнительно | Брать 1.3.6, не embedded 1.3.4 |
| minivorbis `4e40a167726effb0ae9d3065452a9ee2bb80a84f` | Текст BSD-3-Clause, Xiph.org + Eduardo Bart | `minivorbis.h`: 749 276 байт / 21 755 строк; amalgamation libogg 1.3.4 + libvorbis 1.3.7 + vorbisfile; libc/math | Меньше файлов, но не меньше сопровождения; не рекомендован новым vendoring |
| opencore-amr 0.1.6, `7dba8c32238418ce0b316a852b2224df586ca896` | Apache-2.0: umbrella + wrapper + проверенные PacketVideo файлы | Upstream decoder-only AMR-NB manifest: wrapper + 35 decoder + 56 common `.cpp` = 92 units, 1 022 963 байта без headers; OSCL заменён небольшими libc shims | Предпочтительный переносимый AMR-NB fallback, если он действительно нужен |
| AOSP Android 15 `android-15.0.0_r1`, AMR-NB module | PacketVideo source headers Apache-2.0; directory build metadata перечисляет Apache-2.0 **и BSD**, common — Apache-2.0 | Decoder + common, Android Soong/liblog; `darwin.enabled: false` в проверенных targets | Источник для сравнения fixes, не готовая Apple-библиотека |

Первичные manifests и лицензии: [S1, M1–M3, V1–V3, O1–O2, A1–A4, D1–D3].
AMR count включает upstream common set целиком, даже если отдельные функции
декодеру не нужны; это не обещание математически минимального набора.

Зафиксированные release-архивы:

| Архив | Байты | SHA-256 |
| --- | ---: | --- |
| `libvorbis-1.3.7.tar.gz` | 1 658 963 | `0e982409a9c3fc82ee06e08205b1355e5c6aa4c36bca58146ef399621b0ce5ab` |
| `libogg-1.3.6.tar.gz` | 604 469 | `83e6704730683d004d20e21b8f7f55dcb3383cdf84c0daedf30bde175f774638` |
| `opencore-amr-0.1.6.tar.gz` | 939 179 | `483eb4061088e2b34b358e47540b5d495a96cd468e361050fae615b1809dc4a1` |

Vorbis archive получен с Xiph release mirror OSUOSL после TLS-ошибки основного
адреса. Для воспроизводимости использовать pin + hash, не плавающий `master`.

## Vorbis: совместимость, состояние, память

### stb_vorbis

Из явного списка ограничений исходника: нет floor 0 (старые pre-2004 Vorbis),
нет concatenated/chained streams, игнорируется lossless sample-truncation в
начале; sample positions 32-битные. Последнее само по себе не мешает лимиту
600 секунд при 192 kHz: 115 200 000 samples/channel помещаются в 32 бита.
Skeleton также не следует считать поддержанным: history отмечает explicit
failure. Ogg/Opus — другой codec, а не поддерживаемый «любой OGG». [S1]

Один handle нельзя декодировать конкурентно; независимые handles допустимы.
Есть file/memory pull и push API. `open_memory` требует весь compressed input
живым до close. `decode_memory`/`decode_filename` распаковывают весь файл в
новый PCM allocation — **не использовать** для данного envelope. Вызывать
frame/block API, копировать transient output до следующего decode. Настроить
`STB_VORBIS_MAX_CHANNELS=8`, если вариант вообще принят. [S1]

По умолчанию setup uses malloc, frame temporaries — alloca. Указанные автором
«~150 KB на моих файлах» не являются upper bound. `stb_vorbis_alloc` позволяет
выделить arena для setup и temporary памяти; недостаточная arena возвращает
`VORBIS_outofmem`, но точный размер заранее неизвестен. Это полезная граница
allocation, **не доказательство memory safety** на атакующем input; количество
комментариев, codebooks, арифметика размеров и CPU всё равно требуют проверки
на конкретном pin. Размер одного Vorbis packet спецификацией не ограничен,
что прямо отмечено в push API. [S1]

Версия в заголовке всё ещё 1.22 с датой 2021-07-11, но это не дата каждого
последующего исправления. Нельзя оценивать актуальность по одной строке
версии. README репозитория прямо предупреждает: security-bugs обсуждаются
публично, исправления могут долго не сливаться; нет оснований объявлять
проверенный pin «безопасным, потому что маленький». [S1–S2]

### libvorbis/libogg и minivorbis

Upstream registry включает floor 0 и floor 1. `libvorbisfile` обрабатывает
chaining и возвращает logical-stream index из `ov_read`; **sample rate и
channels могут меняться между links**. Нельзя склеивать полученный PCM в WAV
с параметрами первого link без проверки/существующей нормализации. Лимит
600 секунд относится к сумме всех links, не каждому отдельно. [V2, V4]

`ov_read` принимает bounded output buffer, возвращает actual bytes, `0` для
EOF и отрицательные коды ошибок (`OV_HOLE`, `OV_EBADLINK`, `OV_EINVAL`).
Пример minivorbis, который заканчивает цикл на любом `bytes <= 0`, не является
готовой production-политикой: так corruption становится «успешной короткой
записью». Нужно сохранить текущую политику ошибок приложения и исключить
бесконечный retry/no-progress. `ov_clear` освобождает decoder buffers и обычно
закрывает datasource; ownership задаётся callbacks. [V4–V5, M1]

Состояние per-stream, не global decoder. Реальные heap allocations зависят от
headers/codebooks/comments и Ogg packet assembly; input 64 MiB **не означает**
RAM ≤64 MiB. У `libvorbisfile` нет публичного per-instance arena/quota API.
Ограничение готовых PCM chunks не ограничивает allocation при open/setup.
В source есть allocations без немедленной проверки результата, например
`mdct_init` в `mdct.c`; считать произвольную allocator failure штатной,
полностью обработанной ошибкой нельзя. Нужны malformed/fuzz/ASan-проверки,
а не обещание безопасного OOM по названию библиотеки. [V2, V6, O2]

Minivorbis не исправляет эти свойства. Его `gen.sh` просто конкатенирует
upstream sources, включая analysis/psy и другие части core; это не заново
написанный decoder-only engine. `gen.sh`/README зафиксированы на Ogg 1.3.4;
libogg 1.3.5 исправляет overflow check в `ogg_sync_buffer` и macOS typedef,
1.3.6 — UBsan/allocation failure handling. Поэтому stock header не выбран,
а обновление генератора стало бы собственным maintenance обязательством.
README перечисляет создание 02-Nov-2020; активное сопровождение по нему не
подтверждено. [M1–M3, O3]

## AMR-NB: точная граница decoder API

`Decoder_Interface_init/Decode/exit` opencore — минимальный portable C ABI.
`Decode` берёт **один frame с TOC byte**, извлекает `FT=(in[0]>>3)&15`,
пропускает TOC и вызывает `AMRDecode(..., MIME_IETF)`. Нет input length,
нет возврата ошибки из публичного `void Decode`, нет file/container parser.
Это не API для передачи whole `.amr`, `.3gp`, RTP packets или IF2. Нижний
`AMRDecode` умеет и IF2/ETSI-представления, но стандартный wrapper их не
выбирает. [A2, A5]

Минимальный безопасный adapter для обычного mono storage `.amr`:

1. Проверить `#!AMR\n`, не путать с `#!AMR-WB\n`, multichannel magic или
   произвольными данными с расширением `.amr`. [R1 §5]
2. До decode проверить TOC/padding, FT и наличие **всего** payload. Для FT
   0..7 payload sizes: `12,13,15,17,19,20,26,31`, для FT8 SID — 5 байт;
   FT15 NO_DATA — 0. В каждом случае TOC добавляет ещё один байт. FT9..14
   reserved в AMR-NB storage: не принимать только потому, что в таблице
   старого test tool есть ненулевые legacy entries. [R1, A6]
3. Отдельно определить совместимую политику Q=0/bad frames. Wrapper сам Q
   не проверяет; `bfi != 0` переводит FT в `AMR_NO_DATA`. Не выдавать
   concealment за проверенную успешную декодировку damaged input. [A2]
4. На каждый frame подготовить выход 160 signed 16-bit samples, 8 kHz mono,
   длительность 20 ms. 600 s — максимум 30 000 frame-blocks, с учётом SID и
   NO_DATA, а не только speech frames. Смена speech modes 4.75..12.2 kbps
   внутри файла нормальна. [R1 §3.1, §3.3–3.4]
5. На EOF посреди payload — ошибка, не тихий success, как в upstream demo.
   Проверять null init, release state на каждом выходе. [A2, A6]

AMR multichannel storage определён RFC: для него нужны разбор header,
отдельный state на канал и frame-block timing; wrapper сам не делает этого.
Глобальный лимит 8ch не даёт права молча урезать ранее поддерживаемые варианты
AMR. Подтвердить fixtures/действующий контракт перед выбором mono-only parser.
Поддержка bare AMR frames без magic тоже не должна появляться по эвристике.

У opencore состояние фильтров/DTX хранится в фиксированных structs одного
decoder; `GSMInitDecode` выделяет `Speech_Decode_FrameState`, вложенный
`Decoder_amr_init` инициализирует embedded state. Рабочая память не растёт
пропорционально длительности, output — 320 bytes/frame. Точный `sizeof`/stack
на Apple arm64 **не измерен**; headers/constants, фиксированная арифметика и
указатели всё равно не дают memory-safety гарантии. Особенно важна валидация
input size снаружи: wrapper физически не знает длину. [A2, A5, A7]

Для сопровождения важен именно 0.1.6: ChangeLog указывает исправление infinite
loop на части AMR-NB samples и noise spikes на non-voice frames. Проверенный
upstream master совпал с tag v0.1.6; этого недостаточно, чтобы обещать свежие
security backports. Не брать произвольную старую копию PacketVideo по названию.
[A1, A8]

## Дополнение: minimp4 как 3GPP → AMR-NB sample demux

### Итог feasibility

Проверен **lieff/minimp4**, commit
`2a3014a7753cfcba4442adeb469431967238fe6a` (HEAD на момент проверки),
`minimp4.h` **143 725 байт** в исходной CRLF-кодировке. LICENSE — полный
**CC0-1.0**, header содержит public-domain dedication. Зависимости — стандартные
C headers/libc; codec/Android runtime не нужны. C ABI позволяет собрать одну
implementation translation unit для Apple arm64; сборка не выполнялась.
В одном header есть и mux, и demux: наличие fMP4 в README относится к **mux**
и не доказывает поддержку fMP4 reader. [C1–C3]

**Да, stock MP4D может построить offsets/sizes для неизвестного codec. Нет,
stock API нельзя считать готовым безопасным AMR/3GPP adapter.** Код не требует
известного codec/DSI для `MP4D_open` и `MP4D_frame_offset`, но не предоставляет
достаточные сведения о codec/sample-description/data-reference. Это вывод из
actual source path, не проверка на fixture и не обещание совместимости со
всеми файлами с расширением `.3gp`. [C1]

### Что именно делает parser

| Часть | Фактическое поведение на pin | Следствие |
| --- | --- | --- |
| `stsd` | `case BOX_stsd` пропускает `entry_count` и спускается в child boxes | Сам count и полный список descriptions не возвращаются |
| `samr`, `damr` | Имена есть в enum, **нет соответствующих case/envelope entries** | Unknown `samr` целиком пропускается; AMR config не читается |
| `mp4a` | Есть handler базового AudioSampleEntry и envelope для вложенных boxes | Это не generic handler для любого AudioSampleEntry |
| Codec identity | `MP4D_track_t` имеет MPEG `object_type_indication`, но **нет sample-entry FourCC** | `samr` не превращается в AMR identity; `soun` без DSI не доказывает AMR |
| `stsz` | Fixed sample size и per-sample sizes поддержаны, sizes материализуются в array | Позволяет получить raw payload без codec parser; даже fixed-size table расходует O(samples) RAM |
| `stz2` | Есть 4/8/16-bit compact size handlers | Неподдержанные field sizes не считать валидными без дополнительной проверки |
| `stsc` | Читаются `first_chunk`, `samples_per_chunk`; **`sample_description_index` пропускается** | Нет корректной привязки chunk → codec/reference при multiple descriptions |
| `stco` / `co64` | Есть оба handlers и 64-bit offset type | Нужна повторная проверка каждого offset+size по actual input, не доверие таблицам |
| `stts` | Таблица разворачивается в per-sample timestamp/duration arrays | Проверять полноту и timescale; metadata duration не заменяет actual decoded time |
| `dinf/dref/url /urn ` | `dinf` — envelope; `dref` и entries не имеют demux validation handlers | Никакой гарантии self-contained; сам факт отсутствия network IO недостаточен для обязательного rejection |
| `moof/tfhd/trun` | `moof` **закомментирован** в demux envelope list, handlers построения fragment sample index отсутствуют | fMP4 demux не реализован; `MP4D_TFDT_SUPPORT` не исправляет это |
| `elst` | Есть имя box, нет применения edit-list timeline | Нельзя обещать сохранение delay/trim/presentation duration |

Source anchors: public track fields ~184–299; enum ~518–612;
envelopes ~2709–2737; `stsz/stsc/stts` ~2886–3035; `stco/co64` ~3065–3089;
`stsd/mp4a` ~3146–3178; unknown-skip ~3316–3388;
`MP4D_frame_offset` ~3489–3560. [C1]

Почему unknown codec не мешает raw sample path: `samr` пропускается, но
соседние `stsz/stsc/stco` внутри `stbl` всё равно заполняют track index.
`MP4D_open` в конце проверяет лишь наличие хотя бы одного track, а
`MP4D_frame_offset` использует эти arrays и не обращается к codec identity.
Payload нужно прочитать самостоятельно через тот же bounded local reader
по возвращённому диапазону. Нельзя угадывать AMR по байтам payload вместо
валидации `stsd`: так неизвестный или иной codec станет ложным AMR. [C1]

**Отдельная ловушка 64-bit configuration:** header объявляет
`MINIMP4_ALLOW_64BIT=1`, но decode extended-size/co64 branches проверяют
другое имя — `MP4D_64BIT_SUPPORTED`, не определённое в проверенном header.
Без external define zero high-word offsets поддерживаются, ненулевые high
words отвергаются. Для ≤64 MiB input такие offsets всё равно недопустимы;
сам `co64` с small offsets и extended-size header с high word 0 остаются
необходимыми positive fixtures. Не переносить флаг «supports >4GB» из header
в обещание о фактически проверенной reader-конфигурации. [C1 ~2777–2796,
3075–3087]

### Reader, allocation и отмена: stock safeguards недостаточны

- `MP4D_open` получает `file_size`, callback `(int64_t offset, void *buffer,
  size_t size, void *token)`; **0 означает success**, nonzero — failure.
  Parser читает header байтами через callback, skip лишь меняет `read_pos`.
  Callback обязан reject negative offset и проверять `offset <= input_size`,
  затем `size <= input_size - offset`, без overflow. Для sample read применить
  ту же проверку к unsigned offset до signed conversion. Никаких URL/файлов
  по container references не открывать. Immutable bounded snapshot не меняется.
- Запоминать в reader context **sticky failure/cancel**. `MP4D_open` может
  завершить цикл на EOF/callback failure и вернуть success, если track уже
  найден. Поэтому `open == 1` не является достаточным доказательством полного
  parse. Нужны fail-closed parse status/структурные проверки, а не превращение
  отмены или truncation в короткий успешный файл. [C1 ~2525–2580, 3390–3406]
- `MP4D_frame_offset` не валидирует вызывающие `ntrack`/`nsample` перед
  обращением к arrays. Caller должен гарантировать оба bounds, наличие
  согласованных tables, nonzero valid sample size, range внутри input; offset
  0 отдельно не является надёжным error sentinel. В pin часть плохих indices
  возвращает `frame_bytes=0`, а отсутствующие timestamps превращаются в 0 —
  это не полноценная валидация всего track. [C1 ~3489–3560]
- На этом pin есть hardening fixes от fuzzing: guard для missing tables,
  widened allocation arithmetic, sequential lookup caches, ограничение
  **256 MiB на отдельную table allocation**. Это не total/per-job quota и
  даже больше допустимого input; `track` растёт через обычный `realloc`.
  В worst case compressed metadata может запросить большой heap до sample
  decode. В `stts` есть inline loops, где cancel callback не означает
  немедленного выхода. Input cap сам по себе не ограничивает CPU/RAM.
- Публичного allocator/token hook нет: используются прямые `malloc/realloc/
  free`, внутренний `minimp4_bounded_malloc`, hardcoded enum ceiling.
  Не называть `MALLOC` публичным allocator API. Для строгих total allocations
  нужна локальная vendor-настройка allocator calls/контекста или другой
  подтверждённый механизм. Проверять counts против payload **до** allocation,
  общую квоту и cancel в длинных parser loops.
- OOM cleanup не считать доказанным: `stts` напрямую присваивает результаты
  двух `realloc` в старые pointers до проверки обоих; при реальном allocation
  failure старый блок соответствующего pointer может потеряться. Нужны
  сохранение old pointers при failure и fault-injection test, если pin будет
  интегрирован. `MP4D_close` освобождает сохранённые indices; после успешного
  open он обязателен на success/error/cancel, как и AMR decoder exit. [C1]

### Наименьший безопасный integration shape (план, не реализация)

**Не писать свой MP4 box walker.** Использовать существующие index/sample
API minimp4; недостающую семантику добавить в его **единственный существующий
parser**, с зафиксированным diff и лицензией:

1. На уровне `stsd` сохранить FourCC, description count и BaseSampleEntry
   `data_reference_index`; распознать `samr`, разобрать обязательный `damr`
   (`frames_per_sample`, mode config). Сохранить `stsc.sample_description_index`
   или явно согласовать поддержку единственного description; не отбрасывать
   произвольные previously-supported cases молча.
2. В том же parser валидировать `dref` и self-contained URL flag/reference
   mapping; внешние URL/URN и unresolved references **отвергать**, а не
   просто не открывать. Сохранить `mdat` ranges и подтвердить, что samples
   целиком находятся в local media data, не лишь где-то внутри файла.
3. Разделить normal EOF, malformed box и callback failure/cancel; применить
   budget/fail-closed guards до allocations/loops. Проверить consistency
   sample-count/chunk-count/`stts`, monotone `stsc`, ranges и арифметику.
   Не использовать наличие track как admission criterion.
4. Для выбранного подтверждённого `samr` читать samples последовательно
   через `MP4D_frame_offset`; **один MP4 sample не обязательно один AMR
   frame**. TS 26.244 §6.1 задаёт single-channel AMR storage bytes **без magic**;
   §6.7 `damr.frames_per_sample` допускает 1..15 frames, последний sample
   может содержать меньше. Внутри sample идти по TOC+payload с уже описанной
   AMR-NB FT/size/Q валидацией, каждый frame отдавать opencore отдельно;
   не добавлять/искать `#!AMR\n` внутри samples. [C4, R1, A2]
5. 15×32 = **480 байт** — максимум полного sample из 15 обычных AMR-NB
   frames максимального размера в таком профиле. Это позволяет использовать
   маленький buffer после проверки `damr`, а не выделять file-declared sample
   size. Учитывать фактическое число frames, `stts` и timescale; один frame =
   160 PCM samples/20 ms. Суммарный лимит 600 s сохраняется, независимо от
   sample aggregation. Отдельно сохранить всю описанную выше cleanup/publish
   дисциплину и immutable original.
6. fMP4, encryption/unknown description, edit lists и смена descriptions
   нельзя объявлять поддержанными этим path. Если текущий контракт включает
   их, minimp4 **не полная замена** без дополнительной работы/другого demuxer;
   простое rejection допустимо лишь в явно согласованном acceptance profile.
   Это исследование не добавляет поддержку и не сужает контракт.

Итого: **raw sample extraction годится как основа; drop-in без vendor patch
не годится**. Расширить minimp4 в этих конкретных местах меньше и безопаснее,
чем иметь второй произвольный parser рядом, но назвать изменение «один header
и пара вызовов» было бы неверно. Выбор этого maintenance tradeoff остаётся
за основной сессией.

### Проверка, которая должна закрыть feasibility, прежде чем удалить FFmpeg

- Уже известный exact `.3gp`: 962 bytes, SHA-256
  `f81c04f29cffcba93be208c36cfc0e2df9196c3fb2e097d5e7234a17ed72426c` из
  `docs/redesign-audit/codec-preflight-summary.md`. Требуемый результат
  demux+opencore: 11 AMR frames, **1760 finite PCM samples / 0.22 s**,
  согласованная амплитуда с paired raw AMR и unchanged original hash.
  Fixtures в данной сессии не читались и не декодировались.
- Positive: `stsz` fixed/per-sample, multi-chunk `stsc`, `stco` и small-offset
  `co64`, moov до/после mdat, extended-size boxes, `frames_per_sample=1` и
  aggregation с shorter last sample; корректный `samr` рядом с non-audio track.
- Negative: unknown/sawb/masquerading audio, missing/duplicate/multiple
  descriptions по согласованному профилю, bad reference index, external URL/
  URN (даже если bogus offsets попадают внутрь input), sample вне mdat/EOF,
  inconsistent tables, absurd counts, corrupted nested box sizes, fMP4 и
  edit-list cases по явно заданной политике.
- Fault-injected allocation failure, cancel на parse/sample/decode/write,
  no-progress/CPU bounds и malformed corpus под sanitizers; отсутствие
  leaked state/partial files. Source inspection не заменяет эти проверки.

## Apple arm64: что следует из C/C++ sources, а что не проверено

- **libogg/libvorbis:** portable C, libc/math; libogg имеет Apple integer-type
  branch. В libvorbis `os.h` x86 asm/SSE ветки охраняются architecture macros,
  для arm64 есть generic C path. Использовать стандартные `ogg`, `vorbis`,
  `vorbisfile` targets, корректный generated config/header, не `vorbisenc`.
  Это source-level обоснование переносимости, не результат Xcode/iOS build.
  [O2, V3, V7]
- **stb:** один C translation unit, публичный `extern "C"`, стандартный
  scalar decoder; никаких обязательных Android/runtime dependencies. Собирать
  реализацию один раз, не копировать её в каждый bridging translation unit.
  Упомянутые compatibility/security причины остаются независимо от CPU. [S1]
- **opencore:** upstream по умолчанию компилирует `.cpp` как C99 (`-x c`),
  специально избегая libstdc++; можно и как C++. Взять upstream OSCL shims,
  decoder/common source lists, `DISABLE_AMRNB_ENCODER`, не тянуть Android
  framework. **Не включать** `--enable-gcc-armv5`: это 32-bit ARM asm, не
  AArch64. Generic `basic_op_c_equivalent.h` выбирается без ARMv5 defines.
  `configure` создаёт оба NB/WB subdirs; «encoder disabled» не равно «WB не
  собирается» — final build target должен быть только AMR-NB. [A3–A4, A9]
- **Современный AOSP:** Android 15 decoder/common targets explicitly отключены
  на Darwin и decoder имеет liblog dependency. Это не доказательство, что DSP
  невозможно перенести, но готового drop-in Apple build нет. Не путать старый
  `external/opencore` с актуальной копией `frameworks/av/media/module/codecs`.
  Проверенная AOSP metadata не даёт права назвать весь выбранный directory
  «только Apache»: есть BSD license-kind, нужен file-level inventory выбранного
  набора. [D1–D3]

## Границы всего решения, которые нельзя потерять

1. Original input остаётся immutable. Decoder читает bounded snapshot/local
   datasource; вывод — отдельный temporary artifact, publish только после
   полного success. Ошибка/cancel удаляет partial output и закрывает handle.
2. Проверить compressed bytes ≤64 MiB **до** allocation/copy; channels 1..8,
   sample rate 1..192 000; не только metadata duration, но actual decoded
   frames/time ≤600 s. Считать длительность across chained streams и AMR DTX.
3. Не декодировать весь разрешённый PCM в RAM: 600 s ×192 kHz ×8ch×2 bytes =
   **1 843 200 000 bytes** только S16, float вдвое больше. Bounded chunks,
   счётчики с проверкой переполнения, лимит output/temp storage нужны отдельно.
4. Проверять cancel между decode units и перед publish, закрывать state/input/
   temp на всех выходах. У рассмотренных APIs нет встроенного interrupt
   callback; cancel не прерывает уже исполняющийся C decode/open. Нужны
   no-progress guard и проверка worst-case latency на corrupt input.
5. В byte-only Vorbis/AMR decoder нет URL/network callbacks. Но запрет
   non-self-contained 3GPP references должен обеспечивать **container path**
   до чтения media data; codec этим не занимается. Нельзя подменить demux
   простым поиском AMR sync/TOC внутри `.3gp`.
6. Vorbis PCM channel order не совпадает автоматически с произвольной WAV/
   platform layout: например 5.1 — L,C,R,RL,RR,LFE. Rate, interleaving,
   signedness, endian, frame-count и channel mapping проверяются явно. [V4]

## Самая маленькая достаточная проверка перед заменой

- Ворота сборки: Apple device arm64 и Simulator arm64, static link выбранных
  targets, symbol/license inventory; подтвердить отсутствие encoder/WB там,
  где они не нужны. Binary/RAM цифр до сборки не обещать.
- Vorbis: mono/stereo/8ch; 192 kHz; short/long blocks; floor0; chained streams
  с одинаковыми и разными parameters; start/end granule trimming; truncated
  headers/pages, corrupt CRC, large comments/codebooks, zero/no-progress.
- AMR-NB: все 8 modes, mode-switching, SID/DTX/NO_DATA, Q=0, reserved FT,
  truncated payload; raw storage и AMR внутри self-contained `.3gp` должны
  дать согласованные duration/rate/output на соответствующем input.
- Для тона проверять decoded duration, частоту, RMS/пиковую амплитуду и
  отсутствие spikes, а не равенство compressed data или float PCM бит-в-бит.
  AMR — speech codec 8 kHz: тон вне его полосы/Nyquist не годится для проверки
  «сохраняет исходный звук»; DTX comfort noise не обязан быть цифровым нулём.
  Сравнить с текущим trusted decoder на фиксированном fixture corpus.
- Envelope: 64 MiB/600s/8ch/192k boundaries и превышения; суммарный chained
  duration; cancel во время open/decode/write; отсутствие partial artifacts и
  неизменность hash original; external references rejected offline.
- ASan/UBSan malformed corpus на точном pin; не считать permissive license
  или отсутствие heap на frame доказательством безопасности.

### Не добавлять сейчас

`vo-amrwbenc` — AMR-WB **encoder**, не AMR-NB decoder, поэтому задачи не
решает. MP3 fallback пока не нужен без доказанного native gap. Если такой gap
найдётся: `minimp3.h` у `ea99364f61c14656440e8d77e9c233ccf3124633`,
76 831 байт, CC0-1.0, frame API с caller-owned state/output, до 2 channels,
есть AArch64/NEON branch. Не брать whole-file `minimp3_ex` helper ради
минимального fallback; отдельно проверять MP3 reservoir/truncated-frame и
gapless metadata/duration. Это запасной кандидат, не рекомендация менять
MP3 path сейчас. [P1–P2]

## Лицензионная граница

MIT/BSD/Apache source licenses допускают permissive integration без
LGPL-style relink материалов **для этих компонентов**. Всё равно приложить
соответствующие license/copyright notices; для Apache соблюдать §4, включая
notices об изменениях и upstream NOTICE, если он входит в выбранный набор.
Minivorbis сохраняет также copyright автора amalgamation. Не обещать, что
удаление одного decoder автоматически устраняет обязательства от оставшихся
FFmpeg binaries/других dependencies. [S1, M3, V1, O1, A1]

Это не заключение о патентах, праве собственности или отсутствии прав третьих
лиц. В PacketVideo headers прямо сохранён 3GPP attribution и statement о
разрешении применять указанные условия; это наблюдение об исходнике, а не
независимая проверка chain of title. Apache §3 ограничен claims contributors;
CC0 §4(a) прямо не передаёт patent/trademark rights. Никаких гарантий
«AMR royalty-free во всех странах» из этого исследования не следует.

## Первичные источники (pins)

- **S1:** [stb_vorbis.c, pin 4e9b368](https://github.com/nothings/stb/blob/4e9b368bfc071e97206ea5c6adba68dd0cbf8897/stb_vorbis.c): limitations, memory/thread API, macros, source-end dual license.
- **S2:** [stb README, тот же pin](https://github.com/nothings/stb/blob/4e9b368bfc071e97206ea5c6adba68dd0cbf8897/README.md): security maintenance warning.
- **V1:** [libvorbis v1.3.7 COPYING](https://github.com/xiph/vorbis/blob/v1.3.7/COPYING).
- **V2:** [libvorbis v1.3.7 lib/Makefile.am](https://github.com/xiph/vorbis/blob/v1.3.7/lib/Makefile.am); [registry.c](https://github.com/xiph/vorbis/blob/v1.3.7/lib/registry.c).
- **V3:** [libvorbis v1.3.7 lib/CMakeLists.txt](https://github.com/xiph/vorbis/blob/v1.3.7/lib/CMakeLists.txt).
- **V4:** [v1.3.7 ov_read documentation](https://github.com/xiph/vorbis/blob/v1.3.7/doc/vorbisfile/ov_read.html): errors, chaining, PCM ordering.
- **V5:** [v1.3.7 ov_clear documentation](https://github.com/xiph/vorbis/blob/v1.3.7/doc/vorbisfile/ov_clear.html).
- **V6:** [v1.3.7 mdct.c](https://github.com/xiph/vorbis/blob/v1.3.7/lib/mdct.c); [vorbisfile.c](https://github.com/xiph/vorbis/blob/v1.3.7/lib/vorbisfile.c).
- **V7:** [v1.3.7 os.h](https://github.com/xiph/vorbis/blob/v1.3.7/lib/os.h).
- **O1:** [libogg v1.3.6 COPYING](https://github.com/xiph/ogg/blob/v1.3.6/COPYING).
- **O2:** [libogg v1.3.6 framing.c](https://github.com/xiph/ogg/blob/v1.3.6/src/framing.c); [os_types.h](https://github.com/xiph/ogg/blob/v1.3.6/include/ogg/os_types.h).
- **O3:** [libogg v1.3.6 CHANGES](https://github.com/xiph/ogg/blob/v1.3.6/CHANGES).
- **M1:** [minivorbis README, pin 4e40a16](https://github.com/edubart/minivorbis/blob/4e40a167726effb0ae9d3065452a9ee2bb80a84f/README.md).
- **M2:** [minivorbis gen.sh, тот же pin](https://github.com/edubart/minivorbis/blob/4e40a167726effb0ae9d3065452a9ee2bb80a84f/gen.sh); [minivorbis.h](https://github.com/edubart/minivorbis/blob/4e40a167726effb0ae9d3065452a9ee2bb80a84f/minivorbis.h).
- **M3:** [minivorbis LICENSE.txt, тот же pin](https://github.com/edubart/minivorbis/blob/4e40a167726effb0ae9d3065452a9ee2bb80a84f/LICENSE.txt).
- **A1:** [opencore-amr 7dba8c tree / README](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/); [LICENSE](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/LICENSE).
- **A2:** [amrnb/wrapper.cpp, тот же pin](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/amrnb/wrapper.cpp).
- **A3:** [amrnb/Makefile.am](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/amrnb/Makefile.am).
- **A4:** [configure.ac](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/configure.ac).
- **A5:** [amrdecode.cpp](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/opencore/codecs_v2/audio/gsm_amr/amr_nb/dec/src/amrdecode.cpp).
- **A6:** [test/amrnb-dec.c](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/test/amrnb-dec.c).
- **A7:** [sp_dec.cpp](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/opencore/codecs_v2/audio/gsm_amr/amr_nb/dec/src/sp_dec.cpp); [dec_amr.cpp](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/opencore/codecs_v2/audio/gsm_amr/amr_nb/dec/src/dec_amr.cpp).
- **A8:** [ChangeLog](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/ChangeLog).
- **A9:** [basic_op.h](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/opencore/codecs_v2/audio/gsm_amr/amr_nb/common/include/basic_op.h); [oscl_mem.h](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/oscl/oscl_mem.h); [oscl_base.h](https://sourceforge.net/p/opencore-amr/code/ci/7dba8c32238418ce0b316a852b2224df586ca896/tree/oscl/oscl_base.h).
- **D1:** [AOSP android-15.0.0_r1 AMR-NB decoder Android.bp](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/media/module/codecs/amrnb/dec/Android.bp).
- **D2:** [AOSP тот же tag, common Android.bp](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/media/module/codecs/amrnb/common/Android.bp).
- **D3:** [AOSP тот же tag, sp_dec.cpp](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/media/module/codecs/amrnb/dec/src/sp_dec.cpp); [decoder NOTICE](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/media/module/codecs/amrnb/dec/NOTICE).
- **R1:** [RFC 4867, §3–5](https://www.rfc-editor.org/rfc/rfc4867.html): неизменяемая опубликованная спецификация AMR frame/storage framing.
- **P1:** [minimp3 pin ea99364, LICENSE](https://github.com/lieff/minimp3/blob/ea99364f61c14656440e8d77e9c233ccf3124633/LICENSE).
- **P2:** [minimp3.h, тот же pin](https://github.com/lieff/minimp3/blob/ea99364f61c14656440e8d77e9c233ccf3124633/minimp3.h).
- **C1:** [minimp4.h, pin 2a3014a](https://github.com/lieff/minimp4/blob/2a3014a7753cfcba4442adeb469431967238fe6a/minimp4.h): actual MP4D structures, parser, indexing, allocation/cleanup paths.
- **C2:** [minimp4 LICENSE, тот же pin](https://github.com/lieff/minimp4/blob/2a3014a7753cfcba4442adeb469431967238fe6a/LICENSE): CC0-1.0, включая отсутствие patent/trademark grant в §4(a).
- **C3:** [minimp4 README, тот же pin](https://github.com/lieff/minimp4/blob/2a3014a7753cfcba4442adeb469431967238fe6a/README.md): fragmented режим описан в разделе Muxing.
- **C4:** [ETSI TS 126 244 V15.0.0 / 3GPP TS 26.244 Release 15, 2018-07](https://www.etsi.org/deliver/etsi_ts/126200_126299/126244/15.00.00_60/ts_126244v150000p.pdf), §§6.1, 6.5, 6.7: `samr`, storage framing без magic, `damr`, 1..15 frames/sample и короткий последний sample. PDF прочитан через уже установленный `pdftotext`, без установки инструментов и сохранения дополнительных файлов в repo.
- Release archives: [Xiph Vorbis](https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.gz), [OSU mirror](https://ftp.osuosl.org/pub/xiph/releases/vorbis/libvorbis-1.3.7.tar.gz), [Xiph Ogg](https://downloads.xiph.org/releases/ogg/libogg-1.3.6.tar.gz), [opencore-amr](https://downloads.sourceforge.net/project/opencore-amr/opencore-amr/opencore-amr-0.1.6.tar.gz).

Не проверено: сборка/размер бинарника/real-device RAM, fuzz campaign, полная
совместимость с fixtures приложения и полный юридический inventory каждого
vendored файла. Главный оставшийся функциональный риск — потеря 3GPP demux
или редких Vorbis variants при «упрощённой» замене.
