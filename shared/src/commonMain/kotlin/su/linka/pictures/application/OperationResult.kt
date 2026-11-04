package su.linka.pictures.application

enum class ProblemCode {
    NOT_FOUND, CONFLICT, INVALID_DATA, NEWER_SCHEMA, IO_FAILURE,
    UNSUPPORTED_VERSION, UNSUPPORTED_MODE, UNSUPPORTED_FEATURE, UNSUPPORTED_MEDIA, UNSAFE_ARCHIVE,
    LIMIT_EXCEEDED, UNAUTHORIZED, OWNER_VERIFICATION_UNAVAILABLE, PERMISSION_DENIED,
    VOICE_UNAVAILABLE, INTERRUPTED, UNFINISHED_FORM, RECOVERY_UNAVAILABLE, STALE_REQUEST,
    MISSING_ASSET, INTEGRITY_FAILURE, SPEECH_UNAVAILABLE, SPEECH_MATERIALIZATION_REQUIRED,
}

// Structured diagnostics deliberately cannot carry message text, passwords, paths or raw exceptions.
data class Problem(
    val code: ProblemCode,
    val subjectId: String? = null,
    val expectedRevision: Long? = null,
    val actualRevision: Long? = null,
    val systemCode: Int? = null,
)

sealed interface OperationResult<out T> {
    data class Completed<T>(val value: T) : OperationResult<T>
    data object Cancelled : OperationResult<Nothing>
    data class Failed(val problem: Problem) : OperationResult<Nothing>
}
