package dev.archbox.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Camera screen, top to bottom: status bar, viewfinder (sized to the chosen aspect ratio),
 * empty space, shutter row. Settings live on their own screen.
 */
public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int REQ_CAMERA = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CaptureSettings settings;
    private CameraController camera;
    private float density;

    private LinearLayout root;
    private TextView status;
    private FrameLayout viewfinder;
    private int viewfinderWidth;
    private TextureView preview;
    private View flash;
    private GridView grid;
    private HistogramView hist;
    private ImageView thumb;
    private ShutterButton shutter;
    private LinearLayout controls;

    private SurfaceTexture surface;
    private int latestShotId = -1;
    private boolean countingDown;
    private int countdownLeft;
    private boolean videoMode;
    private Button modeButton;
    private long recordStartMs;
    private boolean stopping;

    private final Runnable countdownTick = this::tickCountdown;
    private final Runnable histogramTick = new Runnable() {
        @Override
        public void run() {
            if (!settings.histogram || preview == null) return;
            Bitmap sample = preview.getBitmap(64, 48);
            if (sample != null) {
                hist.setSample(sample);
                sample.recycle();
            }
            main.postDelayed(this, 500);
        }
    };

    /** Shows elapsed time while recording, in the status line. */
    private final Runnable recordTick = new Runnable() {
        @Override
        public void run() {
            if (!camera.isRecording() || stopping) return;
            long secs = (System.currentTimeMillis() - recordStartMs) / 1000;
            status.setText("\u25CF REC " + String.format("%02d:%02d", secs / 60, secs % 60));
            main.postDelayed(this, 500);
        }
    };

    private final CameraController.Listener listener = new CameraController.Listener() {
        @Override
        public void onStatus(String text) {
            if ("Recording".equals(text)) {
                startRecordingUi();
                return;
            }
            if (stopping) stopRecordingUi();
            status.setText(text);
        }

        @Override
        public void onPhotoSaved(int shotId, String displayName) {
            if (shotId == latestShotId) {
                thumb.animate().alpha(1f).setDuration(150).start();
            }
            status.setText(camera.inFlight() > 0 ? "Saving..." : "Saved " + displayName);
            refreshShutter();
        }

        @Override
        public void onShotFailed(int shotId, String message) {
            if (shotId == latestShotId) {
                thumb.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> thumb.setImageDrawable(null)).start();
            }
            status.setText(message);
            refreshShutter();
        }

        @Override
        public void onError(String message) {
            if (stopping) stopRecordingUi();
            status.setText(message);
            refreshShutter();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        settings = CaptureSettings.load(this);
        camera = new CameraController(this, listener, settings);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // top bar: status and settings
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(8), dp(8), dp(8));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setText("Starting...");
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button settingsButton = new Button(this);
        settingsButton.setText("Settings");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settingsButton);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // viewfinder: preview, flash, grid, histogram
        viewfinder = new FrameLayout(this);
        viewfinder.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        preview.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && v.getWidth() > 0 && v.getHeight() > 0) {
                onTapFocus(e.getX() / v.getWidth(), e.getY() / v.getHeight());
            }
            return true;
        });
        viewfinder.addView(preview, matchParent());

        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0f);
        viewfinder.addView(flash, matchParent());

        grid = new GridView(this);
        grid.setMode(settings.grid);
        viewfinder.addView(grid, matchParent());

        hist = new HistogramView(this);
        FrameLayout.LayoutParams histLp = new FrameLayout.LayoutParams(dp(120), dp(60), Gravity.BOTTOM | Gravity.END);
        histLp.setMargins(dp(12), dp(12), dp(12), dp(12));
        viewfinder.addView(hist, histLp);

        viewfinder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            viewfinderWidth = r - l;
            applyViewfinderAspect();
        });
        root.addView(viewfinder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // flexible space keeps the shutter row at the bottom
        root.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // shutter row: [last shot] [shutter] [spacer the same width as the thumbnail]
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(24), dp(16), dp(24), dp(40));

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF222222);
        thumb.setAlpha(0f);
        thumb.setContentDescription("Last photo");
        controls.addView(thumb, new LinearLayout.LayoutParams(dp(56), dp(56)));

        controls.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> onShutterPressed());
        controls.addView(shutter, new LinearLayout.LayoutParams(dp(72), dp(72)));

        controls.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeButton = new Button(this);
        modeButton.setText("Video");
        modeButton.setTextSize(12);
        modeButton.setOnClickListener(v -> toggleMode());
        controls.addView(modeButton, new LinearLayout.LayoutParams(dp(64), dp(56)));
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        applyTheme();
        scheduleHistogram();
        refreshShutter();
    }

    // ---- shutter ----

    private void onShutterPressed() {
        if (videoMode) {
            if (camera.isRecording()) {
                stopping = true;
                camera.stopVideo();
                status.setText("Saving video...");
            } else {
                camera.startVideo();
            }
            return;
        }
        if (countingDown) {
            cancelCountdown();
            return;
        }
        if (settings.timerSeconds > 0) {
            countingDown = true;
            countdownLeft = settings.timerSeconds;
            tickCountdown();
            return;
        }
        onShutter();
    }

    /** Red mode button and a live timer, so recording is obvious at a glance. */
    private void startRecordingUi() {
        recordStartMs = System.currentTimeMillis();
        stopping = false;
        modeButton.setBackgroundColor(0xFFC62828);
        modeButton.setTextColor(Color.WHITE);
        main.removeCallbacks(recordTick);
        main.post(recordTick);
    }

    private void stopRecordingUi() {
        stopping = false;
        main.removeCallbacks(recordTick);
        modeButton.setBackgroundColor(0x00000000);
        modeButton.setTextColor(Color.WHITE);
        modeButton.setBackgroundResource(android.R.drawable.btn_default);
    }

    /** Switches between photo and video. Not allowed while recording. */
    private void toggleMode() {
        if (camera.isRecording()) return;
        videoMode = !videoMode;
        modeButton.setText(videoMode ? "Photo" : "Video");
        status.setText(videoMode ? "Video mode" : "Photo mode");
    }

    private void tickCountdown() {
        if (!countingDown) return;
        if (countdownLeft == 0) {
            countingDown = false;
            onShutter();
            return;
        }
        status.setText(String.valueOf(countdownLeft));
        countdownLeft--;
        main.postDelayed(countdownTick, 1000);
    }

    private void cancelCountdown() {
        countingDown = false;
        main.removeCallbacks(countdownTick);
        status.setText("Timer cancelled");
    }

    /** Optimistic shot: flash, last preview frame and "Saving..." appear at once. */
    private void onShutter() {
        int id = camera.takePhoto();
        if (id < 0) {
            if (!camera.canShoot()) status.setText("Still saving, one moment");
            return;
        }
        latestShotId = id;

        flash.animate().cancel();
        flash.setAlpha(0.7f);
        flash.animate().alpha(0f).setDuration(220).start();

        Bitmap frame = preview.getBitmap(192, 256);
        if (frame != null) thumb.setImageBitmap(frame);
        thumb.animate().cancel();
        thumb.setAlpha(0.5f);

        status.setText("Saving...");
        refreshShutter();
    }

    private void onTapFocus(float nx, float ny) {
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return;
        settings.manualFocus = false;
        settings.save(this);
        camera.tapFocus(nx, ny);
        status.setText("Focusing...");
    }

    private void refreshShutter() {
        shutter.setAlpha(camera != null && !camera.canShoot() ? 0.4f : 1f);
    }

    // ---- layout and theme ----

    private void applyViewfinderAspect() {
        if (viewfinder == null || viewfinderWidth <= 0) return;
        ViewGroup.LayoutParams lp = viewfinder.getLayoutParams();
        int want = Math.round(viewfinderWidth * settings.aspect.ratio);
        if (lp.height != want) {
            lp.height = want;
            viewfinder.setLayoutParams(lp);
        }
    }

    private void applyTheme() {
        boolean dark = settings.darkTheme;
        controls.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
        root.setBackgroundColor(dark ? Color.BLACK : 0xFFF2F2F2);
        status.setTextColor(dark ? Color.WHITE : Color.BLACK);
        grid.setMode(settings.grid);
    }

    private void scheduleHistogram() {
        main.removeCallbacks(histogramTick);
        hist.setVisibility(settings.histogram ? View.VISIBLE : View.GONE);
        if (settings.histogram) main.post(histogramTick);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    // ---- volume keys ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (settings.volumeShutter && isVolumeKey(keyCode)) {
            if (event.getRepeatCount() == 0) onShutterPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (settings.volumeShutter && isVolumeKey(keyCode)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    // ---- lifecycle and permission ----

    @Override
    protected void onResume() {
        super.onResume();
        // settings may have changed on the settings screen
        settings.reload(this);
        applyTheme();
        applyViewfinderAspect();
        scheduleHistogram();
        if (!hasCameraPermission()) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, REQ_CAMERA);
            return;
        }
        if (surface != null) camera.open(surface);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(histogramTick);
        cancelCountdown();
        camera.close();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (surface != null) camera.open(surface);
        } else {
            status.setText("Camera permission is required");
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // ---- TextureView callbacks ----

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = st;
        if (hasCameraPermission()) camera.open(st);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        camera.close();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }
}
