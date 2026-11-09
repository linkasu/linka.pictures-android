# iOS: минимальная замена FFmpeg для локального воспроизведения

## Итоговое решение для 2.0.0

Оставить `AVAudioPlayer` единственным проигрывателем. WAV PCM16 и MP3 проверяются
ограниченным CoreAudio reader и проигрываются из исходного локального файла;
никакого временного WAV для них не создаётся. Для OGG/Vorbis, AMR-NB и 3GPP
сохраняется уже проверенный bounded FFmpeg8.1.2 fallback, вынесенный в отдельный
динамический `LinkaFFmpeg.framework`. Формат оригинала не меняется.

Выбор основан не только на документации: минимальный iOS17 уже дал FAIL для
OGG/AMR и 0 audio tracks для AMR/3GP в реальном preflight
(`docs/redesign-audit/codec-preflight-summary.md`). Новый набор libvorbis/opencore
и MP4 demux требует дополнительных parser/allocation/dref/fMP4-проверок; это не
меньшее изменение перед выпуском. См. соседнее исследование декодеров.

Static application-object relink-пакет отменён; пользовательский rebuild касается
только совместимого dynamic runtime из точных исходников, без объектов приложения
и оригинальных signing keys. Юридические/патентные гарантии этим не заявляются.
Результаты runtime/build/privacy-проверок фиксируются в release ledger отдельно
от документационного исследования ниже.

Дата: 2026-11-09. Минимальная ОС: **iOS 17.0**, не 17.4. Проверены публичные документы Apple, спецификации IETF/3GPP и комментарии установленного **iPhoneSimulator27.2.sdk**. Это исследование, **не результат запуска декодеров**. Приложение, сборки, signing, аккаунты и зависимости не изменялись.

## Решение

**Для обычного проигрывания файла оставить `AVAudioPlayer(contentsOf:)`. Не заменять FFmpeg собственным универсальным медиадвижком.** WAV с PCM и MP3 — нормальный нативный путь. Для действительно необходимых несовместимых файлов — один ограниченный путь получения PCM/временного WAV, после которого работает тот же проигрыватель. Не перекодировать заведомо поддерживаемые файлы перед каждым воспроизведением. Apple описывает `AVAudioPlayer` именно как объект для проигрывания файла/буфера; потоковое воспроизведение и сложный граф здесь не нужны [1].

**Полностью нативную поддержку всех пяти входов на всей линейке iOS 17+ обещать нельзя.** В частности:

- Apple объявила поддержку **кодека Vorbis в Safari 17.4**, а **контейнера Ogg с Vorbis/Opus — только в Safari/iOS 18.4**. Это две разные возможности; заявления о Safari не являются гарантией тех же комбинаций в `AVAudioPlayer`, `AVAudioFile` или AudioToolbox [6, 7].
- Для AMR-NB нельзя выводить наличие декодера из `kAudioFormatAMR`, `.amr`/`.3gp`, MIME, успешного импорта трека или старой таблицы macOS. Нужны сведения о декодерах **данного runtime** и реальное получение PCM [8–11].
- **Да, `AVAssetReaderTrackOutput(track: ..., outputSettings: nil)` документированно пропускает декодирование и возвращает исходные сжатые samples.** Поэтому системный demux 3GP → отдельный AMR-NB-декодер — правильный первый кандидат, даже если PCM-декодер AMR отсутствует. **Но Apple не гарантирует, что любой 3GP с AMR будет импортирован и прочитан на каждом runtime без AMR-декодера.** Эту конкретную комбинацию нужно доказать экспериментом, прежде чем удалять последний работающий fallback [12–14].

Минимальная целевая схема: **нативный player → при необходимости узкий codec fallback → принадлежащий операции PCM/WAV → тот же player**. Для AMR в 3GP сначала проверять системный demux, а не писать ISO BMFF-парсер. Для Ogg/Vorbis на iOS 17.0 необходим ненативный fallback, если этот формат обязателен. Выбор и юридическая проверка стороннего permissive-декодера — отдельная работа: в этой записке исследованы только Apple API и устройство форматов.

## Что именно доказано источниками

