package su.linka.pictures;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

public class SetManifest {

    private final String version;
    public int columns;
    public int rows;
    public boolean withoutSpace;
    public ArrayList< Card> cards;
    protected final File file;

    public SetManifest(File file){

        this.file = file;
        version = "1.0";
        columns =4;
        rows=3;
        withoutSpace = false;
        cards = new ArrayList<>();
    }

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


    public String getDefaultBitmap(){
        String path = null;

        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            path = card.imagePath;
            if(path!=null) {
                return path;
            }
        }

        return (null);
    }

    @Override
    public String toString() {
        return file.getName();
    }

    public JSONObject toJSONObject() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", version)
                .put("columns", columns)
                .put("rows", rows)
                .put("withoutSpace", withoutSpace);
        JSONArray array = new JSONArray();
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            JSONObject jcard = card.toJSONObject();
            array.put(i, jcard);
        }
        o.put("cards", array);
        return o;
    }
}
