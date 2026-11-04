# P05: остановка для решения COORD

Статус: **BLOCKED, не codec PASS и не G1/G3 PASS**. Base:
`0816394017adefd3fc77aeebca2a1a052493206e`; ветка
`implementation/p05-linka-codec`. Production codec/fixtures не добавлены.

## Неопределённая граница passive/active extensions

`code-contracts.md:10,109–111` требует сохранять неизвестные пассивные
root/page/card fields, но отклонять неизвестную активную схему до публикации.
K5 повторяет это требование. Прочитанное P00 evidence содержит известные
`mode`/`quiz` и passive quiz metadata, но не правило классификации произвольных
неизвестных ключей и их значений. Придумывать такое правило P05 не уполномочен.

Конкретный синтетический вход `config.json` (не выполнялся как peer oracle):

```json
{
  "version": "3.0",
  "withoutSpace": false,
  "directSet": false,
  "actions": [{"type": "openBoard", "target": "next"}],
  "pages": [{
    "id": "p", "mode": "standard", "rows": 1, "columns": 1,
    "cards": [{"id": "c", "cardType": 0, "title": "test"}]
  }]
}
```

Нужно решение COORD: `actions` здесь — сохраняемое пассивное JSON поле либо
неизвестная активная схема с отказом всего архива? Требуется общее исполнимое
правило для неизвестных root/page/card полей, включая вложенные объекты, а не
исключение только для этого примера. Без него permissive сохранение может
пропустить запрещённую активную схему, а самостоятельно придуманный denylist
может нарушить lossless сохранение metadata. Известные `quiz=true`, page
`quiz`/`match`/unknown mode уже однозначно отклоняются по принятой политике;
решение по ним не запрашивается повторно.

## Проверка доступности ZIP primitive

Причины останавливаться из-за отсутствия public inflater не обнаружено.
Установлен Okio **3.10.2** (`shared/build.gradle:38`), zip4j объявлен только в
androidMain и не выбран для common codec. Временная проба внутри разрешённого
common `portability/linka/` компилировала именно:

```kotlin
import okio.Inflater
import okio.Source
import okio.inflate

internal fun inflateApiProbe(source: Source): Source = source.inflate(Inflater(true))
```

Она прошла `:shared:jvmTest :shared:compileKotlinIosSimulatorArm64` с принятыми
JAVA_HOME/ANDROID_HOME/DEVELOPER_DIR. Проба удалена; не оставлен неиспользуемый
production helper. Это compile evidence публичного raw DEFLATE API, **не**
проверка ZIP trust boundary, CRC, лимитов inflate или обработки media.

Read-only upstream source tag `3.10.2`:

- `okio/src/zlibMain/kotlin/okio/InflaterSource.kt`: public `Source.inflate`.
- `okio/src/zlibMain/kotlin/okio/Inflater.kt`: public `Inflater(nowrap)`.
- `okio/src/nativeMain/kotlin/okio/Inflater.kt`: native actual через zlib.
- `okio/build.gradle.kts`: zlibMain включён в JVM и Native targets.

Исходники зависимости не копировались. Отсутствие файла InflaterSource в
upstream commonMain само по себе **не** означает отсутствия API в сборке
данного набора targets; это проверено компиляцией, не предположено.

## Границы результата

После удаления пробы повторены `:shared:jvmTest` (14 tests, 0 failures/errors,
0 skipped по XML) и `:shared:compileKotlinIosSimulatorArm64`: BUILD SUCCESSFUL.
`git diff --check` прошёл. Изменён только этот документ; эти проверки защищают
исходный G2 baseline, не являются новыми codec tests.

Electron не открывался и не запускался. Peer E остаётся статическим evidence
`c540646e1bf06d5f4ea76d6db6b2ab1b82bfe89b`; synthetic 2.0 oracle, Electron
writer round-trip, реальные ZIP/media, Native decoder/device proof не исполнены.
Нет новых dependencies, изменений ports/domain/Gradle/platform и доступа к
пользовательским данным. После решения классификации P05 требует полной
реализации и trust review COORD; эту остановку нельзя интегрировать как codec.
