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
import android.widget.Toast;

import net.lingala.zip4j.exception.ZipException;

import su.linka.pictures.ActivityResultListener;
import su.linka.pictures.Callback;
import su.linka.pictures.Card;
import su.linka.pictures.R;
import su.linka.pictures.Set;
import su.linka.pictures.SetManifest;
import su.linka.pictures.SetsManager;
import su.linka.pictures.components.CardGrid;
import su.linka.pictures.components.ConfirmDialog;
import su.linka.pictures.components.EditCardDialog;
import su.linka.pictures.components.EditCardGrid;
import su.linka.pictures.components.InputDialog;

public class SetEditActivity extends AppCompatActivity {

    private SetsManager setsManager;
    private Set set;
    private EditText rowsCountEditText;
    private EditText columnsEditText;
    private CheckBox withoutSpaceCheckbox;
    private EditCardGrid grid;
    private ActivityResultListener activityResultListener;
    private EditCardDialog cardDialog;
    private String setName;

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
                        grid.setPage(0);
                        grid.setGridSize(manifest.rows, manifest.columns);
                        grid.refresh();

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

        findViewById(R.id.prev_button)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        grid.prevPage();
                    }
                });
        findViewById(R.id.next_button)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean result = grid.nextPage();
                        if(result) return;
                        ConfirmDialog
                                .showConfirmDialog(v.getContext(), R.string.confirm_page_creation, new Callback() {
                                    @Override
                                    public void onDone(Object p) {
                                        int nextPageIndex = (grid.getPage()+1)*grid.getPageSize()+1;
                                        onCardSelected(null, nextPageIndex);
                                    }

                                    @Override
                                    public void onFail(Exception error) {

                                    }
                                });
                    }
                });

        Bundle b = getIntent().getExtras();
        if(b!=null) {
            setName =  b.getString("file");
        }

        if(setName==null){
            showEnterTitleDialog();
        } else {
            loadFromFile();
        }

    }

    private void onCardSelected(Card card, int pos ) {
        onCardSelected(card, pos, false);

    }
        private void onCardSelected(Card card, int pos, boolean nextPage ) {

        cardDialog.show(set, (card!=null&& card.cardType<3)? card: null);

        cardDialog.setCallback(new Callback<Card>() {
            @Override
            public void onDone(Card result) {
                addCard(pos, result);
                if (nextPage){
                    grid.nextPage();
                }
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
                        setName = result+".linka";
                        set = setsManager.createSet();
                        loadSet();
                    }

                    @Override
                    public void onFail(Exception error) {
                        finish();
                    }
                });
    }

    private void loadFromFile() {

        try {
            set = setsManager.getSet(setName);
        } catch (ZipException e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.set_open_error, Toast.LENGTH_LONG).show();
            finish();
        }
        loadSet();
    }

    private void loadSet() {
        SetManifest manifest = set.getManifest();
        withoutSpaceCheckbox.setChecked(manifest.withoutSpace);
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
        ConfirmDialog
                .showConfirmDialog(this, R.string.confirm_save_dialog, new Callback() {
                    @Override
                    public void onDone(Object o) {
                        set.getManifest()
                                        .withoutSpace =withoutSpaceCheckbox.isChecked();
                        setsManager.save(set, setName, new Callback(){

                            @Override
                            public void onDone(Object o) {
                                setResult(RESULT_OK);
                                finish();

                            }

                            @Override
                            public void onFail(Exception error) {
                                finish();
                            }
                        });
                    }

                    @Override
                    public void onFail(Exception error) {

                    }
                });
    }

}