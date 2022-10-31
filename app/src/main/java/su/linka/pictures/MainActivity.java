package su.linka.pictures;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static Context context;
    private ListView setsList;
    private ArrayAdapter<SetManifest> adapter;

    public static Context getContext() {
        return context;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = getApplicationContext();
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