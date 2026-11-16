package dev.archbox.camera;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * Settings overlay on top of the viewfinder. Rebuilt each time it opens so the
 * sliders match the camera's current limits. Every change is applied immediately.
 */
public class SettingsPanel extends ScrollView {

    public interface Callback {
        /** needsReopen is true when the change alters the stream size (aspect ratio). */
        void onChanged(boolean needsReopen);
        void onClose();
    }

    private interface BoolAction { void run(boolean value); }
    private interface IntAction { void run(int value); }

    private static final String[] WB_LABELS = { "Auto", "Daylight", "Cloudy", "Tungsten", "Fluorescent", "Kelvin" };
    private static final String[] GRID_LABELS = { "Off", "Rule of thirds", "Golden ratio", "Square", "Centre cross" };
    private static final String[] TIMER_LABELS = { "Off", "2 s", "5 s", "10 s" };
    private static final int[] TIMER_VALUES = { 0, 2, 5, 10 };

    private final CaptureSettings s;
    private final CameraController camera;
    private final Callback cb;
    private final float density;
    private final LinearLayout col;

    private TextView focusReadout;
    private TextView evReadout;
    private SeekBar kelvinBar;

    public SettingsPanel(Context context, CaptureSettings settings, CameraController camera, Callback cb) {
        super(context);
        this.s = settings;
        this.camera = camera;
        this.cb = cb;
        this.density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xE6000000);

        col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Rebuilds all controls from the current settings and camera limits. */
    public void rebuild() {
        col.removeAllViews();
        CameraController.Limits lim = camera.limits();

        header("Focus");
        Switch manual = toggle("Manual focus", s.manualFocus, v -> {
            s.manualFocus = v;
            changed(false);
        });
        manual.setEnabled(lim.hasManualFocus);
        focusReadout = text(focusText(lim));
        col.addView(focusReadout);
        col.addView(seek(0, 1000, Math.round(s.focusPosition * 1000), v -> {
            s.focusPosition = v / 1000f;
            focusReadout.setText(focusText(lim));
            changed(false);
        }));
        col.addView(text("Tap the viewfinder to focus on a point (turns manual focus off)."));

        header("Exposure");
        evReadout = text(evText(lim));
        col.addView(evReadout);
        col.addView(seek(lim.evMin, lim.evMax, s.evSteps, v -> {
            s.evSteps = v;
            evReadout.setText(evText(lim));
            changed(false);
        }));

        header("White balance");
        col.addView(spinner(WB_LABELS, s.whiteBalance.ordinal(), i -> {
            CaptureSettings.WhiteBalance w = CaptureSettings.WhiteBalance.values()[i];
            if (w == s.whiteBalance) return;
            s.whiteBalance = w;
            kelvinBar.setEnabled(w == CaptureSettings.WhiteBalance.KELVIN);
            changed(false);
        }));
        col.addView(text("Kelvin (approximate)"));
        kelvinBar = seek(2500, 8000, s.kelvin, v -> {
            s.kelvin = v;
            changed(false);
        });
        kelvinBar.setEnabled(s.whiteBalance == CaptureSettings.WhiteBalance.KELVIN);
        col.addView(kelvinBar);

        header("Frame");
        col.addView(text("Aspect ratio"));
        String[] aspects = new String[CaptureSettings.Aspect.values().length];
        for (int i = 0; i < aspects.length; i++) aspects[i] = CaptureSettings.Aspect.values()[i].label;
        col.addView(spinner(aspects, s.aspect.ordinal(), i -> {
            CaptureSettings.Aspect a = CaptureSettings.Aspect.values()[i];
            if (a == s.aspect) return;
            s.aspect = a;
            changed(true);
        }));
        col.addView(text("JPEG quality"));
        col.addView(seek(50, 100, s.jpegQuality, v -> {
            s.jpegQuality = v;
            changed(false);
        }));
        col.addView(text("Grid"));
        col.addView(spinner(GRID_LABELS, s.grid.ordinal(), i -> {
            s.grid = CaptureSettings.Grid.values()[i];
            changed(false);
        }));

        header("Shooting");
        col.addView(text("Self-timer"));
        col.addView(spinner(TIMER_LABELS, indexOf(TIMER_VALUES, s.timerSeconds), i -> {
            s.timerSeconds = TIMER_VALUES[i];
            changed(false);
        }));
        toggle("Volume keys take photos", s.volumeShutter, v -> {
            s.volumeShutter = v;
            changed(false);
        });
        col.addView(text("File name prefix"));
        EditText prefix = new EditText(getContext());
        prefix.setTextColor(0xFFFFFFFF);
        prefix.setText(s.filenamePrefix);
        prefix.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { }
            @Override
            public void onTextChanged(CharSequence cs, int a, int b, int c) {
                String value = cs.toString().trim();
                s.filenamePrefix = value.isEmpty() ? "IMG" : value;
                changed(false);
            }
        });
        col.addView(prefix);

        header("Display");
        toggle("Histogram", s.histogram, v -> {
            s.histogram = v;
            changed(false);
        });
        toggle("Dark controls", s.darkTheme, v -> {
            s.darkTheme = v;
            changed(false);
        });

        Button done = new Button(getContext());
        done.setText("Done");
        done.setOnClickListener(v -> cb.onClose());
        col.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void changed(boolean needsReopen) {
        cb.onChanged(needsReopen);
    }

    private String focusText(CameraController.Limits lim) {
        float d = s.focusPosition * lim.minFocusDiopters;
        if (!lim.hasManualFocus || d < 0.01f) return "Focus: infinity";
        return String.format(Locale.US, "Focus: %.2f m", 1f / d);
    }

    private String evText(CameraController.Limits lim) {
        return String.format(Locale.US, "Exposure: %+.1f EV", s.evSteps * lim.evStep);
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return 0;
    }

    private void header(String title) {
        TextView t = new TextView(getContext());
        t.setText(title.toUpperCase(Locale.US));
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setPadding(0, dp(20), 0, dp(6));
        col.addView(t);
    }

    private TextView text(String value) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(14);
        return t;
    }

    private Switch toggle(String label, boolean on, BoolAction action) {
        Switch sw = new Switch(getContext());
        sw.setText(label);
        sw.setTextColor(0xFFFFFFFF);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((view, checked) -> action.run(checked));
        col.addView(sw);
        return sw;
    }

    private SeekBar seek(int min, int max, int value, IntAction action) {
        SeekBar bar = new SeekBar(getContext());
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) action.run(progress + min);
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        return bar;
    }

    private Spinner spinner(String[] labels, int selected, IntAction action) {
        Spinner sp = new Spinner(getContext());
        sp.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, labels));
        sp.setSelection(selected, false);
        // Spinners report the initial selection on layout. Each action ignores unchanged values.
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                action.run(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        return sp;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
