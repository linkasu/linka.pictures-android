package su.linka.pictures.activity;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// The standalone test-APK provider cannot use Kotlin stdlib supplied only by the target APK.
public final class SmotriDocumentsProvider extends DocumentsProvider {
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
        "Фраза.linka", "Азбука.linka", "sounds2-validated-v2.linka", "3x3.linka", "Клавиатура.linka"));
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON});
        MatrixCursor.RowBuilder row = cursor.newRow();
        add(cursor, row, Root.COLUMN_ROOT_ID, "smotri");
        add(cursor, row, Root.COLUMN_DOCUMENT_ID, "smotri");
        add(cursor, row, Root.COLUMN_TITLE, "SMOTRI originals");
        add(cursor, row, Root.COLUMN_FLAGS, 0);
        add(cursor, row, Root.COLUMN_MIME_TYPES, "application/octet-stream");
        add(cursor, row, Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery);
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        append(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!"smotri".equals(parent)) throw new FileNotFoundException("Unknown fixture directory");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        for (String name : NAMES) if (file(name).isFile()) append(cursor, name);
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Fixtures are read-only");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File file(String id) throws FileNotFoundException {
        if (!NAMES.contains(id)) throw new FileNotFoundException("Unknown fixture");
        try {
            File directory = new File(getContext().getFilesDir(), "smotri").getCanonicalFile();
            File source = new File(directory, id).getCanonicalFile();
            if (!directory.equals(source.getParentFile())) throw new FileNotFoundException("Outside fixture directory");
            return source;
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    private void append(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean directory = "smotri".equals(id);
        File source = directory ? null : file(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        add(cursor, row, Document.COLUMN_DOCUMENT_ID, id);
        add(cursor, row, Document.COLUMN_DISPLAY_NAME, directory ? "SMOTRI originals" : id);
        add(cursor, row, Document.COLUMN_MIME_TYPE, directory ? Document.MIME_TYPE_DIR : "application/octet-stream");
        add(cursor, row, Document.COLUMN_FLAGS, 0);
        add(cursor, row, Document.COLUMN_SIZE, source != null ? source.length() : 0L);
    }

    private static void add(MatrixCursor cursor, MatrixCursor.RowBuilder row, String column, Object value) {
        if (cursor.getColumnIndex(column) >= 0) row.add(column, value);
    }
}
