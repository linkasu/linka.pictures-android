package su.linka.pictures.components;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;

import su.linka.pictures.Card;
import su.linka.pictures.OutputAdapter;
import su.linka.pictures.R;
import su.linka.pictures.Set;
import su.linka.pictures.SetManifest;
import su.linka.pictures.TTS;

public class OutputLine extends LinearLayout {

    private final TTS tts;
    private final MediaPlayer mp;
    private Set set;
    private SetManifest manifest;
    private ImageButton backSpaceButton;
    private ImageButton speakButton;
    private ImageButton clearButton;
    private TextView textOutputView;
    private OutputGrid grid;
    private boolean withoutSpace;
    private OutputAdapter adapter;

    private ArrayList<Card> cards = new ArrayList<>();
    private boolean directMode;
    private int currentPlayCard = 0;
    private boolean isPlaying = false;


    public OutputLine(Context context, AttributeSet set){

        super(context, set);
        inflate(context);
        
        tts = new TTS(context);

        mp = new MediaPlayer();

        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
    }
    private void inflate(Context context) {
        LayoutInflater layoutInflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.output_line, this, true);
        
        backSpaceButton = findViewById(R.id.backspace_button);
        speakButton = findViewById(R.id.speak_button);
        clearButton = findViewById(R.id.clear_button);;
        textOutputView = findViewById(R.id.output_text);
        grid = findViewById(R.id.output_grid);

        backSpaceButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                backspace();
            }
        });
        clearButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                clear();
            }
        });
        speakButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                speak();
            }
        });
    }

    private void speak() {
        if(withoutSpace){
            tts.speak(getText());
        } else {
            if (isPlaying) {
                stop();
            } else {
                playCards();
            }
        }
    }

    private void playCards() {
        if(isPlaying) return;;

        currentPlayCard = 0;
        isPlaying = true;
        if(cards.size()>0) {
            playCard();
        }
    }

    private void playCard() {
        play(cards.get(currentPlayCard), new OnPlayedListener() {
            @Override
            public void onPlayed() {
                if(!isPlaying) return;
                currentPlayCard++;
                if(currentPlayCard<cards.size()){
                    playCard();
                }
            }
        });
    }

    private void play(Card card, OnPlayedListener onPlayedListener) {

        try {
            mp.setDataSource(set.getAudioFile(card.audioPath).getAbsolutePath());
            mp.prepare();

            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    onPlayedListener.onPlayed();
                }
            });
            mp.start();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop(){
        isPlaying=false;
        mp.stop();
    }

    private void clear() {
        cards.clear();
        if(withoutSpace){
            updateText();
        } else {
            grid.clear();
        }
    }

    private void backspace() {
        if(cards.size()==0) return;
        cards.remove(cards.size()-1);
        if(withoutSpace){
            updateText();
        } else {
            grid.backspace();
        }
    }

    public void setSet(Set set) {
        this.set = set;
        manifest =  set.getManifest();
        withoutSpace = manifest.withoutSpace;

        grid.setVisibility(withoutSpace?GONE:VISIBLE);
        textOutputView.setVisibility(withoutSpace?VISIBLE:GONE);

        grid.setSet(set);

    }

    public Set getSet() {
        return set;
    }

    public void addCard(Card newCard) {
        cards.add(newCard);
        if(withoutSpace){
            updateText();
        }else {

            grid.addCard(newCard);
        }
    }

    private void updateText() {
        
        textOutputView.setText(getText());
    }

    private String getText() {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);

            if(card.cardType==0){
                builder.append(card.title);
            } else if(card.cardType==1){
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    public void setDirectMode(boolean directMode) {
        this.directMode = directMode;
    }

    public boolean getDirectMode() {
        return directMode;
    }

    public static abstract class OnPlayedListener{
        public abstract void onPlayed();
    }
}
