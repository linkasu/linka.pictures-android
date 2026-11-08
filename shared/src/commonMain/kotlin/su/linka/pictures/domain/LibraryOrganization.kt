package su.linka.pictures.domain

import kotlinx.serialization.Serializable
import su.linka.pictures.text.isNativeText

@Serializable
data class LibraryFolder(val id: FolderId, val name: String, val parentId: FolderId? = null) {
    init { require(name.isNotBlank() && name.length <= 256 && isNativeText(name)) }
}

/** Library placement only: never a media path, board copy, or permission. */
@Serializable
data class LibraryOrganization(
    val folders: List<LibraryFolder> = emptyList(),
    val placements: Map<BoardId, FolderId> = emptyMap(),
) {
    init {
        require(folders.size <= 4096 && placements.size <= 100000)
        val byId = folders.associateBy { it.id }
        require(byId.size == folders.size)
        require(placements.values.all { it in byId })
        for (folder in folders) {
            val visited = mutableSetOf(folder.id)
            var parent = folder.parentId
            while (parent != null) {
                require(visited.add(parent) && visited.size <= 32)
                parent = requireNotNull(byId[parent]).parentId
            }
        }
    }

    fun validateEntries(ids: Set<BoardId>) { require(placements.keys.all { it in ids }) }
}
