# P00: фактический standard-контракт Electron

## Срез и границы доказательства

- Источник **E = commit `c540646e1bf06d5f4ea76d6db6b2ab1b82bfe89b`**, ветка `main`, корень `/Volumes/data/linka/linka.looks-electron`. Все ссылки `E:path:line` ниже относятся именно к этому commit; диапазоны включительны.
- Write-root Android: `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p00-peer`, исходный HEAD/base `3deade7a9a9d82e6478b0a34d19c99ef204e5add`, **detached HEAD**, содержащая commit ветка `implementation/linka-pokazhi`. Начальный статус обоих корней чистый.
- Прочитан `E:AGENTS.md:1–50`; в Android tracked `AGENTS.md` нет, корневой файл отсутствует. `graphify-out/graph.json` отсутствует в обоих корнях: graphify query не применим; граф не создавался.
- Только статическое чтение исходников/тестов и read-only inventory семи bundled ZIP. Приложения, тесты, сборки, сеть, production-хранилища не запускались/не читались. Код и медиа не переносились. Это evidence, не изменение contracts/build и не выбор архитектуры.
- Заданный мобильный scope: reader/writer `pages` / строка `"3.0"`, плюс legacy1.x; **quiz/match/unknown отвергаются целиком**. Это требование задания, не существующее поведение Electron: Electron принимает quiz/match, неизвестный mode заменяет standard (`E:src/common/interfaces/ConfigFile.ts:68–70,219–224`). Отбрасывание неизвестного режима нормализатором уничтожает evidence для последующего rejection: нельзя считать результат normalizer доказательством исходного standard.

## Runtime audio: не общий fallback, а разные сценарии

| Сценарий standard | Фактическое поведение | Evidence на E |
| --- | --- | --- |
| Открытие набора | `open_file` читает config, повторно нормализует, выставляет output line в `!directSet` | `src/frontend/store/index.ts:471–478` |
| Нажатие карточки без output line, в том числе начальный directSet | `playCards(file,[card],true)`; наличие `withoutSpace` не переводит этот путь в TTS текста | `src/frontend/views/SetExplorerView.vue:166–182,273–291` |
| Фраза, `withoutSpace=false` | В output попадают AudioCard; «сказать» вызывает `playCards` | `src/frontend/utils/setGameLogic.ts:44–49`; `src/frontend/components/OutputLine.vue:143–150` |
| Фраза, `withoutSpace=true` | В output попадают AudioCard и SpaceCard; строка = `title` AudioCard или пробел остальных, соединение без separator; непустая строка передаётся `playText` | `src/frontend/utils/setGameLogic.ts:44–49`; `src/frontend/components/OutputLine.vue:101–107,143–150` |
| `playCards` | Только `cardType===0` и truthy `audioPath`; ZIP bytes; null buffer пропускается. `audioText`, `audioVoice`, `title` не читаются, text-only карточка не синтезируется | `src/frontend/utils/TTS.ts:51–75` |
| `playText` | Серверный TTS, voice аргумента либо выбор по тексту: Latin без Cyrillic → настройка En / `john`, иначе Ru / `alena`. Не voice карточки | `src/frontend/utils/TTS.ts:77–91,134–141` |

Итого: для card playback `audioPath` — единственный источник, **не** `audioPath > audioText > title`. Для withoutSpace output источником становится `title`, даже если audioPath присутствует. `audioText/audioVoice` — сохранённые параметры редакторского TTS, а не runtime fallback. Диалог генерирует файл через `createAudioFromText(text,voice)`, сохраняющий MP3; затем editor записывает все три поля. Preview диалога отдельно вызывает `playText(text,voice)` (`E:src/frontend/components/EditorView/TTSDialog.vue:102–115,126–139`; `E:src/electron/services/card-storage-service.ts:229–232`; `E:src/frontend/views/EditorView.vue:834–869`).

End-to-end статическая цепь чтения звука: TTS → frontend IPC `storage:getAudio` → зарегистрированный backend handler → `getAudio/getBuffer` → `openLinkaZip(...).readFile(entry)` (`E:src/frontend/services/card-storage-service.ts:128–135`; `E:src/electron/services/card-storage-service.ts:51–61,154–161`). Ошибку backend frontend превращает в null; `playCards` не переходит к TTS. HTML Audio получает Blob с фиксированным `audio/mp3`, вне зависимости от имени/реального контейнера; playback errors завершают promise, но не запускают fallback (`E:src/frontend/utils/TTS.ts:23–27,118–130`).

