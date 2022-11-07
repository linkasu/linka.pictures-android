package su.linka.pictures.components;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import androidx.annotation.IntegerRes;
import androidx.annotation.StringDef;

import java.lang.annotation.Retention;

import su.linka.pictures.Callback;
import su.linka.pictures.R;

public class SetContextDialog {
    public static final int OPEN = 0;
    public static final int EDIT = 1;
    public static final int DELETE= 2;


    public static void show(Context context, String title, Callback<Integer> callback){
        // setup the alert builder
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        builder.setItems(R.array.set_context_actions, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                callback.onDone(which);
            }
        })
                .setNegativeButton(R.string.cancel, null);

// create and show the alert dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }
}
