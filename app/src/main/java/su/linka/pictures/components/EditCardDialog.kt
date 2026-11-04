package su.linka.pictures.components

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Toast
import androidx.annotation.IdRes
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.TTS
import su.linka.pictures.Utils
import java.io.File
import java.io.IOException

class EditCardDialog(context: Context) : Dialog(context) {

    private val mediaPlayer = MediaPlayer()
    private val tts = TTS(context)
    private var released = false

    private val cardTypeRadio: RadioGroup
    private val cardTitleEditText: EditText
    private val imageView: ImageView
    private val playAudioButton: Button
    private val recordButton: RecordButton
    private val generateAudioButton: Button
    private val chooseImageButton: Button
    private val generateImageButton: Button

    private var set: Set? = null
    private var card: Card = Card(0, 0)
    private var callback: Callback<Card>? = null

    private var currentAudio: File? = null
    private var currentBitmap: Bitmap? = null
    private var existingImagePath: String? = null
    private var existingAudioPath: String? = null
    private var imageDirty = false
    private var audioDirty = false
    private var audioGenerating = false
    private var audioGeneration = 0L

    init {
        setContentView(R.layout.edit_card_dialog)
        setTitle(R.string.create_card)
        setCancelable(true)
        cardTypeRadio = requireView(R.id.card_type_radiogroup)
        cardTitleEditText = requireView(R.id.card_title_edittext)
        imageView = requireView(R.id.image)
        playAudioButton = requireView(R.id.play_audio_button)
        recordButton = requireView(R.id.record_audio_button)
        generateAudioButton = requireView(R.id.generate_audio_button)
        chooseImageButton = requireView(R.id.choose_image_button)
        generateImageButton = requireView(R.id.generate_image_button)
        prepareDialogButtons()
        setUpListeners()
    }

    fun show(set: Set, inputCard: Card?) {
        cancelAudioGeneration()
        this.set = set
        this.card = inputCard?.clone() ?: Card(0, 0)
        populateCardData()
        super.show()
    }

