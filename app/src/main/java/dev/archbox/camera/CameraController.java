package dev.archbox.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Camera2 pipeline for one back camera. Preview and stills share the aspect ratio
 * chosen in settings. Shots are numbered: takePhoto() returns at once, and the result
 * arrives later via onPhotoSaved or onShotFailed. Camera work runs on a background
 * thread; listener callbacks run on the main thread.
 */
public class CameraController {

    public interface Listener {
        void onStatus(String text);
        void onPhotoSaved(int shotId, String displayName);
        void onShotFailed(int shotId, String message);
        void onError(String message);
    }

    /** What the open camera supports. Filled in by open(). */
    public static class Limits {
        public boolean hasManualFocus;
        /** Focus in diopters at the closest focus the lens supports. */
        public float minFocusDiopters;
        public int evMin = -4;
        public int evMax = 4;
        public double evStep = 1.0 / 3.0;
        public boolean hasVideoStabilisation;
        public boolean hasOpticalStabilisation;
        public boolean zoomSupported;
        public float minZoom = 1f;
        public float maxZoom = 1f;
    }

    private static final long PREVIEW_MAX_AREA = 1920L * 1440L;
    private static final int MAX_IN_FLIGHT = 3;

    private final Context context;
    private final CameraManager manager;
    private final Listener listener;
    private final CaptureSettings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Limits limits = new Limits();

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
    private Rect activeArray;

    // video
    private Size videoSize;
    private MediaRecorder recorder;
    private Surface recorderSurface;
    private VideoSaver videoOut;
    private volatile boolean recording;
    /** Shown instead of "Ready" once the photo preview comes back after a recording. */
    private volatile String pendingStatus;

    public CameraController(Context context, Listener listener, CaptureSettings settings) {
        this.context = context.getApplicationContext();
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.listener = listener;
        this.settings = settings;
    }

