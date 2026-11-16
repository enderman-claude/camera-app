package dev.archbox.camera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Round shutter drawn in code: a white ring with a core that presses in on touch.
 * The press is animated and fires on release only if the finger is still on the button.
 */
public class ShutterButton extends View {
    private static final float PRESSED_SCALE = 0.82f;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private float coreScale = 1f;
    private ValueAnimator anim;
    private boolean pressInside;

    public ShutterButton(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setContentDescription("Shutter");

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4 * density);
        ring.setColor(Color.WHITE);

        core.setStyle(Paint.Style.FILL);
        core.setColor(Color.WHITE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(72 * density);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 4 * density; // ring is centred on this radius
        canvas.drawCircle(cx, cy, radius, ring);
        canvas.drawCircle(cx, cy, radius * 0.78f * coreScale, core);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressInside = true;
                animateCore(PRESSED_SCALE);
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean inside = e.getX() >= 0 && e.getX() <= getWidth()
                        && e.getY() >= 0 && e.getY() <= getHeight();
                if (inside != pressInside) {
                    pressInside = inside;
                    animateCore(inside ? PRESSED_SCALE : 1f);
                }
                return true;
            case MotionEvent.ACTION_UP:
                animateCore(1f);
                if (pressInside) performClick();
                pressInside = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                animateCore(1f);
                pressInside = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void animateCore(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(coreScale, target);
        anim.setDuration(80);
        anim.addUpdateListener(a -> {
            coreScale = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }
}
