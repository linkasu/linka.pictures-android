package su.linka.pictures.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import su.linka.pictures.Card
import su.linka.pictures.Set
import su.linka.pictures.SetManifest
import java.io.File

object ComponentTestUtils {

    fun createSet(
        rows: Int = 1,
        columns: Int = 1,
        cards: List<Card> = emptyList(),
        withoutSpace: Boolean = false
    ): Triple<Context, Set, File> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workingDir = File(context.cacheDir, "components-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val manifest = SetManifest(File(workingDir, "config.json")).apply {
            this.rows = rows
            this.columns = columns
            this.cards.clear()
            this.cards.addAll(cards)
            this.withoutSpace = withoutSpace
        }
        val set = Set(manifest, workingDir)
        return Triple(context, set, workingDir)
    }

    fun cleanup(dir: File) {
        dir.deleteRecursively()
    }
}
