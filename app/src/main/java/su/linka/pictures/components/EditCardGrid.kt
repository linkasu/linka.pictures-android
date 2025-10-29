package su.linka.pictures.components

import android.content.Context
import android.util.AttributeSet
import su.linka.pictures.Card

class EditCardGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CardGrid(context, attrs) {

    fun refresh() {
        render()
    }

    override fun render() {
        val manifest = manifest ?: return
        val currentSet = activeSet ?: return
        val pageSize = getPageSize()
        if (pageSize == 0) return

        for (index in 0 until pageSize) {
            val button = cells.getOrNull(index) ?: continue
            val manifestIndex = pageIndex * pageSize + index
            val card = manifest.cards.getOrNull(manifestIndex) ?: Card(manifestIndex, 3)
            button.setCard(card)
            if (card.cardType == 0) {
                button.setImage(currentSet.getBitmap(card.imagePath))
            } else {
                button.setImage(null)
            }
        }
    }
}
