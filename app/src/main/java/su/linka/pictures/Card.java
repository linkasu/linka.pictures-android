package su.linka.pictures;

public class Card {
    final int id ;
    public final String imagePath ;
    public final String title ;
    final String audioPath ;
    public final int cardType ;

    public Card(int id, String imagePath, String title, String audioPath, int cardType) {
        this.id = id;
        this.imagePath = imagePath;
        this.title = title;
        this.audioPath = audioPath;
        this.cardType = cardType;
    }
}
