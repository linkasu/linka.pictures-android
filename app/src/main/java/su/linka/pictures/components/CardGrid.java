package su.linka.pictures.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import su.linka.pictures.Card;
import su.linka.pictures.Set;
import su.linka.pictures.SetManifest;

public class CardGrid extends LinearLayout {

    private GridButton[] buttons;
    private int rows;
    private int columns;
    int page = 0;
    Set set;
    private SetManifest manifest;
    private OnCardSelectListener cardSelectListener;

    public CardGrid(Context context, AttributeSet set){

        super(context, set);
    }
    public CardGrid(Context context, Set set) {
        this(context, set.getManifest().rows, set.getManifest().columns);
        setSet(set);
    }

    public CardGrid(Context context, int rows, int columns) {
        super(context);
        setGridSize(rows, columns);
    }

    private void setGridSize(int rows, int columns) {
        removeAllViews();
        this.rows = rows;
        this.columns = columns;
        setOrientation(LinearLayout.VERTICAL);
        buttons = new GridButton[getPageSize()];
        for (int i = 0; i < rows; i++) {

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.weight = 1;

            row.setLayoutParams(params);
            addView(row);
            for (int j = 0; j < columns; j++) {

                GridButton button = new GridButton(getContext()); // Creating an instance for View Object
                buttons[i * columns + j + (getPageSize() * page)] = button;
                button.setLayoutParams(params);

                row.addView(button, j);
            }
        }
     }

    private int getPageSize() {
        return rows * columns;
    }
    public int getPagesCount(){
        return manifest.cards.size() / getPageSize();
    }

    
    
    public void setSet(Set set) {
        this.set = set;
        manifest = set.getManifest();
        if(rows!=manifest.rows||columns!=manifest.columns){
            setGridSize(manifest.rows, manifest.columns);
        }
        page = 0;
        render();
    }

    public void nextPage(){
        if(page<getPagesCount()-1){
            page++;
        }
        render();
    }
    public void prevPage(){
        if(page>=1){
            page--;
        }
        render();
    }
    
    private void render() {
        int count = getPageSize();
        for (int i = 0; i < count; i++) {
            int index = getPageSize()*page+i;
            Card card = null;
            if(index<manifest.cards.size()) {
                card = manifest.cards.get(index);

            }
            buttons[i].setCard(card);
            if(card!=null){
                if(card.cardType==0) buttons[i].setImage(set.getBitmap(card.imagePath));
                Card finalCard = card;
                buttons[i].setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(cardSelectListener!=null){
                            cardSelectListener.onCard(finalCard);
                        }
                    }
                });
            }

        }
    }

    public void setCardSelectListener(OnCardSelectListener cardSelectListener) {
        this.cardSelectListener = cardSelectListener;
    }

    public static abstract class OnCardSelectListener{
        public abstract void onCard(Card card);
    }
}