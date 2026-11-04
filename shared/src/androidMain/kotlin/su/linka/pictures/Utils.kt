package su.linka.pictures

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.jvm.JvmOverloads
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Utils {

    @JvmStatic
    @Throws(IOException::class)
    fun copy(input: InputStream, destination: File): File {
        var temporary: File? = null
        try {
            input.use { source ->
                val staged = File.createTempFile("copy-", ".tmp", destination.absoluteFile.parentFile)
                temporary = staged
                FileOutputStream(staged).use { output ->
                    source.copyTo(output)
                }
            }
            return replaceFile(temporary!!, destination)
        } finally {
            temporary?.delete()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copy(source: File, destination: File): File {
        return copy(FileInputStream(source), destination)
    }

    @JvmStatic
    @JvmOverloads
    fun textAsBitmap(text: String?, textSize: Float = 128f, textColor: Int = Color.BLACK): Bitmap? {
        if (text.isNullOrEmpty()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = textColor
            textAlign = Paint.Align.LEFT
        }
        val baseline = -paint.ascent()
        val width = ((paint.measureText(text) + 0.5f).toInt()).coerceAtLeast(1)
        val height = ((baseline + paint.descent() + 0.5f).toInt()).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, 0f, baseline, paint)
        return bitmap
    }

    @JvmStatic
    @Throws(IOException::class)
    fun saveBitmapToFile(
        dir: File,
        fileName: String,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int
    ): File {
        val imageFile = File(dir, fileName)
        val temporary = File.createTempFile("bitmap-", ".tmp", dir)
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(format, quality, output)) {
                    throw IOException("Unable to compress bitmap")
                }
            }
            return replaceFile(temporary, imageFile)
        } finally {
            temporary.delete()
        }
    }

    private fun replaceFile(temporary: File, destination: File): File {
        if (!temporary.renameTo(destination)) {
            throw IOException("Unable to save $destination")
        }
        return destination
    }

    @JvmStatic
    fun unwrap(context: Context): Activity? {
        var current = context
        while (current !is Activity && current is ContextWrapper) {
            current = current.baseContext
        }
        return current as? Activity
    }

    @JvmStatic
    @Throws(IOException::class)
    fun zipFolder(inputFolderPath: String, outZipPath: String) {
        FileOutputStream(outZipPath).use { fos ->
            ZipOutputStream(fos).use { zos ->
                val srcDir = File(inputFolderPath)
                val files = srcDir.listFiles() ?: return
                val buffer = ByteArray(1024)
                for (file in files) {
                    FileInputStream(file).use { fis ->
                        zos.putNextEntry(ZipEntry(file.name))
                        while (true) {
                            val length = fis.read(buffer)
                            if (length <= 0) break
                            zos.write(buffer, 0, length)
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    @JvmStatic
    fun readBitmapFromFile(image: File?): Bitmap? {
        if (image == null) return null
        return BitmapFactory.decodeFile(image.absolutePath)
    }
}
