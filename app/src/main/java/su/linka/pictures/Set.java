package su.linka.pictures;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

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
        if(imagePath==null) return null;
        File file = new File(folder, imagePath);
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    public File getAudioFile(String audioPath) {
        return new File(folder, audioPath);
    }

    public File copyAudioFile(File currentAudioFile) throws IOException {
        return Utils.copy(currentAudioFile, new File(folder, currentAudioFile.getName()));
    }

    public File saveBitmap(Bitmap bm) {
        folder.mkdir();
        return Utils.saveBitmapToFile(folder, UUID.randomUUID().toString()+".png",bm,Bitmap.CompressFormat.PNG,100);

    }

    public void addCard(int pos, Card result) {
        ArrayList<Card> cards = getManifest()
                .cards;
        int size = cards.size();
        if(pos>=size){
            while (cards.size()<pos){
                cards.add(new Card(0, 3 ));
            }
            cards.add(result);

        } else {
            cards.set(pos, result);
        }

    }
}
