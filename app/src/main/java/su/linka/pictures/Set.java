package su.linka.pictures;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

public class Set {
    private final SetManifest manifest;
    private final File folder;
    public Set(SetManifest manifest, File folder) {
        this.manifest = manifest;
        this.folder = folder;

    }

    public SetManifest getManifest() {
        return manifest;
    }

    public Bitmap getBitmap(String imagePath) {
        File file = new File(folder, imagePath);
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    public File getAudioFile(String audioPath) {
        return new File(folder, audioPath);
    }
}
