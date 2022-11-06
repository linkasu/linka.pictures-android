package su.linka.pictures;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class TTS {

    private final Context context;
    private final TextToSpeech tts;

    public TTS(Context context){
        this.context = context;
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {

            }
        });
    }

    public void speak(String text){
        tts.speak((CharSequence) text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    public File speakToBuffer(String text) throws Exception {
        File file = new File(context.getCacheDir(), UUID.randomUUID().toString()+".wav");

            int res = tts.synthesizeToFile(text, null, file,null);
            if(res!=TextToSpeech.SUCCESS) throw new Exception("synth error");
        return file;

    }
}
