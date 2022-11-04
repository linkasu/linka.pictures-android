package su.linka.pictures.activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

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

import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.IOException;

import su.linka.pictures.R;
import su.linka.pictures.SetManifest;
import su.linka.pictures.SetsManager;
import su.linka.pictures.components.ParentPasswordDialog;

public class MainActivity extends AppCompatActivity {

    private static Context context;
    private ListView setsList;
    private ArrayAdapter<SetManifest> adapter;
    private FirebaseAnalytics mFirebaseAnalytics;

    public static Context getContext() {
        return context;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = getApplicationContext();
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
        loadDefaultSets();

         setsList = findViewById(R.id.sets_list);

        adapter = new ArrayAdapter<SetManifest>(context, androidx.appcompat.R.layout.support_simple_spinner_dropdown_item);
        setsList.setAdapter(adapter);
        loadSetsList();
        setsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                SetManifest manifest = adapter.getItem(position);
                Intent intent = new Intent(context, GridActivity.class);
                Bundle b = new Bundle();
                b.putString("file", manifest.toString()); //Your id
                intent.putExtras(b);
                startActivity(intent);

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
            ParentPasswordDialog.showDialog(getWindow().getContext(), new ParentPasswordDialog.OnParentControlResult() {
                @Override
                public void onComplete() {

                    Intent intent = new Intent(context, SettingsActivity.class);
                    startActivity(intent);
                }
            });
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadSetsList() {

        SetManifest[] sets = SetsManager.getInstance()
                .getSets();
        adapter.clear();
        adapter.addAll(sets);
    }

    protected void loadDefaultSets(){


        try {
            SetsManager.getInstance().loadDefaultSets();
        } catch (IOException e) {

            Log.e(getClass().getCanonicalName(), "loadSets: ", e);
            Toast.makeText(this, "copy assets error", Toast.LENGTH_LONG).show();

        }
    }

}