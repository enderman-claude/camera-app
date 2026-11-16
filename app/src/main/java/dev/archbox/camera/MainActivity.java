package dev.archbox.camera;

import android.Manifest;
import android.app.Activity;
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

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int REQ_CAMERA = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CaptureSettings settings;
    private CameraController camera;
    private float density;

    private FrameLayout viewfinder;
    private int viewfinderWidth;
    private TextureView preview;
    private View flash;
    private GridView grid;
    private HistogramView hist;
    private SettingsPanel panel;
    private ImageView thumb;
    private ShutterButton shutter;
    private LinearLayout controls;
    private TextView status;

    private SurfaceTexture surface;
    private int latestShotId = -1;
    private boolean countingDown;
    private int countdownLeft;

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

    private final CameraController.Listener listener = new CameraController.Listener() {
        @Override
        public void onStatus(String text) {
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
            status.setText(message);
            refreshShutter();
        }
    };

    private final SettingsPanel.Callback settingsCallback = new SettingsPanel.Callback() {
        @Override
        public void onChanged(boolean needsReopen) {
            settings.save(MainActivity.this);
            applyViewfinderAspect();
            grid.setMode(settings.grid);
            applyTheme();
            scheduleHistogram();
            if (needsReopen && surface != null && hasCameraPermission()) {
                camera.open(surface);
            } else {
                camera.reapply();
            }
            refreshShutter();
        }

        @Override
        public void onClose() {
            panel.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        settings = CaptureSettings.load(this);
        camera = new CameraController(this, listener, settings);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // viewfinder: preview, flash, grid, histogram, settings overlay, gear button
        viewfinder = new FrameLayout(this);
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

        panel = new SettingsPanel(this, settings, camera, settingsCallback);
        panel.setVisibility(View.GONE);
        viewfinder.addView(panel, matchParent());

        Button gear = new Button(this);
        gear.setText("Settings");
        gear.setOnClickListener(v -> toggleSettings());
        FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        gearLp.setMargins(dp(8), dp(8), dp(8), dp(8));
        viewfinder.addView(gear, gearLp);

        viewfinder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            viewfinderWidth = r - l;
            applyViewfinderAspect();
        });
        root.addView(viewfinder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // bottom controls: status, then [last shot] [shutter] [spacer]
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(8), dp(16), dp(32));

        status = new TextView(this);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, 0, 0, dp(12));
        status.setText("Starting...");
        controls.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF222222);
        thumb.setAlpha(0f);
        thumb.setContentDescription("Last photo");
        row.addView(thumb, new LinearLayout.LayoutParams(dp(56), dp(56)));

        row.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> onShutterPressed());
        row.addView(shutter, new LinearLayout.LayoutParams(dp(72), dp(72)));

        row.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(56), dp(56)));

        controls.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        applyTheme();
        scheduleHistogram();
        refreshShutter();
    }

    // ---- shutter ----

    private void onShutterPressed() {
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

    /**
     * Optimistic shot: the flash, the last preview frame and "Saving..." appear at
     * once. The real result replaces them when the JPEG is written.
     */
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
        controls.setBackgroundColor(settings.darkTheme ? 0xB3000000 : 0xE6FFFFFF);
        status.setTextColor(settings.darkTheme ? Color.WHITE : Color.BLACK);
    }

    private void toggleSettings() {
        if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
        } else {
            panel.rebuild();
            panel.setVisibility(View.VISIBLE);
        }
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
