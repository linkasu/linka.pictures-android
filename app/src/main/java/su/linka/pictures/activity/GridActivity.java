package su.linka.pictures.activity;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;


import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;

import net.lingala.zip4j.exception.ZipException;

import su.linka.pictures.Callback;
import su.linka.pictures.Card;
import su.linka.pictures.Cookie;
import su.linka.pictures.R;
import su.linka.pictures.Set;
import su.linka.pictures.SetsManager;
import su.linka.pictures.components.CardGrid;
import su.linka.pictures.components.OutputLine;
import su.linka.pictures.components.ParentPasswordDialog;

public class GridActivity extends AppCompatActivity {

    private Set set;
    private GridSettings gridSettings = new GridSettings();
    private ImageButton nextButton;
    private ImageButton prevButton;
    private OutputLine outputLine;
    private CardGrid grid;
    private Cookie cookie;
    private String file;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grid);
        cookie = new Cookie(this);
        SetsManager setsManager = new SetsManager(this);
        getOnBackPressedDispatcher()
                    .addCallback(this, new OnBackPressedCallback(true) {
                        @Override
                        public void handleOnBackPressed() {
                            close();
                        }
                    });
         nextButton = findViewById(R.id.next_button);
         prevButton = findViewById(R.id.prev_button);
         outputLine = findViewById(R.id.output_line);


        Bundle b = getIntent().getExtras();
         file = b.getString("file");
        getSupportActionBar().setTitle(file.substring(0, file.length()-5)); // for set actionbar title
        getSupportActionBar().setDisplayHomeAsUpEnabled(true); // for add back arrow in action bar

        try {
            set = setsManager
                    .getSet(file);
            outputLine.setSet(set);
            grid = findViewById(R.id.card_grid);

            grid.setSet(set);

            gridSettings = GridSettings.fromInt(cookie.getSetSettings(file, 3));

            grid.setCardSelectListener(new CardGrid.OnCardSelectListener() {
                @Override
                public void onCard(Card card, int position) {
                    onCardSelect(card);
                }
            });
        } catch (ZipException e) {
            e.printStackTrace();
        }
        prepareView();

    }


    private void onCardSelect(Card card) {
        outputLine.addCard(card);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        getMenuInflater().inflate(R.menu.grid_activity_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if(id==android.R.id.home){
            close();
        } else if(id==R.id.settings){
            ParentPasswordDialog.showDialog(this, new Callback() {
                @Override
                public void onDone(Object o) {

                   showSettings();
                }

                @Override
                public void onFail(Exception error) {

                }
            });
        }
        return super.onOptionsItemSelected(item);
    }

    private void close() {
        ParentPasswordDialog
                .showDialog(this, new Callback() {
                    @Override
                    public void onDone(Object o) {
                        finish();
                    }

                    @Override
                    public void onFail(Exception error) {

                    }
                }
                );
    }

    private void showSettings() {
        GridSettingsView settingsView = new GridSettingsView(this);
        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setView(settingsView);
        settingsView.setSettings(gridSettings);


        // set dialog message
        alertDialogBuilder
                .setTitle(R.string.grid_activity_settings)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,int id) {
                                /** DO THE METHOD HERE WHEN PROCEED IS CLICKED*/
                                settingsView.commit();
                                cookie.setSetSettings(file, gridSettings.toInt());
                                prepareView();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.dismiss();
                            }

                        }

                )
                .show();
    }

    private void prepareView() {

        outputLine.setDirectMode(!gridSettings.isOutput);

        outputLine.setVisibility(gridSettings.isOutput?View.VISIBLE:View.GONE);
        prevButton.setVisibility(gridSettings.isPagesButtons?View.VISIBLE:View.GONE);
        nextButton.setVisibility(gridSettings.isPagesButtons?View.VISIBLE:View.GONE);
        int pages = grid.getPagesCount();

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

    }

}