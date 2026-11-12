package su.linka.pictures.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.portability.TransferPhase
import su.linka.pictures.application.portability.TransferState
import su.linka.pictures.application.ports.SnapshotCounts
import su.linka.pictures.application.ports.WritePermit
import su.linka.pictures.portability.backup.BackupManifest

class CoreContractsTest {
    private val image = AssetId("a".repeat(64))
    private val audio = AssetId("b".repeat(64))
    private val formImage = AssetId("d".repeat(64))
    private val card = CardDocument(CardId("card"), "Хочу", image, SpeechSource.Recording(audio))
    private val page = BoardPage(PageId("page"), 2, 3, listOf(BoardCell(4, CellKind.CONTENT, card)))
    private val board = BoardDocument(BoardId("board"), "Пример", listOf(page))

    @Test
    fun documentRoundTripPreservesSpeechAndPassiveMetadata() {
        val value = board.copy(extensionsJson = """{"custom":{"nested":[1,true,"мёд"]}}""")
        assertEquals(value, Json.decodeFromString<BoardDocument>(Json.encodeToString(value)))
        assertEquals(setOf(image, audio), value.assetIds())
    }

    @Test
    fun sparseCellsKeepPositionsAndRejectDuplicatesOrOverflow() {
        assertEquals(4, page.cells.single().position)
        assertFailsWith<IllegalArgumentException> { page.copy(cells = page.cells + page.cells) }
        assertFailsWith<IllegalArgumentException> { page.copy(cells = listOf(BoardCell(6, CellKind.EMPTY))) }
        assertFailsWith<IllegalArgumentException> { BoardCell(0, CellKind.CONTENT) }
        assertFailsWith<IllegalArgumentException> { page.copy(rows = 0) }
    }

    @Test
    fun selectionOwnsContentWithoutReadingEditedSource() {
        val selected = CardSelectionSnapshot(SelectionOrigin(board.id, 1, page.id, card.id), card.label, card.image, card.speech)
        val message = MessageDraft(listOf(MessagePart.Card(MessagePartId("selected"), selected)))
        val edited = card.copy(label = "Другое", image = null, speech = SpeechSource.Tts("new"))
        assertEquals("Другое", edited.label)
        assertEquals("Хочу", selected.label)
        assertEquals(setOf(image, audio), message.assetIds())
        assertEquals(message, Json.decodeFromString<MessageDraft>(Json.encodeToString(message)))
    }

    @Test
    fun unfinishedCardFormPinsItsReadyMedia() {
        val draft = EditorDraft(DraftId("draft"), board.id, 1, board,
            EditorForm.Card(page.id, 1, CardId("unfinished"), image = formImage))
        assertEquals(setOf(image, audio, formImage), draft.assetIds())
        assertEquals(draft, Json.decodeFromString<EditorDraft>(Json.encodeToString(draft)))
    }

    @Test
    fun backupManifestHasOnlyExplicitLibraryMetadata() {
        val phrase = MessageDraft(listOf(MessagePart.Text(MessagePartId("text"), "CURRENT_MESSAGE_ONLY_MARKER")))
        val snapshot = BackupManifest(1, PortableSettings(), SnapshotCounts(1, 0, 0, 2, 300))
        assertEquals("1.2", snapshot.version)
        val json = Json.encodeToString(snapshot)
        assertEquals(snapshot, Json.decodeFromString<BackupManifest>(json))
        assertTrue(phrase.parts.isNotEmpty())
        assertFalse(json.contains("CURRENT_MESSAGE_ONLY_MARKER"))
        assertFalse(json.contains("parts"))
        assertFalse(json.contains("epoch"))
        assertFailsWith<IllegalArgumentException> { snapshot.copy(version = "2.0") }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(version = "1.0") }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(version = "1.1") }
    }

    @Test
    fun portableSettingsUseStableBoardIdsNotNames() {
        val settings = PortableSettings(boards = mapOf(board.id to BoardPresentation(false)))
        assertEquals(settings, Json.decodeFromString<PortableSettings>(Json.encodeToString(settings)))
        assertFailsWith<IllegalArgumentException> { settings.copy(textScale = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { AssetId("../unsafe") }
    }

    @Test
    fun messageEpochAndCancellationAreNotPersistedSpeechOrFailure() {
        assertFailsWith<IllegalArgumentException> { StoredMessage(-1, 0, MessageDraft()) }
        assertFailsWith<IllegalArgumentException> { StoredMessage(0, -1, MessageDraft()) }
        val result: OperationResult<Unit> = OperationResult.Cancelled
        assertIs<OperationResult.Cancelled>(result)
        assertFalse(Json.encodeToString(StoredMessage(2, 3, MessageDraft())).contains("requestId"))
    }

    @Test
    fun transferCountersAndTerminalStatesDoNotHideFailureAsCancellation() {
        val id = OperationId("operation")
        assertFailsWith<IllegalArgumentException> { TransferState(id, TransferPhase.FAILED) }
        assertFailsWith<IllegalArgumentException> {
            TransferState(id, TransferPhase.CANCELLED, problem = Problem(ProblemCode.IO_FAILURE))
        }
        assertFailsWith<IllegalArgumentException> { TransferState(id, TransferPhase.VALIDATING, processedBytes = -1) }
        assertEquals(null, TransferState(id, TransferPhase.CANCELLED).problem)
        assertEquals(ProblemCode.IO_FAILURE,
            TransferState(id, TransferPhase.FAILED, problem = Problem(ProblemCode.IO_FAILURE)).problem?.code)
        val uncertain: OperationResult<Unit> = OperationResult.UnconfirmedCommit
        assertIs<OperationResult.UnconfirmedCommit>(uncertain)
        assertFailsWith<IllegalArgumentException> {
            TransferState(id, TransferPhase.UNCONFIRMED, problem = Problem(ProblemCode.IO_FAILURE))
        }
        assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN,
            TransferState(id, TransferPhase.UNCONFIRMED, problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)).problem?.code)
        assertFailsWith<IllegalArgumentException> { SnapshotCounts(0, 0, 0, -1, 0) }
        assertFailsWith<IllegalArgumentException> { WritePermit(-1) { OperationResult.Completed(Unit) } }
    }
}
