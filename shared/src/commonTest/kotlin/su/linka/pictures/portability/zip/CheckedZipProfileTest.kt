package su.linka.pictures.portability.zip

import kotlinx.coroutines.test.runTest
import okio.*
import okio.ByteString.Companion.decodeHex
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.portability.linka.*
import kotlin.test.*

class CheckedZipProfileTest {
    @Test fun onlyTwoFixedProfilesWithUnchangedLinkaAndSeparateBackupLimitsExist() = runTest {
        assertEquals(2, ZipProfile.entries.size)
        assertEquals(2000, ZipProfile.LINKA.entries); assertEquals(500L * 1024 * 1024, ZipProfile.LINKA.bytes)
        assertEquals(1024L * 1024, ZipProfile.LINKA.nameBytes)
        assertEquals(60000, ZipProfile.BACKUP.entries); assertEquals(2L * 1024 * 1024 * 1024, ZipProfile.BACKUP.bytes)
        assertEquals(8L * 1024 * 1024, ZipProfile.BACKUP.nameBytes)
        assertEquals(CONFIG_LIMIT, ZipProfile.BACKUP.entryBytes("boards/00000000.json"))
        assertEquals(JSONL_LIMIT, ZipProfile.BACKUP.entryBytes("assets.jsonl"))
        assertEquals(BACKUP_LIMIT, ZipProfile.BACKUP.entryBytes("assets/" + "a".repeat(64)))
        val rig = CodecTestRig()
        val entries = listOf(SyntheticEntry("manifest.json", byteArrayOf()), SyntheticEntry("config.json", byteArrayOf())) + List(1999) { SyntheticEntry("e$it", byteArrayOf()) }
        val file = rig.input(syntheticZip(entries))
        assertEquals(ProblemCode.LIMIT_EXCEEDED, assertFailsWith<CodecFailure> { CheckedZip.open(rig, file, ZipProfile.LINKA) }.problem.code)
        CheckedZip.open(rig, file, ZipProfile.BACKUP).visit { _, _ -> }
        assertEquals(0, rig.openSources)
    }

    @Test fun bothProfilesUseTheSameHostileZipChecksAndActualDeflateStreams() = runTest {
        val raw = """{"version":"1.2","withoutSpace":false,"rows":"1","columns":"1","cards":[{"id":7,"cardType":0,"title":"legacy"}]}""".encodeToByteArray()
        val deflated = "3d8b4b0a80200044ef326b896a13788dda450b312bc154fc1422de3d2368f766de4cc6259c974683a26b7a10dc321c2686d1322e4037a6bc2070e6f6efa27a6e543cf59f985b2bcf1972051dbe624ab65e5b822083aa042576c613ca521e".decodeHex().toByteArray()
        val item = SyntheticEntry("data", raw, compressed = deflated, method = 8)
        for (profile in ZipProfile.entries) {
            val rig = CodecTestRig()
            val headers = listOf(SyntheticEntry("config.json", "{}".encodeToByteArray()), SyntheticEntry("manifest.json", "{}".encodeToByteArray()))
            for (variant in listOf(item, item.copy(flags = 0x808, descriptor = true))) {
                var actual: ByteArray? = null
                CheckedZip.open(rig, rig.input(syntheticZip(headers + variant)), profile).visit { entry, source -> if (entry.name == "data") actual = source.buffer().readByteArray() }
                assertContentEquals(raw, actual)
            }
            val hostile = listOf(
                listOf(item.copy(flags = 0x801)), listOf(item.copy(method = 12)), listOf(item.copy(crc = 1)),
                listOf(item.copy(size = 0, localSize = 0)), listOf(item.copy(localSize = 1)),
                listOf(item.copy(localOffsetDelta = 1)), listOf(item.copy(disk = 1)), listOf(item.copy(needed = 45)),
                listOf(item.copy(extra = byteArrayOf(1, 0, 0, 0))), listOf(item.copy(external = 0xa000 shl 16)),
                listOf(item.copy(name = "../bad")), listOf(item.copy(name = "C:bad")), listOf(item.copy(name = "a\\b")),
                listOf(item, item.copy(name = "DATA")), listOf(item.copy(name = "é"), item.copy(name = "e\u0301")),
                listOf(item.copy(name = "a"), item.copy(name = "a/b")),
            )
            for (bad in hostile) assertFailsWith<CodecFailure> { CheckedZip.open(rig, rig.input(syntheticZip(headers + bad)), profile).visit { _, _ -> } }
            val truncated = syntheticZip(headers + item).dropLast(1).toByteArray()
            assertFailsWith<CodecFailure> { CheckedZip.open(rig, rig.input(truncated), profile) }
            assertEquals(0, rig.openSources)
        }
    }

    @Test fun backupArchiveSizeIsRepresentableWithoutAllocatingItsDeclaredPayload() = runTest {
        val rig = CodecTestRig()
        val headers = listOf(SyntheticEntry("manifest.json", byteArrayOf()), SyntheticEntry("config.json", byteArrayOf()))
        val archive = SyntheticEntry("assets/" + "a".repeat(64), byteArrayOf(), method = 8, size = MEDIA_LIMIT + 1)
        val file = rig.input(syntheticZip(headers + archive))
        assertEquals(MEDIA_LIMIT + 1, CheckedZip.open(rig, file, ZipProfile.BACKUP).items.last().size)
        assertEquals(ProblemCode.LIMIT_EXCEEDED, assertFailsWith<CodecFailure> { CheckedZip.open(rig, file, ZipProfile.LINKA) }.problem.code)
        // Index acceptance proves only the profile budget; full visit still rejects the intentionally absent payload.
        assertFailsWith<Exception> { CheckedZip.open(rig, file, ZipProfile.BACKUP).visit { _, _ -> } }
        assertEquals(0, rig.openSources)
    }
}
