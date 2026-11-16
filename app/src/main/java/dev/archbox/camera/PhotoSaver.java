package dev.archbox.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes JPEG bytes to Pictures/Camera through MediaStore (no storage permission needed, API 29+). */
public final class PhotoSaver {
    private PhotoSaver() {}

    public static String save(Context context, byte[] jpeg) throws IOException {
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("No output stream for " + uri);
            out.write(jpeg);
        }

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return name;
    }
}
