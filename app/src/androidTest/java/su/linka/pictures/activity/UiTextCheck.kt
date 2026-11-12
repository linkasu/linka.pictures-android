package su.linka.pictures.activity

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Run on the JVM after compileDebugAndroidTestKotlin, from the project root. */
fun main() {
    fun resources(locale: String): Map<String, String> = buildMap {
        File("shared/src/commonMain/composeResources/$locale").listFiles()!!
            .filter { it.extension == "xml" }.forEach { file ->
                val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    .getElementsByTagName("string")
                for (index in 0 until strings.length) {
                    val node = strings.item(index)
                    put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
                }
            }
    }
    val ru = resources("values-ru")
    val en = resources("values")
    for (selector in UiText.entries) {
        val (russian, english) = selector.labels
        if (selector == UiText.EmptyMessage) {
            check(selector.labels == listOf(ru.getValue("shared_message_1_s").replace("%1\$s", ru.getValue("empty")),
                en.getValue("shared_message_1_s").replace("%1\$s", en.getValue("empty"))))
        } else {
            val prefix = selector in listOf(UiText.PreviewPages, UiText.PreviewCounts)
            check(ru.any { (key, value) ->
                if (prefix) value.startsWith(russian) && en.getValue(key).startsWith(english)
                else value == russian && en[key] == english
            }) { "Missing or stale RU/EN selector: $selector" }
        }
    }

    for (content in listOf("ХОЧУ КУШАТЬ", "Настройки", "Settings", "empty", "пусто", "“%1\$s” «доска»")) {
        check(UiText.SharedMessage.labels(content) == listOf("Общее сообщение: $content", "Shared message: $content"))
        check(UiText.BoardActions.labels(content) == listOf("Действия с набором «$content»", "Actions for board “$content”"))
        check(UiText.ConfirmImport.labels(content) == listOf("Подтверждаю импорт «$content» как новой доски",
            "Confirm import of “$content” as a new board"))
    }
    check(!UiText.EmptyMessage.matches("Shared message: пусто"))
    check(!UiText.EmptyMessage.matches("Общее сообщение: empty"))
    check(UiText.Page.labels(2, 5) == listOf("Страница 2 из 5", "Page 2 of 5"))
    check(UiText.PreviewCounts.labels(1, 9, 9, 9) == listOf(
        "Страниц: 1; карточек: 9; изображений: 9; записей: 9;",
        "Pages: 1; cards: 9; images: 9; recordings: 9;"))
    check(UiText.PreviewCounts.labels(1, 9, 9, 9).intersect(UiText.PreviewCounts.labels(1, 9, 9, 8).toSet()).isEmpty())
    check(UiText.Grid.starts("Grid 3 by 3."))
    check(UiText.Grid.starts("Сетка 3 на 3."))
    check(!UiText.Grid.starts(null))
    check(!UiText.Grid.starts("Card 3"))
    check(!UiText.OperationCompleted.matches("Operation cancelled."))
    check(!UiText.OperationCompleted.matches("Операция не выполнена."))
    for (status in listOf(UiText.PlaybackCompleted, UiText.PlaybackStopped, UiText.SpeakingMessage)) {
        for (label in status.labels) {
            check(status.matches(label))
            check(!status.matches("$label unexpected"))
            for (other in UiText.entries.filter { it != status }) check(!other.matches(label))
        }
    }
    check(UiText.IncomingFailed.labels("UNSAFE_ARCHIVE").none { it in UiText.IncomingFailed.labels("IO_FAILURE") })
    val cold = StartupWait(0)
    check(!cold.expired(0, listOf("0/5")))
    check(!cold.expired(20_000, listOf("3/5")))
    check(!cold.expired(64_999, listOf("3/5")))
    check(cold.expired(65_000, listOf("3/5")))
    val busy = StartupWait(0)
    for (now in 0L..160_000L step 20_000L) check(!busy.expired(now, listOf("progress:$now")))
    check(busy.expired(180_000, listOf("new progress cannot defeat the total bound")))
    for ((start, target) in listOf(3 to 0, 0 to 3, 2 to 1)) {
        var position = start
        val search = ScrollSearch(false)
        repeat(12) {
            if (position != target) search.advance { backwards ->
                val next = (position + if (backwards) -1 else 1).coerceIn(0, 3)
                (next != position).also { position = next }
            }
        }
        check(position == target) { "Scroll search missed target from $start" }
    }
    val bounded = ScrollSearch(false)
    var moves = 0
    repeat(100) { bounded.advance { moves++; true } }
    check(moves == 24) { "Scroll search must stop after both bounded directions" }
    println("UI selector catalog: ${UiText.entries.size} RU/EN mappings verified; user text and outcomes preserved")
}
