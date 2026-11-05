package su.linka.pictures.portability.zip

import kotlinx.serialization.json.jsonPrimitive
import su.linka.pictures.application.ProblemCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTextBoundaryTest {
    @Test fun rawAndEscapedLoneSurrogatesAreRejectedIncludingKeysAndNestedData() {
        for (text in listOf(
            """{"value":"\uD800"}""",
            """{"value":"\uDC00"}""",
            """{"\uD800":1}""",
            """{"nested":[{"value":"\uD800"}]}""",
            "{\"value\":\"left\uD800right\"}",
        )) {
            assertEquals(ProblemCode.INVALID_DATA, assertFailsWith<CodecFailure> { boundedJson(text) }.problem.code)
        }
    }

    @Test fun validUnicodeAndEscapedNulDataRemainExact() {
        val value = boundedJson("""{"name":"e\u0301 😀","opaque":"left\u0000right","pair":"\uD83D\uDE00"}""")
        assertEquals("e\u0301 😀", value.getValue("name").jsonPrimitive.content)
        assertEquals("left\u0000right", value.getValue("opaque").jsonPrimitive.content)
        assertEquals("😀", value.getValue("pair").jsonPrimitive.content)
    }
}
