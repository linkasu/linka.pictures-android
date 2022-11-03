package su.linka.pictures;

import android.content.Context;
import android.speech.tts.TextToSpeech;

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
}
