package su.linka.pictures.components;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import androidx.annotation.StringRes;

import su.linka.pictures.Callback;
import su.linka.pictures.R;

public class ConfirmDialog {
    public static void showConfirmDialog(Context context, @StringRes int title, Callback callback){
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder
                .setTitle(title)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        callback.onFail(null);
                    }
                })
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        callback.onDone(null);
                    }
                })
                .show();
    }
}
