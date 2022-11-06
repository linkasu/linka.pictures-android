package su.linka.pictures.components;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import su.linka.pictures.Card;
import su.linka.pictures.R;

public class GridButton  extends LinearLayout {
    private Card card;
    private Bitmap image;

    public GridButton(Context context){
        this(context, null, null);
    }
    public GridButton(Context context, Card card, Bitmap image) {
        super(context);

        inflate(getContext(), R.layout.grid_button, this);
        setCard(card);
        setImage(image);
    }

    public void setCard(Card card) {
        if(card!=null&&card.cardType!=2){
            setVisibility(View.VISIBLE);

            if(card.cardType==0){
                ((TextView) findViewById(R.id.text)).setText(card.title);
            }
            if(card.cardType==1){
                ((TextView) findViewById(R.id.text)).setText(" ");
                ((ImageView) findViewById(R.id.image)).setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_space_bar_24));
            }
            if(card.cardType==3){
                ((TextView) findViewById(R.id.text)).setText(R.string.create_card   );
                ((ImageView) findViewById(R.id.image)).setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_add_24));

            }

        } else {
            setVisibility(View.INVISIBLE);

        }
        this.card = card;
    }

    public Card getCard() {

        return card;
    }

    public void setImage(Bitmap image) {
        if(image!=null){

            if(card.cardType==0) {

                ((ImageView) findViewById(R.id.image)).setImageBitmap(image);
            }
        }
        this.image = image;
    }

    public Bitmap getImage() {
        return image;
    }
}
