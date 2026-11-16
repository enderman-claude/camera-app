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
import android.hardware.camera2.CaptureFailure;
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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Camera2 pipeline for one back camera: a 4:3 preview and 4:3 JPEG stills.
 *
 * Shots are numbered. takePhoto() returns the number immediately so the UI can
 * show the shot before the sensor has finished; the result arrives later through
 * onPhotoSaved or onShotFailed. Up to MAX_IN_FLIGHT shots can be pending at once.
 * Camera work runs on a background thread; listener callbacks run on the main thread.
 */
public class CameraController {

    public interface Listener {
        void onStatus(String text);
        void onPhotoSaved(int shotId, String displayName);
        void onShotFailed(int shotId, String message);
        void onError(String message);
    }

    private static final long PREVIEW_MAX_AREA = 1920L * 1440L;
    private static final int MAX_IN_FLIGHT = 3;

    private final Context context;
    private final CameraManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CaptureSettings settings = new CaptureSettings();

    private final AtomicInteger nextShotId = new AtomicInteger(1);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    /** Shot ids in capture order. Only touched on the camera thread. */
    private final ArrayDeque<Integer> pendingIds = new ArrayDeque<>();

    private HandlerThread thread;
    private volatile Handler handler;

    private volatile CameraDevice device;
    private volatile CameraCaptureSession session;
    private volatile ImageReader reader;
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

    public int inFlight() {
        return inFlight.get();
    }

    public boolean canShoot() {
        return inFlight.get() < MAX_IN_FLIGHT;
    }

    @SuppressLint("MissingPermission")
    public void open(SurfaceTexture texture) {
        close();
        surfaceTexture = texture;
        startThread();
        try {
            String id = findBackCamera();
            if (id == null) {
                fail("No back camera found");
                return;
            }
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size preview = pick43(map.getOutputSizes(SurfaceTexture.class), PREVIEW_MAX_AREA);
            Size still = pick43(map.getOutputSizes(ImageFormat.JPEG), 0);

            surfaceTexture.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
            previewSurface = new Surface(surfaceTexture);
            reader = ImageReader.newInstance(still.getWidth(), still.getHeight(), ImageFormat.JPEG, MAX_IN_FLIGHT);
            reader.setOnImageAvailableListener(this::onImageAvailable, handler);

            manager.openCamera(id, deviceCallback, handler);
        } catch (CameraAccessException | RuntimeException e) {
            fail("Could not open camera: " + e.getMessage());
        }
    }

    /**
     * Queues a still. Returns the shot id at once, or -1 if the camera is not ready
     * or too many shots are already pending.
     */
    public int takePhoto() {
        Handler h = handler;
        if (h == null || device == null || session == null || reader == null) {
            fail("Camera not ready");
            return -1;
        }
        if (!canShoot()) return -1;

        int id = nextShotId.getAndIncrement();
        inFlight.incrementAndGet();
        h.post(() -> submit(id));
        return id;
    }

    public void close() {
        Handler h = handler;
        if (h != null) {
            h.post(this::cancelPending);
        }
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        stopThread();
    }

    // ---- camera thread ----

    private void submit(int id) {
        try {
            if (device == null || session == null || reader == null) {
                throw new IllegalStateException("camera closed");
            }
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
            settings.applyTo(b);
            b.setTag(id);
            pendingIds.add(id);
            session.capture(b.build(), captureCallback, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            pendingIds.remove(id);
            finish(id, "Capture failed: " + e.getMessage(), null);
        }
    }

    private void cancelPending() {
        for (Integer id : pendingIds) {
            int shot = id;
            ui(() -> listener.onShotFailed(shot, "Cancelled"));
        }
        pendingIds.clear();
        inFlight.set(0);
    }

    private void onImageAvailable(ImageReader r) {
        Integer polled = pendingIds.poll();
        int id = polled != null ? polled : -1;
        try (Image image = r.acquireNextImage()) {
            if (image == null) {
                finish(id, "No image from camera", null);
                return;
            }
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            String name = PhotoSaver.save(context, bytes);
            finish(id, null, name);
        } catch (Exception e) {
            finish(id, "Save failed: " + e.getMessage(), null);
        }
    }

    private void finish(int id, String error, String savedName) {
        inFlight.updateAndGet(n -> Math.max(0, n - 1));
        if (error != null) {
            ui(() -> listener.onShotFailed(id, error));
        } else {
            ui(() -> listener.onPhotoSaved(id, savedName));
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
            Object tag = request.getTag();
            if (tag instanceof Integer) {
                int id = (Integer) tag;
                pendingIds.remove(id);
                finish(id, "Shot failed", null);
            }
        }
    };

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
