package su.linka.pictures.portability.backup

import kotlinx.coroutines.flow.*
import okio.ByteString.Companion.toByteString
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.linka.CodecTestRig

// Only frozen port fakes. This is not a production SnapshotReader/Builder or a live restore test.
internal class TestSnapshot(
    private val rig: CodecTestRig,
    val boardValues: List<PublishedBoard> = emptyList(),
    val draftValues: List<StoredDraft> = emptyList(),
    val retainedValues: List<RetainedArchive> = emptyList(),
    val assetValues: List<MediaAsset> = emptyList(),
    override val settings: PortableSettings = PortableSettings(),
    override val revision: Long = 12,
    override val counts: SnapshotCounts = SnapshotCounts(boardValues.size, draftValues.size, retainedValues.size, assetValues.size, assetValues.sumOf { it.byteCount }),
    override val organization: LibraryOrganization = LibraryOrganization(),
) : SnapshotReader {
    override val epoch = 987L
    override val lease = rig.Lease(assetValues.map { it.id }.toSet())
    var closed = false
    var beforeEmit: (String, Int) -> Unit = { _, _ -> }
    var assetReads = 0
    var secondAssets: List<MediaAsset>? = null
    private fun <T> values(name: String, list: List<T>): Flow<T> = flow {
        check(!closed)
        for ((index, value) in list.withIndex()) { beforeEmit(name, index); emit(value) }
    }
    override fun boards() = values("boards", boardValues)
    override fun drafts() = values("drafts", draftValues)
    override fun retained() = values("retained", retainedValues)
    override fun assets(): Flow<MediaAsset> { assetReads++; return values("assets", if (assetReads > 1) secondAssets ?: assetValues else assetValues) }
    override fun close() { if (!closed) { closed = true; lease.close() } }
}

