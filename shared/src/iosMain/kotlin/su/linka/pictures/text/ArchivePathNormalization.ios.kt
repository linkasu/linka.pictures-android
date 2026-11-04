package su.linka.pictures.text

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping

@OptIn(BetaInteropApi::class)
internal actual fun normalizeNfc(text: String): String = NSString.create(string = text).precomposedStringWithCanonicalMapping
