package su.linka.pictures.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlin.test.*

class EditorFormKindSerializationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val old = """{"pageId":"page","slotIndex":0,"cardId":"card","label":"","speechText":"","speechChoice":"TTS","useLabelForSpeech":true,"image":null,"recording":null,"audioText":null,"audioVoice":null,"extensionsJson":"{}"}"""

    @Serializable
    private sealed interface LegacyForm

    @Serializable @SerialName("card")
    private data class LegacyCard(
        val pageId: PageId, val slotIndex: Int, val cardId: CardId,
        val label: String = "", val speechText: String = "", val speechChoice: SpeechChoice = SpeechChoice.TTS,
        val useLabelForSpeech: Boolean = true, val image: AssetId? = null, val recording: AssetId? = null,
        val audioText: String? = null, val audioVoice: String? = null, val extensionsJson: String = "{}",
    ) : LegacyForm

    @Test fun contentEncodingIsByteIdenticalAndOldDataDefaultsToContent() {
        val form = EditorForm.Card(PageId("page"), 0, CardId("card"))
        assertEquals(old, json.encodeToString(EditorForm.Card.serializer(), form))
        assertEquals(form, json.decodeFromString(EditorForm.Card.serializer(), old))
        assertEquals(CellKind.CONTENT, form.kind)
        json.decodeFromString(LegacyCard.serializer(), old)
        val oldPolymorphic = "{\"type\":\"card\"," + old.drop(1)
        assertEquals(oldPolymorphic, json.encodeToString(EditorForm.serializer(), form))
        assertEquals(form, json.decodeFromString(EditorForm.serializer(), oldPolymorphic))
        assertEquals(oldPolymorphic, json.encodeToString(LegacyForm.serializer(), json.decodeFromString(LegacyForm.serializer(), oldPolymorphic)))
    }

    @Test fun nonContentRoundTripsButStrictLegacyReaderRejectsWithoutReplacingItsValue() {
        for (kind in listOf(CellKind.SPACE, CellKind.EMPTY)) {
            val form = EditorForm.Card(PageId("page"), 0, CardId("card"), kind = kind)
            val encoded = json.encodeToString(EditorForm.Card.serializer(), form)
            assertEquals(form, json.decodeFromString(EditorForm.Card.serializer(), encoded))
            var retained = json.decodeFromString(LegacyCard.serializer(), old)
            val original = retained
            assertFailsWith<SerializationException> { retained = json.decodeFromString(LegacyCard.serializer(), encoded) }
            assertEquals(original, retained)
            assertFailsWith<SerializationException> {
                json.decodeFromString(LegacyForm.serializer(), json.encodeToString(EditorForm.serializer(), form))
            }
        }
    }

    @Test fun unknownKindFailsWholeDecodeWithoutReplacingExistingForm() {
        var retained = json.decodeFromString(EditorForm.Card.serializer(), old)
        val original = retained
        assertFailsWith<SerializationException> {
            retained = json.decodeFromString(EditorForm.Card.serializer(), old.dropLast(1) + ",\"kind\":\"FUTURE\"}")
        }
        assertEquals(original, retained)
    }
}