Тестовые свидетельства (прочитаны, **не исполнены**): storage audio `E:src/frontend/tests/unit/TTS.spec.ts:83–103`; voice selection и text TTS `:73–81,118–134`; обе output-ветки `E:src/frontend/tests/unit/OutputLine.spec.ts:57–81`. Эти тесты не доказывают воспроизведение настоящих кодеков и не содержат полного precedence-теста с конфликтующими path/text/voice.

## Поля, defaults, версии и writer

Это различие **declared interface / normalized output / строгий whitelist**. В Electron нет доказанного строгого whitelist входного JSON.

| Уровень | Доказанные поля и преобразования | Evidence на E |
| --- | --- | --- |
| Root interface | `version, columns?, rows?, withoutSpace, directSet?, quiz?, questions?, quizAutoNext?, quizReadQuestion?, cards?, pages?, description?` | `src/common/interfaces/ConfigFile.ts:38–51` |
| Root normalized output | Только `version, withoutSpace, directSet, quizAutoNext, quizReadQuestion, description, pages`. Всегда version `"3.0"`; withoutSpace/directSet через boolean coercion; quizAutoNext `?? true`, quizReadQuestion `?? false`, description без валидации. undefined-поля исчезают при JSON serialization | `src/common/interfaces/ConfigFile.ts:63–66,277–295`; `src/electron/services/card-storage-service.ts:299–300` |
| Page interface | `id, mode, columns, rows, cards, topColumns?, bottomColumns?, question?` | `src/common/interfaces/ConfigFile.ts:27–36` |
| Page normalized output | Те же ключи возвращаются явно, посторонние теряются. standard: top/bottom/question undefined. id через `??` page/fallback/UUID. mode default standard; dimensions default 3×3 (root dimensions используются fallback при config normalization), floor и минимум 1 | `src/common/interfaces/ConfigFile.ts:72–75,219–274,280–284` |
| Card interface | `id,cardType,width?,height?,imagePath?,title?,audioPath?,audioText?,audioVoice?,answer?,matchId?,matchLane?` | `src/common/interfaces/ConfigFile.ts:12–25` |
| Card normalized output | **Spread входной card**, поэтому неизвестные поля сохраняются. id `?? UUID`, cardType `?? 3`; standard удаляет answer/matchId/matchLane, spans clamps, остальные значения не проверяются | `src/common/interfaces/ConfigFile.ts:177–217` |
| Card types | Числа: Audio=0, Space=1, Empty=2, New=3. Enum не является runtime-проверкой произвольного JSON | `src/common/interfaces/ConfigFile.ts:3–8,185–189` |

Reader открывает ZIP, читает **root `config.json`**, JSON.parse и normalizeConfigFile; parse/normalization exception логирует и возвращает null. ZIP opening происходит перед try: ошибка ZIP сама по себе бросается (`E:src/electron/services/card-storage-service.ts:116–127`).

`version` объявлен string, current — точно `"3.0"`, не номер 3. Нормализатор **не проверяет входную version**, выбирает pages при непустом pages, иначе legacy. Проверенные тесты используют строки `"2.0"` и `"3.0"`, bundled sample — `"1.0"/"1.2"`; это не полный перечень поддерживаемых версий (`E:src/common/interfaces/ConfigFile.ts:39,63,277–295`; `E:src/frontend/tests/unit/ConfigFile.spec.ts:261–319`; inventory ниже).

**3→2 относится к cardType, не downgrade версии**: saveSet нормализует config, затем NewCard заменяет новым объектом `{id:новый UUID,cardType:2,matchLane}`. Остальные поля NewCard теряются. Writer всегда пишет current `"3.0"`; evidence для writer `3.0→2.0` отсутствует (`E:src/electron/services/card-storage-service.ts:276–310`; `E:src/frontend/tests/unit/CardsStorage.spec.ts:166–200`).

`cleanFile` переносит только точно совпавшие imagePath/audioPath **AudioCard** из normalized config, включая nested names; прочие записи не сохраняет. Отсутствующую media reference не валидирует как обязательную. Save пишет новый config и staged ZIP рядом с destination, проверяет ZIP, rename, finally cleanup. Сохранение не равнозначно побайтовому ZIP round-trip (`E:src/electron/services/card-storage-service.ts:283–309,400–416`; nested test `E:src/frontend/tests/unit/CardsStorage.spec.ts:73–83`; source preservation/failed replacement `:202–239`).

## Spans, overflow и опасные свойства normalizer

