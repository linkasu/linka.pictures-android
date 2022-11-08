package su.linka.pictures.activity;

import static su.linka.pictures.components.SetContextDialog.DELETE;
import static su.linka.pictures.components.SetContextDialog.EDIT;
import static su.linka.pictures.components.SetContextDialog.OPEN;
import static su.linka.pictures.components.SetContextDialog.RENAME;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.IOException;

import su.linka.pictures.Callback;
import su.linka.pictures.R;
import su.linka.pictures.SetManifest;
import su.linka.pictures.SetsManager;
import su.linka.pictures.components.ConfirmDialog;
import su.linka.pictures.components.InputDialog;
import su.linka.pictures.components.ParentPasswordDialog;
import su.linka.pictures.components.SetContextDialog;

public class MainActivity extends AppCompatActivity {

    private ListView setsList;
    private ArrayAdapter<SetManifest> adapter;
    private FirebaseAnalytics mFirebaseAnalytics;
    private SetsManager setsManager;
    private ActivityResultLauncher<Intent> activityLauncher ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        setsManager = new SetsManager(this);
        activityLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            loadSetsList();
                        }
                    }
                });

        loadDefaultSets();

         setsList = findViewById(R.id.sets_list);

        adapter = new ArrayAdapter<SetManifest>(this, androidx.appcompat.R.layout.support_simple_spinner_dropdown_item);
        setsList.setAdapter(adapter);
        loadSetsList();
        setsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                SetManifest manifest = adapter.getItem(position);
                openSet( manifest);

            }
        });

        setsList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                SetManifest manifest = adapter.getItem(position);
                SetContextDialog
                        .show(view.getContext(), manifest.toString(), new Callback<Integer>() {
                            @Override
                            public void onDone(Integer result) {
                                switch (result){
                                    case OPEN:
                                        openSet(manifest);
                                        break;
                                    case EDIT:
                                        editSet(manifest);
                                        break;
                                    case RENAME:
                                        renameSet(manifest);
                                        break;
                                    case DELETE:
                                        deleteSet(manifest);
                                        break;
                                }
                            }

                            @Override
                            public void onFail(Exception error) {

                            }
                        });
                return true;
            }
        });

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createSet();
            }
        });
    }

    private void renameSet(SetManifest manifest) {
        InputDialog
                .showDialog(this, R.string.rename, new Callback<String>() {
                    @Override
                    public void onDone(String result) {
                        setsManager.rename(manifest, result);
                        loadSetsList();
                    }

                    @Override
                    public void onFail(Exception error) {

                    }
                });

    }

    private void deleteSet(SetManifest manifest) {
        ConfirmDialog
                .showConfirmDialog(this, R.string.delete, new Callback() {
                    @Override
                    public void onDone(Object o) {
                        setsManager.delete(manifest);
                        loadSetsList();
                    }

                    @Override
                    public void onFail(Exception error) {

                    }
                });
    }

    private void openSet(SetManifest manifest) {
        Intent intent = new Intent(this, GridActivity.class);
        Bundle b = new Bundle();
        b.putString("file", manifest.toString()); //Your id
        intent.putExtras(b);
        startActivity(intent);
    }
    private void editSet(SetManifest manifest) {
        Intent intent = new Intent(this, SetEditActivity.class);
        Bundle b = new Bundle();
        b.putString("file", manifest.toString()); //Your id
        intent.putExtras(b);
        startActivity(intent);
    }

    private void createSet() {
        Context context = this;
        ParentPasswordDialog
                .showDialog(this, new Callback() {
                    @Override
                    public void onDone(Object o) {
                        Intent intent = new Intent(context, SetEditActivity.class);
                        activityLauncher.launch(intent);
                    }
                    @Override
                    public void onFail(Exception error) {

                    }
                });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        getMenuInflater().inflate(R.menu.main_activity_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if(item.getItemId()==R.id.settings){
            Context context = this;
            ParentPasswordDialog.showDialog(getWindow().getContext(), new Callback() {
                        @Override
                        public void onDone(Object o) {

                    Intent intent = new Intent(context, SettingsActivity.class);
                    startActivity(intent);
                }

                @Override
                public void onFail(Exception error) {

                }
            });
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadSetsList() {

        SetManifest[] sets = setsManager
                .getSets();
        adapter.clear();
        adapter.addAll(sets);
        adapter.notifyDataSetChanged();
    }

    protected void loadDefaultSets(){


        try {
            setsManager.loadDefaultSets();
        } catch (IOException e) {

            Log.e(getClass().getCanonicalName(), "loadSets: ", e);
            Toast.makeText(this, "copy assets error", Toast.LENGTH_LONG).show();

        }
    }

}