| Вход | Что это | Нативный путь и предел утверждения |
|---|---|---|
| WAV / PCM | WAV — контейнер; PCM — кодирование. Суффикс WAV не гарантирует PCM. | PCM и MP3 перечислены Apple среди iOS playback formats [5]. `AVAudioPlayer` для проигрывания; `AVAudioFile` для ограниченного чтения PCM [1, 2]. Поддержку каждого разрядного/канального варианта подтверждать фактическим чтением, не расширением. |
| MP3 | MPEG audio Layer III; возможны ID3 и VBR, а не только одинаковые по размеру кадры. | Штатный кандидат для player/file reader [1, 2, 5]. Успешное открытие не заменяет обработку поздней ошибки чтения/проигрывания. |
| OGG / Vorbis | Ogg — контейнер, Vorbis — кодек. Наличие одного не доказывает другого. | Safari 17.4: Vorbis; Safari 18.4: Ogg Vorbis [6, 7]. **Нет найденного официального обещания полного Ogg/Vorbis-пути через все рассматриваемые native API на iOS 17.0.** На более новых ОС нативный путь можно использовать после отдельной проверки конкретного API. |
| Raw AMR-NB | Обычно файл storage-формата с `#!AMR\n`, заголовками кадров и сжатыми данными; это не «сырой PCM» и не 3GP. | RFC 4867 §§3.1, 5: 8 кГц, 20 мс / 160 PCM-отсчётов на кадр [17]. Нативное чтение файла и наличие AMR→PCM-декодера — разные проверки. При отсутствии системного декодера нужен узкий parser framing + AMR-NB codec. |
| 3GP / AMR-NB | ISO BMFF-подобный контейнер с AMR sample entry `samr`, codec-specific box `damr`, таблицами samples и временем. | Импорт контейнера → чтение compressed samples с `nil` → разбиение AMR frames → декодер. 3GP sample может содержать **1–15 AMR-кадров**, последний — меньше; один `CMSampleBuffer` тоже не обязан равняться одному AMR-кадру [12, 15, 18]. |

Apple архивно перечисляла `audio/amr` и `audio/3gpp` для Safari iOS [19]. Это положительное историческое свидетельство, **не доказательство** современной матрицы `AVAudioFile` / `AudioConverter` / Simulator. Аналогично архивная таблица Core Audio с AMR Narrowband прямо ограничена **OS X 10.4/10.5**: применять её как iOS-обязательство нельзя [20]. Архивный iOS guide [5] помечен retired и не является исчерпывающей современной матрицей: отсутствие AMR в нём само по себе не доказывает отсутствие AMR в нынешней ОС.

## Развести четыре задачи, не четыре взаимозаменяемых API

### 1. Проиграть: `AVAudioPlayer`

- Принимает локальный URL или данные; даёт play/pause/stop, timing и delegate. Это финальный проигрыватель, **не API выдачи декодированного PCM или сжатых пакетов** [1].
- Ошибку инициализации, отказ `prepareToPlay`/`play`, delegate decode error и completion надо различать. Не считать любой отказ «неподдерживаемым кодеком»: cancellation, повреждённый файл, отсутствие доступа или audio-session failure не должны автоматически запускать другой декодер [1, 24].
- Использовать URL, а не загружать целый недоверенный файл в `Data`. MIME/fileTypeHint — подсказка, не конвертер.

### 2. Получить PCM из поддерживаемого файла: `AVAudioFile`

Apple прямо говорит: независимо от исходного формата чтение/запись происходят через PCM-буферы в `processingFormat`, с преобразованием между ним и форматом файла. Чтение последовательное, seek задаётся `framePosition` [2].

