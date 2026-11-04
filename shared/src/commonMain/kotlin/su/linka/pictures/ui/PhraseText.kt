package su.linka.pictures.ui

import su.linka.pictures.Card

/** Keyboard-mode text: concatenate titles verbatim, insert only explicit space cards. */
fun phraseText(cards: List<Card>): String = buildString {
    cards.forEach { card ->
        when (card.cardType) {
            0 -> card.title?.let { append(it) }
            1 -> append(' ')
        }
    }
}
