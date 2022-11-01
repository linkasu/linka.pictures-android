package su.linka.pictures;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

import java.util.List;

import su.linka.pictures.components.GridButton;

public class OutputAdapter extends ArrayAdapter<Card>{
    private final Set set;

    public OutputAdapter(@NonNull Context context, Set set) {
        super(context, R.layout.grid_button);

        this.set = set;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        Card card = getItem(position);
        GridButton button = (GridButton) convertView;
        if (button == null){
            button = new GridButton(getContext(), card, set.getBitmap(card.imagePath));
        } else {
            button.setCard(card);
            button.setImage( set.getBitmap(card.imagePath));
        }

        return (View) button;
    }
}