- standard cards: сначала удаляются falsy элементы, затем **slice до rows×columns**, после чего дополняются NewCard. Данные сверх capacity теряются, в отличие от legacy flat cards, которые разбиваются на несколько страниц (`E:src/common/interfaces/ConfigFile.ts:240–263,297–322`; тесты `E:src/frontend/tests/unit/ConfigFile.spec.ts:120–146,261–282`). Это наблюдение, не разрешение мобильному reader терять данные.
- width/height normalizer floor/min1, верхний предел — полная ширина/высота страницы; `1` удаляется. Placement отдельно ограничивает span оставшимися колонками/строками от **индекса** карточки, помечает covered slots. Он не перепаковывает последующие карточки (`E:src/common/interfaces/ConfigFile.ts:77–80,113–149,190–202`). Grid не рендерит covered placements; covered карточки могут оставаться в config и сохранённых media (`E:src/frontend/components/SetGrid.vue:123–148`; `E:src/electron/services/card-storage-service.ts:400–414`). Тест spans/covered `E:src/frontend/tests/unit/ConfigFile.spec.ts:148–161,175–206`.
- Normalizer — не безопасный parser/schema validator: нет finite/max dimension check, bounds page/card/text count, type/enum/path validation для полей card. Numeric strings попадают в Math.floor; truthy нечисловая строка даёт NaN, Infinity не отсеивается; padding loop зависит от полученного capacity. Это вывод из кода, не исполненный hostile-input тест (`E:src/common/interfaces/ConfigFile.ts:72–80,185–189,219–263,277–284`). Не выполнять такие входы на реальных файлах.
- id defaults — nullish, не uniqueness/nonempty checks. Unknown mode становится standard (существующий тест это прямо ожидает), а legacy truthy quiz становится quiz (`E:src/common/interfaces/ConfigFile.ts:187–188,219–224,265–274,297–317`; `E:src/frontend/tests/unit/ConfigFile.spec.ts:208–221`). Mobile reject-all нельзя доказывать этим нормализатором.

## Paths, nested media, ZIP bounds и collisions

- Storage path — lexical resolve в HOME_DIR либо зарегистрированный temp path; `§` преобразуется в slash. Это **не доказательство realpath/symlink containment для всех операций** (`E:src/electron/services/card-storage-service.ts:129–147,196–200`). Отдельный unpack проверяет существующие symlink в цепочке extraction (`:483–505`; тест `E:src/frontend/tests/unit/CardsStorage.spec.ts:59–71`).
- ZIP paths: slash-separated; nested paths разрешены. Запрещены backslash/NUL/colon, пустые сегменты, `.`/`..`, trailing dot/space, Windows device names; разрешён один trailing slash directory (`E:src/common/utils/linkaZip.ts:7–22`). Это ограничения **entry names**, не полноценный whitelist JSON media references (`E:src/electron/services/card-storage-service.ts:154–161,400–414`).
- ZIP snapshot regular file ≤500 MiB, ≤2000 entries; сумма `max(uncompressed,compressed)` по entry ≤500 MiB. Не encrypted; compression methods только 0/8; UNIX type только unspecified/regular/directory; каждый inflated payload сверяется с declared size (`E:src/common/utils/linkaZip.ts:4–5,24–75`). Это observed Electron limits, **не автоматически утверждённые мобильные resource budgets**.
- Collision key entry: убрать trailing slash, Unicode NFC, lowercase; совпадение отклоняет ZIP целиком, включая case и directory/file same-name (`E:src/common/utils/linkaZip.ts:53–60`). Проверка не является доказательством защиты от всех namespace/prefix конфликтов (`a` versus `a/b`) или всех Unicode/filesystem aliases: такой проверки в показанном алгоритме нет.
- Merge media collisions проверяются сначала по exact existing entry name, при совпадении имя заменяется UUID+extension, ссылки обновляются; итоговый archive снова проходит openLinkaZip. Это не casefold-dedup merge: alias может закончиться отказом. Новый target выбирается с suffix при существующем файле (`E:src/electron/services/card-storage-service.ts:339–397,452–470`; тест exact collision `E:src/frontend/tests/unit/CardsStorage.spec.ts:319–385`).
- appendZip валидирует новое имя, извлекает валидированный source во временный каталог, пишет entry, переархивирует, проверяет staged ZIP и rename. Нет создания новой parent-directory для произвольного нового nested entry; сохранение уже существующего nested media — отдельная доказанная возможность (`E:src/frontend/utils/addToZip.ts:9–38`; `E:src/frontend/tests/unit/CardsStorage.spec.ts:73–83`).
- Тесты unsafe/duplicate/special/false sizes/count/aggregate/truncated описаны в `E:src/electron/tests/unit/linka-zip.spec.ts:30–137`; интеграционные read/import/save/merge no-replacement assertions — `E:src/frontend/tests/unit/CardsStorage.spec.ts:29–57`. Зависимость объявлена `adm-zip ^0.6.1` (`E:package.json:46`); комментарий про bounded inflate не независимый аудит зависимости (`E:src/common/utils/linkaZip.ts:68–73`).

