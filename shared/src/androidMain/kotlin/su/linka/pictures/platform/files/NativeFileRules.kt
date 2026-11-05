package su.linka.pictures.platform.files

import android.os.Looper
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.PickedFileKind
import su.linka.pictures.text.isNativeText

internal object NativeFileRules {
    const val OUTPUT_LIMIT = 2L * 1024 * 1024 * 1024
    class Failure(val result: OperationResult<Nothing>) : Exception()
    fun fail(code: ProblemCode): Nothing = throw Failure(OperationResult.Failed(Problem(code)))
    fun cancelled(): Nothing = throw Failure(OperationResult.Cancelled)
    fun worker() { if (Looper.myLooper() == Looper.getMainLooper()) fail(ProblemCode.IO_FAILURE) }
    fun leaf(value: String) {
        if (value.isEmpty() || value == "." || value == ".." || !isNativeText(value) || value.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) fail(ProblemCode.INVALID_DATA)
    }
    fun mime(value: String) {
        val parts = value.split('/')
        if (parts.size != 2 || parts.any { it.isEmpty() || it.any { ch -> !(ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "!#$&^_.+-") } }) fail(ProblemCode.INVALID_DATA)
    }
    fun budget(kind: PickedFileKind): Long = when (kind) {
        PickedFileKind.IMAGE, PickedFileKind.AUDIO -> 64L * 1024 * 1024
        PickedFileKind.LINKA -> 500L * 1024 * 1024
        PickedFileKind.BACKUP -> OUTPUT_LIMIT
    }
    fun <T> value(result: OperationResult<T>): T = when (result) {
        is OperationResult.Completed -> result.value
        is OperationResult.Failed -> throw Failure(result)
        OperationResult.Cancelled -> throw Failure(OperationResult.Cancelled)
        OperationResult.UnconfirmedCommit -> throw Failure(OperationResult.UnconfirmedCommit)
    }
    fun <T> result(action: () -> T): OperationResult<T> = try { OperationResult.Completed(action()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Failure) { failure.result }
        catch (_: FileNotFoundException) { OperationResult.Failed(Problem(ProblemCode.NOT_FOUND)) }
        catch (_: SecurityException) { OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)) }
        catch (_: Exception) { OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }
}
