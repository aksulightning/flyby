package io.github.aksulightning.flyby;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Creates disposable test files under its own UID and grants scoped SAF access to the target app. */
public class SharedTestSetupActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        File root = new File(getFilesDir(), "shared-test");
        root.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(root, "from-android"))) {
            out.write("android-data".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException(e); }
        grantUriPermission("io.github.aksulightning.flyby",
            DocumentsContract.buildTreeDocumentUri("io.github.aksulightning.flyby.test.shared", "root"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
