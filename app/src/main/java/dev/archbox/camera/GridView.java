package dev.archbox.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Composition overlay drawn over the viewfinder. */
public class GridView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private CaptureSettings.Grid mode = CaptureSettings.Grid.OFF;

    public GridView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint.setColor(0x99FFFFFF);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setStyle(Paint.Style.STROKE);
    }

    public void setMode(CaptureSettings.Grid m) {
        mode = m;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (mode == CaptureSettings.Grid.THIRDS) {
            c.drawLine(w / 3, 0, w / 3, h, paint);
            c.drawLine(2 * w / 3, 0, 2 * w / 3, h, paint);
            c.drawLine(0, h / 3, w, h / 3, paint);
            c.drawLine(0, 2 * h / 3, w, 2 * h / 3, paint);
        } else if (mode == CaptureSettings.Grid.GOLDEN) {
            c.drawLine(w * 0.382f, 0, w * 0.382f, h, paint);
            c.drawLine(w * 0.618f, 0, w * 0.618f, h, paint);
            c.drawLine(0, h * 0.382f, w, h * 0.382f, paint);
            c.drawLine(0, h * 0.618f, w, h * 0.618f, paint);
        } else if (mode == CaptureSettings.Grid.SQUARE) {
            float side = Math.min(w, h);
            float left = (w - side) / 2;
            float top = (h - side) / 2;
            c.drawRect(left, top, left + side, top + side, paint);
        } else if (mode == CaptureSettings.Grid.CROSS) {
            float arm = 30 * density;
            c.drawLine(w / 2 - arm, h / 2, w / 2 + arm, h / 2, paint);
            c.drawLine(w / 2, h / 2 - arm, w / 2, h / 2 + arm, paint);
        }
    }
}
