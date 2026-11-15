package radio.reply.catalog;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import java.io.File;
import java.util.*;

/** Isolated test APK grant owner; intentionally absent from the application. */
public class FixtureGrantActivity extends Activity {
    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        execute();
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        execute();
    }
    @SuppressWarnings("deprecation")
    private void execute() {
        ResultReceiver reply = getIntent().getParcelableExtra("reply");
        try {
            List<String> names = getIntent().getStringArrayListExtra("names");
            if (names == null) names = Collections.emptyList();
            if (!FixtureFiles.names.containsAll(names)) throw new IllegalArgumentException();
            String operation = getIntent().getStringExtra("operation");
            if ("untrusted".equals(operation)) {
                final android.media.browse.MediaBrowser[] browser = new android.media.browse.MediaBrowser[1];
                browser[0] = new android.media.browse.MediaBrowser(this,
                    new android.content.ComponentName("radio.reply.prototype", "radio.reply.playback.RadioService"),
                    new android.media.browse.MediaBrowser.ConnectionCallback() {
                        @Override public void onConnectionFailed() { if (reply != null) reply.send(1, new Bundle()); browser[0].disconnect(); finish(); }
                        @Override public void onConnected() { if (reply != null) reply.send(0, new Bundle()); browser[0].disconnect(); finish(); }
                    }, null);
                browser[0].connect();
                return;
            }
            if ("reset".equals(operation)) FixtureFiles.reset(this);
            else if ("music".equals(operation)) {
                FixtureFiles.reset(this);
                for (int n = 1; n <= 6; n++) {
                    String file = "song" + n + ".mp3";
                    try (java.io.InputStream input = getAssets().open(file);
                         java.io.FileOutputStream output = new java.io.FileOutputStream(new File(getFilesDir(), file))) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                    }
                }
            }
            else if ("grant".equals(operation)) for (String name : names) grantUriPermission("radio.reply.prototype", FixtureFiles.uri(name),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            else if ("revoke".equals(operation)) for (String name : names) revokeUriPermission(FixtureFiles.uri(name), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else if ("delete".equals(operation)) { for (String name : names) if (!new File(getFilesDir(), name + ".mp3").delete()) throw new IllegalStateException(); }
            else if ("corrupt".equals(operation)) { for (String name : names) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(getFilesDir(), name + ".mp3"))) {
                    out.write("broken MPEG test fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } }
            else throw new IllegalArgumentException();
            if (reply != null) reply.send(1, new Bundle());
        } catch (Exception failure) { if (reply != null) reply.send(0, new Bundle()); }
        finish();
    }
}
