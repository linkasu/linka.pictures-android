package su.linka.pictures.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;

import su.linka.pictures.Card;

public class EditCardGrid extends  CardGrid{

    private final ArrayList<Card> cards = new ArrayList<>();

    public EditCardGrid(Context context, AttributeSet set) {
        super(context, set);

    }

    @Override
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
                final int id = i * columns + j + (getPageSize() * page);
                GridButton button = new GridButton(getContext()); // Creating an instance for View Object
                buttons[id] = button;
                button.setLayoutParams(params);
                Card card;
                if(id<cards.size()){
                    card=cards.get(id);
                }else {
                    card  = new Card(i, 3);
                }
                button.setCard(card);


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

    public void refresh() {
        render();
    }
}
