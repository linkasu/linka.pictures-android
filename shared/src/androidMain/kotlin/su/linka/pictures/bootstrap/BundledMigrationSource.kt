package su.linka.pictures.bootstrap

import android.content.res.AssetManager
import okio.Path
import okio.Source
import okio.source
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.migration.*

/** Physical filenames are case-independent; display names retain the shipped identities. */
internal class BundledMigrationSource(private val assets: AssetManager) : MigrationSource {
    override val kind = MigrationSourceKind.BUNDLED

    private data class Board(val file: String, val name: String, val size: Long, val hash: String) {
        fun entry() = LegacyEntry(name, size, hash)
    }

    private val boards = listOf(
        Board("pain.linka", "Болит.linka", 226268, "ae6f1e58f2d7e7a3826e3586aab3d862f38a43d9cecf59168766dc536ca95985"),
        Board("food.linka", "Еда.linka", 239661, "d77a113a5be5bf2773b5ebc70bc1757a4ab48761203c538504a6dabbbb08b14d"),
        Board("keyboard.linka", "Клавиатура.linka", 56563, "c7b1d07cf4c3e926dbf7256d5c3f10acb1de329df70a4753f834128b1320e3b8"),
        Board("want.linka", "ХОЧУ.linka", 2866142, "a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b"),
        Board("numbers-upper.linka", "Цифры.linka", 593343, "4e2a43a6b62ff34a8675fb9e02a3998a0c4db09558044b611bb40c81115c798c"),
        Board("home-sounds.linka", "ШУМЫ ДОМА.linka", 1586614, "04b37130e6c10e0806951c503a0916042c38bd41a601bfa134c98d8f0646dcf1"),
        Board("numbers-lower.linka", "цифры.linka", 593343, "4e2a43a6b62ff34a8675fb9e02a3998a0c4db09558044b611bb40c81115c798c"),
    )

    override fun validateTarget(target: Path) { require(target.isAbsolute) }

    override suspend fun entries(): List<LegacyEntry> = boards.map { board ->
        val entry = board.entry()
        if (sourceHash(open(entry), entry.size) != entry.hash) reject(ProblemCode.INTEGRITY_FAILURE)
        entry
    }

    override fun open(entry: LegacyEntry): Source {
        val board = boards.singleOrNull { it.entry() == entry } ?: reject(ProblemCode.INVALID_DATA)
        return assets.open("bundled/${board.file}").source()
    }
}
