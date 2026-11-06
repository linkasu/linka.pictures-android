# U1.4: тип незавершённой карточки

Реализован принятый COORD seam: trailing `EditorForm.Card.kind = CONTENT`,
`AuthoringAction.ChangeCardKind(kind)` и переходы существующего authoring actor.
UI radio подключается отдельной UI-веткой. Storage/ports/codec/SQL/access не менялись.

## Совместимость

| Данные | Reader | Результат |
|---|---|---|
| Старая form без kind | Новый binary | CONTENT по умолчанию |
| Новая CONTENT form | Старый strict reader | Прежние JSON-байты, включая polymorphic `type=card`; kind отсутствует даже при encodeDefaults=true |
| Новая SPACE/EMPTY form | Новый binary | Полное восстановление kind и скрытых полей |
| Новая SPACE/EMPTY form | Старый strict reader | Отказ на неизвестном поле, не игнорирование kind и не преобразование в CONTENT |
| Неизвестное значение kind | Новый strict reader | Отказ всего decode, не default/fallback |

Downgrade внутренних экспериментальных бинарников с non-CONTENT forms **не поддерживается**.
Не заявляется запуск старого бинарника на новой библиотеке: отказ strict reader
проверен legacy DTO, а не доставленным старым приложением. Schema envelope остаётся 1,
DB user_version — 1; backup format и внешний ordinary `.linka` не меняются.
Существующие strict JSON и read-only первоначальная проверка storage не ослаблялись.

## Переходы и владение

- OpenCard берёт исходный kind; отсутствующая позиция — CONTENT.
  SetCell(CONTENT) готовит сразу CONTENT form одним change, без промежуточного autosave.
- ChangeCardKind проходит прежние owner/capability/epoch/phase gates, работает
  в EDITING/CONFLICT, отменяет media/preview и pending adoption, повышает media generation.
  Поздний результат не принимается, его собственные handles освобождаются.
- Label, speech, image, recording, card extensions остаются в форме при смене типа.
  Документ не меняется до SaveCard; возврат к CONTENT сохраняет работу.
  Существующие assetIds/assetRoles/backup validation удерживают скрытые media независимо
  от kind. Их реализация не менялась.
- SPACE/EMPTY отвергают новые pick/image-from-text/record/synthesis/preview команды
  структурированным INVALID_DATA до media/audio IO. Finish/Cancel не возобновляют запись.
- SaveCard одним change закрывает form и заменяет ячейку. Только CONTENT строит speech,
  требует recording и проверяет content cap; SPACE/EMPTY имеют card=null, даже если
  скрытый speechChoice=RECORDING без asset. Пустая подпись не меняет выбранный kind.
  Cell extensions остаются всегда, card extensions — для CONTENT.
- CancelCard сохраняет исходный документ точно. До Apply опубликованная доска
  и message snapshot не меняются; после Apply message snapshot также не меняется.
- Writer остаётся один: actor → existing saveDraft. Один in-flight и latest pending
  save, leases, draft version/base revision/epoch, conflict и UNKNOWN COMMIT quarantine
  сохраняются. Новых locks/SQL writers/миграции нет; rollback — не downgrade формата.

## Проверка 6 ноября 2026

До production-патча serialization test не компилировался из-за отсутствующего kind (RED).
После correctness-review и исправления синхронизации нового теста:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:jvmTest \
  --tests '*EditorFormKindSerializationTest' \
  --tests 'su.linka.pictures.application.authoring.*' --max-workers=1 --offline
```

**52/52 PASS:** AuthoringSessionTest 49 (40 прежних + 9 новых),
EditorFormKindSerializationTest 3. Без новых dependencies/downloads.

Новые проверки: старые exact JSON-байты direct/polymorphic form, legacy strict refusal,
unknown enum; все source kinds и атомарный SetCell(CONTENT); Save/Cancel всех трёх типов;
пустая label и скрытая отсутствующая recording; сохранность cell/card metadata,
исходной публикации и сообщения; SPACE/EMPTY media no-IO; отмена записи/late picker;
pending reauthentication adoption cleanup; CONFLICT/PAUSED/UNCONFIRMED gates.
Оба non-CONTENT вида проходят actual SQLite autosave → BackupCodec write/read с
проверкой скрытых image/audio roots → полное закрытие runtime → новый runtime →
ResumeDraft → возврат к CONTENT → CancelCard.

Не запускались full allTests, Native/iOS/Android сборки, FFmpeg producer и устройство:
это следующий COORD gate после UI-интеграции. Backup использует настоящие owned файлы
и существующий codec, но media inspection/audio/picker — controlled fakes, не OS proof.
Не выполнялся actual старый binary или отдельная corrupt-SQL fixture; strict storage
boundary не изменён, отказ неизвестного enum проверен на уровне serializer.