- `fileFormat` описывает файловый формат; `processingFormat` — формат буферов. Проверять оба, не путать `kAudioFormatLinearPCM` буфера с исходным кодеком.
- Читать небольшим переиспользуемым `AVAudioPCMBuffer`, учитывать фактический `frameLength`; не выделять память по недоверенной `length` для всего файла.
- `init(forReading:commonFormat:interleaved:)` выбирает представление PCM и interleaving, **не произвольную целевую частоту/канальность**. Для нужного resampling/channel conversion использовать штатный converter или имеющийся writer, не ручной resampler [2, 3].
- Удобен для обычных аудиофайлов, но не предоставляет compressed packet passthrough и не заменяет demux произвольного media asset.
- `AVAudioFile.close()` доступен только с iOS 18. На iOS 17 чтение закрывается при освобождении объекта; детерминированное завершение WAV-записи нельзя строить на новом методе без availability guard. Уже имеющийся `ExtAudioFileDispose` решает задачу финализации, заново проектировать writer не нужно [2; локальный SDK, ниже].

### 3. Разобрать media asset / извлечь samples: `AVURLAsset` + `AVAssetReader`

Загрузить свойства/аудиотрек асинхронно, получить format descriptions и выбрать ровно один поддерживаемый аудиотрек. `isReadable` означает возможность извлечения media через reader; это **не то же самое, что isPlayable** [14]. Не отсекать candidate compressed demux только потому, что asset нельзя проиграть.

Два различных режима одного **`AVAssetReaderTrackOutput`**:

1. **`outputSettings: nil`**: исходное storage-представление, без decoding; compressed samples выдаются в decode order [12, 13]. Не передавать вместо `nil` словарь с `AVFormatIDKey = kAudioFormatAMR`: это запрос сжатого output encoding, а не passthrough.
2. **Ненулевые audio settings с `AVFormatIDKey = kAudioFormatLinearPCM`**: декодирование в PCM. Требует доступного декодера и совместимых параметров; это совсем другой acceptance gate [12; локальный SDK].

**Не подменять TrackOutput на `AVAssetReaderAudioMixOutput`: у AudioMixOutput `audioSettings: nil` означает удобный несжатый формат, а не compressed passthrough.** Это прямо различено в SDK `AVAssetReaderOutput.h`.

Успех — не создание объектов: нужны `canAdd`, успешный `startReading`, реальные непустые buffers, корректные descriptions и терминальный `completed`. `copyNextSampleBuffer() == nil` может означать EOF **или ошибку**: проверять reader status/error [13]. Возможны marker-only buffers с нулевым sample count: их можно пропускать, но ограничивать число/время таких итераций, чтобы не получить бесконечную работу [локальный SDK].

### 4. Преобразовать пакеты/PCM: AudioToolbox `AudioConverter`

`AudioConverterNew` принимает **две ASBD**, а не URL контейнера. Apple документирует преобразования PCM↔compressed, sample rate, interleaving, числового представления и каналов; для наличия конкретного кодека ссылается на runtime Audio Format Services [3].

- Converter не demux-ит Ogg/3GP и не разбирает `.amr` magic header. Ему нужен корректный поток packets, packet descriptions при переменных размерах/длительностях, а при необходимости magic cookie.
- `AudioConverterNew` может не создать конвертер для конкретной пары ASBD. Успешное создание ещё не доказывает успешную декомпрессию файла до EOF.
- Для простого playback не вводить AudioConverter как отдельный общий слой: native file reader/player уже выполняют необходимое преобразование. Применять его там, где реально нужен PCM conversion или имеется packet stream.

## AMR в 3GP без нативного AMR-декодера

### Что документировано

`nil` у TrackOutput **явно отключает decoding** [12, 13]. Это достаточное API-основание для эксперимента «системный demux + отдельный декодер», но не спецификация всех importer/codec combinations. Нельзя утверждать ни «без декодера AVAssetReader принципиально бесполезен», ни «любой AMR/3GP гарантированно извлечётся».

### Минимальная проверяемая последовательность

