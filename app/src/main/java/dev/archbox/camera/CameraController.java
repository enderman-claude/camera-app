package dev.archbox.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Camera2 pipeline for one back camera: a 4:3 preview on a TextureView and
 * 4:3 JPEG stills. All camera work runs on a background thread; listener
 * callbacks are delivered on the main thread.
 */
public class CameraController {

    public interface Listener {
        void onStatus(String text);
        void onPhotoSaved(String displayName);
        void onError(String message);
    }

    private static final long PREVIEW_MAX_AREA = 1920L * 1440L;

    private final Context context;
    private final CameraManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CaptureSettings settings = new CaptureSettings();

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private SurfaceTexture surfaceTexture;
    private int sensorOrientation;

    public CameraController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.listener = listener;
    }

    public CaptureSettings settings() {
        return settings;
    }

    @SuppressLint("MissingPermission")
    public void open(SurfaceTexture texture) {
        close();
        surfaceTexture = texture;
        startThread();
        try {
            String id = findBackCamera();
            if (id == null) {
                ui(() -> listener.onError("No back camera found"));
                return;
            }
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size preview = pick43(map.getOutputSizes(SurfaceTexture.class), PREVIEW_MAX_AREA);
            Size still = pick43(map.getOutputSizes(ImageFormat.JPEG), 0);

            surfaceTexture.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
            previewSurface = new Surface(surfaceTexture);
            reader = ImageReader.newInstance(still.getWidth(), still.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onImageAvailable, handler);

            manager.openCamera(id, deviceCallback, handler);
        } catch (CameraAccessException | RuntimeException e) {
            fail("Could not open camera: " + e.getMessage());
        }
    }

    public void takePhoto() {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> {
            if (device == null || session == null || reader == null) {
                ui(() -> listener.onError("Camera not ready"));
                return;
            }
            try {
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(reader.getSurface());
                b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
                settings.applyTo(b);
                session.capture(b.build(), null, handler);
                ui(() -> listener.onStatus("Capturing..."));
            } catch (CameraAccessException | IllegalStateException e) {
                fail("Capture failed: " + e.getMessage());
            }
        });
    }

    public void close() {
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        stopThread();
    }

    // ---- internals ----

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cam) {
            device = cam;
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice cam) {
            cam.close();
            device = null;
            fail("Camera disconnected");
        }

        @Override
        public void onError(CameraDevice cam, int error) {
            cam.close();
            device = null;
            fail("Camera error " + error);
        }
    };

    private void createSession() {
        try {
            device.createCaptureSession(
                    Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            fail("Camera session failed");
                        }
                    },
                    handler);
        } catch (CameraAccessException | IllegalStateException e) {
            fail("Session error: " + e.getMessage());
        }
    }

    private void startPreview() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            settings.applyTo(b);
            session.setRepeatingRequest(b.build(), null, handler);
            ui(() -> listener.onStatus("Ready"));
        } catch (CameraAccessException | IllegalStateException e) {
            fail("Preview failed: " + e.getMessage());
        }
    }

    private void onImageAvailable(ImageReader r) {
        try (Image image = r.acquireNextImage()) {
            if (image == null) return;
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            String name = PhotoSaver.save(context, bytes);
            ui(() -> listener.onPhotoSaved(name));
        } catch (Exception e) {
            fail("Save failed: " + e.getMessage());
        }
    }

    private String findBackCamera() throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    /** Largest 4:3 size under maxArea (0 = no limit). Falls back to the largest size if none is 4:3. */
    private static Size pick43(Size[] sizes, long maxArea) {
        Size best = null;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            boolean is43 = Math.abs((double) s.getWidth() / s.getHeight() - 4.0 / 3.0) < 0.01;
            if (!is43 || (maxArea > 0 && area > maxArea)) continue;
            if (best == null || area > (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best == null) {
            best = sizes[0];
            for (Size s : sizes) {
                if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
            }
        }
        return best;
    }

    private void startThread() {
        thread = new HandlerThread("camera");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private void stopThread() {
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private void ui(Runnable r) {
        main.post(r);
    }

    private void fail(String message) {
        ui(() -> listener.onError(message));
    }
}
