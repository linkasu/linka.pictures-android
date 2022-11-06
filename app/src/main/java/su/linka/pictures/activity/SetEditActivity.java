package su.linka.pictures.activity;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;

import net.lingala.zip4j.exception.ZipException;

import su.linka.pictures.ActivityResultListener;
import su.linka.pictures.Callback;
import su.linka.pictures.Card;
import su.linka.pictures.R;
import su.linka.pictures.Set;
import su.linka.pictures.SetManifest;
import su.linka.pictures.SetsManager;
import su.linka.pictures.components.CardGrid;
import su.linka.pictures.components.EditCardDialog;
import su.linka.pictures.components.EditCardGrid;
import su.linka.pictures.components.InputDialog;

public class SetEditActivity extends AppCompatActivity {

    private String file;
    private SetsManager setsManager;
    private Set set;
    private EditText rowsCountEditText;
    private EditText columnsEditText;
    private CheckBox withoutSpaceCheckbox;
    private EditCardGrid grid;
    private ActivityResultListener activityResultListener;
    private EditCardDialog cardDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_set_edit);
        cardDialog = new EditCardDialog(this);

        getOnBackPressedDispatcher()
                .addCallback(this, new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        close();
                    }
                });
        getSupportActionBar().setDisplayHomeAsUpEnabled(true); // for add back arrow in action bar
        getSupportActionBar().setTitle(R.string.create_set);

        setsManager = new SetsManager(this);

        grid = findViewById(R.id.card_grid);

        grid.setCardSelectListener(new CardGrid.OnCardSelectListener() {
            @Override
            public void onCard(Card card, int pos) {
                onCardSelected(card, pos);
            }
        });

        rowsCountEditText = findViewById(R.id.rows_count);
        columnsEditText = findViewById(R.id.columns_count);


        findViewById(R.id.change_grid_size_button)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SetManifest manifest = set.getManifest();
                        manifest.rows = Integer.parseInt(rowsCountEditText.getText().toString());
                        manifest.columns = Integer.parseInt(columnsEditText.getText().toString());
                        grid.setGridSize(manifest.rows, manifest.columns);

                    }
                });
        withoutSpaceCheckbox = findViewById(R.id.without_space_checkbox);
        withoutSpaceCheckbox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                set
                        .getManifest()
                        .withoutSpace = withoutSpaceCheckbox.isChecked();
            }
        });


        Bundle b = getIntent().getExtras();
        if(b!=null) {
            file = b.getString("file");
        }

        if(file==null){
            showEnterTitleDialog();
        } else {
            loadFromFile(file);
        }

    }

    private void onCardSelected(Card card, int pos) {

        cardDialog.show(set, card.cardType<3? card: null);

        cardDialog.setCallback(new Callback<Card>() {
            @Override
            public void onDone(Card result) {
                addCard(pos, result);
            }

            @Override
            public void onFail(Exception error) {

            }
        });


    }

    private void addCard(int pos, Card result) {
        set.addCard(pos, result);
        grid.refresh();
        grid.setSet(set);
    }

    private void showEnterTitleDialog() {
        InputDialog
                .showDialog(this, R.string.set_name, new Callback<String>() {
                    @Override
                    public void onDone(String result) {
                        set = setsManager.createSet(result);
                        loadSet();
                    }

                    @Override
                    public void onFail(Exception error) {

                    }
                });
    }

    private void loadFromFile(String file) {

        try {
            set = setsManager.getSet(file);
        } catch (ZipException e) {
            e.printStackTrace();
        }
        loadSet();
    }

    private void loadSet() {
        SetManifest manifest = set.getManifest();
        grid.setGridSize(manifest.rows, manifest.columns);
        rowsCountEditText.setText(manifest.rows+"");
        columnsEditText.setText(manifest.columns+"");
        grid.setSet(set);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        cardDialog.onActivityResult(requestCode, resultCode, data);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        getMenuInflater().inflate(R.menu.set_edit_activity_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if(id==android.R.id.home){
            close();
        }
        return super.onOptionsItemSelected(item);
    }


    private void close() {
        finish();
    }

}