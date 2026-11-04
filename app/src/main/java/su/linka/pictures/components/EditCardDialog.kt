package su.linka.pictures.components

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.activity.ComponentActivity
import su.linka.pictures.ActivityWork
import su.linka.pictures.BitmapDecoding
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.TTS
import su.linka.pictures.Utils
import su.linka.pictures.EditorDraftStore
import su.linka.pictures.EditorDraftJson
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.io.IOException

class EditCardDialog(context: Context) : Dialog(context) {

    private val mediaPlayer = MediaPlayer()
    private val tts = TTS(context)
    @Volatile private var released = false
    private val work = ActivityWork(Utils.unwrap(context) as ComponentActivity)

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
    private var ownedAudio: File? = null
    private var originalImage: File? = null
    private var ownedImage: File? = null
    private var savingMedia = false
    private var currentBitmap: Bitmap? = null
    private var existingImagePath: String? = null
    private var existingAudioPath: String? = null
    private var imageDirty = false
    private var audioDirty = false
    private var audioGenerating = false
    private var audioGeneration = 0L
    private var pendingImage = false
    private var deferredImageResult: Triple<Int, Int, Intent?>? = null
    private var imageRequestCode = PICK_IMAGE_BASE
    private var generation = 0
    private var capturedBitmap: Bitmap? = null
    private var capturedOriginal: File? = null
    private var capturedImage: String? = null
    private var capturedAudioKey: String? = null
    private var capturedAudio: String? = null
    var onChooseImage: (() -> Boolean)? = null

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

    fun show(set: Set, inputCard: Card?, loadImage: Boolean = true) {
        if (work.busy) return
        clearOwnedMedia()
        cancelAudioGeneration()
        generation++
        callback = null
        pendingImage = false
        deferredImageResult = null
        capturedBitmap = null
        capturedOriginal = null
        capturedImage = null
        capturedAudioKey = null
        capturedAudio = null
        this.set = set
        this.card = inputCard?.clone() ?: Card(0, 0)
        populateCardData(loadImage)
        super.show()
    }