internal class BackupTestStore(val rig: CodecTestRig) : LibraryStore {
    var builders = 0
    var created = 0
    var addCalls = 0
    var failAddAt = Int.MAX_VALUE
    var addOutcome: OperationResult<Unit> = OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
    var createOutcome: OperationResult<SnapshotBuilder>? = null
    var sealOverride: (suspend (Builder, Long, PortableSettings) -> OperationResult<SnapshotReader>)? = null
    var returned: TestSnapshot? = null
    inner class Builder : SnapshotBuilder {
        val boards = mutableListOf<PublishedBoard>()
        val drafts = mutableListOf<StoredDraft>()
        val archives = mutableListOf<RetainedArchive>()
        val assets = mutableListOf<MediaAsset>()
        private val pins = mutableListOf<MediaLease>()
        var organization = LibraryOrganization()
        var closed = false
        init { builders++ }
        private fun add(mutate: () -> Unit): OperationResult<Unit> {
            check(!closed)
            if (++addCalls == failAddAt) return addOutcome
            mutate()
            return OperationResult.Completed(Unit)
        }
        override suspend fun addBoard(value: PublishedBoard) = add { boards.add(value) }
        override suspend fun addDraft(value: StoredDraft) = add { drafts.add(value) }
        override suspend fun addRetained(value: RetainedArchive) = add { archives.add(value) }
        override suspend fun addAsset(value: MediaAsset) = add { assets.add(value); pins.add(rig.Lease(setOf(value.id))) }
        fun reader(revision: Long, settings: PortableSettings): TestSnapshot = TestSnapshot(rig, boards.toList(), drafts.toList(), archives.toList(), assets.toList(), settings, revision, organization = organization).also { returned = it }
        override suspend fun seal(revision: Long, settings: PortableSettings, organization: LibraryOrganization): OperationResult<SnapshotReader> {
            check(!closed)
            this.organization = organization
            organization.validateEntries((boards.map { it.document.id } + archives.map { it.id }).toSet())
            return sealOverride?.invoke(this, revision, settings) ?: OperationResult.Completed(reader(revision, settings))
        }
        override fun close() { if (!closed) { closed = true; pins.forEach { it.close() }; builders-- } }
    }
    override suspend fun createSnapshotBuilder(): OperationResult<SnapshotBuilder> {
        created++
        return createOutcome ?: OperationResult.Completed(Builder())
    }
    override val listing: StateFlow<LibraryListing> get() = error("codec must not access live listing")
    override suspend fun board(id: BoardId): OperationResult<LeasedBoard> = error("live")
    override suspend fun draft(id: DraftId): OperationResult<LeasedDraft> = error("live")
    override suspend fun drafts(): OperationResult<List<DraftSummary>> = error("live")
    override suspend fun saveDraft(value: EditorDraft, expectedVersion: Long?, permit: WritePermit): OperationResult<StoredDraft> = error("live")
    override suspend fun deleteDraft(id: DraftId, expectedVersion: Long, permit: WritePermit): OperationResult<Unit> = error("live")
    override suspend fun publishDraft(id: DraftId, expectedDraftVersion: Long, expectedBoardRevision: Long?, permit: WritePermit): OperationResult<PublishedBoard> = error("live")
    override suspend fun addBoard(value: BoardDocument, permit: WritePermit): OperationResult<PublishedBoard> = error("live")
    override suspend fun replaceBoard(value: BoardDocument, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> = error("live")
    override suspend fun renameBoard(id: BoardId, name: String, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> = error("live")
    override suspend fun deleteBoard(id: BoardId, expectedRevision: Long, permit: WritePermit): OperationResult<Unit> = error("live")
    override suspend fun retainArchive(value: RetainedArchive, permit: WritePermit): OperationResult<Unit> = error("live")
    override suspend fun settings(): OperationResult<PortableSettings> = error("live")
    override suspend fun saveSettings(value: PortableSettings, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long> = error("live")
    override suspend fun saveOrganization(value: LibraryOrganization, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long> = error("live")
    override suspend fun snapshot(): OperationResult<SnapshotReader> = error("live")
    override suspend fun replaceLibrary(value: SnapshotReader, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> = error("live mutation forbidden")
    override suspend fun recovery(): OperationResult<RecoveryInfo?> = error("live")
    override suspend fun returnToRecovery(id: RecoveryId, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> = error("live")
    override suspend fun deleteRecovery(id: RecoveryId, permit: WritePermit): OperationResult<Unit> = error("live")
}

internal fun CodecTestRig.asset(data: ByteArray, kind: MediaKind, type: String): MediaAsset {
    val id = AssetId(data.toByteString().sha256().hex())
    return MediaAsset(id, kind, type, data.size.toLong()).also { assets[id] = it; bytes[id.value] = data }
}

internal fun fullSnapshot(rig: CodecTestRig): TestSnapshot {
    val image = rig.asset(byteArrayOf(1, 2), MediaKind.IMAGE, "image/png")
    val audio = rig.asset(byteArrayOf(3, 4), MediaKind.AUDIO, "audio/wav")
    val formImage = rig.asset(byteArrayOf(5, 6), MediaKind.IMAGE, "image/png")
    val formAudio = rig.asset(byteArrayOf(7, 8), MediaKind.AUDIO, "audio/wav")
    val archive = rig.asset(byteArrayOf(9, 8, 7), MediaKind.ARCHIVE, "application/x-unreadable-archive\n\"raw\"")
    val card = CardDocument(CardId("card"), "label", image.id, SpeechSource.Recording(audio.id, "editor text", "voice"), """{"id":7,"custom":{"actions":[]}}""")
    val doc = BoardDocument(BoardId("board"), "board", listOf(BoardPage(PageId("page"), 1, 2, listOf(BoardCell(0, CellKind.CONTENT, card), BoardCell(1, CellKind.SPACE, extensionsJson = """{"custom":"space"}""")))), BoardBehavior(InteractionMode.DIRECT, InputStyle.LITERAL), "description", """{"custom":{"script":"passive"}}""")
    val draftDoc = doc.copy(id = BoardId("draft-document"), pages = listOf(BoardPage(PageId("draft-page"), 1, 2, listOf(BoardCell(0, CellKind.CONTENT, CardDocument(CardId("tts"), "text", speech = SpeechSource.Tts("offline text", "ru-RU")))))))
    val form = EditorForm.Card(PageId("draft-page"), 1, CardId("open-form"), label = "form", speechText = "new speech", speechChoice = SpeechChoice.RECORDING, image = formImage.id, recording = formAudio.id, audioText = "form text", audioVoice = "form voice", extensionsJson = """{"custom":true}""")
    return TestSnapshot(rig, listOf(PublishedBoard(doc, 17)), listOf(StoredDraft(23, EditorDraft(DraftId("draft"), null, null, draftDoc, form))),
        listOf(RetainedArchive(BoardId("retained"), "unreadable", 7, archive.id, RetainedReason.INVALID_ARCHIVE)),
        listOf(image, audio, formImage, formAudio, archive), PortableSettings(1.5f, true, "ru-RU", mapOf(doc.id to BoardPresentation(false))), 91)
}