1. Принять только принадлежащую приложению неизменяемую локальную копию с ограниченным размером. Создать `AVURLAsset` с **`AVURLAssetReferenceRestrictionsKey = AVAssetReferenceRestrictions.forbidAll.rawValue`**: разрешать только media внутри самого контейнера [21].
2. Асинхронно загрузить audio tracks и format descriptions; проверить media type audio, реальный subtype/ASBD `samr`, 8 кГц и допустимую канальность. Reject AMR-WB (`sawb`) и другие кодеки: это не AMR-NB. Нужны реальные descriptions, не переименование расширения [16–18].
3. Создать reader и **TrackOutput с `nil` settings**. Не требовать PCM и не создавать AudioMixOutput. Проверить add/start и последующее чтение/terminal status.
4. Для каждого непустого `CMSampleBuffer` получить format description, ASBD и, если применимо, magic cookie; отказать при неожиданной смене формата. `CMAudioFormatDescriptionGetStreamBasicDescription` возвращает заимствованный read-only pointer — владение description должно переживать обращение [16].
5. Получить `CMBlockBuffer`, полную длину, packet/sample descriptions. `CMSampleBufferGetAudioStreamPacketDescriptionsPtr` даёт заимствованный массив **на срок удержания sample buffer**. Для CBR с постоянными frames per packet успешный вызов может вернуть **ноль descriptions**; это не ошибка само по себе. Тогда использовать проверенные sample sizes/ASBD, а не придумывать одинаковые размеры для переменного AMR [15].
6. Проверять каждый offset/size и переполнение относительно длины data buffer. Не считать `CMBlockBuffer` непрерывным: `CMBlockBufferCopyDataBytes` документированно работает и для noncontiguous ranges, копируя в уже ограниченную область памяти [22]. Не передавать в C-декодер указатель на память после освобождения buffer.
7. Распаковать **AMR framing**, а не декодировать 3GP sample как один speech frame. По TS 26.244 §6.1 данные AMR в 3GP хранятся в single-channel storage framing **без magic numbers**; §6.7 допускает несколько frames на sample [18]. Пройти каждый frame header (FT/Q и padding), проверить допустимую длину по FT, полноту кадра и предел count; сохранить SID/no-data/bad-frame semantics, предусмотренные декодером. Не удалять первый байт как будто это «лишний заголовок» и не добавлять `#!AMR\n` каждому packet [17, 18].
8. Отдельный AMR-NB decoder выдаёт по 160 PCM-отсчётов на 20-мс speech frame; сохранить порядок, timestamp/duration и обработку DTX. Если допустимые для продукта edit/gap semantics не реализованы, отказать явно, а не молча склеивать нетривиальную временную шкалу. Потоково записать ограниченный PCM/WAV и финализировать перед передачей player [17, 18].

**Не обещано источниками:** точный layout `damr` в Apple magic cookie; будет ли importer нормализовать/перегруппировывать AMR samples; успешный passthrough на конкретных iOS 17/27 Simulator и устройствах при отсутствующем native decoder. Эти пункты определяются по реально возвращённым format/sample descriptions и byte-level проверке на собственных fixtures. Parser обязан следовать фактическим packet boundaries, не только контейнерному `frames_per_sample`.

**Если importer не даёт samples**, внешний AMR decoder сам по себе этого не исправит. Тогда полная поддержка 3GP требует другого bounded demux либо сохранения текущего рабочего fallback. Не добавлять большой MP4 framework или собственный общий ISO BMFF parser до этого отрицательного результата.

## Runtime capability discovery: правильная иерархия доказательств

1. **Контейнерные readers:** AudioToolbox `AudioFileGetGlobalInfo[Size]` с `kAudioFileGlobalInfo_ReadableTypes`, затем `kAudioFileGlobalInfo_AvailableFormatIDs` для конкретного file type. Это возможности **Audio File Services**, не универсальный список AVFoundation [10].
2. **AVURLAsset:** `audiovisualTypes()` / `audiovisualMIMETypes()`; на iOS 26+ имеется `audiovisualContentTypes`. Наличие 3GPP означает распознавание container type, не любой codec внутри. На SDK 27 старый `audiovisualTypes()` deprecated; не делать новый API обязательным для min iOS 17 [11; локальный SDK].
3. **Декодеры AudioToolbox:** `AudioFormatGetPropertyInfo/GetProperty` с `kAudioFormatProperty_DecodeFormatIDs`, затем `kAudioFormatProperty_Decoders` для требуемого format ID. Второй запрос возвращает **установленные декодеры** (`AudioClassDescription`), первый — допустимые входные format IDs converter [8, 9]. Hardware-only enumeration недостаточна: software decoder тоже подходит.
4. **Конкретная пара форматов:** source ASBD из фактического file/track/sample description → PCM destination ASBD → `AudioConverterNew` → ограниченное реальное преобразование. `mFormatID` в ASBD — идентификатор кодирования; остальные поля — rate/channels/frames/bytes/flags, а не доказательство установленного decoder [3, 16].
5. **Последнее слово — реальный путь:** поддерживаемый fixture декодируется в непустой конечный PCM с допустимыми samples, rate/channels/frame count и ожидаемой длительностью; completion не равен cancellation/failure. Player дополнительно проверяется в нормальном app host с разрешённой audio session.

