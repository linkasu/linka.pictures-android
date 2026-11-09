package su.linka.pictures.domain

fun SpeechSource.assetIds(): Set<AssetId> = when (this) {
    is SpeechSource.Recording -> setOf(assetId)
    is SpeechSource.Tts, SpeechSource.None -> emptySet()
}

fun BoardDocument.assetIds(): Set<AssetId> = pages.flatMap { page ->
    page.cells.flatMap { cell -> cell.card?.let { listOfNotNull(it.image) + it.speech.assetIds() }.orEmpty() }
}.toSet()

fun MessageDraft.assetIds(): Set<AssetId> = parts.flatMap { part ->
    when (part) {
        is MessagePart.Card -> listOfNotNull(part.snapshot.image) + part.snapshot.speech.assetIds()
        is MessagePart.Text -> emptyList()
    }
}.toSet()

fun EditorDraft.assetIds(): Set<AssetId> = document.assetIds() + when (val active = form) {
    is EditorForm.Card -> listOfNotNull(active.image, active.recording).toSet()
    is EditorForm.Grid, null -> emptySet()
}
