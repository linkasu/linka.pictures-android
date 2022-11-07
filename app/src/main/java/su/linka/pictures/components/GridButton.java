package su.linka.pictures.components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.amulyakhare.textdrawable.TextDrawable;

import su.linka.pictures.Card;
import su.linka.pictures.R;
import su.linka.pictures.Utils;

public class GridButton  extends LinearLayout {
    private Card card;
    private Bitmap image;
    private boolean isOutputCard;

    public GridButton(Context context){
        this(context, null, null, false);
    }

    public GridButton(Context context, Card card, Bitmap image){
        this(context, card, image, false);
    }
    public GridButton(Context context, Card card, Bitmap image, boolean isOutputCard) {
        super(context);
        this.isOutputCard = isOutputCard;

        inflate(getContext(), R.layout.grid_button, this);
        setCard(card);
        setImage(image);
    }

    public GridButton(Context context, boolean isOutputCard) {
        this(context, null, null, isOutputCard);
    }

    public void setCard(Card card) {
        if(card!=null&&card.cardType!=2){
            setVisibility(View.VISIBLE);

            if(card.cardType==0){
                setText(card.title);
            }
            if(card.cardType==1){
                setText("");
                ((ImageView) findViewById(R.id.image)).setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_space_bar_24));
            }
            if(card.cardType==3){
                setText(R.string.create_card);
                ((ImageView) findViewById(R.id.image)).setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_add_24));

            }

        } else {
            setVisibility(View.INVISIBLE);

        }
        this.card = card;
    }

    private void setText(@StringRes int id) {
        setText(getContext().getString(id));
    }

    private void setText(String title) {
        Bitmap bm = Utils.textAsBitmap(title);
        ImageView image = (ImageView) findViewById(R.id.image_text);
        image.setImageBitmap(bm);
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

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if(isOutputCard) {
            super.onMeasure(heightMeasureSpec, heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
