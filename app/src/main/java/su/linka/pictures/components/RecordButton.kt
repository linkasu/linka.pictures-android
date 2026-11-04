package su.linka.pictures.components

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.media.MediaRecorder
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.permissionx.guolindev.PermissionX
import com.permissionx.guolindev.callback.RequestCallback
import su.linka.pictures.Callback
import su.linka.pictures.R
import su.linka.pictures.Utils
import java.io.File
import java.io.IOException
import java.util.UUID

class RecordButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs), View.OnClickListener {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordFile: File? = null
    private var onRecordListener: Callback<File>? = null
    private var permissionRequest = 0

    init {
        setOnClickListener(this)
        refreshView()
    }

    override fun onClick(v: View?) {
        if (isRecording) {
            stopRecording()
        } else {
            startRecordingWithPermission()
        }
    }

    fun setOnRecordListener(onRecordListener: Callback<File>) {
        this.onRecordListener = onRecordListener
    }

    fun onDestroy() {
        permissionRequest++
        stopRecordingInternal()?.delete()
    }

    override fun onDetachedFromWindow() {
        onDestroy()
        super.onDetachedFromWindow()
    }

    private fun startRecordingWithPermission() {
        val activity = Utils.unwrap(context) as? FragmentActivity ?: run {
            onRecordListener?.onFail(IllegalStateException("Context is not a FragmentActivity"))
            return
        }

        val request = ++permissionRequest
        PermissionX.init(activity)
            .permissions(Manifest.permission.RECORD_AUDIO)
            .request(object : RequestCallback {
                override fun onResult(
                    allGranted: Boolean,
                    grantedList: MutableList<String>,
                    deniedList: MutableList<String>
                ) {
                    if (request != permissionRequest || !isAttachedToWindow ||
                        activity.isFinishing || activity.isDestroyed ||
                        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) return
                    if (allGranted) {
                        startRecording()
                    } else {
                        onRecordListener?.onFail(Exception("Permission denied: $deniedList"))
                    }
                }
            })
    }

    private fun startRecording() {
        onDestroy()
        val targetFile = try {
            File.createTempFile(UUID.randomUUID().toString(), ".3gpp", context.cacheDir)
        } catch (error: IOException) {
            onRecordListener?.onFail(error)
            return
        }
        recordFile = targetFile
        try {
            val recorder = MediaRecorder()
            mediaRecorder = recorder
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setOutputFile(targetFile.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                prepare()
                start()
                isRecording = true
                refreshView()
            }
        } catch (error: Exception) {
            onDestroy()
            onRecordListener?.onFail(error)
        }
    }

    private fun stopRecording() {
        val file = stopRecordingInternal()
        if (file != null) {
            val listener = onRecordListener
            if (listener != null) listener.onDone(file) else file.delete()
        } else {
            onRecordListener?.onFail(Exception("Recording failed"))
        }
    }

    private fun stopRecordingInternal(): File? {
        val recorder = mediaRecorder
        val file = recordFile
        var succeeded = isRecording && recorder != null
        try {
            if (isRecording) recorder?.stop()
        } catch (_: Exception) {
            succeeded = false
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
                succeeded = false
            }
            mediaRecorder = null
            recordFile = null
            isRecording = false
            refreshView()
        }
        if (succeeded && file != null && file.isFile && file.length() > 0) return file
        file?.delete()
        return null
    }

    private fun refreshView() {
        setText(if (isRecording) R.string.recording else R.string.record_audio)
        setTextColor(if (isRecording) Color.RED else Color.BLACK)
    }
}
