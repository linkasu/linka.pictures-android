package su.linka.pictures.components;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;


import androidx.preference.PreferenceManager;

import su.linka.pictures.R;


public class ParentPasswordDialog {

    public static void showDialog(Context context, OnParentControlResult listener){



        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        boolean parent = sharedPreferences.getBoolean("parent", false);

        if(!parent){
            listener.onComplete();
            return;
        }
        String password = sharedPreferences.getString("parent_password", "");

        LayoutInflater li = LayoutInflater.from(context);
        View promptsView = li.inflate(R.layout.password_prompt, null);
        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
        alertDialogBuilder.setView(promptsView);

        final EditText userInput = (EditText) promptsView
                .findViewById(R.id.password_prompt);


        // set dialog message
        alertDialogBuilder
                .setTitle(R.string.parent_password)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,int id) {
                                /** DO THE METHOD HERE WHEN PROCEED IS CLICKED*/
                                String user_text = (userInput.getText()).toString();

                                if (user_text.equals(password))
                                {
                                    listener.onComplete();
                                }
                                else{

                                }
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.dismiss();
                            }

                        }

                );

        // create alert dialog
        AlertDialog alertDialog = alertDialogBuilder.create();

        // show it
        alertDialog.show();


    }

    public static abstract class OnParentControlResult {
        public abstract void onComplete();
    }
}
