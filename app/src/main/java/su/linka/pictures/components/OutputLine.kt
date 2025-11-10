package su.linka.pictures.components

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.SetManifest
import su.linka.pictures.TTS
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class OutputLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val tts = TTS(context)
    private val mediaPlayer = MediaPlayer().apply {
        @Suppress("DEPRECATION")
        setAudioStreamType(AudioManager.STREAM_MUSIC)
    }

    private var set: Set? = null
    private var manifest: SetManifest? = null
    private val backSpaceButton: ImageButton
    private val speakButton: ImageButton
    private val clearButton: ImageButton
    private val textOutputView: TextView
    private val grid: OutputGrid
    private val scrollView: HorizontalScrollView

    private val cards = mutableListOf<Card>()
    private var withoutSpace: Boolean = false
    private var directMode: Boolean = false
    private var currentPlayCard: Int = 0
    private val isPlaying = AtomicBoolean(false)

    init {
        inflate(context)
    }

    private fun inflate(context: Context) {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.output_line, this, true)

        backSpaceButton = requireView(R.id.backspace_button)
        speakButton = requireView(R.id.speak_button)
        clearButton = requireView(R.id.clear_button)
        textOutputView = requireView(R.id.output_text)
        grid = requireView(R.id.output_grid)
        scrollView = requireView(R.id.scroll_grid)

        backSpaceButton.setOnClickListener { backspace() }
        clearButton.setOnClickListener { clear() }
        speakButton.setOnClickListener { speak() }
    }

    fun setSet(set: Set) {
        this.set = set
        manifest = set.getManifest()
        withoutSpace = manifest?.withoutSpace ?: false

        scrollView.visibility = if (withoutSpace) View.GONE else View.VISIBLE
        textOutputView.visibility = if (withoutSpace) View.VISIBLE else View.GONE

        grid.setSet(set, output = true)
    }

    fun getSet(): Set? = set

    fun addCard(newCard: Card) {
        if (directMode) {
            play(newCard, null)
            return
        }
        cards.add(newCard)
        if (withoutSpace) {
            updateText()
        } else {
            grid.addCard(newCard)
            postScrollToEnd()
        }
    }

    fun stop() {
        isPlaying.set(false)
        try {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
        } catch (_: Exception) {
        }
    }

    fun clear() {
        cards.clear()
        if (withoutSpace) {
            updateText()
        } else {
            grid.clear()
        }
    }

    fun backspace() {
        if (cards.isEmpty()) return
        cards.removeAt(cards.lastIndex)
        if (withoutSpace) {
            updateText()
        } else {
            grid.backspace()
        }
    }

    fun setDirectMode(directMode: Boolean) {
        this.directMode = directMode
    }

    fun release() {
        stop()
        mediaPlayer.release()
        tts.shutdown()
    }

    private fun speak() {
        if (withoutSpace) {
            tts.speak(getText())
        } else {
            if (isPlaying.get()) {
                stop()
            } else {
                playCards()
            }
        }
    }

    private fun playCards() {
        if (!isPlaying.compareAndSet(false, true)) return
        if (cards.isEmpty()) {
            isPlaying.set(false)
            return
        }
        currentPlayCard = 0
        playNextCard()
    }

    private fun playNextCard() {
        if (!isPlaying.get()) return
        val card = cards.getOrNull(currentPlayCard)
        if (card == null) {
            isPlaying.set(false)
            return
        }
        play(card) {
            if (!isPlaying.get()) return@play
            currentPlayCard++
            if (currentPlayCard < cards.size) {
                playNextCard()
            } else {
                isPlaying.set(false)
            }
        }
    }

    private fun play(card: Card, onPlayedListener: (() -> Unit)?) {
        val set = set ?: return
        val audio: File = set.getAudioFile(card.audioPath) ?: run {
            onPlayedListener?.invoke()
            return
        }
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(audio.absolutePath)
            mediaPlayer.setOnCompletionListener {
                onPlayedListener?.invoke()
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
        } catch (error: IOException) {
            error.printStackTrace()
            onPlayedListener?.invoke()
        }
    }

    private fun updateText() {
        textOutputView.text = getText()
    }

    private fun getText(): String {
        val builder = StringBuilder()
        cards.forEach { card ->
            when (card.cardType) {
                0 -> builder.append(card.title)
                1 -> builder.append(' ')
            }
        }
        return builder.toString()
    }

    private fun postScrollToEnd() {
        val activity = context as? Activity
        val runnable = Runnable {
            scrollView.fullScroll(View.FOCUS_RIGHT)
        }
        if (activity != null) {
            activity.runOnUiThread(runnable)
        } else {
            scrollView.post(runnable)
        }
    }

    private fun <T : View> requireView(@IdRes id: Int): T {
        return findViewById<T>(id)
            ?: throw IllegalStateException("View with id $id not found in OutputLine")
    }
}
