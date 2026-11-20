package dev.archbox.camera;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
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

/** Full-screen settings. Each change is saved straight away and picked up when the camera screen resumes. */
public class SettingsActivity extends Activity {

    private interface BoolAction { void run(boolean value); }
    private interface IntAction { void run(int value); }

    private static final String[] WB_LABELS = { "Auto", "Daylight", "Cloudy", "Tungsten", "Fluorescent", "Kelvin" };
    private static final String[] GRID_LABELS = { "Off", "Rule of thirds", "Golden ratio", "Square", "Centre cross" };
    private static final String[] TIMER_LABELS = { "Off", "2 s", "5 s", "10 s" };
    private static final int[] TIMER_VALUES = { 0, 2, 5, 10 };
    private static final int ACCENT = 0xFF8AB4F8;

    private CaptureSettings s;
    private CameraController.Limits lim;
    private float density;
    private LinearLayout col;
    private TextView focusReadout;
    private TextView evReadout;
    private SeekBar kelvinBar;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        s = CaptureSettings.load(this);
        lim = CameraController.queryLimits(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(12), dp(20), dp(32));
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        build();
        setContentView(scroll);
    }

    private void build() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button done = new Button(this);
        done.setText("Done");
        done.setOnClickListener(v -> finish());
        top.addView(done);
        col.addView(top);

        header("Focus");
        Switch manual = toggle("Manual focus", s.manualFocus, v -> {
            s.manualFocus = v;
            save();
        });
        manual.setEnabled(lim.hasManualFocus);
        focusReadout = body(focusText());
        col.addView(focusReadout);
        col.addView(seek(0, 1000, Math.round(s.focusPosition * 1000), v -> {
            s.focusPosition = v / 1000f;
            focusReadout.setText(focusText());
            save();
        }));
        col.addView(note("Tap the viewfinder to focus on a point. That turns manual focus off."));

        header("Exposure");
        evReadout = body(evText());
        col.addView(evReadout);
        col.addView(seek(lim.evMin, lim.evMax, s.evSteps, v -> {
            s.evSteps = v;
            evReadout.setText(evText());
            save();
        }));

        header("White balance");
        col.addView(spinner(WB_LABELS, s.whiteBalance.ordinal(), i -> {
            CaptureSettings.WhiteBalance w = CaptureSettings.WhiteBalance.values()[i];
            if (w == s.whiteBalance) return;
            s.whiteBalance = w;
            kelvinBar.setEnabled(w == CaptureSettings.WhiteBalance.KELVIN);
            save();
        }));
        col.addView(body("Kelvin (approximate)"));
        kelvinBar = seek(2500, 8000, s.kelvin, v -> {
            s.kelvin = v;
            save();
        });
        kelvinBar.setEnabled(s.whiteBalance == CaptureSettings.WhiteBalance.KELVIN);
        col.addView(kelvinBar);

        header("Frame");
        col.addView(body("Aspect ratio"));
        String[] aspects = new String[CaptureSettings.Aspect.values().length];
        for (int i = 0; i < aspects.length; i++) aspects[i] = CaptureSettings.Aspect.values()[i].label;
        col.addView(spinner(aspects, s.aspect.ordinal(), i -> {
            CaptureSettings.Aspect a = CaptureSettings.Aspect.values()[i];
            if (a == s.aspect) return;
            s.aspect = a;
            save();
        }));
        col.addView(body("Video stabilisation"));
        CaptureSettings.Stabilisation[] stab = CaptureSettings.Stabilisation.values();
        String[] stabLabels = new String[stab.length];
        for (int i = 0; i < stab.length; i++) stabLabels[i] = stab[i].label;
        col.addView(spinner(stabLabels, s.stabilisation.ordinal(), i -> {
            if (stab[i] == s.stabilisation) return;
            s.stabilisation = stab[i];
            save();
        }));
        col.addView(body("JPEG quality"));
        col.addView(seek(50, 100, s.jpegQuality, v -> {
            s.jpegQuality = v;
            save();
        }));
        col.addView(body("Grid"));
        col.addView(spinner(GRID_LABELS, s.grid.ordinal(), i -> {
            CaptureSettings.Grid g = CaptureSettings.Grid.values()[i];
            if (g == s.grid) return;
            s.grid = g;
            save();
        }));

        header("Shooting");
        col.addView(body("Self-timer"));
        col.addView(spinner(TIMER_LABELS, indexOf(TIMER_VALUES, s.timerSeconds), i -> {
            if (TIMER_VALUES[i] == s.timerSeconds) return;
            s.timerSeconds = TIMER_VALUES[i];
            save();
        }));
        toggle("Volume keys take photos", s.volumeShutter, v -> {
            s.volumeShutter = v;
            save();
        });
        col.addView(body("File name prefix"));
        EditText prefix = new EditText(this);
        prefix.setTextColor(0xFFFFFFFF);
        prefix.setText(s.filenamePrefix);
        prefix.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { }

            @Override
            public void onTextChanged(CharSequence cs, int a, int b, int c) {
                String value = cs.toString().trim();
                s.filenamePrefix = value.isEmpty() ? "IMG" : value;
                save();
            }
        });
        col.addView(prefix);

        header("Display");
        toggle("Histogram", s.histogram, v -> {
            s.histogram = v;
            save();
        });
        toggle("Dark controls", s.darkTheme, v -> {
            s.darkTheme = v;
            save();
        });
    }

    private void save() {
        s.save(this);
    }

    private String focusText() {
        float d = s.focusPosition * lim.minFocusDiopters;
        if (!lim.hasManualFocus || d < 0.01f) return "Focus: infinity";
        return String.format(Locale.US, "Focus: %.2f m", 1f / d);
    }

    private String evText() {
        return String.format(Locale.US, "Exposure: %+.1f EV", s.evSteps * lim.evStep);
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return 0;
    }

    private void header(String title) {
        TextView t = new TextView(this);
        t.setText(title.toUpperCase(Locale.US));
        t.setTextColor(ACCENT);
        t.setTextSize(13);
        t.setPadding(0, dp(24), 0, dp(4));
        col.addView(t);
    }

    private TextView body(String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(16);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private TextView note(String value) {
        TextView t = body(value);
        t.setTextColor(0xFFBBBBBB);
        t.setTextSize(13);
        return t;
    }

    private Switch toggle(String label, boolean on, BoolAction action) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(0xFFFFFFFF);
        sw.setTextSize(16);
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((view, checked) -> action.run(checked));
        col.addView(sw);
        return sw;
    }

    private SeekBar seek(int min, int max, int value, IntAction action) {
        SeekBar bar = new SeekBar(this);
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
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
        sp.setSelection(selected, false);
        // Spinners report the current selection once on layout. Each action ignores unchanged values.
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
