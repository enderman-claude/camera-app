package dev.archbox.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A video file being recorded into Movies/Camera through MediaStore. The entry stays pending
 * (hidden from other apps) until finish() is called, so a failed recording never shows up half-written.
 */
public final class VideoSaver {
    public final Uri uri;
    public final String name;
    private final ParcelFileDescriptor descriptor;

    private VideoSaver(Uri uri, String name, ParcelFileDescriptor descriptor) {
        this.uri = uri;
        this.name = name;
        this.descriptor = descriptor;
    }

    public static VideoSaver begin(Context context, String prefix) throws IOException {
        String base = (prefix == null || prefix.trim().isEmpty()) ? "VID" : prefix.trim();
        String name = base + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".mp4";
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camera");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw");
            if (pfd == null) throw new FileNotFoundException("No descriptor for " + uri);
            return new VideoSaver(uri, name, pfd);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    public ParcelFileDescriptor descriptor() {
        return descriptor;
    }

    /** Closes the file and makes it visible to other apps. */
    public void finish(Context context) {
        closeDescriptor();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        context.getContentResolver().update(uri, values, null, null);
    }

    /** Closes and deletes the entry, for recordings that failed. */
    public void discard(Context context) {
        closeDescriptor();
        context.getContentResolver().delete(uri, null, null);
    }

    private void closeDescriptor() {
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // nothing useful to do; the entry is finished or deleted either way
        }
    }
}