`AVURLAsset.isPlayableExtendedMIMEType` полезен как подсказка **codec+container**, но не gate для compressed extraction. В iOS 17 изменилась интерпретация codec-параметров относительно MIME/container; SDK также предупреждает: если у MIME нет поддерживаемого codec namespace, запрос с codec parameter возвращает NO. Поэтому отрицательный ответ на вручную составленный AMR/Ogg MIME не заменяет реальный reader probe [11; локальный SDK `AVAsset.h:662–669`].

Не кэшировать «есть в SDK» как capability. Логировать platform (device/simulator), OS version/build, API path, source ASBD/subtype, decoder enumeration и полный NSError domain/code/underlying error либо OSStatus. Список decoder IDs — диагностика, а не замена интеграционному тесту выбранного высокоуровневого API.

## Simulator, физическое устройство и SDK 27

- Simulator не эквивалентен физическому iPhone по аппаратуре, производительности и поддерживаемым API [23]. Нельзя объяснять любой его отрицательный результат «Simulator ограничен», но также нельзя переносить его PASS/FAIL на устройство без проверки.
- «Собрано SDK 27.2 с min 17» не устанавливает кодеки из iOS 27 в iOS 17 runtime. Это разные оси: API availability при компиляции и фактический codec/importer при исполнении.
- Минимальная release-матрица: **iOS 17.0 Simulator + физический iOS 17.x; текущая iOS 27.x Simulator + физическое устройство**. Включать 17.4 и 18.4 как дополнительные диагностические точки Vorbis/Ogg, не подменять ими минимальную ОС. Если старого устройства нет, честно оставить device gate OPEN.
- В актуальных документах и SDK 27 `copyNextSampleBuffer()` deprecated в пользу `AVAssetReaderOutput.Provider.next()`. Это не отменяет старый путь для iOS 17. Не использовать новый API без availability branch и не подавлять предупреждения вместо обоснованной совместимости [13]. Для уже существующего KMP binding выбор совместимого bridge остаётся задачей интеграции; исследование не предлагает переписывать весь reader ради SDK.

### Что непосредственно прочитано в установленном Apple SDK

Корень: `/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator27.2.sdk/System/Library/Frameworks/`.

- `AVFoundation.framework/Headers/AVAssetReaderOutput.h:85–95, 254–320`: marker-only buffers; nil passthrough/skip decoding; PCM-only nonnil audio output; ошибки неподходящего словаря. `:366–423`: nil у AudioMixOutput — **uncompressed**.
- `AVFoundation.framework/Headers/AVAsset.h:129–152, 560–563`: forbidAll и запрет внешних media refs; `:647–669`: runtime type queries, новые availability/deprecation и MIME codec namespace caveat.
- `AudioToolbox.framework/Headers/AudioFormat.h:175–201`: необходимость packet descriptions для variable-byte packets; runtime DecodeFormatIDs/Decoders.
- `CoreAudioTypes.framework/Headers/CoreAudioBaseTypes.h:366–369, 423–424`: **только значения идентификаторов** `kAudioFormatAMR='samr'`, AMR-WB='sawb'; не обещание работающего codec.
- `CoreMedia.framework/Headers/CMSampleBuffer.h:468–500, 891–937`: audio packet descriptions, lifetime pointer, отсутствие descriptions для constant-size/constant-frames audio.
- `AVFAudio.framework/Headers/AVAudioFile.h:94–106`: закрытие reading file при deallocation и `close` с iOS 18.

