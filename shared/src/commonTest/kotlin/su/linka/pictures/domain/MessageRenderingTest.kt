package su.linka.pictures.domain

import kotlin.test.*
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.SpeechSegment

class MessageRenderingTest {
    private fun card(label: String, speech: SpeechSource = SpeechSource.Tts(label)) = MessagePart.Card(
        MessagePartId("card-$label"), CardSelectionSnapshot(
            SelectionOrigin(BoardId("board"), 1, PageId("page"), CardId(label)), label, null, speech,
        ),
    )

    @Test fun semanticSpacingDoesNotRewriteLiteralRuns() {
        for ((literal, expected) in listOf("мёд" to "Хочу мёд", " мёд" to "Хочу мёд", ", мир" to "Хочу, мир", "—мёд" to "Хочу—мёд", "  м ё д" to "Хочу  м ё д")) {
            assertEquals(expected, renderMessage(MessageDraft(listOf(card("Хочу"), MessagePart.Text(MessagePartId("text"), literal)))))
        }
        assertEquals("Хочу кашу", renderMessage(MessageDraft(listOf(card("Хочу"), card("кашу")))))
    }

    @Test fun mixedSpeechIsOrderedAndNeverDuplicatesRecordingOrFallsBackFromNone() {
        val asset = AssetId("a".repeat(64))
        val parts = listOf(card("Хочу", SpeechSource.Recording(asset, "metadata, not speech", "voice")), MessagePart.Text(MessagePartId("text"), "мёд"), card("ещё"))
        val result = assertIs<OperationResult.Completed<*>>(speechPlan(MessageDraft(parts))).value
        val plan = assertIs<su.linka.pictures.application.ports.SpeechPlan>(result)
        assertEquals(listOf(SpeechSegment.Recording(asset), SpeechSegment.Text("мёд"), SpeechSegment.Text("ещё")), plan.segments)
        assertEquals("Хочу мёд ещё", plan.renderedText)
        assertEquals(ProblemCode.SPEECH_UNAVAILABLE, assertIs<OperationResult.Failed>(speechPlan(MessageDraft(parts + card("нет", SpeechSource.None)))).problem.code)
        assertEquals(ProblemCode.SPEECH_UNAVAILABLE, assertIs<OperationResult.Failed>(speechPlan(MessageDraft(listOf(card("пусто", SpeechSource.Tts("")))))).problem.code)
    }
}
