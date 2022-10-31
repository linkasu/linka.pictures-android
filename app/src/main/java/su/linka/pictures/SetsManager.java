package su.linka.pictures;

import android.Manifest;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class SetsManager {
    private static SetsManager instance;

    public static SetsManager getInstance() {
        if(instance==null){
            instance=new SetsManager();
        }
        return instance;
    }

    public void loadDefaultSets() throws IOException {
        if(Cookie.getInstance().get(Cookie.ASSETS_LOADER, false)){
            return;
        }
        File sets = getSetsDirectory();
        sets.mkdir();
        AssetManager am = getAssetsManager();
            String[] list = am.list("sets");
            for (int i = 0; i < list.length; i++) {
                InputStream in = am.open("sets/"+list[i]);
                File outFile = new File(sets.getAbsoluteFile(), list[i]);;

                OutputStream out = new FileOutputStream(outFile);
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                in.close();
                out.close();
            }
        Cookie.getInstance().set(Cookie.ASSETS_LOADER, true);
    }

    private File getSetsDirectory() {
        return new File(getRootDirectory(). getAbsoluteFile(), "/sets/");
    }


    private File getRootDirectory(){
        //Create File object for Parent Directory
        return MainActivity.getContext().getFilesDir();
    }

    private AssetManager getAssetsManager(){
        return MainActivity.getContext().getAssets();
    }

    public SetManifest[] getSets() {

        String[] files = getSetsDirectory().list();
        SetManifest[] manifests = new SetManifest[files.length];
        for (int i = 0; i < files.length; i++) {
            File file = new File(getSetsDirectory(), files[i]);
            try {

               manifests[i] =  getSetManifest(file);
            } catch (IOException e) {
                Log.e(getClass().getCanonicalName(), "getSets: ", e);
                e.printStackTrace();
            }
        }
        return manifests;
    }
    public SetManifest getSetManifest(String name) throws IOException {
        return getSetManifest(getSetFile(name));

    }
    public SetManifest getSetManifest(File file) throws IOException {
        File outputDir = getOutputDir(); // context being the Activity pointer
        Log.d(getClass().getCanonicalName(), "getSetManifest: "+outputDir.getAbsolutePath());
        new ZipFile(file).extractFile("config.json", outputDir.getAbsolutePath());
        File configFile = new File(outputDir, "config.json");
        return parseManifest(file, configFile);
    }

    @Nullable
    private SetManifest parseManifest(File file, File configFile) {
        String raw = readStringFile(configFile);
        try {
            return new SetManifest(file, new JSONObject(raw));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    @NonNull
    private File getOutputDir() {
        return new File(MainActivity.getContext().getCacheDir(), UUID.randomUUID().toString() + "/");
    }

    private String readStringFile(File file){

//Read text from file
        StringBuilder text = new StringBuilder();

        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;

            while ((line = br.readLine()) != null) {
                text.append(line);
                text.append('\n');
            }
            br.close();
        }
        catch (IOException e) {
            //You'll need to add proper error handling here
        }
        return  text.toString();
     }

     public Set getSet(String name) throws ZipException {
        return getSet(getSetFile(name));
     }

    private Set getSet(File setFile) throws ZipException {
        File outputDir =getOutputDir(); // context being the Activity pointer
        new ZipFile(setFile).extractAll( outputDir.getAbsolutePath());

        SetManifest manifest = parseManifest(setFile, new File(outputDir, "config.json"));
        return new Set(manifest, outputDir);
    }

    public File getSetFile(String name) {
        return new File(getSetsDirectory(), name);
    }
}
