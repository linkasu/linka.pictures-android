package su.linka.pictures

import org.json.JSONException
import org.json.JSONObject

data class Card(
    val id: Int,
    var imagePath: String? = null,
    var title: String? = null,
    var audioPath: String? = null,
    var cardType: Int = 0
) : Cloneable {

    constructor(id: Int, cardType: Int) : this(id, null, null, null, cardType)

    @Throws(JSONException::class)
    fun toJSONObject(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("imagePath", imagePath)
            .put("audioPath", audioPath)
            .put("cardType", if (cardType < 3) cardType else 2)
    }

    public override fun clone(): Card = copy()
}
