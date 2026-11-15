package radio.reply.catalog;

import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;

/** Test APK only; the application manifest never exposes this provider. */
public class FixtureDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT_COLUMNS = {DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE};
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        cursor.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, "rr003").add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
            .add(DocumentsContract.Root.COLUMN_TITLE, "RR003 fixtures").add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY)
            .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "audio/mpeg");
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection); addDocument(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) {
        if (!parent.equals("root")) throw new IllegalArgumentException();
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        for (String name : FixtureFiles.names) if (new File(getContext().getFilesDir(), name + ".mp3").exists()) addDocument(cursor, name);
        return cursor;
    }
    private void addDocument(MatrixCursor cursor, String id) {
        if (!id.equals("root") && !FixtureFiles.names.contains(id)) throw new IllegalArgumentException();
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, id.equals("root") ? "RR003 fixtures" : "RR003 " + id + ".mp3")
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, id.equals("root") ? DocumentsContract.Document.MIME_TYPE_DIR : "audio/mpeg")
            .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            .add(DocumentsContract.Document.COLUMN_SIZE, id.equals("root") ? 0 : new File(getContext().getFilesDir(), id + ".mp3").length());
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!FixtureFiles.names.contains(id) || !mode.equals("r")) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), id + ".mp3"), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
