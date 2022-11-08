package su.linka.pictures.activity;

import static androidx.activity.result.ActivityResultCallerKt.registerForActivityResult;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.File;
import java.io.IOException;

import su.linka.pictures.AnalyticsEvents;
import su.linka.pictures.Callback;
import su.linka.pictures.R;
import su.linka.pictures.SetsManager;
import su.linka.pictures.Utils;
import su.linka.pictures.components.ConfirmDialog;

public class BroadcastReceiverActivity extends Activity {
    private FirebaseAnalytics firebaseAnalytics;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        firebaseAnalytics = FirebaseAnalytics.getInstance(this);
//        setContentView(R.layout.activity_main);
        final Intent intent = getIntent();
        final String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action)) {
            //uri = intent.getStringExtra("URI");
            Uri uri = intent.getData();
            ConfirmDialog
                    .showConfirmDialog(this, R.string.add_set_to_library, new Callback() {
                        @Override
                        public void onDone(Object result) {
                            copy(uri);
                        }


                        @Override
                        public void onFail(Exception error) {
finish();
                        }
                    });
            // now you call whatever function your app uses
            // to consume the txt file whose location you now know
        } else {
        }
    }

    private void copy(Uri uri) {
        try {
            Utils.copy( getContentResolver().openInputStream(uri), new File ((new SetsManager(this)).getSetsDirectory(),uri.getPathSegments().get(uri.getPathSegments().size()-1) ));
        } catch (IOException e) {
            e.printStackTrace();
            finish();
        }
        firebaseAnalytics.logEvent(AnalyticsEvents.ADD_SET, null);

        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
