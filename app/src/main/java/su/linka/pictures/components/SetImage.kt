package su.linka.pictures.components

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import java.io.File

@Composable
fun SetImage(file: File?, description: String?, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember(context) { ImageView(context) }
    val path = file?.absolutePath
    val modified = file?.lastModified()
    val length = file?.length()

    DisposableEffect(view, path, modified, length) {
        val requests = Glide.with(view)
        // Glide's view target rejects stale callbacks and detaches pooled bitmaps before release.
        requests.asBitmap().load(file)
            .signature(ObjectKey("$modified:$length"))
            .override(512, 512)
            .fitCenter()
            .into(view)
        onDispose {
            requests.clear(view)
        }
    }

    AndroidView(factory = { view }, modifier = modifier, update = { it.contentDescription = description })
}
