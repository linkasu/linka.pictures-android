package su.linka.pictures.components;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;


import androidx.preference.PreferenceManager;

import su.linka.pictures.Callback;
import su.linka.pictures.R;


public class ParentPasswordDialog {

    public static void showDialog(Context context, Callback listener){



        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        boolean parent = sharedPreferences.getBoolean("parent", false);

        if(!parent){
            listener.onDone(null);
            return;
        }
        String password = sharedPreferences.getString("parent_password", "");

       InputDialog
               .showDialog(context, R.string.parent_password, InputType.TYPE_TEXT_VARIATION_PASSWORD, new Callback<String>() {
                   @Override
                   public void onDone(String text) {
                       if (text.equals(password))
                       {
                           listener.onDone(null);
                       }
                       else{
                        listener.onFail(new Exception("Error pass"));
                       }

                   }

                   @Override
                   public void onFail(Exception error) {

                   }

               });

    }
}
