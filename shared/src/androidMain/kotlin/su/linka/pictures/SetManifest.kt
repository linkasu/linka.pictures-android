package su.linka.pictures

import org.json.JSONArray
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

    constructor(configFile: File, jsonObject: JSONObject, archiveFile: File? = null) : this(
        configFile = configFile,
        version = jsonObject.optString("version", "1.0"),
        columns = jsonObject.optInt("columns", 4),
        rows = jsonObject.optInt("rows", 3),
        withoutSpace = jsonObject.optBoolean("withoutSpace", false),
        cards = ArrayList<Card>(jsonObject.optJSONArray("cards")?.length() ?: 0).apply inner@{
            val array = jsonObject.optJSONArray("cards") ?: return@inner
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optInt("id")
                val cardType = obj.optInt("cardType")
                val title = if (obj.isNull("title")) null else obj.optString("title")
                val audioPath = if (obj.isNull("audioPath")) null else obj.optString("audioPath")
                val imagePath = if (obj.isNull("imagePath")) null else obj.optString("imagePath")
                add(Card(id, imagePath, title, audioPath, cardType))
            }
        },
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

    @Throws(JSONException::class)
    fun toJSONObject(): JSONObject {
        val array = JSONArray()
        cards.forEachIndexed { index, card ->
            array.put(index, card.toJSONObject())
        }
        return JSONObject()
            .put("version", version)
            .put("columns", columns)
            .put("rows", rows)
            .put("withoutSpace", withoutSpace)
            .put("cards", array)
    }
}
