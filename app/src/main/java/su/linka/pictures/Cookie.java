package su.linka.pictures;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.StringDef;

import java.lang.annotation.Retention;

import su.linka.pictures.activity.MainActivity;

public class Cookie {

                @Retention(SOURCE)
                @StringDef({
                        ASSETS_LOADER,
                })
                public @interface FieldName {}
                public static final String ASSETS_LOADER = "ASSETS_LOADER";


    private static final String APP_PREFERENCES = "my";
    private static Cookie instance;
    private final SharedPreferences preferences;

    public Cookie(Context context) {
        this.preferences = context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE);

    }

    public  boolean get(@FieldName String id, boolean def){
        return preferences.getBoolean(id, def);
    }
    public void set(@FieldName String id, boolean value) {
        preferences.edit()
                .putBoolean(id, value)
                .apply();
    }
    public int getSetSettings(String id, int def) {
        return preferences.getInt(id, def);
    }
    public void setSetSettings(String id, int value){
        preferences.edit()
                .putInt(id, value)
                .apply();
    }
}
