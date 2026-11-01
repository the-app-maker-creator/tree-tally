package nz.ricksblock.tally;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Keeps taps on the phone until they are sent to the site. */
public final class Store {
    private Store() {}

    public static final String[] TASKS = {"plant", "prune", "release", "thin", "blank", "other"};
    public static final String[] TASK_NAMES = {"Planting", "Pruning", "Releasing", "Thinning", "Gap / dead", "Other"};

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tally", Context.MODE_PRIVATE);
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device", null);
        if (id == null) {
            id = Long.toString(Math.abs(new Random().nextLong()), 36);
            if (id.length() > 8) id = id.substring(0, 8);
            p.edit().putString("device", id).apply();
        }
        return id;
    }

    public static String taskName(String task) {
        for (int i = 0; i < TASKS.length; i++) if (TASKS[i].equals(task)) return TASK_NAMES[i];
        return task;
    }

    // ---------- queue file ----------
    private static File queueFile(Context c) { return new File(c.getFilesDir(), "queue.json"); }

    public static synchronized JSONArray loadQueue(Context c) {
        try {
            File f = queueFile(c);
            if (!f.exists()) return new JSONArray();
            byte[] b = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            }
            return new JSONArray(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void saveQueue(Context c, JSONArray q) {
        try {
            File tmp = new File(c.getFilesDir(), "queue.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(q.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(queueFile(c))) {
                try (OutputStream out = new FileOutputStream(queueFile(c))) { out.write(q.toString().getBytes(StandardCharsets.UTF_8)); }
            }
        } catch (Exception ignored) {}
    }

    /** Records one tree. Returns the new count for this task today. */
    public static synchronized int addTap(Context c, String task, double lat, double lon, float acc, boolean hasFix) {
        SharedPreferences p = prefs(c);
        String worker = p.getString("name", "");
        String device = deviceId(c);
        long now = System.currentTimeMillis();
        String id = device + "-" + Long.toString(now, 36) + "-" + Integer.toString(new Random().nextInt(1679616), 36);
        try {
            JSONObject t = new JSONObject();
            t.put("id", id);
            t.put("t", now);
            t.put("task", task);
            t.put("worker", worker);
            t.put("device", device);
            if (hasFix) {
                t.put("lat", Math.round(lat * 1e7) / 1e7);
                t.put("lon", Math.round(lon * 1e7) / 1e7);
                t.put("acc", Math.round(acc));
            }
            JSONArray q = loadQueue(c);
            q.put(t);
            saveQueue(c, q);
        } catch (Exception ignored) {}

        // today's counts and an undo list
        String day = today();
        if (!day.equals(p.getString("day", ""))) {
            SharedPreferences.Editor e = p.edit().putString("day", day).putString("todayIds", "[]");
            for (String k : TASKS) e.putInt("count_" + k, 0);
            e.apply();
        }
        int n = p.getInt("count_" + task, 0) + 1;
        try {
            JSONArray ids = new JSONArray(p.getString("todayIds", "[]"));
            JSONObject last = new JSONObject();
            last.put("id", id);
            last.put("task", task);
            ids.put(last);
            while (ids.length() > 300) ids.remove(0);
            p.edit().putInt("count_" + task, n).putString("todayIds", ids.toString()).apply();
        } catch (Exception e) {
            p.edit().putInt("count_" + task, n).apply();
        }
        return n;
    }

    /** Removes the last tree. Returns a short message. */
    public static synchronized String undoLast(Context c) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray ids = new JSONArray(p.getString("todayIds", "[]"));
            if (ids.length() == 0) return "Nothing to undo";
            JSONObject last = ids.getJSONObject(ids.length() - 1);
            ids.remove(ids.length() - 1);
            String id = last.getString("id");
            String task = last.getString("task");
            JSONArray q = loadQueue(c);
            boolean removed = false;
            for (int i = q.length() - 1; i >= 0; i--) {
                JSONObject o = q.getJSONObject(i);
                if (id.equals(o.optString("id"))) { q.remove(i); removed = true; break; }
            }
            if (!removed) { // already sent: send an undo record
                JSONObject u = new JSONObject();
                u.put("undo", id);
                u.put("t", System.currentTimeMillis());
                u.put("worker", p.getString("name", ""));
                u.put("device", deviceId(c));
                q.put(u);
            }
            saveQueue(c, q);
            int n = Math.max(0, p.getInt("count_" + task, 0) - 1);
            p.edit().putString("todayIds", ids.toString()).putInt("count_" + task, n).apply();
            return "Removed last " + taskName(task).toLowerCase(Locale.ROOT) + " tree";
        } catch (Exception e) {
            return "Nothing to undo";
        }
    }

    public static int todayCount(Context c, String task) {
        SharedPreferences p = prefs(c);
        if (!today().equals(p.getString("day", ""))) return 0;
        return p.getInt("count_" + task, 0);
    }

    // ---------- upload ----------
    /** Sends waiting taps. Returns a short status line. Call off the main thread. */
    public static String upload(Context c) {
        SharedPreferences p = prefs(c);
        String site = p.getString("site", "").trim();
        String code = p.getString("code", "");
        if (site.isEmpty()) return "Set the site address";
        if (site.endsWith("/")) site = site.substring(0, site.length() - 1);
        if (!site.startsWith("http")) site = "https://" + site;
        int sentTotal = 0;
        while (true) {
            JSONArray q = loadQueue(c);
            if (q.length() == 0) return sentTotal > 0 ? "Sent " + sentTotal : "All sent";
            JSONArray batch = new JSONArray();
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < q.length() && i < 500; i++) {
                JSONObject o = q.optJSONObject(i);
                if (o == null) continue;
                batch.put(o);
                keys.add(o.optString("id", "") + "|" + o.optString("undo", ""));
            }
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("taps", batch);
                conn = (HttpURLConnection) new URL(site + "/api/taps").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("content-type", "application/json");
                conn.setRequestProperty("x-crew-code", code);
                try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int status = conn.getResponseCode();
                if (status == 401) return "Crew code is wrong";
                if (status < 200 || status >= 300) return "Site error " + status;
            } catch (Exception e) {
                return sentTotal > 0 ? "Sent " + sentTotal + ", no signal for the rest" : "No signal, will try again";
            } finally {
                if (conn != null) conn.disconnect();
            }
            synchronized (Store.class) {
                JSONArray now = loadQueue(c);
                JSONArray keep = new JSONArray();
                for (int i = 0; i < now.length(); i++) {
                    JSONObject o = now.optJSONObject(i);
                    if (o == null) continue;
                    if (!keys.contains(o.optString("id", "") + "|" + o.optString("undo", ""))) keep.put(o);
                }
                saveQueue(c, keep);
            }
            sentTotal += batch.length();
        }
    }
}
