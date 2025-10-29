package su.linka.pictures.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import su.linka.pictures.Card
import su.linka.pictures.Set
import su.linka.pictures.SetManifest
import kotlin.math.ceil
import kotlin.math.max

open class CardGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var buttons: MutableList<GridButton> = mutableListOf()
    protected var rows: Int = 0
        private set
    protected var columns: Int = 0
        private set
    protected var pageIndex: Int = 0
        private set
    protected var activeSet: Set? = null
    protected var manifest: SetManifest? = null
    private var cardSelectListener: OnCardSelectListener? = null

    init {
        orientation = VERTICAL
    }

    fun setGridSize(rows: Int, columns: Int) {
        removeAllViews()
        this.rows = rows
        this.columns = columns

        val pageSize = getPageSize()
        buttons = MutableList(pageSize) { index ->
            GridButton(context).apply {
                layoutParams = cellLayoutParams()
                setOnClickListener {
                    val listener = cardSelectListener ?: return@setOnClickListener
                    val absoluteIndex = pageIndex * pageSize + index
                    listener.onCard(getCard(), absoluteIndex)
                }
            }
        }

        for (row in 0 until rows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = rowLayoutParams()
            }
            addView(rowLayout)
            for (column in 0 until columns) {
                val index = row * columns + column
                val button = buttons[index]
                rowLayout.addView(button)
            }
        }
    }

    fun getPageSize(): Int = rows * columns

    fun getPagesCount(): Int {
        val currentManifest = manifest ?: return 0
        val pageSize = getPageSize()
        if (pageSize == 0) return 0
        val totalCards = currentManifest.cards.size
        return max(1, ceil(totalCards / pageSize.toDouble()).toInt())
    }

    fun setSet(set: Set) {
        setSet(set, output = false)
    }

    open fun setSet(set: Set, output: Boolean) {
        this.activeSet = set
        val manifest = set.getManifest()
        this.manifest = manifest
        val desiredRows = if (output) 1 else manifest.rows
        if (rows != desiredRows || columns != manifest.columns || buttons.isEmpty()) {
            setGridSize(desiredRows, manifest.columns)
        }
        pageIndex = 0
        render()
    }

    fun nextPage(): Boolean {
        val pagesCount = getPagesCount()
        if (pageIndex < pagesCount - 1) {
            pageIndex++
            render()
            return true
        }
        return false
    }

    fun prevPage() {
        if (pageIndex > 0) {
            pageIndex--
        }
        render()
    }

    protected open fun render() {
        val manifest = manifest ?: return
        val currentSet = activeSet ?: return
        val pageSize = getPageSize()
        if (pageSize == 0) return

        for (index in 0 until pageSize) {
            val button = buttons.getOrNull(index) ?: continue
            val manifestIndex = pageIndex * pageSize + index
            val card = manifest.cards.getOrNull(manifestIndex)
            button.setCard(card)
            if (card?.cardType == 0) {
                button.setImage(currentSet.getBitmap(card.imagePath))
            } else {
                button.setImage(null)
            }
        }
        visibility = if (pageSize == 0) View.GONE else View.VISIBLE
    }

    fun setCardSelectListener(listener: OnCardSelectListener?) {
        cardSelectListener = listener
    }

    fun jumpToPage(page: Int) {
        pageIndex = page.coerceIn(0, max(0, getPagesCount() - 1))
        render()
    }

    fun currentPage(): Int = pageIndex

    fun interface OnCardSelectListener {
        fun onCard(card: Card?, position: Int)
    }

    protected val cells: List<GridButton>
        get() = buttons

    private fun rowLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun cellLayoutParams(): LayoutParams =
        LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
}
