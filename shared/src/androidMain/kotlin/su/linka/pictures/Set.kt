package su.linka.pictures

import android.graphics.Bitmap
import org.json.JSONException
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.UUID

class Set(
    private val manifest: SetManifest,
    private val folder: File
) {

    fun getManifest(): SetManifest = manifest

    fun getBitmap(imagePath: String?): Bitmap? {
        if (imagePath == null) return null
        val file = File(folder, imagePath)
        return Utils.readBitmapFromFile(file)
    }

    fun getAudioFile(audioPath: String?): File? {
        if (audioPath == null) return null
        return File(folder, audioPath)
    }

    @Throws(IOException::class)
    fun copyAudioFile(currentAudioFile: File): File {
        folder.mkdirs()
        val source = currentAudioFile.absoluteFile
        val destination = File(folder, source.name).absoluteFile
        if (destination.absolutePath == source.absolutePath) {
            return destination
        }
        val target = if (!destination.exists()) {
            destination
        } else {
            resolveUniqueDestination(source.name)
        }
        return Utils.copy(source, target)
    }

    fun saveBitmap(bitmap: Bitmap): File {
        folder.mkdirs()
        return Utils.saveBitmapToFile(
            folder,
            "${UUID.randomUUID()}.png",
            bitmap,
            Bitmap.CompressFormat.PNG,
            100
        )
    }

    fun addCard(pos: Int, card: Card) {
        val cards = manifest.cards
        if (pos >= cards.size) {
            while (cards.size < pos) {
                cards.add(Card(0, 3))
            }
            cards.add(card)
        } else {
            cards[pos] = card
        }
    }

    fun writeConfig() {
        val json = try {
            manifest.toJSONObject()
        } catch (error: JSONException) {
            error.printStackTrace()
            return
        }
        val configFile = manifest.configFile
        configFile.parentFile?.mkdirs()
        try {
            BufferedWriter(FileWriter(configFile)).use { writer ->
                writer.write(json.toString())
            }
        } catch (_: Exception) {
        }
    }

    fun getFolder(): File = folder

    private fun resolveUniqueDestination(originalName: String): File {
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex != -1) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex != -1) originalName.substring(dotIndex) else ""
        var index = 1
        while (true) {
            val candidate = File(folder, "$baseName-$index$extension").absoluteFile
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }
}
