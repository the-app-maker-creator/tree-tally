package nz.ricksblock.tally;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Set-up screen: name, crew code, task, start/stop shift. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(14, 23, 18), PANEL = Color.rgb(23, 36, 28), FG = Color.rgb(242, 246, 242),
            MUTED = Color.rgb(169, 184, 173), GO = Color.rgb(63, 178, 107), STOP = Color.rgb(224, 119, 60);

    private EditText site, name, code;
    private TextView status, counts;
    private Button shift;
    private final List<Button> taskButtons = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); handler.postDelayed(this, 1000); } };

    private SharedPreferences prefs() { return Store.prefs(this); }

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private GradientDrawable box(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(FG);
        e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        e.setBackground(box(PANEL, 10));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setInputType(secret ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD) : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private Button button(String text, int color, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(box(color, 12));
        b.setPadding(dp(10), dp(14), dp(10), dp(14));
        return b;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        SharedPreferences p = prefs();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(24));
        col.setBackgroundColor(BG);

        col.addView(label("Tree Tally", 26, FG, true));
        col.addView(label("Clip the clicker to your front pocket. Start the shift, put the phone away, and click once at each tree.", 15, MUTED, false), gap(4));

        col.addView(label("Site address", 14, MUTED, false), gap(16));
        site = field("e.g. ricks-block.yourname.workers.dev", p.getString("site", ""), false);
        col.addView(site, gap(4));
        col.addView(label("Your name", 14, MUTED, false), gap(10));
        name = field("e.g. Hemi", p.getString("name", ""), false);
        col.addView(name, gap(4));
        col.addView(label("Crew code", 14, MUTED, false), gap(10));
        code = field("from Rick or Emile", p.getString("code", ""), true);
        col.addView(code, gap(4));

        col.addView(label("Task", 14, MUTED, false), gap(16));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        String current = p.getString("task", "plant");
        for (int i = 0; i < Store.TASKS.length; i++) {
            final String task = Store.TASKS[i];
            Button b = button(Store.TASK_NAMES[i], PANEL, FG);
            b.setTextSize(15);
            b.setTag(task);
            b.setOnClickListener(v -> { prefs().edit().putString("task", task).apply(); paintTasks(); });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f));
            lp.width = 0;
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(b, lp);
            taskButtons.add(b);
        }
        col.addView(grid, gap(4));
        paintTasks();

        shift = button("Start shift", GO, BG);
        shift.setTextSize(22);
        shift.setPadding(dp(10), dp(26), dp(10), dp(26));
        shift.setOnClickListener(v -> toggleShift());
        col.addView(shift, gap(18));

        status = label("", 15, MUTED, false);
        col.addView(status, gap(12));
        counts = label("", 18, FG, true);
        col.addView(counts, gap(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button tap = button("Count one tree", PANEL, FG);
        tap.setOnClickListener(v -> startService(new Intent(this, TallyService.class).setAction(TallyService.ACTION_TAP)));
        Button undo = button("Undo last", PANEL, FG);
        undo.setOnClickListener(v -> { Toast.makeText(this, Store.undoLast(this), Toast.LENGTH_SHORT).show(); refresh(); });
        Button send = button("Send now", PANEL, FG);
        send.setOnClickListener(v -> { saveFields(); prefs().edit().putString("sync", "Sending…").apply();
            new Thread(() -> prefs().edit().putString("sync", Store.upload(getApplicationContext())).apply()).start(); });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w.setMargins(dp(3), 0, dp(3), 0);
        row.addView(tap, w);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w2.setMargins(dp(3), 0, dp(3), 0);
        row.addView(undo, w2);
        LinearLayout.LayoutParams w3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        w3.setMargins(dp(3), 0, dp(3), 0);
        row.addView(send, w3);
        col.addView(row, gap(14));

        CheckBox beep = new CheckBox(this);
        beep.setText("Beep on each tree");
        beep.setTextColor(FG);
        beep.setChecked(p.getBoolean("beep", true));
        beep.setOnCheckedChangeListener((b, on) -> prefs().edit().putBoolean("beep", on).apply());
        col.addView(beep, gap(10));

        col.addView(label("Test before going out: start the shift, lock the screen and click. You should feel a buzz and hear a beep. "
                + "If the clicker has two buttons, use the one marked iOS. A double buzz means no GPS for that tree.", 13, MUTED, false), gap(8));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(col);
        setContentView(scroll);
    }

    private void paintTasks() {
        String current = prefs().getString("task", "plant");
        for (Button b : taskButtons) {
            boolean on = current.equals(b.getTag());
            b.setBackground(box(on ? GO : PANEL, 10));
            b.setTextColor(on ? BG : FG);
        }
    }

    private void saveFields() {
        prefs().edit()
                .putString("site", site.getText().toString().trim())
                .putString("name", name.getText().toString().trim())
                .putString("code", code.getText().toString().trim())
                .apply();
    }

    private void toggleShift() {
        saveFields();
        if (TallyService.running) {
            startService(new Intent(this, TallyService.class).setAction(TallyService.ACTION_STOP));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        if (name.getText().toString().trim().isEmpty() || code.getText().toString().trim().isEmpty() || site.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Fill in the site address, your name and the crew code first.", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), 1); return; }
        startForegroundService(new Intent(this, TallyService.class).setAction(TallyService.ACTION_START));
        handler.postDelayed(this::refresh, 300);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(new Intent(this, TallyService.class).setAction(TallyService.ACTION_START));
            handler.postDelayed(this::refresh, 300);
        } else {
            Toast.makeText(this, "The tally needs location to know where each tree is.", Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        SharedPreferences p = prefs();
        boolean on = TallyService.running;
        shift.setText(on ? "Stop shift" : "Start shift");
        shift.setBackground(box(on ? STOP : GO, 12));
        String gps;
        long at = p.getLong("gps_at", 0);
        if (!on) gps = "Shift not started";
        else if (at == 0) gps = "GPS: searching…";
        else if (System.currentTimeMillis() - at > 60_000) gps = "GPS: lost signal";
        else gps = "GPS: ±" + Math.round(p.getFloat("gps_acc", 0)) + " m";
        int waiting = Store.loadQueue(this).length();
        status.setText(gps + "   ·   " + (waiting > 0 ? waiting + " waiting to send" : "All sent") + "   ·   " + p.getString("sync", ""));
        StringBuilder sb = new StringBuilder("Today: ");
        boolean any = false;
        for (int i = 0; i < Store.TASKS.length; i++) {
            int n = Store.todayCount(this, Store.TASKS[i]);
            if (n > 0) { sb.append(any ? " · " : "").append(Store.TASK_NAMES[i]).append(" ").append(n); any = true; }
        }
        counts.setText(any ? sb.toString() : "Today: no trees yet");
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); saveFields(); }

    /** With the app open, the clicker also counts. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (TallyService.running && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_CAMERA)) {
            if (event.getRepeatCount() == 0)
                startService(new Intent(this, TallyService.class).setAction(TallyService.ACTION_TAP));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
