package su.linka.pictures

import org.json.JSONArray
import org.json.JSONObject

/** Private Android snapshots preserve editor placeholders; BoardCodec.encode is an export format. */
internal object EditorDraftJson {
    fun card(card: Card): JSONObject = JSONObject()
        .put("id", card.id).put("cardType", card.cardType)
        .put("title", card.title ?: JSONObject.NULL)
        .put("imagePath", card.imagePath ?: JSONObject.NULL)
        .put("audioPath", card.audioPath ?: JSONObject.NULL)

    fun board(manifest: SetManifest): JSONObject {
        BoardCodec.validate(manifest.toDocument())
        return JSONObject().put("version", manifest.version)
            .put("rows", manifest.rows).put("columns", manifest.columns)
            .put("withoutSpace", manifest.withoutSpace)
            .put("cards", JSONArray().apply { manifest.cards.forEach { put(card(it)) } })
    }
}
