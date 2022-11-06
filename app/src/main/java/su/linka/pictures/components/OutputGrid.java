package su.linka.pictures.components;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.appcompat.view.menu.MenuBuilder;

import java.util.ArrayList;

import su.linka.pictures.Card;
import su.linka.pictures.Set;

public class OutputGrid extends CardGrid{

    private final ArrayList<Card> cards = new ArrayList<>();

    public OutputGrid(Context context, AttributeSet set) {
        super(context, set);
        setOrientation(LinearLayout.HORIZONTAL);
    }

    public void addCard(Card card){
        cards.add(card);
        render();
    }
    public void backspace(){
        cards.remove(cards.size()-1);
        render();
    }
    public void clear(){
        cards.clear();
        render();
    }

    @Override
    public void setSet(Set set) {
        this.set = set;
        manifest = set.getManifest();
    }
    @Override
    protected void render(){
        removeAllViews();


        int count = cards.size();
        for (int i = 0; i < count; i++) {
            Card card = cards.get(i);
            if(card.cardType!=0) continue;
            GridButton button = new GridButton(getContext()); // Creating an instance for View Object
            button.setLayoutParams(params);

            button.setCard(card);
            button.setImage(set.getBitmap(card.imagePath));
            addView(button, i);

        }
    }
}
