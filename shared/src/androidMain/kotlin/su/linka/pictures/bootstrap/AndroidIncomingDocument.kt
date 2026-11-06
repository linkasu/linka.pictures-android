package su.linka.pictures.bootstrap

import android.content.Intent
import android.net.Uri

// Archive pickers use */* because LINKA/backup files have no universally registered MIME type.
internal fun acceptsIncomingArchive(intent: Intent): Boolean = intent.action == Intent.ACTION_OPEN_DOCUMENT &&
    intent.type in setOf("*/*", "application/octet-stream", "application/zip")

internal fun acceptsIncomingDocument(uri: Uri): Boolean = uri.scheme == "content"