    fun setCallback(callback: Callback<Card>) {
        this.callback = callback
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != imageRequestCode || !pendingImage || !isShowing) return
        if (work.busy) {
            deferredImageResult = Triple(requestCode, resultCode, data)
            return
        }
        pendingImage = false
        if (resultCode == Activity.RESULT_OK && data != null) {
            val selectedImage: Uri = data.data ?: return
            val request = generation
            setMediaBusy(true)
            work.run({
                val file = BitmapDecoding.snapshot(context, selectedImage)
                try { file to BitmapDecoding.decode(file, 1024) }
                catch (error: Exception) { file.delete(); throw error }
            }, { (file, bitmap) ->
                if (!isShowing || generation != request) { file.delete(); return@run }
                ownedImage?.delete()
                originalImage = file
                ownedImage = file
                currentBitmap = bitmap
                imageDirty = true
                imageView.setImageBitmap(bitmap)
                setMediaBusy(false)
            }, {
                if (isShowing && generation == request) {
                    setMediaBusy(false)
                    Toast.makeText(context, R.string.image_open_error, Toast.LENGTH_LONG).show()
                }
            }, abandoned = { it.first.delete() })
        }
    }

    /** Media is materialized before the owner publishes its small saved-state token. */
    fun captureState(media: File): JSONObject? {
        if (!isShowing) return null
        // Interrupted recordings are abandoned, never promoted to ready audio or resumed.
        onHostStop()
        val bitmap = currentBitmap
        val original = originalImage
        val borrowedImage = existingImagePath?.let { File(checkNotNull(set).getFolder(), it) }
        if ((bitmap != null || original != null) && (imageDirty || original != borrowedImage || original == null)) {
            if (bitmap !== capturedBitmap || original != capturedOriginal) {
                val name = "${UUID.randomUUID()}.image"
                EditorDraftStore.write(File(media, name)) { output ->
                    if (original != null) original.inputStream().use { it.copyTo(output) }
                    else check(checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                capturedBitmap = bitmap
                capturedOriginal = original
                capturedImage = name
            }
        }
        val borrowedAudio = set?.getAudioFile(existingAudioPath)
        val audio = currentAudio?.takeIf { it.isFile && (audioDirty || it != borrowedAudio) }
        val audioKey = audio?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        if (audioKey != capturedAudioKey) {
            capturedAudio = audio?.let {
                val name = "${UUID.randomUUID()}.audio"
                EditorDraftStore.write(File(media, name)) { output ->
                    it.inputStream().use { input -> input.copyTo(output) }
                }
                name
            }
            capturedAudioKey = audioKey
        }
        return JSONObject().put("card", EditorDraftJson.card(card))
            .put("title", cardTitleEditText.text.toString()).put("type", getSelectedType())
            .put("image", capturedImage ?: JSONObject.NULL)
            .put("audio", capturedAudio ?: JSONObject.NULL)
            .put("imageDirty", imageDirty).put("audioDirty", audioDirty)
            .put("pendingImage", pendingImage)
            .put("imageRequestCode", imageRequestCode)
    }

    fun restoreState(set: Set, state: JSONObject, media: File) {
        val model = state.getJSONObject("card")
        fun optional(key: String): String? = if (model.isNull(key)) null else model.getString(key)
        val restoredCard = Card(model.getInt("id"),
            optional("imagePath"), optional("title"), optional("audioPath"),
            model.getInt("cardType"))
        require(listOfNotNull(restoredCard.imagePath, restoredCard.audioPath).all {
            File(set.getFolder(), it).canonicalPath.startsWith(set.getFolder().canonicalPath + File.separator)
        })
        show(set, restoredCard, loadImage = false)
        cardTitleEditText.setText(state.optString("title"))
        cardTypeRadio.check(IDS[state.optInt("type").coerceIn(0, IDS.lastIndex)])
        fun mediaFile(key: String): File? {
            val name = state.optString(key)
            if (name.isEmpty() || name == "null" || File(name).name != name) return null
            return File(media, name).takeIf {
                it.isFile && it.canonicalFile.parentFile == media.canonicalFile
            }
        }
        originalImage = if (state.isNull("image")) originalImage else mediaFile("image")
        originalImage?.let { loadPreview(it) }
        currentAudio = if (state.isNull("audio")) currentAudio else mediaFile("audio")
        playAudioButton.isEnabled = currentAudio != null
        imageDirty = state.optBoolean("imageDirty")
        audioDirty = state.optBoolean("audioDirty")
        pendingImage = state.optBoolean("pendingImage")
        imageRequestCode = state.optInt("imageRequestCode", PICK_IMAGE_BASE).coerceIn(PICK_IMAGE_BASE, 65535)
    }

    fun validate(): Boolean {
        if (audioGenerating || work.busy) return false
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
            val requestGeneration = generation
            ConfirmDialog.showConfirmDialog(
                view.context,
                R.string.delete,
                object : Callback<Any?>() {
                    override fun onDone(result: Any?) {
                        if (!isShowing || generation != requestGeneration || work.busy) return
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
                if (!isShowing) { result.delete(); return }
                cancelAudioGeneration()
                replaceAudio(result)
                audioDirty = true
                playAudioButton.isEnabled = true
            }

            override fun onFail(error: Exception?) {
                cancelAudioGeneration()
                playAudioButton.isEnabled = false
                replaceAudio(null)
            }
        })
        generateAudioButton.setOnClickListener { generateAudio() }
        chooseImageButton.setOnClickListener { chooseImage() }
        generateImageButton.setOnClickListener { generateImage() }
        playAudioButton.setOnClickListener { playAudio() }
    }

    private fun populateCardData(loadImage: Boolean) {
        val set = set ?: return
        val manifestCardType = card.cardType.coerceIn(0, IDS.lastIndex)
        cardTypeRadio.check(IDS[manifestCardType])

        if (card.cardType == 0) {
            cardTitleEditText.setText(card.title.orEmpty())
            existingImagePath = card.imagePath
            existingAudioPath = card.audioPath

            currentBitmap = null
            originalImage = card.imagePath?.let { File(set.getFolder(), it) }
            if (loadImage) originalImage?.let { loadPreview(it) }
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
            originalImage = null
            currentAudio = null
        }

        imageDirty = false
        audioDirty = false
    }

    private fun playAudio() {
        if (audioGenerating || work.busy) return
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
        if (work.busy || audioGenerating) return
        val session = audioGeneration
        InputDialog.showDialog(
            context,
            R.string.generate_audio,
            object : Callback<String>() {
                override fun onDone(result: String) {
                    if (session != audioGeneration || !isShowing || work.busy) return
                    cancelAudioGeneration()
                    val generation = audioGeneration
                    audioGenerating = true
                    replaceAudio(null)
                    audioDirty = false
                    playAudioButton.isEnabled = false
                    recordButton.isEnabled = false
                    requireView<View>(R.id.positiveBtn).isEnabled = false
                    tts.synthesize(result, object : Callback<File>() {
                        override fun onDone(result: File) {
                            if (generation != audioGeneration || !isShowing) { result.delete(); return }
                            finishAudioGeneration()
                            replaceAudio(result)
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
        recordButton.isEnabled = !work.busy
        requireView<View>(R.id.positiveBtn).isEnabled = !work.busy
    }

    // Invalidate both input prompts and engine callbacks before the dialog is reused.
    private fun cancelAudioGeneration() {
        audioGeneration++
        tts.cancelSynthesis()
        finishAudioGeneration()
    }

    private fun generateImage() {
        if (work.busy) return
        val requestGeneration = generation
        InputDialog.showDialog(
            context,
            R.string.generate_image,
            object : Callback<String>() {
                override fun onDone(result: String) {
                    if (!isShowing || generation != requestGeneration || work.busy) return
                    ownedImage?.delete()
                    ownedImage = null
                    originalImage = null
                    currentBitmap = Utils.textAsBitmap(result)
                    imageDirty = true
                    imageView.setImageBitmap(currentBitmap)
                }

                override fun onFail(error: Exception?) = Unit
            }
        )
    }

    private fun chooseImage() {
        if (work.busy || pendingImage) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.choose_image))
        imageRequestCode = if (imageRequestCode == 65535) PICK_IMAGE_BASE else imageRequestCode + 1
        pendingImage = true
        if (onChooseImage?.invoke() == false) {
            pendingImage = false
            return
        }
        (Utils.unwrap(context) ?: return).startActivityForResult(chooser, imageRequestCode)
    }

    private fun save(isDeleting: Boolean): Boolean {
        if (work.busy) return false
        val selectedType = getSelectedType()
        if (!isDeleting && !validate()) {
            Toast.makeText(context, R.string.card_fields_doesnt, Toast.LENGTH_LONG).show()
            return false
        }

        if (selectedType == 0 && !isDeleting) {
            val currentSet = set ?: return false
            val bitmap = if (imageDirty && originalImage == null) currentBitmap ?: return false else null
            val original = if (imageDirty) originalImage else null
            val audio = if (audioDirty) currentAudio ?: return false else null
            val title = cardTitleEditText.text.toString()
            val request = generation
            val owned = listOfNotNull(ownedAudio, ownedImage)
            savingMedia = true
            setMediaBusy(true)
            work.run({
                var savedImage: File? = null
                try {
                    savedImage = when {
                        original != null -> currentSet.copyAudioFile(original)
                        bitmap != null -> currentSet.saveBitmap(bitmap)
                        else -> null
                    }
                    savedImage to audio?.let { currentSet.copyAudioFile(it) }
                } catch (error: Exception) {
                    savedImage?.takeUnless { it == original }?.delete()
                    throw error
                }
                finally { if (released) owned.forEach { it.delete() } }
            }, { (image, sound) ->
                savingMedia = false
                if (!isShowing || generation != request) { clearOwnedMedia(); return@run }
                card.title = title
                card.imagePath = image?.name ?: existingImagePath
                card.audioPath = sound?.name ?: existingAudioPath
                card.cardType = selectedType
                imageDirty = false
                audioDirty = false
                setMediaBusy(false)
                callback?.onDone(card)
                dismiss()
            }, { error ->
                savingMedia = false
                if (isShowing && generation == request) {
                    setMediaBusy(false)
                    Toast.makeText(context, R.string.set_save_error, Toast.LENGTH_LONG).show()
                    callback?.onFail(error)
                }
                if (released) owned.forEach { it.delete() }
            }, abandoned = { owned.forEach { it.delete() } })
            return false // The asynchronous success callback owns dismissal.
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
        generation++
        pendingImage = false
        deferredImageResult = null
        callback = null
        onHostStop()
        if (!savingMedia) clearOwnedMedia()
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

    private fun replaceAudio(file: File?) {
        ownedAudio?.takeUnless { it == file }?.delete()
        currentAudio = file
        ownedAudio = file
    }

    private fun clearOwnedMedia() {
        ownedAudio?.delete()
        ownedAudio = null
        ownedImage?.delete()
        ownedImage = null
    }

    private fun setMediaBusy(busy: Boolean) {
        listOf(R.id.positiveBtn, R.id.choose_image_button, R.id.generate_image_button,
            R.id.record_audio_button, R.id.generate_audio_button, R.id.delete_button,
            R.id.cancel_button, R.id.card_type_radiogroup, R.id.card_title_edittext,
            R.id.standard_card_radio, R.id.space_card_radio, R.id.empty_card_radio).forEach {
            requireView<View>(it).isEnabled = !busy
        }
        requireView<View>(R.id.positiveBtn).isEnabled = !busy && !audioGenerating
        recordButton.isEnabled = !busy && !audioGenerating
        playAudioButton.isEnabled = !busy && !audioGenerating && currentAudio != null
        setCancelable(!busy)
    }

    private fun loadPreview(file: File) {
        val request = generation
        setMediaBusy(true)
        work.run({ BitmapDecoding.decode(file, 1024) }, { bitmap ->
            if (generation == request && !released) {
                currentBitmap = bitmap
                imageView.setImageBitmap(bitmap)
                setMediaBusy(false)
                deliverDeferredImage()
            }
        }, {
            if (generation == request && !released) {
                setMediaBusy(false)
                Toast.makeText(context, R.string.image_open_error, Toast.LENGTH_LONG).show()
                deliverDeferredImage()
            }
        })
    }

    private fun deliverDeferredImage() {
        val (request, result, data) = deferredImageResult ?: return
        deferredImageResult = null
        onActivityResult(request, result, data)
    }

    companion object {
        private val IDS = intArrayOf(
            R.id.standard_card_radio,
            R.id.space_card_radio,
            R.id.empty_card_radio
        )
        private const val PICK_IMAGE_BASE = 16384
    }

    private fun <T : View> requireView(@IdRes id: Int): T {
        return findViewById<T>(id)
            ?: throw IllegalStateException("View with id $id not found in EditCardDialog")
    }
}
