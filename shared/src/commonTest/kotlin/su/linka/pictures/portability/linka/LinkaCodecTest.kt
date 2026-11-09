package su.linka.pictures.portability.linka

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.Buffer
import okio.buffer
import okio.Source
import okio.Timeout
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import su.linka.pictures.application.*
import su.linka.pictures.application.portability.PreparedBoard
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.binary.Crc32
import su.linka.pictures.portability.zip.*
import kotlin.test.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LinkaCodecTest {
    @Test fun legacySpaceAndEmptyIgnoreInactiveMediaLikeElectronWithoutChangingSlots() = runTest {
        val config = """{"version":"1.0","rows":1,"columns":2,"withoutSpace":true,"cards":[{"id":45,"cardType":1,"title":",","imagePath":"inactive.png","custom":"keep"},{"id":59,"cardType":2,"title":"+","imagePath":"inactive.png","audioPath":"unused.mp3"}]}"""
        val rig = CodecTestRig()
        val bytes = syntheticZip(listOf(configEntry(config), SyntheticEntry("inactive.png", byteArrayOf(1)), SyntheticEntry("unused.mp3", byteArrayOf(2))))
        val input = rig.input(bytes)
        val board = rig.codec.read(input, "Клавиатура").value()
        try {
            assertEquals(3, board.report.ignoredInactiveMediaReferences)
            val cells = board.document.pages.single().cells
            assertEquals(listOf(0, 1), cells.map { it.position })
            assertEquals(listOf(CellKind.SPACE, CellKind.EMPTY), cells.map { it.kind })
            assertTrue(cells.all { it.card == null })
            assertEquals(InputStyle.LITERAL, board.document.behavior.inputStyle)
            assertEquals("keep", boundedJson(cells.first().extensionsJson).string("custom"))
            assertEquals(0, rig.inspections)
            assertEquals(0, rig.prepares)
            assertContentEquals(bytes, rig.raw(input))
            val exported = rig.codec.write(PublishedBoard(board.document, 1), board.lease).value()
            try {
                val again = rig.codec.read(exported, "Клавиатура").value()
                try { assertEquals(cells.map { it.kind }, again.document.pages.single().cells.map { it.kind }) }
                finally { again.close() }
            } finally { rig.discard(exported) }
        } finally { board.close() }
        clean(rig)
    }

    @Test fun ignoredLegacyAttachmentsStillValidatePathsAndCanonicalFormatRemainsStrict() = runTest {
        val legacy = """{"version":"1.0","rows":1,"columns":1,"cards":[{"cardType":2,"imagePath":"../escape"}]}"""
        val rig = CodecTestRig()
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(legacy)))), "test"), ProblemCode.UNSAFE_ARCHIVE)
        val canonical = cardConfig(card("space", image = "inactive.png").changed("cardType", JsonPrimitive(1)))
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(canonical), SyntheticEntry("inactive.png", byteArrayOf(1))))), "test"), ProblemCode.INVALID_DATA)
        val privateUpgrade = legacy.replace("1.0", "1.3").replace("../escape", "inactive.png")
        assertFailure(rig.codec.readLegacyUpgrade(rig.input(syntheticZip(listOf(configEntry(privateUpgrade), SyntheticEntry("inactive.png", byteArrayOf(1))))), "test"), ProblemCode.INVALID_DATA)
        assertEquals(0, rig.prepares); assertEquals(0, rig.inspections)
        clean(rig)
    }
    @Test fun privateUpgradeAcceptsShippedFlat13WithoutExpandingExternalVersionWhitelist() = runTest {
        val config = """{"version":"1.3","rows":1,"columns":1,"directSet":true,"description":"сохранить","cards":[{"id":7,"cardType":0,"title":"Болит","width":2,"height":2,"custom":{"source":"legacy"}}]}"""
        val rig = CodecTestRig()
        val original = syntheticZip(listOf(configEntry(config)))
        val input = rig.input(original)
        assertFailure(rig.codec.read(input, "Болит"), ProblemCode.UNSUPPORTED_VERSION)
        val upgraded = rig.codec.readLegacyUpgrade(input, "Болит").value()
        try {
            assertEquals("сохранить", upgraded.document.description)
            assertEquals(InteractionMode.DIRECT, upgraded.document.behavior.defaultMode)
            assertEquals("Болит", upgraded.document.pages.single().cells.single().card!!.label)
            assertTrue(upgraded.document.pages.single().cells.single().card!!.extensionsJson.contains("legacy"))
            assertTrue(upgraded.report.layoutNormalized)
            assertContentEquals(original, rig.raw(input))
        } finally { upgraded.close() }
        assertFailure(rig.codec.readLegacyUpgrade(rig.input(syntheticZip(listOf(configEntry(config.replace("1.3", "2.99"))))), "test"), ProblemCode.UNSUPPORTED_VERSION)
        assertFailure(rig.codec.readLegacyUpgrade(rig.input(syntheticZip(listOf(configEntry(config.replace("\"rows\":1", "\"actions\":null,\"rows\":1"))))), "test"), ProblemCode.UNSUPPORTED_FEATURE)
        clean(rig)
    }

    @Test fun lossyNativeNameIsRejectedBeforePreparingAnyMedia() = runTest {
        for (name in listOf("left\u0000right", "left\uD800right", "left\uDC00right")) {
            val rig = CodecTestRig()
            val input = rig.input(syntheticZip(listOf(configEntry(cardConfig(card("x", image = "image"))), SyntheticEntry("image", byteArrayOf(1, 2)))))
            assertFailure(rig.codec.read(input, name), ProblemCode.INVALID_DATA)
            assertEquals(0, rig.prepares)
            assertEquals(0, rig.inspections)
            clean(rig)
        }
    }

    @Test fun zipFilenameBudgetUsesUtf8BytesBeforeCentralOrLocalNameAllocation() = runTest {
        for (directory in listOf(false, true)) {
            val name = if (directory) "é".repeat(2047) + "a/" else "é".repeat(2048)
            assertEquals(4096, name.encodeToByteArray().size)
            val rig = CodecTestRig()
            val board = rig.codec.read(rig.input(syntheticZip(listOf(configEntry(simpleConfig), SyntheticEntry(name, byteArrayOf())))), "test").value()
            board.close()
            assertEquals(0, rig.prepares); assertEquals(0, rig.inspections); clean(rig)
            val over = if (directory) name.dropLast(1) + "a/" else name + "a"
            assertEquals(4097, over.encodeToByteArray().size)
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(simpleConfig), SyntheticEntry(over, byteArrayOf())))), "test"), ProblemCode.LIMIT_EXCEEDED)
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(simpleConfig), SyntheticEntry("small", byteArrayOf(), localName = over)))), "test"), ProblemCode.LIMIT_EXCEEDED)
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(simpleConfig), SyntheticEntry(name, byteArrayOf(), flags = 0)))), "test"), ProblemCode.UNSAFE_ARCHIVE)
            assertEquals(0, rig.prepares); assertEquals(0, rig.inspections); clean(rig)
        }
        val truncatedName = syntheticZip(listOf(configEntry(simpleConfig)))
        val centralOffset = Buffer().write(truncatedName, truncatedName.size - 6, 4).readIntLe()
        truncatedName[centralOffset + 28] = 1
        truncatedName[centralOffset + 29] = 0x10
        val rig = CodecTestRig()
        // No 4097 name bytes exist: LIMIT rather than EOF proves the header is checked before readByteArray.
        assertFailure(rig.codec.read(rig.input(truncatedName), "test"), ProblemCode.LIMIT_EXCEEDED)
        assertEquals(0, rig.prepares); assertEquals(0, rig.inspections); clean(rig)
    }

    @Test fun aggregateCentralFilenameBudgetIncludesDirectoriesAndAcceptsExactlyOneMiB() = runTest {
        val directories = List(255) { index -> SyntheticEntry(index.toString().padStart(4, '0') + "x".repeat(4091) + "/", byteArrayOf()) }
        val remaining = (1024 * 1024 - "config.json".encodeToByteArray().size - 255 * 4096)
        for (extra in 0..1) {
            val last = SyntheticEntry("last" + "y".repeat(remaining + extra - 5) + "/", byteArrayOf())
            val entries = listOf(configEntry(simpleConfig)) + directories + last
            assertEquals(1024L * 1024 + extra, entries.sumOf { it.name.encodeToByteArray().size.toLong() })
            val rig = CodecTestRig()
            val result = rig.codec.read(rig.input(syntheticZip(entries)), "test")
            if (extra == 0) result.value().close() else assertFailure(result, ProblemCode.LIMIT_EXCEEDED)
            assertEquals(0, rig.prepares); assertEquals(0, rig.inspections); clean(rig)
        }
    }

    @Test fun mediaReferenceUtf8PathBudgetRejectsBeforeLookupPreparationOrInspection() = runTest {
        for (reference in listOf("x".repeat(4097), "é".repeat(2048) + "a")) {
            val rig = CodecTestRig()
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(cardConfig(card("x", image = reference)))))), "test"), ProblemCode.LIMIT_EXCEEDED)
            assertEquals(0, rig.prepares); assertEquals(0, rig.inspections); clean(rig)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun nonCooperativeFinalAcquireAfterCancellationCannotHandOffPreparedBoard() = runTest {
        val rig = CodecTestRig()
        var pending: Continuation<OperationResult<MediaLease>>? = null
        var roots: Set<AssetId> = emptySet()
        rig.acquireOverride = { assets ->
            roots = assets
            suspendCoroutine { pending = it }
        }
        val original = syntheticZip(listOf(configEntry(cardConfig(card("x", image = "image"))), SyntheticEntry("image", byteArrayOf(1, 2))))
        val file = rig.input(original)
        var handedOff = false
        var cancellationObserved = false
        val owner = launch {
            try {
                rig.codec.read(file, "test")
                handedOff = true
            } catch (cancelled: CancellationException) {
                cancellationObserved = true
                throw cancelled
            }
        }
        runCurrent()
        assertNotNull(pending)
        assertEquals(1, rig.prepares); assertEquals(1, rig.acquires); assertEquals(1, rig.openLeases)
        owner.cancel()
        pending!!.resume(OperationResult.Completed(rig.Lease(roots)))
        owner.join()
        assertTrue(cancellationObserved); assertTrue(owner.isCancelled); assertFalse(handedOff)
        assertEquals(1, rig.assets.size)
        assertContentEquals(byteArrayOf(1, 2), rig.bytes.getValue(roots.single().value))
        assertContentEquals(original, rig.raw(file))
        clean(rig)
    }

    private fun JsonObject.changed(key: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().also { it[key] = value })
    private val simple get() = wireJson.parseToJsonElement(simpleConfig).jsonObject
    private fun cardConfig(vararg cards: JsonObject, rows: Int = 1, columns: Int = 1): String = simple.changed("pages", JsonArray(listOf(
        simple["pages"]!!.jsonArray[0].jsonObject.changed("rows", JsonPrimitive(rows)).changed("columns", JsonPrimitive(columns)).changed("cards", JsonArray(cards.toList()))
    ))).toString()
    private fun card(title: String, image: String? = null, audio: String? = null): JsonObject = buildJsonObject {
        put("id", 7); put("cardType", 0); put("title", title)
        image?.let { put("imagePath", it) }; audio?.let { put("audioPath", it) }
    }
    private suspend fun read(rig: CodecTestRig, json: String, vararg media: SyntheticEntry): PreparedBoard =
        rig.codec.read(rig.input(syntheticZip(listOf(configEntry(json)) + media)), "test").value()
    private suspend fun exportedConfig(rig: CodecTestRig, file: LocalFile): JsonObject {
        var config: JsonObject? = null
        CheckedZip.open(rig, file, ZipProfile.LINKA).visit { entry, source -> if (entry.name == "config.json") config = boundedJson(source.buffer().readUtf8()) }
        return config!!
    }
    private fun assertFailure(result: OperationResult<*>, code: ProblemCode) = assertEquals(code, assertIs<OperationResult.Failed>(result).problem.code)
    private fun clean(rig: CodecTestRig, leases: Int = 0) {
        assertEquals(0, rig.openSources); assertEquals(0, rig.openSinks); assertEquals(leases, rig.openLeases); assertTrue(rig.temporary.isEmpty())
    }

    @Test fun reservedKeysAtEveryStructuralLevelIncludingNullAndEmptyAreRejectedBeforePrepare() = runTest {
        for (key in activeKeys) for (value in listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))) for (level in 0..2) {
            val root = simple
            val page = root["pages"]!!.jsonArray[0].jsonObject
            val card = page["cards"]!!.jsonArray[0].jsonObject.changed("imagePath", JsonPrimitive("image.png"))
            val changed = when (level) {
                0 -> root.changed(key, value).changed("pages", JsonArray(listOf(page.changed("cards", JsonArray(listOf(card))))))
                1 -> root.changed("pages", JsonArray(listOf(page.changed(key, value).changed("cards", JsonArray(listOf(card))))))
                else -> root.changed("pages", JsonArray(listOf(page.changed("cards", JsonArray(listOf(card.changed(key, value)))))))
            }
            val rig = CodecTestRig()
            val bytes = syntheticZip(listOf(configEntry(changed.toString()), SyntheticEntry("image.png", byteArrayOf(1))))
            assertFailure(rig.codec.read(rig.input(bytes), "test"), ProblemCode.UNSUPPORTED_FEATURE)
            assertEquals(0, rig.prepares); assertEquals(0, rig.acquires); clean(rig)
        }
    }

    @Test fun latePageAndCardModesOrFeaturesRejectTheWholeArchive() = runTest {
        for (bad in listOf("quiz", "match", "mystery")) {
            val page = simple["pages"]!!.jsonArray[0].jsonObject
            val root = simple.changed("pages", JsonArray(listOf(page, page.changed("mode", JsonPrimitive(bad)))))
            val rig = CodecTestRig()
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(root.toString())))), "test"), ProblemCode.UNSUPPORTED_MODE)
            assertEquals(0, rig.prepares); clean(rig)
        }
        val page = simple["pages"]!!.jsonArray[0].jsonObject
        val late = page.changed("cards", JsonArray(listOf(card("first", "image"), card("last").changed("actions", JsonNull))))
        val rig = CodecTestRig()
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(simple.changed("pages", JsonArray(listOf(page, late))).toString()), SyntheticEntry("image", byteArrayOf(1))))), "test"), ProblemCode.UNSUPPORTED_FEATURE)
        assertEquals(0, rig.prepares); clean(rig)
    }

    @Test fun semanticRoundTripPreservesAllFieldsPassiveMetadataRecordingAndLiteralTitle() = runTest {
        val custom = buildJsonObject { put("actions", buildJsonArray { add(buildJsonObject { put("type", "openBoard") }) }); put("mode", "quiz") }
        val first = card("literal title", "nested/image.png", "nested/UUID..wav")
            .changed("audioText", JsonPrimitive("editor text, not spoken title"))
            .changed("audioVoice", JsonPrimitive("voice"))
            .changed("custom", custom).changed("answer", JsonPrimitive("passive"))
        val second = card("no recording").changed("audioText", JsonPrimitive("not a fallback"))
        val page = simple["pages"]!!.jsonArray[0].jsonObject.changed("columns", JsonPrimitive(2))
            .changed("cards", JsonArray(listOf(first, second))).changed("custom", custom).changed("question", custom)
        val root = simple.changed("withoutSpace", JsonPrimitive(true)).changed("directSet", JsonPrimitive(true))
            .changed("description", JsonPrimitive("description"))
            .changed("custom", custom).changed("Actions", JsonPrimitive("case sensitive passive"))
            .changed("quizAutoNext", JsonPrimitive(true)).changed("pages", JsonArray(listOf(page)))
        val rig = CodecTestRig()
        val original = syntheticZip(listOf(configEntry(root.toString()), SyntheticEntry("nested/image.png", byteArrayOf(1, 2)), SyntheticEntry("nested/UUID..wav", byteArrayOf(3, 4, 5))))
        val input = rig.input(original)
        val board = rig.codec.read(input, "test").value()
        assertContentEquals(original, rig.raw(input))
        assertEquals(InputStyle.LITERAL, board.document.behavior.inputStyle)
        assertEquals(InteractionMode.DIRECT, board.document.behavior.defaultMode)
        val cards = board.document.pages.single().cells.mapNotNull { it.card }
        assertEquals("literal title", cards[0].label)
        assertEquals("editor text, not spoken title", assertIs<SpeechSource.Recording>(cards[0].speech).audioText)
        assertEquals(SpeechSource.None, cards[1].speech)
        assertNotEquals(cards[0].id, cards[1].id)
        assertNotEquals("7", cards[0].id.value)
        assertEquals(JsonPrimitive(7), boundedJson(cards[0].extensionsJson)["id"])
        assertEquals(5, board.report.mediaBytes)
        val output = rig.codec.write(PublishedBoard(board.document, 1), board.lease).value()
        println("P05_WRITER_ZIP_HEX=" + rig.raw(output).toByteString().hex())
        assertEquals(1, rig.openLeases)
        var exported: JsonObject? = null
        CheckedZip.open(rig, output, ZipProfile.LINKA).visit { entry, source -> if (entry.name == "config.json") exported = boundedJson(source.buffer().readUtf8()) }
        val wire = exported!!
        assertEquals("3.0", wire.string("version"))
        assertEquals(root["custom"], wire["custom"])
        assertEquals(root["Actions"], wire["Actions"])
        val exportedPage = wire["pages"]!!.jsonArray[0].jsonObject
        assertEquals(page["custom"], exportedPage["custom"])
        val exportedCard = exportedPage["cards"]!!.jsonArray[0].jsonObject
        assertEquals(JsonPrimitive(cards[0].id.value), exportedCard["id"])
        assertEquals(JsonPrimitive(board.document.pages.single().id.value), exportedPage["id"])
        assertNotEquals(exportedCard["id"], exportedPage["cards"]!!.jsonArray[1].jsonObject["id"])
        assertEquals(first["custom"], exportedCard["custom"])
        assertEquals(first["answer"], exportedCard["answer"])
        assertEquals(first["audioVoice"], exportedCard["audioVoice"])
        val again = rig.codec.read(output, "test").value()
        assertEquals(board.document.behavior, again.document.behavior)
        assertEquals(board.document.description, again.document.description)
        assertEquals(cards.map { it.label }, again.document.pages.single().cells.mapNotNull { it.card?.label })
        assertEquals(board.document.assetIds(), again.document.assetIds())
        assertNotEquals(board.document.id, again.document.id)
        val output2 = rig.codec.write(PublishedBoard(board.document, 2), board.lease).value()
        assertContentEquals(rig.raw(output), rig.raw(output2))
        rig.discard(output); rig.discard(output2); again.close(); board.close(); clean(rig)
    }

    @Test fun sourceSparsePositionsSpansAndOverflowRetainEveryContentAndPlaceholderIsStableEmpty() = runTest {
        val wide = card("wide").changed("width", JsonPrimitive(8)).changed("height", JsonPrimitive(1))
        val root = simple.changed("pages", JsonArray(listOf(simple["pages"]!!.jsonArray[0].jsonObject
            .changed("rows", JsonPrimitive(2)).changed("columns", JsonPrimitive(2))
            .changed("cards", JsonArray(listOf(JsonNull, wide, card("covered"), card("next"),
                buildJsonObject { put("id", "placeholder"); put("cardType", 3); put("custom", true) },
                buildJsonObject { put("id", "space"); put("cardType", 1); put("title", " "); put("custom", "space metadata") }))))))
        val rig = CodecTestRig()
        val board = read(rig, root.toString())
        assertEquals(3, board.report.cards)
        assertTrue(board.report.layoutNormalized)
        assertEquals(1, board.document.pages.first().cells.first().position)
        assertEquals(listOf("wide", "covered", "next"), board.document.pages.flatMap { it.cells }.mapNotNull { it.card?.label })
        assertEquals(2, board.document.pages.size)
        assertEquals(listOf(1, 2, 3), board.document.pages.first().cells.map { it.position })
        assertEquals(listOf(0, 1), board.document.pages.last().cells.map { it.position })
        assertEquals(CellKind.EMPTY, board.document.pages.last().cells.first().kind)
        assertEquals(CellKind.SPACE, board.document.pages.last().cells.last().kind)
        assertEquals(JsonPrimitive(true), boundedJson(board.document.pages.last().cells.first().extensionsJson)["custom"])
        val idCalls = rig.idCalls
        val output = rig.codec.write(PublishedBoard(board.document, 1), board.lease).value()
        assertEquals(idCalls, rig.idCalls)
        val second = rig.codec.read(output, "test").value()
        assertEquals(3, second.report.cards)
        assertEquals(board.document.pages.size, second.document.pages.size)
        assertTrue(rig.idCalls > idCalls)
        rig.discard(output); board.close(); second.close(); clean(rig)
    }

    @Test fun writerRequiresExplicitSpeechMaterializationAndNeverConsumesCallerLease() = runTest {
        val rig = CodecTestRig()
        val prepared = read(rig, simpleConfig)
        val page = prepared.document.pages.single()
        val cell = page.cells.single()
        val document = prepared.document.copy(pages = listOf(page.copy(cells = listOf(cell.copy(card = cell.card!!.copy(speech = SpeechSource.Tts("text")))))))
        assertFailure(rig.codec.write(PublishedBoard(document, 1), prepared.lease), ProblemCode.SPEECH_MATERIALIZATION_REQUIRED)
        assertEquals(1, rig.openLeases); assertTrue(rig.temporary.isEmpty())
        prepared.close(); clean(rig)
    }

    @Test fun writerRejectsKnownFieldOverridesButNormalizesOpaqueGeometry() = runTest {
        val rig = CodecTestRig()
        val board = read(rig, cardConfig(card("one"), card("two"), columns = 2))
        val page = board.document.pages.single()
        val first = page.cells.first()
        val overridden = first.copy(card = first.card!!.copy(extensionsJson = """{"title":"override"}"""))
        val overlapping = first.copy(extensionsJson = """{"width":2}""")
        val invalid = board.document.copy(pages = listOf(page.copy(cells = listOf(overridden, page.cells[1]))))
        assertFailure(rig.codec.write(PublishedBoard(invalid, 1), board.lease), ProblemCode.INVALID_DATA)
        clean(rig, leases = 1)
        val uniform = board.document.copy(pages = listOf(page.copy(cells = listOf(overlapping, page.cells[1]))))
        val output = rig.codec.write(PublishedBoard(uniform, 1), board.lease).value()
        val cards = exportedConfig(rig, output)["pages"]!!.jsonArray.single().jsonObject["cards"]!!.jsonArray
        assertEquals(listOf("one", "two"), cards.map { it.jsonObject.string("title") })
        assertTrue(cards.all { it.jsonObject["width"] == JsonPrimitive(1) && it.jsonObject["height"] == JsonPrimitive(1) })
        rig.discard(output); clean(rig, leases = 1)
        board.close(); clean(rig)
    }

    @Test fun legacyVersionsAndCanonicalStringDimensionsAcceptOnlyTheFrozenShapes() = runTest {
        for (version in listOf("1.0", "1.2", "2.0")) {
            val json = """{"version":"$version","rows":"1","columns":"1","withoutSpace":false,"cards":[{"id":9,"cardType":0,"title":"a"},{"id":9,"cardType":0,"title":"b"}]}"""
            val rig = CodecTestRig()
            val board = read(rig, json)
            assertEquals(2, board.document.pages.size)
            assertEquals(2, board.report.cards)
            assertEquals(2, board.document.pages.flatMap { it.cells }.mapNotNull { it.card?.id }.toSet().size)
            board.close(); clean(rig)
        }
        for (dimension in listOf("\"01\"", "\"+1\"", "\"1.0\"", "\" 1\"", "true", "0", "-1", "1.5", "null", "\"NaN\"")) {
            val rig = CodecTestRig()
            val json = """{"version":"1.2","rows":$dimension,"columns":1,"cards":[]}"""
            assertIs<OperationResult.Failed>(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(json)))), "test"))
            clean(rig)
        }
        val overflow = CodecTestRig()
        assertFailure(overflow.codec.read(overflow.input(syntheticZip(listOf(configEntry(cardConfig(*Array(257) { card("retain or reject") }))))), "test"), ProblemCode.LIMIT_EXCEEDED)
        clean(overflow)
        for (version in listOf("1.1", "1.3", "4.0", "3", "")) {
            val rig = CodecTestRig()
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(simple.changed("version", JsonPrimitive(version)).toString())))), "test"), ProblemCode.UNSUPPORTED_VERSION)
        }
        val rig = CodecTestRig()
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(simple.changed("quiz", JsonPrimitive(true)).toString())))), "test"), ProblemCode.UNSUPPORTED_MODE)
        clean(rig)
    }

    @Test fun installedInflaterReadsActualFixedDeflateAndRejectsActualInflateOverBudget() = runTest {
        val legacy = """{"version":"1.2","withoutSpace":false,"rows":"1","columns":"1","cards":[{"id":7,"cardType":0,"title":"legacy"}]}"""
        val compressed = "3d8b4b0a80200044ef326b896a13788dda450b312bc154fc1422de3d2368f766de4cc6259c974683a26b7a10dc321c2686d1322e4037a6bc2070e6f6efa27a6e543cf59f985b2bcf1972051dbe624ab65e5b822083aa042576c613ca521e".decodeHex().toByteArray()
        val rig = CodecTestRig()
        val entry = configEntry(legacy).copy(compressed = compressed, method = 8)
        for (variant in listOf(entry, entry.copy(flags = 0x808, descriptor = true))) {
            val board = rig.codec.read(rig.input(syntheticZip(listOf(variant))), "test").value()
            assertEquals("legacy", board.document.pages.single().cells.single().card!!.label)
            board.close()
        }
        assertIs<OperationResult.Failed>(rig.codec.read(rig.input(syntheticZip(listOf(entry.copy(size = 0, localSize = 0)))), "test"))
        val bomb = ("ecc18100000000802095fd2917a90a" + "00".repeat(4094) + "80db8303020000000021ff5f3724" + "00".repeat(4033) + "ac05").decodeHex().toByteArray()
        val hostile = SyntheticEntry("config.json", byteArrayOf(), compressed = bomb, method = 8, size = CONFIG_LIMIT, crc = 174521935)
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(hostile))), "test"), ProblemCode.LIMIT_EXCEEDED)
        assertEquals(0, rig.prepares); clean(rig)
        for (broken in listOf(compressed.copyOf(compressed.size - 2), byteArrayOf(0xff.toByte()))) {
            assertIs<OperationResult.Failed>(rig.codec.read(rig.input(syntheticZip(listOf(entry.copy(compressed = broken)))), "test"))
            clean(rig)
        }
    }

    @Test fun hostileZipMetadataPathsAndPayloadsNeverPrepareMedia() = runTest {
        val valid = configEntry(simpleConfig)
        val image = SyntheticEntry("image", byteArrayOf(1))
        val hostileEntries = listOf(
            listOf(valid.copy(crc = 1)), listOf(valid.copy(localSize = valid.size + 1)),
            listOf(valid.copy(localName = "different")), listOf(valid.copy(localOffsetDelta = 1)),
            listOf(valid.copy(flags = 0x801)), listOf(valid.copy(flags = 0x840)),
            listOf(valid.copy(method = 12)), listOf(valid.copy(needed = 45)), listOf(valid.copy(disk = 1)),
            listOf(valid.copy(extra = byteArrayOf(1, 0, 0, 0))),
            listOf(valid, image.copy(external = 0xa000 shl 16)),
            listOf(valid, image.copy(external = 0xa000 shl 16, madeBy = 20)),
            listOf(valid, image.copy(external = 8)),
            listOf(valid, image.copy(external = 0x1000 shl 16)),
            listOf(valid, image, image.copy(name = "IMAGE")),
            listOf(valid, image.copy(name = "é"), image.copy(name = "e\u0301")),
            listOf(valid, image.copy(name = "a"), image.copy(name = "A/b")),
            listOf(valid, image.copy(name = "a/"), image.copy(name = "a")),
            listOf(valid, image.copy(name = "../x")), listOf(valid, image.copy(name = "a/../x")),
            listOf(valid, image.copy(name = "/x")), listOf(valid, image.copy(name = "a\\b")),
            listOf(valid, image.copy(name = "C:x")), listOf(valid, image.copy(name = "a\u0000b")),
            listOf(valid, image.copy(name = "a//b")), listOf(valid, image.copy(name = "a./b")),
            listOf(valid, image.copy(name = "a /b")), listOf(valid, image.copy(name = "NUL.wav")),
            listOf(valid, image.copy(name = "com1.txt")), listOf(image),
        )
        for (entries in hostileEntries) {
            val rig = CodecTestRig()
            assertIs<OperationResult.Failed>(rig.codec.read(rig.input(syntheticZip(entries)), "test"), entries.map { it.name }.toString())
            assertEquals(0, rig.prepares); clean(rig)
        }
        val rig = CodecTestRig()
        val bytes = syntheticZip(listOf(valid))
        for (truncated in listOf(bytes.copyOf(20), bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1))) {
            assertIs<OperationResult.Failed>(rig.codec.read(rig.input(truncated), "test")); clean(rig)
        }
        val crc = Crc32().also { it.update("123456789".encodeToByteArray()) }
        assertEquals(0xcbf43926L, crc.value)
    }

    @Test fun declaredEntryCountAggregateAndPerFileLimitsAreEnforced() = runTest {
        val valid = configEntry(simpleConfig)
        val tooMany = listOf(valid) + (0 until 2000).map { SyntheticEntry("e$it", byteArrayOf()) }
        val tooLarge = listOf(valid, SyntheticEntry("huge", byteArrayOf(), method = 8, size = MEDIA_LIMIT + 1))
        val aggregate = listOf(valid) + (0 until 8).map { SyntheticEntry("e$it", byteArrayOf(), method = 8, size = MEDIA_LIMIT) }
        for (entries in listOf(tooMany, tooLarge, aggregate)) {
            val rig = CodecTestRig()
            assertFailure(rig.codec.read(rig.input(syntheticZip(entries)), "test"), ProblemCode.LIMIT_EXCEEDED)
            assertEquals(0, rig.prepares); clean(rig)
        }
    }

    @Test fun jsonDimensionsDepthStringsNamesAndDuplicateFieldsAreBounded() = runTest {
        val bad = listOf(
            simpleConfig.replace("\"version\":\"3.0\"", "\"version\":\"3.0\",\"version\":\"3.0\""),
            simple.changed("description", JsonPrimitive("x".repeat(65537))).toString(),
            simple.changed("x".repeat(257), JsonNull).toString(),
            simpleConfig.dropLast(1) + ",\"custom\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}",
            cardConfig(card("a"), rows = 65),
            simple.changed("pages", JsonArray(List(257) { simple["pages"]!!.jsonArray[0] })).toString(),
        )
        for (json in bad) {
            val rig = CodecTestRig()
            assertIs<OperationResult.Failed>(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(json)))), "test"))
            assertEquals(0, rig.prepares); clean(rig)
        }
    }

    @Test fun missingMediaInspectionFailureHashFailureAndAcquireFailureCleanAllOwnedResources() = runTest {
        val json = cardConfig(card("a", "image", "audio"))
        val rig = CodecTestRig()
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(json)))), "test"), ProblemCode.MISSING_ASSET)
        assertEquals(0, rig.prepares); clean(rig)
        for (kind in 0..4) {
            val r = CodecTestRig()
            when (kind) {
                0 -> r.inspectionOutcome = OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA))
                1 -> r.imageInfo = ImageInfo(8193, 1, "image/png")
                2 -> r.audioInfo = AudioInfo(600001, "audio/wav")
                3 -> r.badHash = true
                4 -> r.failAcquire = true
            }
            assertIs<OperationResult.Failed>(r.codec.read(r.input(syntheticZip(listOf(configEntry(json), SyntheticEntry("image", byteArrayOf(1)), SyntheticEntry("audio", byteArrayOf(2))))), "test"))
            clean(r)
        }
    }

    @Test fun unconfirmedPreparePropagatesWithoutFinalPublicationAndClosesEarlierLease() = runTest {
        val rig = CodecTestRig()
        rig.failPrepareAt = 2
        rig.prepareOutcome = OperationResult.UnconfirmedCommit
        val archive = syntheticZip(listOf(configEntry(cardConfig(card("a", "image", "audio"))), SyntheticEntry("image", byteArrayOf(1)), SyntheticEntry("audio", byteArrayOf(2))))
        assertEquals(OperationResult.UnconfirmedCommit, rig.codec.read(rig.input(archive), "test"))
        assertEquals(2, rig.prepares); assertEquals(0, rig.acquires)
        assertEquals(1, rig.assets.size)
        clean(rig)
    }

    @Test fun cleanupErrorsDoNotMaskUnconfirmedOutcomeOrLeakFinalLeaseOnFailedRead() = runTest {
        for (unconfirmed in listOf(false, true)) {
            val rig = CodecTestRig()
            rig.failDiscardAfterRemoval = true
            if (unconfirmed) { rig.failPrepareAt = 2; rig.prepareOutcome = OperationResult.UnconfirmedCommit }
            val archive = syntheticZip(listOf(configEntry(cardConfig(card("a", "image", "audio"))), SyntheticEntry("image", byteArrayOf(1)), SyntheticEntry("audio", byteArrayOf(2))))
            val result = rig.codec.read(rig.input(archive), "test")
            if (unconfirmed) assertEquals(OperationResult.UnconfirmedCommit, result)
            else assertFailure(result, ProblemCode.IO_FAILURE)
            assertEquals(2, rig.discardAttempts)
            assertEquals(if (unconfirmed) 0 else 1, rig.acquires)
            clean(rig)
        }
    }

    @Test fun cancellationAndCancelledResultsStayDistinctAndReleasePartialPreparation() = runTest {
        for (cancelled in listOf(false, true)) {
            val rig = CodecTestRig()
            rig.failPrepareAt = 2; rig.prepareOutcome = OperationResult.Cancelled
            if (cancelled) {
                var calls = 0
                rig.onInspection = { if (++calls == 2) throw CancellationException("test") }
            }
            val input = rig.input(syntheticZip(listOf(configEntry(cardConfig(card("a", "image", "audio"))), SyntheticEntry("image", byteArrayOf(1)), SyntheticEntry("audio", byteArrayOf(2)))))
            if (cancelled) assertFailsWith<CancellationException> { rig.codec.read(input, "test") }
            else assertEquals(OperationResult.Cancelled, rig.codec.read(input, "test"))
            assertEquals(0, rig.acquires); clean(rig)
        }
    }

    @Test fun deflateDataDescriptorAndNestedDirectoryEntriesWorkWithoutExtraction() = runTest {
        val rig = CodecTestRig()
        val input = rig.input(syntheticZip(listOf(configEntry(cardConfig(card("a", audio = "a/UUID..wav"))).copy(flags = 0x808, descriptor = true),
            SyntheticEntry("a/", byteArrayOf(), external = 0x4000 shl 16), SyntheticEntry("a/UUID..wav", byteArrayOf(1, 2), flags = 0x808))))
        CheckedZip.open(rig, input, ZipProfile.LINKA).visit { _, _ -> }
        val board = rig.codec.read(input, "test").value()
        assertIs<SpeechSource.Recording>(board.document.pages.single().cells.single().card!!.speech)
        board.close(); clean(rig)
    }

    @Test fun archiveActualBytesAndContentCellCountsCannotBypassLimits() = runTest {
        val rig = CodecTestRig()
        val file = rig.input(byteArrayOf())
        rig.sourceOverride = { object : Source {
            var remaining = ARCHIVE_LIMIT + 1
            val zeros = ByteArray(8192)
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1
                val n = minOf(remaining, byteCount, zeros.size.toLong()).toInt()
                sink.write(zeros, 0, n); remaining -= n
                return n.toLong()
            }
        } }
        assertFailure(rig.codec.read(file, "test"), ProblemCode.LIMIT_EXCEEDED)
        clean(rig)
        val many = cardConfig(*Array(100001) { card("x") }, rows = 64, columns = 64)
        val second = CodecTestRig()
        assertFailure(second.codec.read(second.input(syntheticZip(listOf(configEntry(many)))), "test"), ProblemCode.LIMIT_EXCEEDED)
        assertEquals(0, second.prepares); clean(second)
    }

    @Test fun writerAndReaderIoFailuresNeverLeakOwnedFilesSourcesOrLeases() = runTest {
        for (kind in 0..3) {
            val rig = CodecTestRig()
            val board = read(rig, cardConfig(card("image", "image")), SyntheticEntry("image", byteArrayOf(1, 2)))
            val id = board.document.assetIds().single()
            when (kind) {
                0 -> rig.failSinkWrites = true
                1 -> rig.failSourcePath = id.value
                2 -> rig.bytes[id.value] = byteArrayOf(9, 9)
                3 -> rig.onInspection = { throw CancellationException("test") }
            }
            if (kind == 3) assertFailsWith<CancellationException> { rig.codec.write(PublishedBoard(board.document, 1), board.lease) }
            else assertIs<OperationResult.Failed>(rig.codec.write(PublishedBoard(board.document, 1), board.lease))
            clean(rig, leases = 1)
            assertFalse((board.lease as CodecTestRig.Lease).closed)
            board.close(); clean(rig)
        }
        val rig = CodecTestRig()
        rig.failSinkWrites = true
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(cardConfig(card("x", "image"))), SyntheticEntry("image", byteArrayOf(1))))), "test"), ProblemCode.IO_FAILURE)
        clean(rig)
        val source = rig.source(rig.input(byteArrayOf(1))).value()
        assertFailsWith<CodecFailure> { CheckedZipWriter(Buffer(), ZipProfile.LINKA).add("../unsafe", source) }
        clean(rig)
    }

    @Test fun allDecoderBudgetsAndOpaqueLocalUriBoundaryAreChecked() = runTest {
        for (width in listOf(4001, 8192)) {
            val rig = CodecTestRig()
            rig.imageInfo = ImageInfo(width, 4000, "image/png")
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(cardConfig(card("x", "image"))), SyntheticEntry("image", byteArrayOf(1))))), "test"), ProblemCode.LIMIT_EXCEEDED)
            assertEquals(0, rig.prepares); clean(rig)
        }
        for (uri in listOf("file:///private/x", "content://provider/x", "ph://x", "assets-library://x")) {
            val rig = CodecTestRig()
            val root = simple.changed("custom", buildJsonObject { put("nested", uri) })
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(root.toString())))), "test"), ProblemCode.INVALID_DATA)
            clean(rig)
        }
    }

    @Test fun spanningContentIsUniformAndVisibleToPeer() = runTest {
        val rig = CodecTestRig()
        val json = cardConfig(card("wide").changed("width", JsonPrimitive(2)).changed("custom", JsonPrimitive("card metadata")), card("covered"), card("last"), rows = 2, columns = 2)
        val root = boundedJson(json)
        val page = root["pages"]!!.jsonArray.single().jsonObject.changed("custom", JsonPrimitive("page metadata"))
        val original = syntheticZip(listOf(configEntry(root.changed("custom", JsonPrimitive("root metadata")).changed("pages", JsonArray(listOf(page))).toString())))
        val input = rig.input(original)
        val board = rig.codec.read(input, "test").value()
        assertTrue(board.report.layoutNormalized)
        assertEquals(listOf(0, 1, 2), board.document.pages.single().cells.map { it.position })
        assertTrue(board.document.pages.single().cells.all { boundedJson(it.extensionsJson)["width"] == JsonPrimitive(1) && boundedJson(it.extensionsJson)["height"] == JsonPrimitive(1) })
        val output = rig.codec.write(PublishedBoard(board.document, 1), board.lease).value()
        println("P05_SPARSE_ZIP_HEX=" + rig.raw(output).toByteString().hex())
        val again = rig.codec.read(output, "test").value()
        assertEquals(1, again.document.pages.size)
        assertEquals(listOf("wide", "covered", "last"), again.document.pages.single().cells.mapNotNull { it.card?.label })
        assertEquals(listOf(0, 1, 2), again.document.pages.single().cells.filter { it.card != null }.map { it.position })
        assertFalse(again.report.layoutNormalized)
        assertEquals("root metadata", boundedJson(again.document.extensionsJson).string("custom"))
        assertEquals("page metadata", boundedJson(again.document.pages.single().extensionsJson).string("custom"))
        assertEquals("card metadata", boundedJson(again.document.pages.single().cells.first().card!!.extensionsJson).string("custom"))
        assertContentEquals(original, rig.raw(input))
        rig.discard(output); again.close(); board.close(); clean(rig)
    }

    @Test fun movedAndResizedCellsExportUniformlyWithoutParsingTheirOpaqueGeometry() = runTest {
        val rig = CodecTestRig()
        val wide = card("wide", audio = "nested/UUID..wav").changed("width", JsonPrimitive(2))
            .changed("audioText", JsonPrimitive("editor text")).changed("audioVoice", JsonPrimitive("voice"))
            .changed("custom", JsonPrimitive("card metadata"))
        val json = cardConfig(wide, card("covered"), card("last"), rows = 2, columns = 2)
        val original = syntheticZip(listOf(configEntry(json), SyntheticEntry("nested/UUID..wav", byteArrayOf(3, 4, 5))))
        val input = rig.input(original)
        val board = rig.codec.read(input, "test").value()
        val page = board.document.pages.single()
        val moved = listOf(page.cells[2].copy(position = 0), page.cells[0].copy(position = 1, extensionsJson = """{"width":2,"height":8}"""), page.cells[1].copy(position = 3))
        val edited = board.document.copy(pages = listOf(page.copy(rows = 1, columns = 4, cells = moved)))
        val output = rig.codec.write(PublishedBoard(edited, 2), board.lease).value()
        println("P05_EDITED_ZIP_HEX=" + rig.raw(output).toByteString().hex())
        val again = rig.codec.read(output, "test").value()
        assertEquals(listOf(0, 1, 3), again.document.pages.single().cells.filter { it.card != null }.map { it.position })
        assertEquals(listOf("last", "wide", "covered"), again.document.pages.single().cells.mapNotNull { it.card?.label })
        assertEquals("card metadata", boundedJson(again.document.pages.single().cells[1].card!!.extensionsJson).string("custom"))
        assertEquals(board.document.assetIds(), again.document.assetIds())
        assertContentEquals(original, rig.raw(input))
        assertEquals("""{"width":2,"height":8}""", edited.pages.single().cells[1].extensionsJson)
        rig.discard(output); again.close(); board.close(); clean(rig)
    }

    @Test fun originalSpanValuesAreValidatedAndReportedButNeverBecomeCanonicalGeometry() = runTest {
        for (span in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive(2), JsonPrimitive(1e100))) {
            val rig = CodecTestRig()
            val board = read(rig, cardConfig(card("x").changed("width", span).changed("height", span)))
            assertEquals(span != JsonNull && span != JsonPrimitive(1), board.report.layoutNormalized)
            val ext = boundedJson(board.document.pages.single().cells.single().extensionsJson)
            assertEquals(JsonPrimitive(1), ext["width"]); assertEquals(JsonPrimitive(1), ext["height"])
            board.close(); clean(rig)
        }
        for (bad in listOf(JsonPrimitive("2"), JsonPrimitive(true), JsonObject(emptyMap()), JsonArray(emptyList()))) {
            val rig = CodecTestRig()
            val json = cardConfig(card("x").changed("width", JsonPrimitive(2)).changed("height", bad))
            assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(json)))), "test"), ProblemCode.INVALID_DATA)
            clean(rig)
        }
        val rig = CodecTestRig()
        val nonFinite = cardConfig(card("x").changed("width", JsonPrimitive(2))).replace("\"width\":2", "\"width\":1e309")
        assertFailure(rig.codec.read(rig.input(syntheticZip(listOf(configEntry(nonFinite)))), "test"), ProblemCode.INVALID_DATA)
        clean(rig)
    }

    @Test fun writerOmitsOnlyImplicitTrailingPaddingAndKeepsHolesAndExplicitEmptyMetadata() = runTest {
        val rig = CodecTestRig()
        val root = boundedJson(cardConfig(rows = 64, columns = 64))
        val page = root["pages"]!!.jsonArray.single().jsonObject.changed("cards", JsonArray(listOf(JsonNull, JsonNull, JsonNull, card("at three"), JsonNull, JsonNull,
            buildJsonObject { put("id", "explicit empty"); put("cardType", 2); put("custom", "keep empty metadata") })))
        val board = read(rig, root.changed("pages", JsonArray(listOf(page))).toString())
        val output = rig.codec.write(PublishedBoard(board.document, 1), board.lease).value()
        println("P05_HOLES_ZIP_HEX=" + rig.raw(output).toByteString().hex())
        val cards = exportedConfig(rig, output)["pages"]!!.jsonArray.single().jsonObject["cards"]!!.jsonArray
        assertEquals(7, cards.size)
        for (position in listOf(0, 1, 2, 4, 5)) assertEquals(JsonPrimitive(2), cards[position].jsonObject["cardType"])
        assertEquals("keep empty metadata", cards[6].jsonObject.string("custom"))
        val again = rig.codec.read(output, "test").value()
        assertEquals(3, again.document.pages.single().cells.single { it.card != null }.position)
        assertEquals("keep empty metadata", boundedJson(again.document.pages.single().cells.last().extensionsJson).string("custom"))
        rig.discard(output); again.close(); board.close(); clean(rig)
        val empty = read(rig, cardConfig(rows = 64, columns = 64))
        val emptyOutput = rig.codec.write(PublishedBoard(empty.document, 1), empty.lease).value()
        assertTrue(exportedConfig(rig, emptyOutput)["pages"]!!.jsonArray.single().jsonObject["cards"]!!.jsonArray.isEmpty())
        rig.discard(emptyOutput); empty.close(); clean(rig)
    }

    @Test fun exactBoundaryValuesAndNonContentMetadataRemainSupported() = runTest {
        val rig = CodecTestRig()
        rig.imageInfo = ImageInfo(4000, 4000, "image/png")
        rig.audioInfo = AudioInfo(600000, "audio/wav")
        val metadataKey = "x".repeat(256)
        val content = card("😀".repeat(32768), "image", "audio").changed(metadataKey, JsonPrimitive("ok"))
        val space = buildJsonObject { put("id", "space"); put("cardType", 1); put("title", " "); put("custom", "space metadata") }
        val empty = buildJsonObject { put("id", "new"); put("cardType", 3); put("title", "placeholder"); put("custom", "empty metadata") }
        val board = read(rig, cardConfig(content, space, empty, columns = 3), SyntheticEntry("image", byteArrayOf(1)), SyntheticEntry("audio", byteArrayOf(2)))
        val out = rig.codec.write(PublishedBoard(board.document, 1), board.lease).value()
        val again = rig.codec.read(out, "test").value()
        assertEquals(65536, again.document.pages.single().cells[0].card!!.label.length)
        assertEquals(CellKind.SPACE, again.document.pages.single().cells[1].kind)
        assertEquals(CellKind.EMPTY, again.document.pages.single().cells[2].kind)
        assertEquals("placeholder", boundedJson(again.document.pages.single().cells[2].extensionsJson).string("title"))
        rig.discard(out); again.close(); board.close(); clean(rig)
        val manyPages = simple.changed("pages", JsonArray(List(256) { simple["pages"]!!.jsonArray[0] }))
        val exactPages = read(rig, manyPages.toString())
        assertEquals(256, exactPages.document.pages.size)
        exactPages.close(); clean(rig)
        val exactGrid = read(rig, cardConfig(rows = 64, columns = 64))
        assertEquals(4096, exactGrid.document.pages.single().capacity)
        exactGrid.close(); clean(rig)
    }
}
