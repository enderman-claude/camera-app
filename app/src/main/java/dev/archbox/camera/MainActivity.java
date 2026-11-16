package dev.archbox.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int REQ_CAMERA = 1;

    private TextureView preview;
    private TextView status;
    private CameraController camera;
    private SurfaceTexture surface;

    private final CameraController.Listener listener = new CameraController.Listener() {
        @Override public void onStatus(String text) { status.setText(text); }
        @Override public void onPhotoSaved(String displayName) { status.setText("Saved " + displayName); }
        @Override public void onError(String message) { status.setText(message); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        // Keep the viewfinder at 3:4 (portrait) to match the 4:3 stream.
        preview.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = (r - l) * 4 / 3;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (r - l > 0 && lp.height != want) {
                lp.height = want;
                v.setLayoutParams(lp);
            }
        });
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(0, 0, 0, 48);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setText("Starting...");
        bar.addView(status);

        Button shutter = new Button(this);
        shutter.setText("Shoot");
        shutter.setOnClickListener(v -> camera.takePhoto());
        bar.addView(shutter);

        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        camera = new CameraController(this, listener);
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
