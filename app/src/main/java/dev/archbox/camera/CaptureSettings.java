package dev.archbox.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.RggbChannelVector;

/**
 * Every capture setting. Defaults are automatic. Manual values only take effect
 * when their switch or control is active, so the camera behaves like a normal app until changed.
 */
public class CaptureSettings {

    public enum Aspect {
        R4_3("4:3", 4f / 3f), R16_9("16:9", 16f / 9f), R1_1("1:1", 1f);

        public final String label;
        /** Long side divided by short side. */
        public final float ratio;

        Aspect(String label, float ratio) {
            this.label = label;
            this.ratio = ratio;
        }
    }

    public enum WhiteBalance { AUTO, DAYLIGHT, CLOUDY, TUNGSTEN, FLUORESCENT, KELVIN }

    public enum Grid { OFF, THIRDS, GOLDEN, SQUARE, CROSS }

    /** Video stabilisation. Auto uses it wherever the camera reports support. */
    public enum Stabilisation {
        OFF("Off"), AUTO("Auto"), ON("On");

        public final String label;

        Stabilisation(String label) {
            this.label = label;
        }
    }

    /** Manual focus on or off. Tapping the viewfinder turns it off. */
    public boolean manualFocus = false;
    /** 0 = infinity, 1 = closest focus the lens supports. */
    public float focusPosition = 0f;
    /** Exposure compensation in device steps. 0 = automatic. */
    public int evSteps = 0;
    public WhiteBalance whiteBalance = WhiteBalance.AUTO;
    public int kelvin = 5500;
    public Aspect aspect = Aspect.R4_3;
    public int jpegQuality = 92;
    public int timerSeconds = 0;
    public Grid grid = Grid.OFF;
    public boolean histogram = false;
    public boolean volumeShutter = true;
    public boolean darkTheme = true;
    public Stabilisation stabilisation = Stabilisation.AUTO;
    /** Zoom ratio chosen with the chips on the viewfinder. Not saved between launches. */
    public float zoom = 1f;
    public String filenamePrefix = "IMG";

    private static final String PREFS = "camera_settings";

    public static CaptureSettings load(Context context) {
        CaptureSettings s = new CaptureSettings();
        s.reload(context);
        return s;
    }

    /** Re-reads saved values into this object, so the camera keeps the same instance. */
    public void reload(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        manualFocus = p.getBoolean("manualFocus", manualFocus);
        focusPosition = p.getFloat("focusPosition", focusPosition);
        evSteps = p.getInt("evSteps", evSteps);
        whiteBalance = enumOf(WhiteBalance.class, p.getString("wb", null), whiteBalance);
        kelvin = p.getInt("kelvin", kelvin);
        aspect = enumOf(Aspect.class, p.getString("aspect", null), aspect);
        jpegQuality = p.getInt("jpegQuality", jpegQuality);
        timerSeconds = p.getInt("timerSeconds", timerSeconds);
        grid = enumOf(Grid.class, p.getString("grid", null), grid);
        histogram = p.getBoolean("histogram", histogram);
        volumeShutter = p.getBoolean("volumeShutter", volumeShutter);
        darkTheme = p.getBoolean("darkTheme", darkTheme);
        stabilisation = enumOf(Stabilisation.class, p.getString("stabilisation", null), stabilisation);
        filenamePrefix = p.getString("filenamePrefix", filenamePrefix);
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("manualFocus", manualFocus)
                .putFloat("focusPosition", focusPosition)
                .putInt("evSteps", evSteps)
                .putString("wb", whiteBalance.name())
                .putInt("kelvin", kelvin)
                .putString("aspect", aspect.name())
                .putInt("jpegQuality", jpegQuality)
                .putInt("timerSeconds", timerSeconds)
                .putString("grid", grid.name())
                .putBoolean("histogram", histogram)
                .putBoolean("volumeShutter", volumeShutter)
                .putBoolean("darkTheme", darkTheme)
                .putString("stabilisation", stabilisation.name())
                .putString("filenamePrefix", filenamePrefix)
                .apply();
    }

    /** Applies these settings to a request. Stills and previews share the same settings. */
    public void applyTo(CaptureRequest.Builder b, CameraController.Limits lim) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        int ev = Math.max(lim.evMin, Math.min(lim.evMax, evSteps));
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        if (lim.zoomSupported) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(lim.minZoom, Math.min(lim.maxZoom, zoom)));
        }

        if (manualFocus && lim.hasManualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusPosition * lim.minFocusDiopters);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }

        switch (whiteBalance) {
            case DAYLIGHT:
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
                break;
            case CLOUDY:
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT);
                break;
            case TUNGSTEN:
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT);
                break;
            case FLUORESCENT:
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);
                break;
            case KELVIN:
                // Approximate: scales red and blue gains around 5500 K. Not a calibrated colour temperature.
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST);
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinGains(kelvin));
                break;
            default:
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                break;
        }

        b.set(CaptureRequest.JPEG_QUALITY, (byte) Math.max(1, Math.min(100, jpegQuality)));
    }

    static RggbChannelVector kelvinGains(int k) {
        float t = (5500f - k) / 5500f; // positive for warm light: cool the image down
        float r = clamp(1f - 0.4f * t, 0.5f, 1.8f);
        float blue = clamp(1f + 0.4f * t, 0.5f, 1.8f);
        return new RggbChannelVector(r, 1f, 1f, blue);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(name)) return e;
        }
        return fallback;
    }
}
