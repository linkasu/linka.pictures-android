package su.linka.pictures.ui.images

import kotlin.test.*
import su.linka.pictures.domain.*

class VisibleImageDemandTest {
    private val a = AssetId("a".repeat(64))
    private val b = AssetId("b".repeat(64))

    @Test
    fun composedSourcesUnionDeduplicatesAndRetainsOtherOwners() {
        val calls = mutableListOf<Set<AssetId>>()
        val registry = VisibleImageRegistry()
        registry.listen { calls.add(it) }
        registry.register("message-part", setOf(a))
        registry.register("grid-row", setOf(a, b))
        registry.register("grid-row", setOf(a, b))
        assertEquals(listOf(emptySet(), setOf(a), setOf(a, b)), calls)
        registry.remove("message-part")
        assertEquals(3, calls.size)
        registry.remove("grid-row")
        assertEquals(emptySet(), calls.last())
    }

    @Test
    fun disposingAnOldPageOrEffectCannotRemoveItsReplacement() {
        var current = emptySet<AssetId>()
        val registry = VisibleImageRegistry()
        registry.listen { current = it }
        val old = Any()
        val replacement = Any()
        registry.register(old, setOf(a))
        registry.register(replacement, setOf(b))
        registry.remove(old)
        assertEquals(setOf(b), current)
        registry.remove(old)
        assertEquals(setOf(b), current)
    }

    @Test
    fun callbackReplacementPublishesCurrentUnionAndDisposalUsesCurrentCallback() {
        val old = mutableListOf<Set<AssetId>>()
        val current = mutableListOf<Set<AssetId>>()
        val registry = VisibleImageRegistry()
        registry.listen { old.add(it) }
        registry.register("form", setOf(a))
        val callback: (Set<AssetId>) -> Unit = { current.add(it) }
        registry.listen(callback)
        registry.listen(callback)
        assertEquals(setOf(a), old.last())
        assertEquals(listOf(setOf(a)), current)
        registry.close()
        assertEquals(emptySet(), current.last())
        assertEquals(listOf(emptySet(), setOf(a)), old)
    }

    @Test
    fun closedScreenRejectsOrphanEffectsAndCanBeReplacedByANewGeneration() {
        val calls = mutableListOf<Set<AssetId>>()
        val registry = VisibleImageRegistry()
        registry.listen { calls.add(it) }
        registry.register("row", setOf(a))
        registry.close()
        val count = calls.size
        registry.remove("row")
        registry.register("late", setOf(b))
        registry.listen { error("closed registry must not attach a callback") }
        registry.close()
        assertEquals(count, calls.size)
        assertEquals(emptySet(), calls.last())
        val restored = VisibleImageRegistry()
        restored.register("row", setOf(b))
        restored.listen { calls.add(it) }
        assertEquals(setOf(b), calls.last())
    }

    @Test
    fun nullCallbackKeepsManualBorrowedMapCompatibility() {
        val registry = VisibleImageRegistry()
        registry.register("row", setOf(a))
        registry.listen(null)
        var current = emptySet<AssetId>()
        registry.listen { current = it }
        assertEquals(setOf(a), current)
        registry.listen(null)
        assertEquals(emptySet(), current)
    }

    @Test
    fun onlyTheComposedContentRowOfTheSelectedPageIsDemanded() {
        fun card(id: String, asset: AssetId) = CardDocument(CardId(id), id, image = asset)
        val selected = BoardPage(PageId("selected"), 3, 2, listOf(
            BoardCell(0, CellKind.CONTENT, card("first", a)),
            BoardCell(4, CellKind.CONTENT, card("last", b)),
            BoardCell(1, CellKind.SPACE), BoardCell(2, CellKind.EMPTY),
        ))
        val other = BoardPage(PageId("other"), 1, 1, listOf(BoardCell(0, CellKind.CONTENT, card("other", AssetId("c".repeat(64))))))
        val document = BoardDocument(BoardId("board"), "board", listOf(selected, other))
        assertEquals(setOf(a), visibleRowImages(document.pages.first(), 0))
        assertEquals(emptySet(), visibleRowImages(selected, 1))
        assertEquals(setOf(b), visibleRowImages(selected, 2))
    }
}
