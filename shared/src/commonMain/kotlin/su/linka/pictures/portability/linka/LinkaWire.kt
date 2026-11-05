package su.linka.pictures.portability.linka

import kotlinx.serialization.json.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.domain.*
import su.linka.pictures.portability.zip.*

private val rootFields = setOf("version", "withoutSpace", "directSet", "description", "pages", "rows", "columns", "cards")
private val pageFields = setOf("id", "mode", "rows", "columns", "cards")
private val cardFields = setOf("id", "cardType", "title", "imagePath", "audioPath", "width", "height", "audioText", "audioVoice")

internal data class WireCard(val raw: JsonObject, val type: Int, val title: String, val image: String?, val audio: String?, val width: Double, val height: Double)
internal data class WirePage(val raw: JsonObject, val rows: Int, val columns: Int, val cards: List<WireCard?>)
internal data class LinkaWire(val raw: JsonObject, val pages: List<WirePage>, val literal: Boolean, val direct: Boolean, val description: String?) {
    val references: Map<String, MediaKind> get() {
        val refs = linkedMapOf<String, MediaKind>()
        for (card in pages.flatMap { it.cards }.filterNotNull()) {
            for ((path, kind) in listOf(card.image to MediaKind.IMAGE, card.audio to MediaKind.AUDIO)) if (path != null) {
                checkData(refs[path] == null || refs[path] == kind)
                refs[path] = kind
            }
        }
        return refs
    }

    companion object {
        fun parse(raw: JsonObject, legacyUpgrade: Boolean = false): LinkaWire {
            raw.structural()
            if (raw.bool("quiz")) fail(ProblemCode.UNSUPPORTED_MODE)
            val version = raw.string("version") ?: fail(ProblemCode.UNSUPPORTED_VERSION)
            if (version !in setOf("1.0", "1.2", "2.0", "3.0") && !(legacyUpgrade && version == "1.3")) fail(ProblemCode.UNSUPPORTED_VERSION)
            val legacy = version != "3.0"
            if (legacy) checkData("pages" !in raw)
            else checkData(listOf("rows", "columns", "cards").none { it in raw })
            val withoutSpace = raw.bool("withoutSpace")
            val direct = raw.bool("directSet")
            val description = raw.string("description")
            val pageObjects = if (legacy) listOf(raw) else {
                val array = raw["pages"] as? JsonArray ?: fail(ProblemCode.INVALID_DATA)
                limit(array.size <= 256)
                checkData(array.isNotEmpty())
                array.map { it as? JsonObject ?: fail(ProblemCode.INVALID_DATA) }
            }
            var content = 0
            val pages = pageObjects.map { page ->
                page.structural()
                page["mode"]?.let { if (it !is JsonPrimitive || !it.isString || it.content != "standard") fail(ProblemCode.UNSUPPORTED_MODE) }
                if (!legacy) sourceId(page["id"])
                val rows = page.dimension("rows", legacy)
                val columns = page.dimension("columns", legacy)
                val cards = page["cards"] as? JsonArray ?: fail(ProblemCode.INVALID_DATA)
                val parsed = cards.map { value ->
                    if (value == JsonNull) null else {
                        val card = value as? JsonObject ?: fail(ProblemCode.INVALID_DATA)
                        card.structural()
                        sourceId(card["id"])
                        val typeValue = card["cardType"] as? JsonPrimitive ?: fail(ProblemCode.INVALID_DATA)
                        checkData(!typeValue.isString)
                        val type = typeValue.intOrNull ?: fail(ProblemCode.INVALID_DATA)
                        if (type !in 0..3) fail(ProblemCode.UNSUPPORTED_FEATURE)
                        if (type == 0) { content++; limit(content <= 100000) }
                        val title = card.string("title", "") ?: ""
                        val image = reference(card.string("imagePath"))
                        val audio = reference(card.string("audioPath"))
                        card.string("audioText"); card.string("audioVoice")
                        checkData(type == 0 || image == null && audio == null)
                        passive(JsonObject(card.filterKeys { it !in cardFields }))
                        WireCard(card, type, title, image, audio, span(card["width"]), span(card["height"]))
                    }
                }
                if (!legacy) passive(JsonObject(page.filterKeys { it !in pageFields }))
                WirePage(if (legacy) JsonObject(emptyMap()) else page, rows, columns, parsed)
            }
            passive(JsonObject(raw.filterKeys { it !in rootFields }))
            return LinkaWire(raw, pages, withoutSpace, direct, description).also { it.references }
        }
    }

