package su.linka.pictures.ui.components

import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import su.linka.pictures.ui.theme.LinkaMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationCardMeasurePolicyTest {
    private val scope = object : MeasureScope {
        override val density = 1f
        override val fontScale = 1f
        override val layoutDirection = LayoutDirection.Ltr
    }

    private class Child(private val requestedWidth: Int, private val requestedHeight: Int) : Measurable {
        var measured: Constraints? = null
        override val parentData: Any? = null
        override fun minIntrinsicWidth(height: Int) = requestedWidth
        override fun maxIntrinsicWidth(height: Int) = requestedWidth
        override fun minIntrinsicHeight(width: Int) = requestedHeight
        override fun maxIntrinsicHeight(width: Int) = requestedHeight
        override fun measure(constraints: Constraints): Placeable {
            measured = constraints
            return object : Placeable() {
                init {
                    measuredSize = IntSize(
                        constraints.constrainWidth(requestedWidth), constraints.constrainHeight(requestedHeight),
                    )
                }
                override fun get(alignmentLine: AlignmentLine) = AlignmentLine.Unspecified
                override fun placeAt(position: IntOffset, zIndex: Float, layerBlock: (GraphicsLayerScope.() -> Unit)?) = Unit
            }
        }
    }

    @Test
    fun platformMinimumStillLeavesPixelsAndCaptionInsideTheRealMeasurePolicy() {
        val width = (LinkaMetrics.touchTarget.value - 8).toInt()
        val height = (LinkaMetrics.touchTarget.value - 8).toInt()
        assertTrue(width >= 25)
        assertTrue(height >= 29)
        val image = Child(500, 500)
        val caption = Child(width, 8)
        val result = with(CommunicationCardMeasurePolicy) {
            scope.measure(listOf(image, caption), Constraints.fixed(width, height))
        }
        assertEquals(width, result.width)
        assertEquals(height, result.height)
        assertEquals(Constraints.fixed(width, height - 8 - 6), image.measured)
    }

    @Test
    fun boundedGridOwnsBothDimensionsAndWrappedCaptionBorrowsImageSpace() {
        for ((width, height) in listOf(240 to 60, 160 to 160, 72 to 144)) {
            for (captionHeight in listOf(12, 24, 48)) {
                val image = Child(1000, 1000)
                val caption = Child(width, captionHeight)
                val result = with(CommunicationCardMeasurePolicy) {
                    scope.measure(listOf(image, caption), Constraints.fixed(width, height))
                }
                assertEquals(width, result.width)
                assertEquals(height, result.height)
                val reserve = minOf(48, height / 3)
                val captionMaxHeight = height - reserve - 6
                assertEquals(Constraints.fixed(width, height - minOf(captionHeight, captionMaxHeight) - 6), image.measured)
                assertEquals(captionMaxHeight, caption.measured!!.maxHeight)
                assertEquals(0, caption.measured!!.minHeight)
                assertEquals(0, caption.measured!!.minWidth)
            }
        }
    }

    @Test
    fun oversizedCaptionKeepsAnImageViewportInsideBoundedHeight() {
        for (pixels in listOf(0, 1000)) {
            val image = Child(pixels, pixels)
            val caption = Child(180, 500)
            val result = with(CommunicationCardMeasurePolicy) {
                scope.measure(listOf(image, caption), Constraints.fixed(180, 125))
            }
            assertEquals(180, result.width)
            assertEquals(125, result.height)
            assertEquals(Constraints(maxWidth = 180, maxHeight = 78), caption.measured)
            assertEquals(Constraints.fixed(180, 41), image.measured)
        }
    }

    @Test
    fun zeroBoundsKeepBothChildrenAndParentAtZero() {
        val image = Child(1000, 1000)
        val caption = Child(180, 500)
        val result = with(CommunicationCardMeasurePolicy) {
            scope.measure(listOf(image, caption), Constraints.fixed(0, 0))
        }
        assertEquals(0, result.width)
        assertEquals(0, result.height)
        assertEquals(Constraints.fixed(0, 0), caption.measured)
        assertEquals(Constraints.fixed(0, 0), image.measured)
    }

    @Test
    fun unboundedLegacyCallerGetsAnImageSquareWithoutTheOld160Cap() {
        val image = Child(1000, 1000)
        val caption = Child(180, 32)
        val result = with(CommunicationCardMeasurePolicy) {
            scope.measure(listOf(image, caption), Constraints(maxWidth = 220))
        }
        assertEquals(220, result.width)
        assertEquals(258, result.height)
        assertEquals(Constraints.fixed(220, 220), image.measured)
        assertEquals(Constraints(maxWidth = 220), caption.measured)
    }

    @Test
    fun unboundedHeightStillRespectsTheParentMinimum() {
        val image = Child(1000, 1000)
        val result = with(CommunicationCardMeasurePolicy) {
            scope.measure(listOf(image, Child(48, 12)), Constraints(minHeight = 96, maxWidth = 48))
        }
        assertEquals(48, result.width)
        assertEquals(96, result.height)
        assertEquals(Constraints.fixed(48, 78), image.measured)
    }

    @Test
    fun loadingPixelsCannotChangeParentGeometry() {
        for (pixels in listOf(0, 1000)) {
            val image = Child(pixels, pixels)
            val result = with(CommunicationCardMeasurePolicy) {
                scope.measure(listOf(image, Child(180, 24)), Constraints.fixed(180, 72))
            }
            assertEquals(180, result.width)
            assertEquals(72, result.height)
            assertEquals(Constraints.fixed(180, 42), image.measured)
        }
    }

    @Test
    fun tinyBoundsCapTheGapWithoutLosingTheImageReservation() {
        for (height in 1..8) {
            val image = Child(1000, 1000)
            val caption = Child(48, 500)
            val result = with(CommunicationCardMeasurePolicy) {
                scope.measure(listOf(image, caption), Constraints.fixed(48, height))
            }
            assertEquals(height, result.height)
            assertEquals(height / 3, image.measured!!.maxHeight)
            assertTrue(caption.measured!!.maxHeight >= 0)
        }
    }
}
