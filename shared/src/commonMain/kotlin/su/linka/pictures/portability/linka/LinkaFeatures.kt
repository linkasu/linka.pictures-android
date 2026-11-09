package su.linka.pictures.portability.linka

import kotlinx.serialization.json.JsonObject
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.portability.zip.fail

internal val activeKeys = setOf("action", "actions", "command", "commands", "event", "events", "script", "scripts", "navigation")

internal fun JsonObject.structural() {
    if (keys.any { it in activeKeys }) fail(ProblemCode.UNSUPPORTED_FEATURE)
}
