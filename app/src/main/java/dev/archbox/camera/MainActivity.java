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
import android.widget.SeekBar;
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
    private LinearLayout modeStrip;
    private Button photoTab;
    private Button videoTab;
    private LinearLayout zoomRow;
    private SeekBar exposureBar;
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
            if ("Ready".equals(text)) rebuildCameraControls();
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

        // Viewfinder: messages, settings and camera controls sit on top of the preview,
        // so the screen is mostly picture.
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
        FrameLayout.LayoutParams histLp = new FrameLayout.LayoutParams(dp(120), dp(60), Gravity.TOP | Gravity.START);
        histLp.setMargins(dp(12), dp(56), dp(12), dp(12));
        viewfinder.addView(hist, histLp);

        // Status: the one place for messages (saves, errors, countdown, recording time).
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setBackgroundColor(0x99000000);
        status.setText("Starting...");
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusLp.setMargins(dp(96), dp(12), dp(96), 0);
        viewfinder.addView(status, statusLp);

        // Settings: top right, out of the way of the framing.
        Button settingsButton = new Button(this);
        settingsButton.setText("Settings");
        settingsButton.setTextSize(12);
        settingsButton.setTextColor(Color.WHITE);
        settingsButton.setBackgroundColor(0x99000000);
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        FrameLayout.LayoutParams settingsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        settingsLp.setMargins(0, dp(8), dp(8), 0);
        viewfinder.addView(settingsButton, settingsLp);

        // Bottom of the viewfinder: exposure slider, then zoom chips.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout exposureRow = new LinearLayout(this);
        exposureRow.setOrientation(LinearLayout.HORIZONTAL);
        exposureRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView evLabel = new TextView(this);
        evLabel.setText("EV");
        evLabel.setTextColor(Color.WHITE);
        evLabel.setTextSize(12);
        exposureRow.addView(evLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        exposureBar = new SeekBar(this);
        exposureRow.addView(exposureBar, new LinearLayout.LayoutParams(
                dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(exposureRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams zoomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zoomLp.topMargin = dp(8);
        bottom.addView(zoomRow, zoomLp);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottomLp.setMargins(0, 0, 0, dp(12));
        viewfinder.addView(bottom, bottomLp);

        viewfinder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            viewfinderWidth = r - l;
            applyViewfinderAspect();
        });
        root.addView(viewfinder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // flexible space keeps the controls at the bottom
        root.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Mode strip: PHOTO | VIDEO, always visible, directly above the shutter.
        modeStrip = new LinearLayout(this);
        modeStrip.setOrientation(LinearLayout.HORIZONTAL);
        modeStrip.setGravity(Gravity.CENTER);
        modeStrip.setPadding(0, dp(8), 0, 0);
        photoTab = modeTab("PHOTO");
        videoTab = modeTab("VIDEO");
        photoTab.setOnClickListener(v -> setVideoMode(false));
        videoTab.setOnClickListener(v -> setVideoMode(true));
        modeStrip.addView(photoTab, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        modeStrip.addView(videoTab, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(modeStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // shutter row: [last shot] [shutter] [spacer the same width as the thumbnail]
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(24), dp(12), dp(24), dp(40));

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
        controls.addView(new View(this), new LinearLayout.LayoutParams(dp(56), dp(56)));
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        applyTheme();
        rebuildCameraControls();
        setVideoMode(false);
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
        videoTab.setBackgroundColor(0xFFC62828);
        videoTab.setTextColor(Color.WHITE);
        main.removeCallbacks(recordTick);
        main.post(recordTick);
    }

    private void stopRecordingUi() {
        stopping = false;
        main.removeCallbacks(recordTick);
        styleTab(videoTab, videoMode);
    }

    /** Selects photo or video. Locked while recording, so the shutter can't change meaning mid-take. */
    private void setVideoMode(boolean video) {
        if (camera.isRecording()) return;
        videoMode = video;
        styleTab(photoTab, !video);
        styleTab(videoTab, video);
        status.setText(video ? "Video mode" : "Photo mode");
    }

    private Button modeTab(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        return b;
    }

    /** Selected tab is solid white; the other is plain text. */
    private void styleTab(Button b, boolean selected) {
        b.setBackgroundColor(selected ? 0xFFFFFFFF : 0x00000000);
        b.setTextColor(selected ? Color.BLACK : 0xFFAAAAAA);
    }

    /** Zoom chips and the EV slider, set up once the camera reports its limits. */
    private void rebuildCameraControls() {
        final CameraController.Limits lim = camera.limits();
        zoomRow.removeAllViews();
        float[] presets = {0.5f, 1f, 2f, 4f};
        for (float z : presets) {
            if (!lim.zoomSupported || z < lim.minZoom - 0.001f || z > lim.maxZoom + 0.001f) continue;
            Button chip = new Button(this);
            chip.setText((z == Math.floor(z) ? String.valueOf((int) z) : String.valueOf(z)) + "x");
            chip.setTextSize(13);
            chip.setTag(z);
            chip.setOnClickListener(v -> {
                settings.zoom = z;
                camera.reapply();
                updateZoomChips();
            });
            zoomRow.addView(chip, new LinearLayout.LayoutParams(dp(60), dp(40)));
        }
        updateZoomChips();

        exposureBar.setOnSeekBarChangeListener(null);
        exposureBar.setMax(lim.evMax - lim.evMin);
        exposureBar.setProgress(settings.evSteps - lim.evMin);
        exposureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                settings.evSteps = progress + lim.evMin;
                settings.save(MainActivity.this);
                camera.reapply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void updateZoomChips() {
        for (int i = 0; i < zoomRow.getChildCount(); i++) {
            Button chip = (Button) zoomRow.getChildAt(i);
            boolean selected = Math.abs((Float) chip.getTag() - settings.zoom) < 0.01f;
            chip.setBackgroundColor(selected ? 0xFFFFFFFF : 0x99000000);
            chip.setTextColor(selected ? Color.BLACK : Color.WHITE);
        }
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
        if (modeStrip != null) modeStrip.setBackgroundColor(dark ? Color.BLACK : 0xFFF2F2F2);
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
