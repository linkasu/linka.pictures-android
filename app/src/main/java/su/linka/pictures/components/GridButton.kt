package su.linka.pictures.components

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Utils

class GridButton @JvmOverloads constructor(
    context: Context,
    private var card: Card? = null,
    private var image: Bitmap? = null,
    private val isOutputCard: Boolean = false
) : LinearLayout(context) {

    init {
        inflate(context, R.layout.grid_button, this)
        setCard(card)
        setImage(image)
    }

    fun setCard(card: Card?) {
        this.card = card
        val imageView = findViewById<ImageView>(R.id.image)
        when {
            card == null || card.cardType == 2 -> {
                visibility = View.INVISIBLE
                imageView.setImageDrawable(null)
                setText("")
            }
            card.cardType == 0 -> {
                visibility = View.VISIBLE
                setText(card.title.orEmpty())
            }
            card.cardType == 1 -> {
                visibility = View.VISIBLE
                setText("")
                imageView.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_baseline_space_bar_24)
                )
            }
            card.cardType == 3 -> {
                visibility = View.VISIBLE
                setText(R.string.create_card)
                imageView.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_baseline_add_24)
                )
            }
        }
    }

    private fun setText(@StringRes id: Int) {
        setText(context.getString(id))
    }

    private fun setText(title: String) {
        val bitmap = Utils.textAsBitmap(title)
        val textImage = findViewById<ImageView>(R.id.image_text)
        textImage.setImageBitmap(bitmap)
    }

    fun getCard(): Card? = card

    fun setImage(image: Bitmap?) {
        this.image = image
        val target = findViewById<ImageView>(R.id.image)
        if (card?.cardType == 0) {
            if (image != null) {
                Glide.with(this)
                    .load(image)
                    .into(target)
            } else {
                target.setImageDrawable(null)
            }
        }
    }

    fun getImage(): Bitmap? = image

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isOutputCard) {
            super.onMeasure(heightMeasureSpec, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
