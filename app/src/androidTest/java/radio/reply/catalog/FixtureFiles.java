package radio.reply.catalog;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Generated silent MP3 bytes, never a user's music; Java allows a standalone test-provider process. */
public final class FixtureFiles {
    public static final List<String> names = Arrays.asList("song1", "song2", "song3", "song4", "song5", "song6",
            "missing-year", "bad-year", "broken", "wav");
    public static Uri uri(String name) { return DocumentsContract.buildDocumentUri("radio.reply.prototype.test.documents", name); }
    public static void reset(Context context) throws IOException {
        for (String name : names) {
            byte[] bytes;
            if (name.equals("broken")) bytes = "not audio".getBytes(StandardCharsets.UTF_8);
            else if (name.equals("wav")) bytes = wav();
            else bytes = mp3(name, name.equals("missing-year") ? null : name.equals("bad-year") ? "oops" :
                    (Integer.parseInt(name.substring(4)) % 2 == 1 ? "1988" : "2014"));
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name + ".mp3"))) { out.write(bytes); }
        }
    }
    private static byte[] frame(String id, String text) throws IOException {
        byte[] value = text.getBytes(StandardCharsets.UTF_16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id.getBytes(StandardCharsets.US_ASCII)); out.write(ByteBuffer.allocate(4).putInt(value.length + 1).array());
        out.write(new byte[]{0, 0, 1}); out.write(value); return out.toByteArray();
    }
    private static byte[] mp3(String name, String year) throws IOException {
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        tags.write(frame("TIT2", "검증 곡 " + name)); tags.write(frame("TPE1", "합성 테스트"));
        if (year != null) tags.write(frame("TYER", year));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{73, 68, 51, 3, 0, 0});
        for (int i = 3; i >= 0; i--) out.write((tags.size() >> (i * 7)) & 127);
        out.write(tags.toByteArray());
        // MPEG-1 Layer III, 128 kbps / 44.1 kHz, zero side info and main data: silent frames.
        for (int i = 0; i < 50; i++) { out.write(new byte[]{(byte)0xff, (byte)0xfb, (byte)0x90, 0}); out.write(new byte[413]); }
        return out.toByteArray();
    }
    private static byte[] wav() {
        return ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(16036)
            .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1)
            .putInt(8000).putInt(16000).putShort((short)2).putShort((short)16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(16000).put(new byte[16000]).array();
    }
}
