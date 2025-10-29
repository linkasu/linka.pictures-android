package su.linka.pictures.components

import android.content.Context
import android.util.AttributeSet
import su.linka.pictures.Card
import su.linka.pictures.Set

class OutputGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CardGrid(context, attrs) {

    private val cards: MutableList<Card> = mutableListOf()

    init {
        orientation = HORIZONTAL
    }

    fun addCard(card: Card) {
        cards.add(card)
        render()
    }

    fun backspace() {
        if (cards.isNotEmpty()) {
            cards.removeAt(cards.lastIndex)
            render()
        }
    }

    fun clear() {
        cards.clear()
        render()
    }

    override fun setSet(set: Set, output: Boolean) {
        this.activeSet = set
        this.manifest = set.getManifest()
        render()
    }

    override fun render() {
        removeAllViews()
        val currentSet = activeSet ?: return
        cards.forEachIndexed { index, card ->
            if (card.cardType != 0) return@forEachIndexed
            val button = GridButton(context, card, currentSet.getBitmap(card.imagePath), true)
            addView(button, index)
        }
    }
}
