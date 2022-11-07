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
    final LayoutParams params;
    GridButton[] buttons;
    int rows;
    int columns;
    int page = 0;
    Set set;
    SetManifest manifest;
    OnCardSelectListener cardSelectListener;

    public CardGrid(Context context, AttributeSet set){

        super(context, set);
        params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.weight = 1;

    }

    public void setGridSize(int rows, int columns) {
        removeAllViews();
        this.rows = rows;
        this.columns = columns;
        setOrientation(LinearLayout.VERTICAL);
        buttons = new GridButton[getPageSize()];
        for (int i = 0; i < rows; i++) {

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);

            row.setLayoutParams(params);
            addView(row);
            for (int j = 0; j < columns; j++) {
                final  int id = i * columns + j + (getPageSize() * page);
                GridButton button = new GridButton(getContext()); // Creating an instance for View Object
                buttons[id] = button;
                button.setLayoutParams(params);

                button.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(cardSelectListener!=null){
                            cardSelectListener.onCard(button.getCard(), id);
                        }
                    }
                });

                row.addView(button, j);
            }
        }
     }

    public int getPageSize() {
        return rows * columns;
    }
    public int getPagesCount(){
    return (int) Math.ceil( (float) manifest.cards.size() / (float) getPageSize());
    }

    
    public void setSet(Set set){
        setSet(set, false);
    }
    public void setSet(Set set, boolean output) {
        this.set = set;
        manifest = set.getManifest();
        if(rows!=manifest.rows||columns!=manifest.columns){
            setGridSize(output?1:manifest.rows, manifest.columns);
        }
        page = 0;
        render();
    }

    public boolean nextPage(){
        if(page<getPagesCount()-1){
            page++;
            render();
            return true;
        }
        return false;
    }
    public void prevPage(){
        if(page>=1){
            page--;
        }
        render();
    }
    
    protected void render() {

        int count = getPageSize();
        for (int i = 0; i < count; i++) {
            int index = count*page+i;
            Card card = null;

            if(index<manifest.cards.size()) {

                card = manifest.cards.get(index);

            }
            if(card!=null){
                buttons[i].setCard(card);
                if(card.cardType==0) buttons[i].setImage(set.getBitmap(card.imagePath));
                Card finalCard = card;

            }

        }
    }

    public void setCardSelectListener(OnCardSelectListener cardSelectListener) {
        this.cardSelectListener = cardSelectListener;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPage() {
        return page;
    }


    public static abstract class OnCardSelectListener{
        public abstract void onCard(Card card, int position);
    }
}