## Read-only inventory bundled fixtures

Выборка ограничена seed items из `E:src/electron/services/card-storage-service.ts:39–40,554–567`, packaging allowlist `E:package.json:147–156`: два root ZIP и пять `.linka` непосредственно в `Крупная клавиатура/`. Другие bundled коллекции не открывались. Файлы tracked на E (проверено `git ls-files --stage -- extraResources`); у бинарных ZIP нет корректных source line numbers: provenance задаётся **E + точный path + SHA256**, а не выдуманной строкой. Читались config summary и первые 16 байт каждой media entry, без извлечения/копирования файлов и без вывода текстов карточек.

| Path относительно `extraResources/defaultSets/` | SHA256 | ZIP bytes / entries | Version / dimensions | Signature counts |
| --- | --- | --- | --- | --- |
| `Клавиатура.linka` | `c7b1d07cf4c3e926dbf7256d5c3f10acb1de329df70a4753f834128b1320e3b8` | 56563 / 95 | `"1.0"` / 12×4 | PNG 94 |
| `цифры знаки.linka` | `4e2a43a6b62ff34a8675fb9e02a3998a0c4db09558044b611bb40c81115c798c` | 593343 / 31 | `"1.0"` / 3×4 | PNG 20; RIFF/WAVE 10 **с extension `.ogg`** |
| `Крупная клавиатура/1x1.linka` | `1317ac939db84e90ad4b5466823e940d91303e62fd9658e56f9145c237e5e267` | 1001664 / 87 | `"1.2"` / `"1"`×`"1"` | PNG 43; RIFF/WAVE 43 (`.wav`) |
| `Крупная клавиатура/2x1.linka` | `db726ba0235970200725e1b7edb9279fe79a6f939dc154d650e69e43690fab3b` | 1001671 / 87 | `"1.2"` / `"2"`×`"1"` | PNG 43; RIFF/WAVE 43 (`.wav`) |
| `Крупная клавиатура/2x2.linka` | `c41c2bb2e2ed565c29edeae6cad1f4d031b67ef9c458771195d4ce4c4f8cd2eb` | 1001667 / 87 | `"1.2"` / `"2"`×`"2"` | PNG 43; RIFF/WAVE 43 (`.wav`) |
| `Крупная клавиатура/3x3.linka` | `04a9b92ea6a33d727e5d7044bbde0ff690ff96539d8efe67be35ac4bf17070b0` | 1001672 / 87 | `"1.2"` / `"3"`×`"3"` | PNG 43; RIFF/WAVE 43 (`.wav`) |
| `Крупная клавиатура/4x4.linka` | `5e1f3528e44c2376737d3ed46f70cd70661c2b91352ee4941771442de9e14f54` | 1001666 / 87 | `"1.2"` / `"4"`×`"4"` | PNG 43; RIFF/WAVE 43 (`.wav`) |

Все 561 entries используют ZIP method 8; nested entry count 0; 329 PNG + 225 RIFF/WAVE + 7 config. Root keys обоих `1.0`: `cards,columns,rows,version,withoutSpace`; у пяти `1.2` дополнительно `description,directSet`. Card-key union выборки: `audioPath,cardType,id,imagePath,title`. AudioText/audioVoice отсутствуют; truthy audioPath: 0 / 10 / по 43 в пяти крупных клавиатурах. Card types: клавиатура 0×83, 1×3, 2×10; цифры 0×20, 2×4; крупные по 0×43. Эти **наблюдаемые ключи не whitelist legacy1.x**. RIFF/WAVE header — контейнерная сигнатура, не codec decoding, не доказательство PCM или Android compatibility.

## License / attribution

Атрибуция исследованного источника: LINKa / `linkasu/linka.looks-electron`, author `ibakaidov`, repository URL `https://github.com/linkasu/linka.looks-electron.git`, package license **GPL-3.0-or-later** (`E:package.json:2–15`), корневой текст GNU GPL v3 (`E:LICENSE:1–6,195–243`). Перенос кода/медиа не выполнялся; это самостоятельно изложенные факты с provenance.

