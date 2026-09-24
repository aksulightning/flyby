package io.github.aksulightning.flyby;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** SDK-only fixture: its separate test-APK process cannot rely on the target's Kotlin runtime. */
public class SharedTestProvider extends DocumentsProvider {
    private File root() {
        File root = new File(getContext().getFilesDir(), "shared-test");
        root.mkdirs();
        return root;
    }
    private File file(String id) throws FileNotFoundException {
        if (!id.equals("root") && !id.startsWith("root/")) throw new FileNotFoundException();
        try {
            File root = root().getCanonicalFile();
            File f = id.equals("root") ? root : new File(root, id.substring(5)).getCanonicalFile();
            if (!f.toPath().startsWith(root.toPath())) throw new FileNotFoundException();
            return f;
        } catch (IOException e) { throw new FileNotFoundException(); }
    }
    private String id(File f) { return f.equals(root()) ? "root" : "root/" + root().toPath().relativize(f.toPath()); }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[]{"root_id"} : projection); }
    private Cursor rows(File[] files, String[] projection) {
        String[] columns = projection == null ? new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        if (files != null) for (File f : files) {
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case Document.COLUMN_DOCUMENT_ID: values[i] = id(f); break;
                    case Document.COLUMN_DISPLAY_NAME: values[i] = f.getName(); break;
                    case Document.COLUMN_MIME_TYPE: values[i] = f.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                    case Document.COLUMN_SIZE: values[i] = f.length(); break;
                    case Document.COLUMN_LAST_MODIFIED: values[i] = f.lastModified(); break;
                    case Document.COLUMN_FLAGS: values[i] = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME |
                        (f.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : 0); break;
                }
            }
            result.addRow(values);
        }
        return result;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        File f = file(documentId); if (!f.exists()) throw new FileNotFoundException();
        return rows(new File[]{f}, projection);
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        return rows(file(parentDocumentId).listFiles(), projection);
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }
    private void name(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")) throw new IllegalArgumentException();
    }
    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        name(displayName); File f = new File(file(parentDocumentId), displayName);
        try {
            if (!(mimeType.equals(Document.MIME_TYPE_DIR) ? f.mkdir() : f.createNewFile())) throw new FileNotFoundException();
        } catch (IOException e) { throw new FileNotFoundException(); }
        return id(f);
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (documentId.equals("root") || !file(documentId).delete()) throw new FileNotFoundException();
    }
    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        name(displayName); if (documentId.equals("root")) throw new FileNotFoundException();
        File f = file(documentId), next = new File(f.getParentFile(), displayName);
        if (!f.renameTo(next)) throw new FileNotFoundException();
        return id(next);
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try { return file(documentId).toPath().startsWith(file(parentDocumentId).toPath()); }
        catch (FileNotFoundException e) { return false; }
    }
}
