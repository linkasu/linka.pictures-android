package su.linka.pictures.storage

import su.linka.pictures.model.SetManifest

expect class LinkaFileManager {
    fun loadDefaultSets()
    
    fun getSets(): List<SetManifest>
    
    fun getSet(fileName: String): LinkaSet
    
    fun createSet(): LinkaSet
    
    fun saveSet(set: LinkaSet, fileName: String): Result<Unit>
    
    fun deleteSet(manifest: SetManifest): Result<Unit>
    
    fun renameSet(manifest: SetManifest, newName: String): Result<Unit>
    
    fun getImagePath(manifest: SetManifest, imagePath: String?): String?
    
    fun saveImage(workspaceId: String, imageData: ByteArray, format: ImageFormat): String
    
    fun saveAudio(workspaceId: String, audioData: ByteArray, fileName: String): String
    
    fun copyAudioFile(workspaceId: String, sourcePath: String): String
    
    fun getAudioPath(workspaceId: String, audioPath: String?): String?
}

enum class ImageFormat {
    PNG,
    JPEG,
    WEBP
}

