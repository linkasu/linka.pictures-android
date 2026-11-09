package su.linka.pictures.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ArchivePathNormalizationTest {
    @Test
    fun matchesPeerNfcLowercaseAndDirectoryCollisionOrder() {
        assertEquals("images/é.png", archivePathCollisionKey("IMAGES/E\u0301.PNG"))
        assertEquals(archivePathCollisionKey("ПАПКА/"), archivePathCollisionKey("папка"))
        assertEquals("i\u0307", archivePathCollisionKey("\u0130"))
        assertEquals("é/😀", archivePathCollisionKey("E\u0301/😀"))
        assertEquals("uuid..wav", archivePathCollisionKey("UUID..wav"))
        assertNotEquals(archivePathCollisionKey("ß"), archivePathCollisionKey("ss"))
    }
}