Это комментарии Apple, поставленные с SDK, а не результаты импорта/линковки или исполнения. Внешние ссылки на соответствующие публичные API приведены ниже.

## Недоверенный локальный файл, cancellation и background

Следующие пункты — **рекомендуемые инварианты интеграции**, а не обещание, что Apple API обеспечит sandbox внутри процесса.

- Принимать bounded immutable local regular-file copy; не URL из метаданных и не источник, который может меняться во время чтения. Проверка расширения не является проверкой формата. Для AVURLAsset устанавливать `forbidAll`: одного `file://` у корневого URL недостаточно, контейнер может ссылаться на другую media [21]. Не добавлять network resource loader, remote URL fallback или загрузку недостающих кодеков.
- Сохранить текущие продуктовые пределы (в прочитанных документах — 64 MiB входа/выхода, 600 s); проверять их **во время** работы: вход, decoded frames, packet count, output bytes и монотонный deadline. Маленький compressed input не гарантирует маленький PCM. Не доверять только metadata duration, `length` и bitrate.
- Проверять finite rate, разрешённые channels/bit depth, переполнения `frames × channels × bytes`, packet offsets/lengths; ограничивать buffer/allocation size до выделения. Не читать весь clip и не запускать цепочку повторных «всех доступных» декодеров на любой ошибке.
- Native API не предоставляют общего публичного жёсткого лимита CPU/RSS/probe bytes для недоверенного parser. Лимит памяти собственных буферов не доказывает ограничение внутренних allocations. Deadline вокруг синхронного `AVAudioFile.read`/converter callback **не умеет принудительно прервать** зависший вызов. Не заявлять гарантированную hard timeout или полную защиту от malicious media; нужен отдельный adversarial corpus.
- File parsing/conversion — вне Main; session/player controls — в существующем управляющем контексте. Один владелец поколения/call; после отмены старые callbacks не могут запускать playback или публиковать Ready. Проверка cancellation перед/после каждой порции и перед передачей владения результатом.
- `AVAssetReader.cancelReading()` документированно прекращает background read operations; отменять pending asset loading через `AVAsset.cancelLoading()`. Не освобождать converter/file/buffer, пока синхронный worker ещё ими пользуется. Для `AVAudioFile` cooperative cancellation — между reads; для callback-based converter — в callback. Cancellation сохранять как Cancelled, а не «неподдерживаемый формат» [25].
- Недозавершённый собственный temp удалить только после завершения worker и закрытия writer/converter; исходный импорт не удалять. Success публиковать только после EOF/completed, финализации и повторной проверки лимитов/cancellation. WAV является playback compatibility temp, не новой canonical/export-копией без отдельного требования.
- **Background совместимость не требует background playback.** Сохранить описанную в P07 политику: уход в background/interruption/потеря устройства останавливают текущую операцию без автоматического replay. Не добавлять `UIBackgroundModes=audio`, новые session preferences или BGTask для короткого decode. Apple отдельно описывает interruptions/session lifecycle; audio background mode нужен только когда продукт действительно намерен продолжать playback [26].

## Какие проверки нужны до удаления FFmpeg

Исследование само их **не запускало**. Родительская сессия владеет существующими реализацией и тестами.

1. Для каждой runtime/platform записать две независимые матрицы: **native playback/PCM decode** и **compressed extraction**. WAV/PCM, MP3, Ogg/Vorbis, raw AMR-NB, 3GP/AMR-NB — отдельные строки, не общий «audio PASS».
2. 3GP/AMR: доказать, что nil-settings reader выдаёт `samr` bytes на runtime, где AMR→PCM действительно недоступен, если такой runtime есть. Если decoder есть везде доступно для проверки, гипотеза «demux работает без него» остаётся API-обоснованной, но не экспериментально подтверждённой — не отключать системные компоненты для искусственного доказательства.
3. Для AMR проверить несколько speech FT, смену mode, SID/no-data, malformed/truncated payload, Q/bad-frame handling, 3GP с несколькими frames/sample и укороченным последним sample. На own fixtures сравнить raw AMR и extracted 3GP frames без magic header; затем их PCM длительность/число кадров/энергию с допустимым эталоном, не обязательно bit-identical между разными decoder implementations.
4. Проверить silent PCM как допустимый звук: nonzero energy требуется у специально звучащего fixture, но не у любого пользовательского файла. Проверить конечность PCM, законные rate/channels и отсутствие частично успешного результата после ошибки.
5. Проверить отмену при loading/reading/decoding/finalization, background без replay, поздний callback, oversized metadata/output, запрет remote **и внешних local** refs, неизменность исходника, отсутствие temp leaks и поведение повреждённого файла. Тестировать реальный app host отдельно от CLI/native harness.
6. Если AMR/3GP compressed extraction и fallback codec ещё не доказаны, **удаление FFmpeg блокируется именно этим пробелом**, а не желанием заменить весь native playback. Для обязательного Ogg/Vorbis на iOS 17.0 отдельный fallback также остаётся необходимым.