Package license не доказывает авторство/права на каждый bundled image/audio, право на TTS voices, trademark permissions, права на внешние сервисы или выполнение обязательств при будущем переносе GPL-кода в Android. GPL прямо отдельно рассматривает output и разрешает запуск, но не объявляет любой output автоматически GPL (`E:LICENSE:154–162`); правила attribution/conveying остаются релевантными при реальном копировании (`E:LICENSE:195–243`). Юридическое заключение и media clearance не выполнялись.

## Конечный список неизвестных / blockers координатору

1. **Codec-policy blocker:** реальные legacy `.ogg` с RIFF/WAVE и runtime fixed MP3 MIME опровергают extension-only предположение. Нужны решение координатора и отдельная безопасная codec/device проверка: какие контейнеры/кодеки принимаются, sniffing, error behavior. Здесь ничего не перекодировано и не объявлено совместимым.
2. **Security-policy blocker:** Electron limits/normalizer не определяют мобильные bounds JSON, pixels, dimensions/spans/counts, ZIP memory budget, canonical reference matching и prefix collisions. Не принимать Electron normalizer за валидатор недоверенного input. Политику не выбирал.
3. Legacy1.x: доказаны только seed `1.0`/`1.2`, включая numeric-string dimensions. Нет полного whitelist всех 1.x, rules unknown versions/unknown legacy fields, обязательности id/audio, воспроизводимости остальных historical producers. Нужна отдельная верификация; legacy нельзя вывести только из TS interface или семи примеров.
4. Mobile reject-whole matrix (mixed pages, quiz root flags, unknown modes/versions) задана, но тестами Electron не доказана; нормализация скрывает unknown. В рамках этой независимой части mobile implementation не исследовалась.
5. Lossless expectations для unknown card fields, standard overflow, covered slots, unused media и NewCard spans/metadata требуют явного принятия coordinator; Electron writer заведомо делает описанные потери. Ни writer downgrade `3.0→2.0`, ни byte-identical round-trip не доказаны.
6. Настоящее playback на Android/Electron, offline voice availability, повреждённые media streams, full dependency ZIP audit и полный runtime precedence regression test не выполнены. Прочитанные тесты — evidence намерений, не новый passing test run.
7. Media licenses и последствия будущего копирования исходников требуют отдельной проверки прав; package-level GPL недостаточно для asset clearance.

## Команды и контроль неизменности

Каждый вызов ограничен одним явным root; поиска по родительскому каталогу/HOME и других агентов не было. Для dependency применены scoped read/grep, поскольку file-search MCP текущей сессии привязан к другому проекту.

- В каждом root: `git rev-parse HEAD`, `git branch --show-current`, `git status --porcelain=v1`; Android дополнительно `git rev-parse --show-toplevel`, `git log -1 --format='%H %s'`, `git branch -a --contains HEAD`, проверка hooksPath (default). Результаты base/branch/status приведены выше.
- Python `pathlib` проверил root `AGENTS.md`, `graphify-out/graph.json`; `git ls-files` inventory названных корней. Для source immutability использован **`git ls-files -z`**, не quoted pathname output: SHA256 потока `relative UTF-8 path + NUL + file bytes` в порядке index; Android исключает только этот новый evidence-файл.
- Before/after Android source digest одинаков: `185b3c35f9bb9fde907a7970050e73ff2532e98c09da37d6f1df90b8529315e2`; before/after Electron одинаков: `76651aaff4d776e369d99af117fcba1ade252329870ed30d180bf126dab984e9`. Electron HEAD неизменен, финальный полный status пуст. Финальный Android commit сообщается координатору отдельно, чтобы не создавать self-referential hash.
- Read-only ZIP inventory: Python `zipfile.ZipFile` → `infolist`, `json.loads(read('config.json'))`, field/count summaries, `ZipFile.open(entry).read(16)`, `hashlib.sha256(path.read_bytes())`; ровно семь путей таблицы. `git ls-files --stage -- extraResources` подтвердил tracked fixtures. `git check-ignore -v` для трёх seed путей вернул exit 1 / без вывода; `git status --porcelain=v1 --untracked-files=all -- extraResources/defaultSets` — пусто.
- Обязательный финальный gate: `git diff --check` и staged equivalent, повтор source digests / `git status --porcelain=v1 --untracked-files=all`, `git show --stat` локального docs-only commit. Никакого push.
