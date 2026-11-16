package dev.archbox.camera;

import android.hardware.camera2.CaptureRequest;

/**
 * Capture settings. Every field defaults to automatic. Manual values are only
 * used when the user changes them, so the camera behaves like a normal app by default.
 */
public class CaptureSettings {
    /** Exposure compensation in device steps. 0 means automatic. */
    public int exposureCompensation = 0;

    /** Focus mode. Continuous auto focus is the default. */
    public int afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;

    /** White balance mode. Auto is the default. */
    public int awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;

    public void applyTo(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);
        b.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
    }
}
