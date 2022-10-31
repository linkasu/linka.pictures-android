package su.linka.pictures;

import static su.linka.pictures.MainActivity.getContext;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;


import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import net.lingala.zip4j.exception.ZipException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import su.linka.pictures.components.CardGrid;
import su.linka.pictures.components.GridButton;

public class GridActivity extends AppCompatActivity {

    private Set set;
    private  int page =0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grid);



        Bundle b = getIntent().getExtras();
        String file = b.getString("file");
        getSupportActionBar().setTitle(file.substring(0, file.length()-5)); // for set actionbar title
        getSupportActionBar().setDisplayHomeAsUpEnabled(true); // for add back arrow in action bar

        try {
            set = SetsManager
                    .getInstance()
                    .getSet(file);
            CardGrid grid = findViewById(R.id.card_grid);

            grid.setSet(set);
            int pages = grid.getPagesCount();

            View nextButton = findViewById(R.id.next_button);
            View prevButton = findViewById(R.id.prev_button);

            if (pages>1) {
                nextButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        grid.nextPage();
                    }
                });

                prevButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        grid.prevPage();
                    }
                });
            } else{
                nextButton.setVisibility(View.GONE);
                prevButton.setVisibility(View.GONE);
            }
            grid.setCardSelectListener(new CardGrid.OnCardSelectListener() {
                @Override
                public void onCard(Card card) {
                    onCardSelect(card);
                }
            });
        } catch (ZipException e) {
            e.printStackTrace();
        }

    }


    private void onCardSelect(Card card) {
        //set up MediaPlayer
        Log.d(getClass().getCanonicalName(), "onCardSelect: "+card);
        MediaPlayer mp = new MediaPlayer();

        try {
            if(card.audioPath==null) return;

            File file = set.getAudioFile(card.audioPath);
            mp.setDataSource(file.getAbsolutePath());

            mp.prepare();
            mp.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if(item.getItemId()==android.R.id.home){
            finish();
        }
        return super.onOptionsItemSelected(item);
    }
}