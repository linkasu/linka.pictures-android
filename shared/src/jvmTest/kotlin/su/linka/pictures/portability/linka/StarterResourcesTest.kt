package su.linka.pictures.portability.linka

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.ExperimentalResourceApi
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.portability.PreparedBoard
import su.linka.pictures.application.ports.AudioInfo
import su.linka.pictures.domain.CellKind
import su.linka.pictures.domain.InputStyle
import su.linka.pictures.domain.MediaKind
import su.linka.pictures.domain.SpeechSource
import su.linka.pictures.resources.Res
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalResourceApi::class)
class StarterResourcesTest {
    @Test fun generatedComposeResourcesPassThePublicCodecWithoutChangingLetterSemantics() = runTest {
        val expectedPages = linkedMapOf("core" to 3, "food" to 1, "activities" to 1, "feelings" to 1, "letters" to 4)
        var positions = 0
        for ((name, pageCount) in expectedPages) {
            val bytes = Res.readBytes("files/starter/ru.$name.v1.linka")
            // This checks the actual wire codec; the rig is not native media-decoder evidence.
            val rig = CodecTestRig().apply { audioInfo = AudioInfo(1000, "audio/mpeg") }
            val result = rig.codec.read(rig.input(bytes), name)
            val board = assertIs<OperationResult.Completed<PreparedBoard>>(result, name).value
            try {
                assertEquals(pageCount, board.document.pages.size, name)
                assertTrue(board.document.pages.all { it.rows == 3 && it.columns == 3 }, name)
                val cells = board.document.pages.flatMap { it.cells }
                assertEquals(pageCount * 9, cells.size, name)
                positions += cells.size
                if (name == "letters") {
                    assertEquals(InputStyle.LITERAL, board.document.behavior.inputStyle)
                    assertEquals(1, cells.count { it.kind == CellKind.SPACE })
                    assertEquals(0, rig.prepares)
                    assertEquals(listOf(".", "?"), cells.takeLast(2).map { it.card!!.label })
                } else {
                    assertTrue(cells.all { it.kind == CellKind.CONTENT })
                    assertTrue(cells.all { it.card!!.image != null })
                    assertTrue(cells.all { it.card!!.speech is SpeechSource.Recording })
                    assertTrue(cells.all { (it.card!!.speech as SpeechSource.Recording).audioVoice == "anton" })
                    for (asset in rig.assets.values.filter { it.kind == MediaKind.IMAGE }) {
                        val image = assertNotNull(rig.bytes.getValue(asset.id.value).inputStream().use { ImageIO.read(it) })
                        assertTrue(image.width > 0 && image.height > 0)
                    }
                }
            } finally { board.close() }
            assertEquals(0, rig.openLeases, name)
            assertEquals(0, rig.openSources, name)
            assertEquals(0, rig.openSinks, name)
            assertTrue(rig.temporary.isEmpty(), name)
        }
        assertEquals(90, positions)
    }
}
