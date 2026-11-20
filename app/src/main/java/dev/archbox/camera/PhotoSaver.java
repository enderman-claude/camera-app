package dev.archbox.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.nio.file.Files;
import java.util.Locale;

/** Writes JPEG bytes to Pictures/Camera through MediaStore (no storage permission needed, API 29+). */
public final class PhotoSaver {
    private PhotoSaver() {}

    public static String save(Context context, byte[] jpeg, String prefix, CaptureSettings settings) throws IOException {
        String base = (prefix == null || prefix.trim().isEmpty()) ? "IMG" : prefix.trim();
        String name = base + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");

        // Tag a temp copy first: ExifInterface needs a real file to save attributes.
        File temp = File.createTempFile("capture", ".jpg", context.getCacheDir());
        try {
            try (OutputStream tmp = new FileOutputStream(temp)) {
                tmp.write(jpeg);
            }
            PhotoMetadata.write(temp.getAbsolutePath(), settings);
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("No output stream for " + uri);
                Files.copy(temp.toPath(), out);
            }
        } finally {
            temp.delete();
        }

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return name;
    }
}
