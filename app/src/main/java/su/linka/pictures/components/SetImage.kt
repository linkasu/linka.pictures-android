package su.linka.pictures.components

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import java.io.File

/** Decorative thumbnail only; the common screen supplies its accessible text label. */
@Composable
fun SetImage(file: File?, modifier: Modifier, revision: String = "") {
    val signature = ObjectKey("$revision:${file?.lastModified()}:${file?.length()}")
    AndroidView(
        factory = { context -> ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        } },
        modifier = modifier,
        update = { view ->
            Glide.with(view).asBitmap().load(file)
                .signature(signature)
                .override(512, 512).fitCenter().into(view)
        },
        onRelease = { view -> Glide.with(view).clear(view) },
    )
}
