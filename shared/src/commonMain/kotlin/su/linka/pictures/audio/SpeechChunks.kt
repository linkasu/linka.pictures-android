package su.linka.pictures.audio

import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.text.graphemeBoundaries

internal fun speechChunks(text: String, maximum: Int): OperationResult<List<String>> {
    if (maximum <= 0) return OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED))
    if (text.isBlank()) return OperationResult.Completed(emptyList())
    val boundaries = graphemeBoundaries(text)
    val chunks = mutableListOf<String>()
    var start = 0
    var end = 0
    for (boundary in boundaries.drop(1)) {
        if (boundary - end > maximum) return OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED))
        if (boundary - start > maximum) {
            chunks += text.substring(start, end)
            start = end
        }
        end = boundary
    }
    if (end > start) chunks += text.substring(start, end)
    return OperationResult.Completed(chunks.toList())
}
