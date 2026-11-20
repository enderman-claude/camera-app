package dev.archbox.camera;

import android.os.Build;

import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes EXIF tags into a JPEG file on disk. Tags reflect the capture, not the save. */
public final class PhotoMetadata {
    private PhotoMetadata() {}

    public static void write(String path, CaptureSettings settings) throws IOException {
        ExifInterface exif = new ExifInterface(path);
        String now = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date());
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now);
        exif.setAttribute(ExifInterface.TAG_DATETIME, now);
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "dev.archbox.camera");
        if (settings != null) {
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT,
                    "aspect=" + settings.aspect.label
                            + " wb=" + settings.whiteBalance
                            + (settings.whiteBalance == CaptureSettings.WhiteBalance.KELVIN ? " kelvin=" + settings.kelvin : "")
                            + " ev=" + settings.evSteps
                            + " quality=" + settings.jpegQuality);
        }
        exif.saveAttributes();
    }
}
