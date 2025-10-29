package su.linka.pictures

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.util.LruCache
import net.lingala.zip4j.exception.ZipException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SetsAdapter(context: Context) :
    ArrayAdapter<SetManifest>(context, R.layout.set_grid_button) {

    private val setsManager = SetsManager(context)
    private val inflater = LayoutInflater.from(context)
    private val imageCache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.set_grid_button, parent, false)
        val manifest = getItem(position) ?: return view

        val imageView = view.findViewById<ImageView>(R.id.picture)
        val titleView = view.findViewById<TextView>(R.id.text)

        titleView.text = manifest.name.removeSuffix(".linka")

        val bitmapPath = manifest.getDefaultBitmap()
        if (bitmapPath == null) {
            imageView.setImageDrawable(null)
            imageView.tag = null
        } else {
            val cacheKey = "${manifest.cacheKey}/$bitmapPath"
            imageView.tag = cacheKey
            val cached = imageCache.get(cacheKey)
            if (cached != null) {
                imageView.setImageBitmap(cached)
            } else {
                imageView.setImageDrawable(null)
                loadPreviewAsync(cacheKey, manifest, bitmapPath, imageView)
            }
        }

        return view
    }

    override fun clear() {
        super.clear()
        imageCache.evictAll()
    }

    private fun loadPreviewAsync(
        cacheKey: String,
        manifest: SetManifest,
        path: String,
        imageView: ImageView
    ) {
        executor.execute {
            try {
                val imageFile = setsManager.getSetImage(manifest, path)
                val bitmap = Utils.readBitmapFromFile(imageFile)
                if (bitmap != null) {
                    imageCache.put(cacheKey, bitmap)
                    mainHandler.post {
                        if (imageView.tag == cacheKey) {
                            imageView.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (error: ZipException) {
                error.printStackTrace()
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_CACHE_SIZE_KB = 4 * 1024 // 4MB
    }
}
