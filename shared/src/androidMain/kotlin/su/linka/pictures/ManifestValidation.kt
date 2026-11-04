package su.linka.pictures

import org.json.JSONException
import org.json.JSONObject

object ManifestValidation {
    @Throws(JSONException::class)
    fun validate(json: JSONObject): JSONObject {
        integer(json, "rows", 3)
        integer(json, "columns", 4)
        if (json.has("withoutSpace") && json.get("withoutSpace") !is Boolean)
            throw JSONException("withoutSpace must be a boolean")
        if (json.has("cards")) {
            val cards = json.getJSONArray("cards")
            for (index in 0 until cards.length()) {
                val card = cards.getJSONObject(index)
                integer(card, "id", 0)
                integer(card, "cardType", 0)
                for (key in listOf("title", "audioPath", "imagePath")) {
                    if (!card.has(key) || card.isNull(key)) continue
                    if (card.get(key) !is String) throw JSONException("$key must be a string")
                }
            }
        }
        return json
    }

    private fun integer(json: JSONObject, key: String, default: Int): Int {
        if (!json.has(key)) return default
        val value = json.get(key)
        if (value !is Number || value.toDouble() != value.toInt().toDouble())
            throw JSONException("$key must be a 32-bit integer")
        return value.toInt()
    }
}
