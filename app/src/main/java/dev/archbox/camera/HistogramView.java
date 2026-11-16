package dev.archbox.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Arrays;

/** Small luminance histogram, computed from a low-resolution copy of the preview. */
public class HistogramView extends View {
    private static final int BINS = 64;
    private final int[] bins = new int[BINS];
    private final Paint bars = new Paint();
    private final Paint background = new Paint();
    private int maxBin = 1;

    public HistogramView(Context context) {
        super(context);
        background.setColor(0x99000000);
        bars.setColor(0xDDFFFFFF);
    }

    public void setSample(Bitmap sample) {
        Arrays.fill(bins, 0);
        int w = sample.getWidth();
        int h = sample.getHeight();
        int[] pixels = new int[w * h];
        sample.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int p : pixels) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            bins[lum * BINS / 256]++;
        }
        maxBin = 1;
        for (int v : bins) {
            if (v > maxBin) maxBin = v;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        c.drawRect(0, 0, w, h, background);
        float barWidth = w / BINS;
        for (int i = 0; i < BINS; i++) {
            float barHeight = h * bins[i] / (float) maxBin;
            c.drawRect(i * barWidth, h - barHeight, (i + 1) * barWidth, h, bars);
        }
    }
}
