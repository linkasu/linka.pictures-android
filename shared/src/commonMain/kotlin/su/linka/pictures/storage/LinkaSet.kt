package su.linka.pictures.storage

import su.linka.pictures.model.Card
import su.linka.pictures.model.SetManifest

class LinkaSet(
    val manifest: SetManifest,
    val workspaceId: String
) {
    fun addCard(pos: Int, card: Card) {
        val cards = manifest.cards
        if (pos >= cards.size) {
            while (cards.size < pos) {
                cards.add(Card(0, 3))
            }
            cards.add(card)
        } else {
            cards[pos] = card
        }
    }
}

