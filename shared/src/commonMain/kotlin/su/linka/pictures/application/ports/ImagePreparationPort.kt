package su.linka.pictures.application.ports

import su.linka.pictures.application.OperationResult

data class TextImageRequest(val text: String, val foregroundArgb: Long = 0xff000000, val backgroundArgb: Long = 0xffffffff)

interface ImagePreparationPort {
    suspend fun prepareImage(file: LocalFile): OperationResult<TemporaryFile>
    suspend fun imageFromText(request: TextImageRequest): OperationResult<TemporaryFile>
}