    fun document(name: String, assets: Map<String, AssetId>, ids: IdSource): Pair<BoardDocument, Boolean> {
        val usedIds = mutableSetOf<String>()
        fun next(): String = ids.next().also { checkData(it.isNotBlank() && it.length <= 256 && usedIds.add(it)) }
        val boardId = BoardId(next())
        val result = mutableListOf<BoardPage>()
        var normalized = false
        for (page in pages) {
            val capacity = page.rows * page.columns
            val cells = mutableListOf<MutableList<BoardCell>>()
            fun ensure(index: Int) {
                limit(result.size + index + 1 <= 256)
                while (cells.size <= index) cells.add(mutableListOf())
            }
            ensure(0)
            for ((index, card) in page.cards.withIndex()) {
                if (card == null) continue
                val segment = index / capacity
                val position = index % capacity
                ensure(segment)
                normalized = normalized || segment > 0 || card.width != 1.0 || card.height != 1.0 || card.type == 3
                val extras = card.raw.filterKeys { it !in cardFields }.toMutableMap()
                card.raw["id"]?.let { extras["id"] = it }
                // Source IDs and editor speech metadata are passive interchange data, never internal identity/fallback.
                for (key in listOf("audioText", "audioVoice")) card.raw[key]?.let { extras[key] = it }
                val cellExtras = if (card.type == 0) mutableMapOf<String, JsonElement>() else extras
                if (card.type != 0) card.raw["title"]?.let { cellExtras["title"] = it }
                cellExtras["width"] = JsonPrimitive(1)
                cellExtras["height"] = JsonPrimitive(1)
                val content = if (card.type == 0) CardDocument(
                    CardId(next()), card.title, card.image?.let { assets.getValue(it) },
                    card.audio?.let { SpeechSource.Recording(assets.getValue(it), card.raw.string("audioText"), card.raw.string("audioVoice")) } ?: SpeechSource.None,
                    JsonObject(extras).toString(),
                ) else null
                cells[segment].add(BoardCell(position, when (card.type) { 0 -> CellKind.CONTENT; 1 -> CellKind.SPACE; else -> CellKind.EMPTY }, content, JsonObject(cellExtras).toString()))
            }
            for ((index, pageCells) in cells.withIndex()) {
                val extras = page.raw.filterKeys { it !in pageFields }.toMutableMap()
                if (index == 0) page.raw["id"]?.let { extras["id"] = it }
                result.add(BoardPage(PageId(next()), page.rows, page.columns, pageCells, JsonObject(extras).toString()))
            }
        }
        val document = BoardDocument(boardId, name, result,
            BoardBehavior(if (direct) InteractionMode.DIRECT else InteractionMode.COMPOSE, if (literal) InputStyle.LITERAL else InputStyle.CARDS),
            description, JsonObject(raw.filterKeys { it !in rootFields }).toString())
        return document to normalized
    }
}

private fun reference(path: String?): String? = path?.takeIf { it.isNotEmpty() }?.also { safePath(it); checkData(!it.endsWith('/')) }
private fun sourceId(id: JsonElement?) {
    if (id == null || id == JsonNull) return
    checkData(id is JsonPrimitive && (id.isString || id.booleanOrNull == null && id.doubleOrNull?.isFinite() == true))
    if (id is JsonPrimitive && id.isString) passive(JsonObject(mapOf("id" to id)))
}
private fun span(value: JsonElement?): Double {
    if (value == null || value == JsonNull) return 1.0
    checkData(value is JsonPrimitive && !value.isString)
    val n = value.jsonPrimitive.doubleOrNull ?: fail(ProblemCode.INVALID_DATA)
    checkData(n.isFinite())
    return n
}

