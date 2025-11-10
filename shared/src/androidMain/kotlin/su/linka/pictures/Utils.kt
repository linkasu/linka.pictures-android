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
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Utils {

    @JvmStatic
    @Throws(IOException::class)
    fun copy(input: InputStream, destination: File): File {
        var output: OutputStream? = null
        try {
            output = FileOutputStream(destination)
            val buffer = ByteArray(1024)
            while (true) {
                val length = input.read(buffer)
                if (length <= 0) break
                output.write(buffer, 0, length)
            }
        } catch (error: Exception) {
            error.printStackTrace()
        } finally {
            try {
                output?.close()
            } catch (closeError: IOException) {
                closeError.printStackTrace()
            }
            try {
                input.close()
            } catch (closeError: IOException) {
                closeError.printStackTrace()
            }
        }
        return destination
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
    fun saveBitmapToFile(
        dir: File,
        fileName: String,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int
    ): File {
        val imageFile = File(dir, fileName)
        var outputStream: FileOutputStream? = null
        try {
            outputStream = FileOutputStream(imageFile)
            bitmap.compress(format, quality, outputStream)
        } catch (error: IOException) {
            error.printStackTrace()
        } finally {
            try {
                outputStream?.close()
            } catch (closeError: IOException) {
                closeError.printStackTrace()
            }
        }
        return imageFile
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
