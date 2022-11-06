package su.linka.pictures;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

public class Card {
    final int id ;
    public String imagePath ;
    public String title ;
    public String audioPath ;
    public int cardType ;

    public Card(int id, int cardType){
        this.id = id;
        this.cardType = cardType;
    }
    public Card(int id, String imagePath, String title, String audioPath, int cardType) {
        this.id = id;
        this.imagePath = imagePath;
        this.title = title;
        this.audioPath = audioPath;
        this.cardType = cardType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Card card = (Card) o;
        return id == card.id && cardType == card.cardType && Objects.equals(imagePath, card.imagePath) && Objects.equals(title, card.title) && Objects.equals(audioPath, card.audioPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, imagePath, title, audioPath, cardType);
    }

    public JSONObject toJSONObject() throws JSONException {
        JSONObject card = new JSONObject();
        card.put("id", id)
                .put("title", title)
                .put("imagePath", imagePath)
                .put("audioPath", audioPath)
                .put("cardType", cardType);
        return card;
    }

    @Override
    public Card clone() throws CloneNotSupportedException {
        return (Card) super.clone();
    }
}
