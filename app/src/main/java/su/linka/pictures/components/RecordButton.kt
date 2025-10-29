package su.linka.pictures.components

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.media.MediaRecorder
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.FragmentActivity
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
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (_: Exception) {
            }
        }
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        refreshView()
    }

    private fun startRecordingWithPermission() {
        val activity = Utils.unwrap(context) as? FragmentActivity ?: run {
            onRecordListener?.onFail(IllegalStateException("Context is not a FragmentActivity"))
            return
        }

        PermissionX.init(activity)
            .permissions(Manifest.permission.RECORD_AUDIO)
            .request(object : RequestCallback {
                override fun onResult(
                    allGranted: Boolean,
                    grantedList: MutableList<String>,
                    deniedList: MutableList<String>
                ) {
                    if (allGranted) {
                        startRecording()
                    } else {
                        onRecordListener?.onFail(Exception("Permission denied: $deniedList"))
                    }
                }
            })
    }

    private fun startRecording() {
        stopRecordingInternal()
        recordFile = try {
            File.createTempFile(UUID.randomUUID().toString(), ".3gpp")
        } catch (error: IOException) {
            onRecordListener?.onFail(error)
            return
        }
        mediaRecorder = MediaRecorder().apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setOutputFile(recordFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                prepare()
                start()
                isRecording = true
                refreshView()
            } catch (error: Exception) {
                release()
                mediaRecorder = null
                recordFile?.delete()
                recordFile = null
                onRecordListener?.onFail(error)
            }
        }
    }

    private fun stopRecording() {
        val file = recordFile
        stopRecordingInternal()
        if (file != null) {
            onRecordListener?.onDone(file)
        } else {
            onRecordListener?.onFail(Exception("Recording failed"))
        }
    }

    private fun stopRecordingInternal() {
        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (error: RuntimeException) {
                    recordFile?.delete()
                }
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaRecorder = null
            isRecording = false
            refreshView()
        }
    }

    private fun refreshView() {
        setText(if (isRecording) R.string.recording else R.string.record_audio)
        setTextColor(if (isRecording) Color.RED else Color.BLACK)
    }
}