Прочитанные локальные материалы: `docs/development/p07-ios-audio.md` (существующие ownership/lifecycle и writer constraints), `docs/development/ios-ffmpeg-preflight.md` (в нём доказан именно FFmpeg decode на iOS 17 Simulator; это **не native Apple decoder evidence**). Широкий поиск реализации и повторение трассировки родительской сессии не выполнялись.

## Первичные источники

Все URL ниже — источники исследования, **не сетевые media references для приложения**.

1. Apple, [AVAudioPlayer](https://developer.apple.com/documentation/avfaudio/avaudioplayer).
2. Apple, [AVAudioFile](https://developer.apple.com/documentation/avfaudio/avaudiofile), [reading initializer](https://developer.apple.com/documentation/avfaudio/avaudiofile/init(forreading:commonformat:interleaved:)).
3. Apple, [AudioConverterNew](https://developer.apple.com/documentation/audiotoolbox/audioconverternew(_:_:_:)).
4. Apple, [Audio Converter Services](https://developer.apple.com/documentation/audiotoolbox/audio-converter-services).
5. Apple, [Using Audio — iOS playback codecs](https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/MultimediaPG/UsingAudio/UsingAudio.html) — retired, историческая база, не современная исчерпывающая матрица.
6. Apple, [Safari 17.4 Release Notes, Media](https://developer.apple.com/documentation/safari-release-notes/safari-17_4-release-notes): Vorbis; отдельно WebM/VP8/VP9.
7. Apple, [Safari 18.4 Release Notes, Media](https://developer.apple.com/documentation/safari-release-notes/safari-18_4-release-notes): Ogg Vorbis/Opus на iOS 18.4.
8. Apple, [kAudioFormatProperty_DecodeFormatIDs](https://developer.apple.com/documentation/audiotoolbox/kaudioformatproperty_decodeformatids).
9. Apple, [kAudioFormatProperty_Decoders](https://developer.apple.com/documentation/audiotoolbox/kaudioformatproperty_decoders).
10. Apple, [ReadableTypes](https://developer.apple.com/documentation/audiotoolbox/kaudiofileglobalinfo_readabletypes), [AvailableFormatIDs](https://developer.apple.com/documentation/audiotoolbox/kaudiofileglobalinfo_availableformatids).
11. Apple, [AVURLAsset audiovisualTypes](https://developer.apple.com/documentation/avfoundation/avurlasset/audiovisualtypes()), [isPlayableExtendedMIMEType](https://developer.apple.com/documentation/avfoundation/avurlasset/isplayableextendedmimetype(_:)).
12. Apple, [TrackOutput initializer](https://developer.apple.com/documentation/avfoundation/avassetreadertrackoutput/init(track:outputsettings:)), [outputSettings](https://developer.apple.com/documentation/avfoundation/avassetreadertrackoutput/outputsettings): nil = storage format / skip decoding.
13. Apple, [copyNextSampleBuffer](https://developer.apple.com/documentation/avfoundation/avassetreaderoutput/copynextsamplebuffer()): order, EOF/error distinction, iOS 27 deprecation.
14. Apple, [AVAsset isReadable](https://developer.apple.com/documentation/avfoundation/avasset/isreadable): в современном Swift использовать async `load(.isReadable)`, не deprecated synchronous getter.
15. Apple, [CMSampleBufferGetAudioStreamPacketDescriptionsPtr](https://developer.apple.com/documentation/coremedia/cmsamplebuffergetaudiostreampacketdescriptionsptr(_:packetdescriptionspointerout:sizeout:)), [copying variant](https://developer.apple.com/documentation/coremedia/cmsamplebuffergetaudiostreampacketdescriptions(_:allocatedsize:packetdescriptionsout:packetdescriptionssizeneededout:)).
16. Apple, [CMAudioFormatDescriptionGetStreamBasicDescription](https://developer.apple.com/documentation/coremedia/cmaudioformatdescriptiongetstreambasicdescription(_:)), [GetMagicCookie](https://developer.apple.com/documentation/coremedia/cmaudioformatdescriptiongetmagiccookie(_:sizeout:)).
17. IETF, [RFC 4867 §§3.1, 5.1, 5.3](https://www.rfc-editor.org/rfc/rfc4867.html#section-5): AMR-NB кадры и storage framing; RTP-часть этого документа не предлагается как формат локального файла.
18. ETSI / 3GPP, [TS 126 244 V15.0.0 / TS 26.244, §§6.1, 6.5, 6.7](https://www.etsi.org/deliver/etsi_ts/126200_126299/126244/15.00.00_60/ts_126244v150000p.pdf): storage framing без magic, samr/damr, 1–15 frames/sample. Текст PDF проверен через `pdftotext`, не только поисковый snippet.
19. Apple, [Safari Web Content Guide, rich media MIME types](https://developer.apple.com/library/archive/documentation/AppleApplications/Reference/SafariWebContent/CreatingContentforSafarioniPhone/CreatingContentforSafarioniPhone.html) — архив, контекст Safari.
20. Apple, [Supported Audio File and Data Formats in OS X](https://developer.apple.com/library/archive/documentation/MusicAudio/Conceptual/CoreAudioOverview/SupportedAudioFormatsMacOSX/SupportedAudioFormatsMacOSX.html) — **не iOS**.
21. Apple, [AVURLAssetReferenceRestrictionsKey](https://developer.apple.com/documentation/avfoundation/avurlassetreferencerestrictionskey), [forbidAll](https://developer.apple.com/documentation/avfoundation/avassetreferencerestrictions/forbidall).
22. Apple, [CMBlockBufferCopyDataBytes](https://developer.apple.com/documentation/coremedia/cmblockbuffercopydatabytes(_:atoffset:datalength:destination:)).
23. Apple, [Simulator Help Topics — Differences between simulated and physical devices](https://developer.apple.com/library/archive/documentation/IDEs/Conceptual/simulator_help_topics/Chapter/Chapter.html), [Simulator differences](https://help.apple.com/simulator/mac/current/#/devb0244142d). Не использовать старые списки отсутствующих microphone/camera API как описание сегодняшнего Simulator.
24. Apple, [AVAudioPlayerDelegate](https://developer.apple.com/documentation/avfaudio/avaudioplayerdelegate).
25. Apple, [AVAssetReader cancelReading](https://developer.apple.com/documentation/avfoundation/avassetreader/cancelreading()), [AVAsset cancelLoading](https://developer.apple.com/documentation/avfoundation/avasset/cancelloading()).
26. Apple, [Audio Session Programming Guide — interruptions](https://developer.apple.com/library/archive/documentation/Audio/Conceptual/AudioSessionProgrammingGuide/HandlingAudioInterruptions/HandlingAudioInterruptions.html), [background playback configuration](https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/MediaPlaybackGuide/Contents/Resources/en.lproj/ConfiguringAudioSettings/ConfiguringAudioSettings.html).

Современные страницы Apple проверялись также через их официальные DocC JSON endpoints (`https://developer.apple.com/tutorials/data/documentation/...json`), поскольку HTML часто содержит только JavaScript-shell.

**Не проверено:** runtime native matrix, physical devices, codec fallback package/license и производственная интеграция. Главный риск — объявить importer/constant/Safari support доказательством PCM-декодирования или удалить FFmpeg до доказанного AMR/3GP и iOS 17 Ogg fallback.
