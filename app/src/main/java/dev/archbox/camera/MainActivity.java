package dev.archbox.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int REQ_CAMERA = 1;

    private float density;
    private FrameLayout viewfinder;
    private TextureView preview;
    private View flash;
    private ImageView thumb;
    private ShutterButton shutter;
    private TextView status;

    private CameraController camera;
    private SurfaceTexture surface;
    private int latestShotId = -1;

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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // viewfinder: preview with a flash overlay on top, kept at 3:4 (portrait)
        viewfinder = new FrameLayout(this);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        viewfinder.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0f);
        viewfinder.addView(flash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        viewfinder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = (r - l) * 4 / 3;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (r - l > 0 && lp.height != want) {
                lp.height = want;
                v.setLayoutParams(lp);
            }
        });
        root.addView(viewfinder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // bottom controls: status line, then [last shot] [shutter] [spacer]
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), 0, dp(16), dp(32));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, 0, 0, dp(16));
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

        row.addView(new View(this), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> onShutter());
        row.addView(shutter, new LinearLayout.LayoutParams(dp(72), dp(72)));

        row.addView(new View(this), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // empty slot the same width as the thumbnail, so the shutter stays centred
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(56), dp(56)));

        controls.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        camera = new CameraController(this, listener);
        refreshShutter();
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

    private void refreshShutter() {
        shutter.setAlpha(camera != null && !camera.canShoot() ? 0.4f : 1f);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasCameraPermission()) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, REQ_CAMERA);
            return;
        }
        if (surface != null) camera.open(surface);
    }

    @Override
    protected void onPause() {
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
