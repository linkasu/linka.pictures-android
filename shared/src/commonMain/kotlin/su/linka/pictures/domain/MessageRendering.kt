package su.linka.pictures.domain

import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.SpeechPlan
import su.linka.pictures.application.ports.SpeechSegment

private fun MessagePart.visibleText(): String = when (this) {
    is MessagePart.Card -> snapshot.label
    is MessagePart.Text -> text
}

// Separators belong to semantic boundaries, never to characters inside a literal run.
private fun needsSeparator(previous: Char?, next: Char?): Boolean = previous != null && next != null &&
    !previous.isWhitespace() && !next.isWhitespace() && next.category !in punctuation && previous !in "([{«“‘"

private val punctuation = setOf(
    CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
    CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
    CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
    CharCategory.OTHER_PUNCTUATION,
)

fun renderMessage(message: MessageDraft): String = buildString {
    for (part in message.parts) {
        val text = part.visibleText()
        if (needsSeparator(lastOrNull(), text.firstOrNull())) append(' ')
        append(text)
    }
}

fun speechPlan(message: MessageDraft): OperationResult<SpeechPlan> {
    val segments = mutableListOf<SpeechSegment>()
    for (part in message.parts) {
        val segment = when (part) {
            is MessagePart.Text -> part.text.takeIf { it.isNotEmpty() }?.let { SpeechSegment.Text(it) }
            is MessagePart.Card -> when (val speech = part.snapshot.speech) {
                is SpeechSource.Recording -> SpeechSegment.Recording(speech.assetId)
                is SpeechSource.Tts -> speech.text.takeIf { it.isNotBlank() }?.let { SpeechSegment.Text(it, speech.languageTag) }
                SpeechSource.None -> null
            } ?: return OperationResult.Failed(Problem(ProblemCode.SPEECH_UNAVAILABLE))
        }
        if (segment != null) segments += segment
    }
    return OperationResult.Completed(SpeechPlan(segments.toList(), renderMessage(message)))
}
