package su.linka.pictures.components;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.FragmentActivity;

import com.permissionx.guolindev.PermissionX;
import com.permissionx.guolindev.callback.RequestCallback;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import su.linka.pictures.Callback;
import su.linka.pictures.R;
import su.linka.pictures.Utils;

public class RecordButton extends androidx.appcompat.widget.AppCompatButton implements View.OnClickListener {

    private MediaRecorder mediaRecorder;
    boolean isRecording = false;
    private File recordFile;
    private Callback<File> onRecordListener;

    public RecordButton(Context context, AttributeSet set){
        super(context, set);
        mediaRecorder = new MediaRecorder();
        prepareView();
        setOnClickListener(this);
    }

    public void onDestroy(){
    if(mediaRecorder!=null &&isRecording) mediaRecorder.reset();
    }

    private void prepareView() {

        setText(isRecording?R.string.recording: R.string.record_audio);
        setTextColor(isRecording?Color.RED:Color.BLACK);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onClick(View v)  {
        isRecording=!isRecording;
        prepareView();
        if(isRecording){
            PermissionX
                    .init((FragmentActivity) Utils.unwrap( getContext()))
                    .permissions(Manifest.permission.RECORD_AUDIO)
                    .request(new RequestCallback() {
                        @Override
                        public void onResult(boolean allGranted, @NonNull List<String> grantedList, @NonNull List<String> deniedList) {
                            if(allGranted){

                                try {
                                    recordFile = File.createTempFile(UUID.randomUUID().toString(), ".3gpp");
                                    mediaRecorder = new MediaRecorder();
                                mediaRecorder.reset();

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setOutputFile(recordFile.getAbsoluteFile());
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.prepare();
            mediaRecorder.start();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }

                            } else {
                                onRecordListener.onFail(new Exception());
                            }
                        }
                    });
        }else {
        mediaRecorder.release();
        onRecordListener.onDone(recordFile);
        }

    }

    public void setOnRecordListener(Callback<File> onRecordListener) {
        this.onRecordListener = onRecordListener;
    }
}