internal fun encodeWire(document: BoardDocument, paths: Map<AssetId, String>): JsonObject {
    var bytes = 0L
    fun reserve(value: JsonObject): JsonObject {
        bytes += value.toString().encodeToByteArray().size
        limit(bytes <= CONFIG_LIMIT)
        return value
    }
    fun extensions(text: String, known: Set<String>, identity: Boolean = false, spans: Boolean = false, speech: Boolean = false, nonContent: Boolean = false): JsonObject {
        val ext = boundedJson(text)
        ext.structural()
        val allowed = buildSet { if (identity) add("id"); if (spans) addAll(listOf("width", "height")); if (speech) addAll(listOf("audioText", "audioVoice")); if (nonContent) add("title") }
        checkData(ext.keys.none { it in known && it !in allowed })
        if (identity) sourceId(ext["id"])
        if (spans) { span(ext["width"]); span(ext["height"]) }
        if (speech) { ext.string("audioText"); ext.string("audioVoice") }
        return passive(ext)
    }
    val root = extensions(document.extensionsJson, rootFields).toMutableMap()
    root["version"] = JsonPrimitive("3.0")
    root["withoutSpace"] = JsonPrimitive(document.behavior.inputStyle == InputStyle.LITERAL)
    root["directSet"] = JsonPrimitive(document.behavior.defaultMode == InteractionMode.DIRECT)
    document.description?.let { limit(it.length <= 65536); root["description"] = JsonPrimitive(it) }
    reserve(JsonObject(root))
    var contentCount = 0
    root["pages"] = JsonArray(document.pages.map { page ->
        val fields = extensions(page.extensionsJson, pageFields, identity = true).toMutableMap()
        fields["id"] = JsonPrimitive(page.id.value)
        fields["mode"] = JsonPrimitive("standard")
        fields["rows"] = JsonPrimitive(page.rows)
        fields["columns"] = JsonPrimitive(page.columns)
        reserve(JsonObject(fields))
        val byPosition = page.cells.associateBy { it.position }
        val length = (page.cells.maxOfOrNull { it.position } ?: -1) + 1
        fields["cards"] = JsonArray((0 until length).map { position ->
            // Electron filters null slots before placement; explicit empty cards keep sparse indices intact.
            val cell = byPosition[position] ?: return@map reserve(buildJsonObject {
                put("id", "${page.id.value}:$position"); put("cardType", 2)
            })
            val card = cell.card
            val cellExt = extensions(cell.extensionsJson, cardFields, identity = true, spans = true, speech = true, nonContent = card == null)
            val ext = if (card != null) extensions(card.extensionsJson, cardFields, identity = true, speech = true) else cellExt
            val data = ext.toMutableMap()
            if (card != null) {
                limit(card.label.length <= 65536)
                contentCount++; limit(contentCount <= 100000)
                checkData(cellExt.keys.all { it == "width" || it == "height" })
            }
            data["id"] = card?.let { JsonPrimitive(it.id.value) } ?: JsonPrimitive("${page.id.value}:$position")
            data["cardType"] = JsonPrimitive(when (cell.kind) { CellKind.CONTENT -> 0; CellKind.SPACE -> 1; CellKind.EMPTY -> 2 })
            data["width"] = JsonPrimitive(1)
            data["height"] = JsonPrimitive(1)
            if (card != null) {
                data["title"] = JsonPrimitive(card.label)
                card.image?.let { data["imagePath"] = JsonPrimitive(paths.getValue(it)) }
                when (val speech = card.speech) {
                    is SpeechSource.Recording -> {
                        data["audioPath"] = JsonPrimitive(paths.getValue(speech.assetId))
                        data.remove("audioText"); data.remove("audioVoice")
                        speech.audioText?.let { limit(it.length <= 65536); data["audioText"] = JsonPrimitive(it) }
                        speech.audioVoice?.let { limit(it.length <= 65536); data["audioVoice"] = JsonPrimitive(it) }
                    }
                    is SpeechSource.Tts -> fail(ProblemCode.SPEECH_MATERIALIZATION_REQUIRED)
                    SpeechSource.None -> Unit
                }
            }
            reserve(JsonObject(data))
        })
        JsonObject(fields)
    })
    return JsonObject(root).also { LinkaWire.parse(boundedJson(it.toString())) }
}
