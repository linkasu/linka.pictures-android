package su.linka.pictures

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.ArrayList

class SetManifest(
    val configFile: File,
    val version: String = "1.0",
    var columns: Int = 4,
    var rows: Int = 3,
    var withoutSpace: Boolean = false,
    val cards: MutableList<Card> = ArrayList(),
    var archiveFile: File? = null
) {

    init {
        BoardCodec.validate(toDocument())
    }

    constructor(configFile: File, jsonObject: JSONObject, archiveFile: File? = null) : this(
        configFile, decodeDocument(jsonObject), archiveFile
    )

    constructor(configFile: File, document: BoardDocument, archiveFile: File? = null) : this(
        configFile = configFile,
        version = document.version,
        columns = document.columns,
        rows = document.rows,
        withoutSpace = document.withoutSpace,
        cards = ArrayList(document.cards.map { it.clone() }),
        archiveFile = archiveFile
    )

    val name: String
        get() = (archiveFile ?: configFile).name

    val cacheKey: String
        get() = name.substringBeforeLast('.', name)

    fun withArchiveFile(file: File?): SetManifest = apply {
        archiveFile = file
    }

    fun getDefaultBitmap(): String? {
        for (card in cards) {
            val path = card.imagePath
            if (path != null) {
                return path
            }
        }
        return null
    }

    override fun toString(): String = name

    fun toDocument(): BoardDocument = BoardDocument(
        version, columns, rows, withoutSpace, cards.map { it.clone() }
    )

    @Throws(JSONException::class)
    fun toJSONObject(): JSONObject = JSONObject(BoardCodec.encode(toDocument()))

    companion object {
        private fun decodeDocument(jsonObject: JSONObject): BoardDocument = try {
            BoardCodec.decode(jsonObject.toString())
        } catch (error: IllegalArgumentException) {
            throw JSONException(error.message ?: "Invalid board document")
        }
    }
}
