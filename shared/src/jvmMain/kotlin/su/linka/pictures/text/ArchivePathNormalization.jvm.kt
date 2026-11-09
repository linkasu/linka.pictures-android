package su.linka.pictures.text

import java.text.Normalizer

internal actual fun normalizeNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
