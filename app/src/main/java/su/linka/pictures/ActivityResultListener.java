package su.linka.pictures;

import android.content.Intent;

public abstract class ActivityResultListener {
    public abstract void onActivityResult(int requestCode, int resultCode, Intent data);
}