    public Limits limits() {
        return limits;
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
            activeArray = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            readLimits(limits, cc);

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            float ratio = settings.aspect.ratio;
            Size preview = pick(map.getOutputSizes(SurfaceTexture.class), ratio, PREVIEW_MAX_AREA);
            Size still = pick(map.getOutputSizes(ImageFormat.JPEG), ratio, 0);
            videoSize = pick(map.getOutputSizes(MediaRecorder.class), ratio, 1920L * 1440L);

            surfaceTexture.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
            previewSurface = new Surface(surfaceTexture);
            reader = ImageReader.newInstance(still.getWidth(), still.getHeight(), ImageFormat.JPEG, MAX_IN_FLIGHT);
            reader.setOnImageAvailableListener(this::onImageAvailable, handler);

            manager.openCamera(id, deviceCallback, handler);
        } catch (CameraAccessException | RuntimeException e) {
            fail("Could not open camera: " + e.getMessage());
        }
    }

    /** Re-sends the preview request so changed settings take effect. */
    public void reapply() {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> {
            if (device != null && session != null) startPreview(false);
        });
    }

    /**
     * Focuses and meters on a point. nx and ny are 0..1 across the viewfinder.
     * The sensor mapping for portrait is approximate and still needs testing on hardware.
     */
    public void tapFocus(float nx, float ny) {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> {
            if (device == null || session == null || activeArray == null || previewSurface == null) return;
            Rect a = activeArray;
            int w = a.width();
            int hgt = a.height();
            float sx;
            float sy;
            switch (sensorOrientation) {
                case 0:
                    sx = nx;
                    sy = ny;
                    break;
                case 180:
                    sx = 1 - nx;
                    sy = 1 - ny;
                    break;
                case 270:
                    sx = 1 - ny;
                    sy = nx;
                    break;
                default:
                    sx = ny;
                    sy = 1 - nx;
                    break;
            }
            int cx = a.left + Math.round(sx * (w - 1));
            int cy = a.top + Math.round(sy * (hgt - 1));
            int half = Math.max(16, w / 16);
            Rect r = new Rect(
                    Math.max(a.left, cx - half), Math.max(a.top, cy - half),
                    Math.min(a.right - 1, cx + half), Math.min(a.bottom - 1, cy + half));
            MeteringRectangle[] region = { new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX) };
            try {
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(previewSurface);
                settings.applyTo(b, limits);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_REGIONS, region);
                b.set(CaptureRequest.CONTROL_AE_REGIONS, region);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(b.build(), null, handler);
                startPreview(false);
            } catch (CameraAccessException | IllegalStateException e) {
                fail("Focus failed: " + e.getMessage());
            }
        });
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
        releaseRecorder();
        Handler h = handler;
        if (h != null) h.post(this::cancelPending);
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        stopThread();
    }

    // ---- camera thread ----

    static void readLimits(Limits target, CameraCharacteristics cc) {
        Float minFocus = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        target.minFocusDiopters = minFocus == null ? 0f : minFocus;
        target.hasManualFocus = target.minFocusDiopters > 0f;

        Range<Integer> ev = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (ev != null) {
            target.evMin = ev.getLower();
            target.evMax = ev.getUpper();
        }
        Rational step = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (step != null && step.getDenominator() != 0) target.evStep = step.doubleValue();

        target.hasVideoStabilisation = contains(
                cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        target.hasOpticalStabilisation = contains(
                cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);

        // Zoom ratio exists from Android 11 (API 30). minSdk is 29, so check first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Range<Float> zoom = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zoom != null) {
                target.zoomSupported = true;
                target.minZoom = zoom.getLower();
                target.maxZoom = zoom.getUpper();
            }
        }
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) return false;
        for (int v : values) if (v == target) return true;
        return false;
    }

    /** Limits of the back camera, read without opening it. Used by the settings screen. */
    public static Limits queryLimits(Context context) {
        Limits result = new Limits();
        try {
            CameraManager m = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : m.getCameraIdList()) {
                CameraCharacteristics cc = m.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    readLimits(result, cc);
                    break;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            // keep defaults
        }
        return result;
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * Starts a video. Recording needs a different session (preview plus recorder surface), so the
     * photo session is closed and rebuilt when recording stops.
     */
    public void startVideo() {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> {
            if (recording || device == null || session == null || videoSize == null) return;
            if (inFlight.get() > 0) {
                ui(() -> listener.onError("Still saving, one moment"));
                return;
            }
            try {
                videoOut = VideoSaver.begin(context, settings.filenamePrefix);
                recorder = new MediaRecorder();
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setOutputFile(videoOut.descriptor().getFileDescriptor());
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
                recorder.setVideoFrameRate(30);
                recorder.setVideoEncodingBitRate(16_000_000);
                recorder.setOrientationHint(sensorOrientation);
                recorder.prepare();
                recorderSurface = recorder.getSurface();
            } catch (IOException | RuntimeException e) {
                String message = "Could not start video: " + e.getMessage();
                releaseRecorder();
                ui(() -> listener.onError(message));
                return;
            }
            closeSession();
            try {
                device.createCaptureSession(
                        Arrays.asList(previewSurface, recorderSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                startRecordRequest();
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession s) {
                                releaseRecorder();
                                createSession();
                                ui(() -> listener.onError("Video session failed"));
                            }
                        },
                        handler);
            } catch (CameraAccessException | IllegalStateException e) {
                releaseRecorder();
                createSession();
                ui(() -> listener.onError("Video session error: " + e.getMessage()));
            }
        });
    }

    private void startRecordRequest() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(previewSurface);
            b.addTarget(recorderSurface);
            settings.applyTo(b, limits);
            applyVideoStabilisation(b);
            session.setRepeatingRequest(b.build(), null, handler);
            recorder.start();
            recording = true;
            ui(() -> listener.onStatus("Recording"));
        } catch (CameraAccessException | IllegalStateException e) {
            releaseRecorder();
            createSession();
            ui(() -> listener.onError("Could not record: " + e.getMessage()));
        }
    }

    /** Stops recording, keeps the file if it has content, and restores the photo session. */
    public void stopVideo() {
        Handler h = handler;
        if (h == null) return;
        h.post(() -> {
            if (!recording) return;
            boolean ok;
            try {
                recorder.stop();
                ok = true;
            } catch (RuntimeException e) {
                ok = false; // stop() throws when the recording is too short to contain data
            }
            VideoSaver out = videoOut;
            videoOut = null;
            releaseRecorder();
            if (ok && out != null) {
                out.finish(context);
                pendingStatus = "Saved " + out.name;
            } else {
                if (out != null) out.discard(context);
                pendingStatus = "Recording too short, discarded";
            }
            closeSession();
            createSession();
        });
    }

    private void applyVideoStabilisation(CaptureRequest.Builder b) {
        boolean wanted = settings.stabilisation != CaptureSettings.Stabilisation.OFF;
        boolean eis = wanted && limits.hasVideoStabilisation;
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                eis ? CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    : CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (limits.hasOpticalStabilisation) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    wanted ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                           : CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
    }

    private void closeSession() {
        CameraCaptureSession s = session;
        session = null;
        if (s != null) s.close();
    }

    /** Stops the recorder and removes any unfinished file. Safe to call at any time. */
    private void releaseRecorder() {
        recording = false;
        if (recorder != null) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
                // already stopped or never started
            }
            recorder = null;
        }
        if (recorderSurface != null) {
            recorderSurface.release();
            recorderSurface = null;
        }
        if (videoOut != null) {
            videoOut.discard(context);
            videoOut = null;
        }
    }

    private void submit(int id) {
        try {
            if (device == null || session == null || reader == null) {
                throw new IllegalStateException("camera closed");
            }
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
            settings.applyTo(b, limits);
            b.setTag(id);
            pendingIds.add(id);
            session.capture(b.build(), captureCallback, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            pendingIds.remove(id);
            finish(id, "Capture failed: " + e.getMessage(), null);
        }
    }

    private void startPreview(boolean announce) {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            settings.applyTo(b, limits);
            session.setRepeatingRequest(b.build(), null, handler);
            if (announce) {
                String text = pendingStatus != null ? pendingStatus : "Ready";
                pendingStatus = null;
                ui(() -> listener.onStatus(text));
            }
        } catch (CameraAccessException | IllegalStateException e) {
            fail("Preview failed: " + e.getMessage());
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
            String name = PhotoSaver.save(context, bytes, settings.filenamePrefix, settings);
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
                            startPreview(true);
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

    private String findBackCamera() throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    /**
     * Largest size with the requested ratio, under maxArea (0 = no limit).
     * If none matches, the size with the closest ratio is used.
     */
    private static Size pick(Size[] sizes, float ratio, long maxArea) {
        Size best = null;
        for (Size s : sizes) {
            if (Math.abs(ratioOf(s) - ratio) > 0.01f) continue;
            if (maxArea > 0 && area(s) > maxArea) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        if (best == null) {
            for (Size s : sizes) {
                if (best == null || closer(s, best, ratio)) best = s;
            }
        }
        return best;
    }

    private static boolean closer(Size a, Size b, float ratio) {
        float da = Math.abs(ratioOf(a) - ratio);
        float db = Math.abs(ratioOf(b) - ratio);
        if (Math.abs(da - db) > 0.01f) return da < db;
        return area(a) > area(b);
    }

    private static float ratioOf(Size s) {
        return (float) Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight());
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
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
