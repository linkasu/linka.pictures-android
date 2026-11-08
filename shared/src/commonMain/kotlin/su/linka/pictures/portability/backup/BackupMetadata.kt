package su.linka.pictures.portability.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.zip.*

internal val backupJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
internal inline fun <reified T> decodeMetadata(bytes: ByteArray): T {
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    boundedJson(text)
    return backupJson.decodeFromString<T>(text)
}
internal inline fun <reified T> encodeMetadata(value: T): ByteArray {
    val text = backupJson.encodeToString(value)
    boundedJson(text)
    return text.encodeToByteArray()
}
internal fun assetBudget(kind: MediaKind): Long = if (kind == MediaKind.ARCHIVE) BACKUP_LIMIT else MEDIA_LIMIT
internal fun checkCounts(counts: SnapshotCounts) {
    limit(counts.boards.toLong() + counts.drafts + counts.retained + counts.assets + 2 <= ZipProfile.BACKUP.entries)
    limit(counts.assetBytes <= BACKUP_LIMIT)
}
internal fun entryName(group: String, index: Int): String = "$group/${index.toString().padStart(8, '0')}.json"

internal class BackupReferences {
    val kinds = mutableMapOf<AssetId, MediaKind>()
    private val entryIds = mutableSetOf<BoardId>()
    private val draftIds = mutableSetOf<DraftId>()
    fun organization(value: LibraryOrganization) { value.validateEntries(entryIds) }
    private fun text(value: String) { limit(value.length <= 65536) }
    private fun extensions(value: String) { text(value); passive(boundedJson(value)) }
    private fun add(id: AssetId?, kind: MediaKind) {
        if (id == null) return
        checkData(kinds[id] == null || kinds[id] == kind)
        kinds[id] = kind
        limit(kinds.size < ZipProfile.BACKUP.entries)
    }
    // Measure individual cells/pages before serializing a document; never build an unbounded giant JSON.
    private fun document(document: BoardDocument) {
        text(document.name); document.description?.let(::text); extensions(document.extensionsJson)
        var budget = document.name.encodeToByteArray().size.toLong() + document.extensionsJson.encodeToByteArray().size +
            (document.description?.encodeToByteArray()?.size ?: 0) + 256
        for (page in document.pages) {
            extensions(page.extensionsJson)
            budget += encodeMetadata(page.copy(cells = emptyList())).size
            for (cell in page.cells) {
                extensions(cell.extensionsJson)
                cell.card?.let { card ->
                    text(card.label); extensions(card.extensionsJson)
                    add(card.image, MediaKind.IMAGE)
                    when (val speech = card.speech) {
                        is SpeechSource.Recording -> {
                            add(speech.assetId, MediaKind.AUDIO)
                            speech.audioText?.let(::text); speech.audioVoice?.let(::text)
                        }
                        is SpeechSource.Tts -> { text(speech.text); speech.languageTag?.let(::text) }
                        SpeechSource.None -> Unit
                    }
                }
                budget += encodeMetadata(cell).size
                limit(budget <= 2 * CONFIG_LIMIT)
            }
            limit(budget <= 2 * CONFIG_LIMIT)
        }
    }
    fun board(value: PublishedBoard) { checkData(entryIds.add(value.document.id)); document(value.document) }
    fun draft(value: StoredDraft) {
        checkData(draftIds.add(value.draft.id))
        document(value.draft.document)
        when (val form = value.draft.form) {
            is EditorForm.Card -> {
                text(form.label); text(form.speechText); extensions(form.extensionsJson)
                form.audioText?.let(::text); form.audioVoice?.let(::text)
                add(form.image, MediaKind.IMAGE); add(form.recording, MediaKind.AUDIO)
            }
            is EditorForm.Grid -> { text(form.rowsText); text(form.columnsText) }
            null -> Unit
        }
    }
    fun retained(value: RetainedArchive) { checkData(entryIds.add(value.id)); text(value.name); add(value.archive, MediaKind.ARCHIVE) }
    fun asset(value: MediaAsset) {
        checkData(kinds[value.id] == value.kind)
        limit(value.byteCount <= assetBudget(value.kind))
    }
}

internal suspend fun readAssetRows(files: PlatformFilesPort, file: LocalFile, expectedHash: String, consume: suspend (MediaAsset) -> Unit) {
    val hash = HashingSource.sha256(files.source(file).value())
    hash.buffer().use { source ->
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val newline = source.indexOf('\n'.code.toByte(), 0, CONFIG_LIMIT + 1)
            val bytes = if (newline >= 0) {
                total += newline + 1
                limit(total <= JSONL_LIMIT)
                source.readByteArray(newline).also { source.skip(1) }
            } else {
                limit(source.buffer.size <= CONFIG_LIMIT)
                source.readByteArray().also { total += it.size; limit(total <= JSONL_LIMIT) }
            }
            if (bytes.isEmpty() && newline < 0) break
            consume(decodeMetadata<MediaAsset>(bytes))
            if (newline < 0) break
        }
    }
    if (hash.hash.hex() != expectedHash) fail(ProblemCode.INTEGRITY_FAILURE)
}
