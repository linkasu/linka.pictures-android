package su.linka.pictures.components

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.View.MeasureSpec
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.Card
import su.linka.pictures.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GridButtonTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun setCard_withEmptyCard_hidesButton() {
        val button = GridButton(context)
        button.setCard(Card(0, cardType = 2))

        assertEquals(View.INVISIBLE, button.visibility)
        val imageText = button.findViewById<ImageView>(R.id.image_text)
        assertNull(imageText.drawable)
    }

    @Test
    fun setCard_withStandardCard_showsTextBitmap() {
        val button = GridButton(context)
        val card = Card(0, title = "Hello", cardType = 0)

        button.setCard(card)

        assertEquals(View.VISIBLE, button.visibility)
        val imageText = button.findViewById<ImageView>(R.id.image_text)
        assertNotNull(imageText.drawable)
        assertSame(card, button.getCard())
    }

    @Test
    fun setImage_withBitmap_assignsDrawable() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val card = Card(1, title = "Hello", cardType = 0)
        val button = GridButton(context, card)

        button.setImage(bitmap)

        val imageView = button.findViewById<ImageView>(R.id.image)
        assertNotNull(imageView.drawable)
        assertSame(bitmap, button.getImage())
    }

    @Test
    fun onMeasure_forOutputCard_usesHeightSpec() {
        val card = Card(2, title = "Hi", cardType = 0)
        val button = GridButton(context, card, isOutputCard = true)
        val widthSpec = MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(150, MeasureSpec.EXACTLY)

        button.measure(widthSpec, heightSpec)

        assertEquals(150, button.measuredWidth)
        assertEquals(150, button.measuredHeight)
    }

    @Test
    fun onMeasure_forRegularCard_keepsWidthSpec() {
        val card = Card(3, title = "Hi", cardType = 0)
        val button = GridButton(context, card, isOutputCard = false)
        val widthSpec = MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(150, MeasureSpec.EXACTLY)

        button.measure(widthSpec, heightSpec)

        assertEquals(200, button.measuredWidth)
        assertEquals(150, button.measuredHeight)
    }
}