    fun setCallback(callback: Callback<Card>) {
        this.callback = callback
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            val selectedImage: Uri = data.data ?: return
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, selectedImage)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, selectedImage)
                }
            } catch (error: IOException) {
                Toast.makeText(context, R.string.image_open_error, Toast.LENGTH_LONG).show()
                error.printStackTrace()
                return
            }
            currentBitmap = bitmap
            imageDirty = true
            imageView.setImageBitmap(bitmap)
        }
    }

    fun validate(): Boolean {
        if (audioGenerating) return false
        return when (getSelectedType()) {
            0 -> {
                val text = cardTitleEditText.text.toString()
                text.isNotEmpty() && currentBitmap != null && currentAudio != null
            }
            1, 2 -> true
            else -> false
        }
    }

    private fun prepareDialogButtons() {
        requireView<View>(R.id.positiveBtn).setOnClickListener {
            if (save(false)) {
                dismiss()
            }
        }
        requireView<View>(R.id.cancel_button).setOnClickListener {
            dismiss()
        }
        requireView<View>(R.id.delete_button).setOnClickListener { view ->
            ConfirmDialog.showConfirmDialog(
                view.context,
                R.string.delete,
                object : Callback<Any?>() {
                    override fun onDone(result: Any?) {
                        card = Card(card.id, 3)
                        save(true)
                        dismiss()
                    }

                    override fun onFail(error: Exception?) = Unit
                }
            )
        }
    }

    private fun setUpListeners() {
        recordButton.setOnRecordListener(object : Callback<File>() {
            override fun onDone(result: File) {
                cancelAudioGeneration()
                currentAudio = result
                audioDirty = true
                playAudioButton.isEnabled = true
            }

            override fun onFail(error: Exception?) {
                cancelAudioGeneration()
                playAudioButton.isEnabled = false
                currentAudio = null
            }
        })
        generateAudioButton.setOnClickListener { generateAudio() }
        chooseImageButton.setOnClickListener { chooseImage() }
        generateImageButton.setOnClickListener { generateImage() }
        playAudioButton.setOnClickListener { playAudio() }
    }

    private fun populateCardData() {
        val set = set ?: return
        val manifestCardType = card.cardType.coerceIn(0, IDS.lastIndex)
        cardTypeRadio.check(IDS[manifestCardType])

        if (card.cardType == 0) {
            cardTitleEditText.setText(card.title.orEmpty())
            existingImagePath = card.imagePath
            existingAudioPath = card.audioPath

            currentBitmap = card.imagePath?.let { path ->
                set.getBitmap(path).also { bitmap ->
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageDrawable(null)
                    }
                }
            }
            currentAudio = card.audioPath?.let { path ->
                set.getAudioFile(path)
            }
            playAudioButton.isEnabled = currentAudio != null
        } else {
            cardTitleEditText.setText("")
            imageView.setImageDrawable(null)
            playAudioButton.isEnabled = false
            existingImagePath = null
            existingAudioPath = null
            currentBitmap = null
            currentAudio = null
        }

        imageDirty = false
        audioDirty = false
    }

    private fun playAudio() {
        if (audioGenerating) return
        val audioFile = currentAudio ?: return
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()
        } catch (error: IOException) {
            error.printStackTrace()
            Toast.makeText(context, R.string.play_audio_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun generateAudio() {
        val session = audioGeneration
        InputDialog.showDialog(
            context,
            R.string.generate_audio,
            object : Callback<String>() {
                override fun onDone(result: String) {
                    if (session != audioGeneration || !isShowing) return
                    cancelAudioGeneration()
                    val generation = audioGeneration
                    audioGenerating = true
                    currentAudio = null
                    audioDirty = false
                    playAudioButton.isEnabled = false
                    recordButton.isEnabled = false
                    requireView<View>(R.id.positiveBtn).isEnabled = false
                    tts.synthesize(result, object : Callback<File>() {
                        override fun onDone(result: File) {
                            if (generation != audioGeneration || !isShowing) return
                            finishAudioGeneration()
                            currentAudio = result
                            audioDirty = true
                            playAudioButton.isEnabled = true
                        }

                        override fun onFail(error: Exception?) {
                            if (generation != audioGeneration || !isShowing) return
                            finishAudioGeneration()
                            Toast.makeText(context, R.string.generate_audio_error, Toast.LENGTH_LONG).show()
                        }
                    })
                }

                override fun onFail(error: Exception?) = Unit
            }
        )
    }

    private fun finishAudioGeneration() {
        audioGenerating = false
        recordButton.isEnabled = true
        requireView<View>(R.id.positiveBtn).isEnabled = true
    }

    // Invalidate both input prompts and engine callbacks before the dialog is reused.
    private fun cancelAudioGeneration() {
        audioGeneration++
        tts.cancelSynthesis()
        finishAudioGeneration()
    }

    private fun generateImage() {
        InputDialog.showDialog(
            context,
            R.string.generate_image,
            object : Callback<String>() {
                override fun onDone(result: String) {
                    currentBitmap = Utils.textAsBitmap(result)
                    imageDirty = true
                    imageView.setImageBitmap(currentBitmap)
                }

                override fun onFail(error: Exception?) = Unit
            }
        )
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.choose_image))
        (Utils.unwrap(context) ?: return).startActivityForResult(chooser, PICK_IMAGE)
    }

    private fun save(isDeleting: Boolean): Boolean {
        val selectedType = getSelectedType()
        if (!isDeleting && !validate()) {
            Toast.makeText(context, R.string.card_fields_doesnt, Toast.LENGTH_LONG).show()
            return false
        }

        if (selectedType == 0 && !isDeleting) {
            val currentSet = set ?: return false
            val bitmap = if (imageDirty) currentBitmap ?: return false else null
            val audio = if (audioDirty) currentAudio ?: return false else null
            var savedImage: File? = null
            val audioPath: String?
            try {
                if (bitmap != null) {
                    savedImage = currentSet.saveBitmap(bitmap)
                }
                audioPath = if (audio != null) {
                    currentSet.copyAudioFile(audio).name
                } else {
                    existingAudioPath
                }
            } catch (error: IOException) {
                savedImage?.delete()
                error.printStackTrace()
                Toast.makeText(context, R.string.set_save_error, Toast.LENGTH_LONG).show()
                callback?.onFail(error)
                return false
            }
            card.title = cardTitleEditText.text.toString()
            card.imagePath = savedImage?.name ?: existingImagePath
            card.audioPath = audioPath
            existingImagePath = card.imagePath
            existingAudioPath = card.audioPath
            imageDirty = false
            audioDirty = false
        } else {
            card.audioPath = null
            card.imagePath = null
        }
        if (!isDeleting) {
            card.cardType = selectedType
        }
        callback?.onDone(card)
        return true
    }

    private fun getSelectedType(): Int {
        val checkedId = cardTypeRadio.checkedRadioButtonId
        for (i in IDS.indices) {
            if (IDS[i] == checkedId) return i
        }
        return 0
    }

    override fun dismiss() {
        onHostStop()
        super.dismiss()
    }

    fun onHostStop() {
        cancelAudioGeneration()
        recordButton.onDestroy()
        if (!released) mediaPlayer.reset()
    }

    fun release() {
        if (released) return
        dismiss()
        released = true
        try {
            mediaPlayer.release()
        } finally {
            tts.shutdown()
        }
    }

    companion object {
        private val IDS = intArrayOf(
            R.id.standard_card_radio,
            R.id.space_card_radio,
            R.id.empty_card_radio
        )
        private const val PICK_IMAGE = 0
    }

    private fun <T : View> requireView(@IdRes id: Int): T {
        return findViewById<T>(id)
            ?: throw IllegalStateException("View with id $id not found in EditCardDialog")
    }
}
