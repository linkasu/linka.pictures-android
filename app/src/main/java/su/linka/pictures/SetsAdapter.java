package su.linka.pictures;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.lingala.zip4j.exception.ZipException;

import java.io.File;

public class SetsAdapter extends ArrayAdapter<SetManifest> {
    private final SetsManager setsManager;

    public SetsAdapter(@NonNull Context context) {
        super(context, R.layout.set_grid_button);
        setsManager = new SetsManager(context);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        SetManifest manifest = getItem(position);

        if(convertView==null){
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.set_grid_button, null);

        }

        String path = manifest.getDefaultBitmap();
        File image = null;
        try {
            image = setsManager.getSetImage(manifest, path);
        } catch (ZipException e) {
            e.printStackTrace();
        }
        ((ImageView) convertView.findViewById(R.id.picture)).setImageBitmap(Utils.readBitmapFromFile(image));
        ((TextView) convertView.findViewById(R.id.text)).setText(manifest.toString());


        return convertView;
    }
}
