package su.linka.pictures;

import java.util.Objects;

public class Card {
    final int id ;
    public final String imagePath ;
    public final String title ;
    public final String audioPath ;
    public final int cardType ;

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
}
