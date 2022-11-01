package su.linka.pictures;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

public class SetManifest {

    private final String version;
    public final int columns;
    public final int rows;
    public final boolean withoutSpace;
    public final ArrayList< Card> cards;
    protected final File file;


    public SetManifest(File file, JSONObject object) throws JSONException {
        this.file = file;
        version = object.getString("version");
        columns = object.getInt("columns");
        rows = object.getInt("rows");
        withoutSpace = object.getBoolean("withoutSpace");

        JSONArray array = object.getJSONArray("cards");
        cards = new ArrayList<Card>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            final int id = o.getInt("id");
            final int cardType = o.getInt("cardType");
            final String title = o.getString("title");
            final String audioPath = o.getString("audioPath");
            final String imagePath = o.getString("imagePath");
            cards.add( new Card(id, imagePath, title, audioPath, cardType));
        }
    }

    @Override
    public String toString() {
        return file.getName();
    }
}
