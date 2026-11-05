package su.linka.pictures.storage

import su.linka.pictures.application.ProblemCode
import su.linka.pictures.domain.*

private fun MutableMap<AssetId, MediaKind>.role(id: AssetId?, kind: MediaKind) {
    if (id == null) return
    if (this[id]?.let { it != kind } == true) fail(ProblemCode.INVALID_DATA)
    this[id] = kind
}

private fun MutableMap<AssetId, MediaKind>.speech(source: SpeechSource) {
    if (source is SpeechSource.Recording) role(source.assetId, MediaKind.AUDIO)
}

internal fun BoardDocument.assetRoles(): Map<AssetId, MediaKind> = buildMap {
    for (page in pages) for (cell in page.cells) cell.card?.let { card ->
        role(card.image, MediaKind.IMAGE)
        speech(card.speech)
    }
}

internal fun EditorDraft.assetRoles(): Map<AssetId, MediaKind> = document.assetRoles().toMutableMap().apply {
    (form as? EditorForm.Card)?.let {
        role(it.image, MediaKind.IMAGE)
        role(it.recording, MediaKind.AUDIO)
    }
}

internal fun MessageDraft.assetRoles(): Map<AssetId, MediaKind> = buildMap {
    for (part in parts) if (part is MessagePart.Card) {
        role(part.snapshot.image, MediaKind.IMAGE)
        speech(part.snapshot.speech)
    }
}

internal fun RetainedArchive.assetRoles(): Map<AssetId, MediaKind> = mapOf(archive to MediaKind.ARCHIVE)
