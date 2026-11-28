package com.taiyeb.daysleft;

import android.content.Context;
import org.json.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** A plan groups items under a deadline. planId 0 on an item means "Inbox" (no plan, no deadline). */
final class Plan {
    int id, color;
    String name = "", emoji = "📚";
    long start, deadline;

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("color", color).put("name", name).put("emoji", emoji).put("start", start).put("deadline", deadline);
    }

    static Plan from(JSONObject o) {
        Plan p = new Plan();
        p.id = o.optInt("id"); p.color = o.optInt("color"); p.name = o.optString("name"); p.emoji = o.optString("emoji", "📚");
        p.start = o.optLong("start"); p.deadline = o.optLong("deadline");
        return p;
    }
}

final class Item {
    int id, planId, type, mins, page, pages, apos;
    String title = "", url = "", pdf = "", pdfName = "", sync = "", audio = "", audioName = "";
    boolean done, speak;   // speak: no audio file, the phone reads the book aloud (its text was prepared when the item was saved)
    long added, doneAt;

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("plan", planId).put("type", type).put("mins", mins).put("title", title)
                .put("url", url).put("done", done).put("added", added).put("doneAt", doneAt)
                .put("pdf", pdf).put("pdfName", pdfName).put("page", page).put("pages", pages).put("apos", apos).put("sync", sync).put("audio", audio).put("audioName", audioName).put("speak", speak);
    }

    static Item from(JSONObject o) {
        Item i = new Item();
        i.id = o.optInt("id"); i.planId = o.optInt("plan"); i.type = o.optInt("type"); i.mins = o.optInt("mins");
        i.title = o.optString("title"); i.url = o.optString("url"); i.done = o.optBoolean("done");
        i.added = o.optLong("added"); i.doneAt = o.optLong("doneAt");
        i.pdf = o.optString("pdf"); i.pdfName = o.optString("pdfName"); i.page = o.optInt("page"); i.pages = o.optInt("pages"); i.apos = o.optInt("apos"); i.sync = o.optString("sync"); i.audio = o.optString("audio"); i.audioName = o.optString("audioName"); i.speak = o.optBoolean("speak");
        return i;
    }
}

final class Learn {
    static final String[] TYPES = {"Book", "Audiobook", "Podcast", "Video", "Article", "Other"};
    static final String[] EMO = {"📖", "🎧", "🎙️", "▶️", "📰", "📌"};
    static final int[] MINS = {360, 540, 45, 20, 10, 30};
    static final Pattern URL = Pattern.compile("https?://\\S+");

    // ---------- storage ----------
    static List<Plan> plans(Context c) {
        List<Plan> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.sp(c).getString("plans", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Plan.from(a.getJSONObject(i)));
        } catch (JSONException ignored) {}
        return out;
    }

    static void savePlans(Context c, List<Plan> l) {
        JSONArray a = new JSONArray();
        try { for (Plan p : l) a.put(p.toJson()); } catch (JSONException ignored) {}
        Store.sp(c).edit().putString("plans", a.toString()).apply();
    }

    static List<Item> items(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.sp(c).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.from(a.getJSONObject(i)));
        } catch (JSONException ignored) {}
        return out;
    }

    static void saveItems(Context c, List<Item> l) {
        JSONArray a = new JSONArray();
        try { for (Item i : l) a.put(i.toJson()); } catch (JSONException ignored) {}
        Store.sp(c).edit().putString("items", a.toString()).apply();
    }

    static int nextPlanId(List<Plan> l) { int m = 0; for (Plan p : l) m = Math.max(m, p.id); return m + 1; }
    static int nextItemId(List<Item> l) { int m = 0; for (Item i : l) m = Math.max(m, i.id); return m + 1; }

    static Plan plan(Context c, int id) { for (Plan p : plans(c)) if (p.id == id) return p; return null; }

    static List<Item> inPlan(List<Item> all, int planId) {
        List<Item> out = new ArrayList<>();
        for (Item i : all) if (i.planId == planId) out.add(i);
        return out;
    }

    static void upsert(Context c, Item it) {
        List<Item> all = items(c);
        boolean found = false;
        for (int k = 0; k < all.size(); k++) if (all.get(k).id == it.id) { all.set(k, it); found = true; }
        if (!found) all.add(it);
        saveItems(c, all);
    }

    static void setDone(Context c, int id, boolean done) {
        List<Item> all = items(c);
        for (Item i : all) if (i.id == id) { i.done = done; i.doneAt = done ? System.currentTimeMillis() : 0; }
        saveItems(c, all);
    }

    static Item item(Context c, int id) { for (Item i : items(c)) if (i.id == id) return i; return null; }

    static void setReading(Context c, int id, int page, int pages) {
        List<Item> all = items(c);
        for (Item i : all) if (i.id == id) { i.page = page; i.pages = pages; }
        saveItems(c, all);
    }

    static void setAudio(Context c, int id, int apos, String sync) {
        List<Item> all = items(c);
        for (Item i : all) if (i.id == id) { i.apos = apos; if (sync != null) i.sync = sync; }
        saveItems(c, all);
    }

    static void setAudioFile(Context c, int id, String uri, String name) {
        List<Item> all = items(c);
        for (Item i : all) if (i.id == id) { i.audio = uri; i.audioName = name; i.apos = 0; i.sync = ""; }
        saveItems(c, all);
    }

    /** YouTube video id from a watch, youtu.be, shorts, live or embed link; "" if it isn't one. */
    static String ytId(String url) {
        if (url == null) return "";
        Matcher m = Pattern.compile("(?:youtu\\.be/|[?&]v=|/embed/|/shorts/|/live/)([A-Za-z0-9_-]{11})").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    // ---------- parsing ----------
    static int guessType(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.contains("youtube.com") || u.contains("youtu.be") || u.contains("vimeo.com")) return 3;
        if (u.contains("audible.") ) return 1;
        if (u.contains("spotify.com/episode") || u.contains("spotify.com/show") || u.contains("podcasts.apple.com") || u.contains("pca.st") || u.contains("overcast.fm")) return 2;
        if (u.contains("goodreads.com") || u.contains("amazon.")) return 0;
        return u.isEmpty() ? 5 : 4;
    }

    /** "3. Grit https://x.y" → {"Grit", "https://x.y"}. Leading numbers and bullets are dropped. */
    static String[] parseLine(String raw) {
        String s = raw == null ? "" : raw.trim();
        String url = "";
        boolean heading = s.endsWith(":");
        Matcher m = URL.matcher(s);
        if (m.find()) { url = m.group(); s = (s.substring(0, m.start()) + " " + s.substring(m.end())); }
        s = s.replaceFirst("^\\s*(?:[-*•–—·]+|\\(?\\d{1,3}[.):])\\s*", "").replaceAll("[\\s|:–—-]+$", "").trim();
        s = s.replaceAll("\\s{2,}", " ");
        if (heading && url.isEmpty()) return new String[]{"", ""};   // an intro line like "The books are:"
        return new String[]{s, url};
    }

    static List<Item> parsePaste(Context c, String text, int planId, int type) {
        List<Item> all = items(c);
        int id = nextItemId(all);
        long now = System.currentTimeMillis();
        List<Item> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String[] p = parseLine(line);
            if (p[0].isEmpty() && p[1].isEmpty()) continue;
            Item it = new Item();
            it.id = id++;
            it.planId = planId;
            it.title = p[0].isEmpty() ? hostOf(p[1]) : p[0];
            it.url = p[1];
            it.type = p[1].isEmpty() ? type : guessTypeKeep(p[1], type);
            it.mins = MINS[it.type];
            it.added = now + out.size();
            out.add(it);
        }
        return out;
    }

    /** Keep the type the user picked unless the link clearly says otherwise (e.g. a YouTube link is a video). */
    static int guessTypeKeep(String url, int picked) {
        int g = guessType(url);
        return g == 4 || g == 5 ? picked : g;
    }

    static String hostOf(String url) {
        try { String h = java.net.URI.create(url).getHost(); return h == null ? url : h.replaceFirst("^www\\.", ""); }
        catch (Exception e) { return url; }
    }

    // ---------- pace ----------
    static final class Pace {
        int total, done, left, behind;
        long daysLeft, minsLeft;
        float frac;
        boolean complete, overdue;
        String headline = "", sub = "";
    }

    static String fmtMins(int m) {
        if (m < 60) return m + " min";
        int h = m / 60, r = m % 60;
        return r == 0 ? h + "h" : h + "h " + r + "m";
    }

    static Pace pace(Plan p, List<Item> its, LocalDate today) {
        Pace k = new Pace();
        k.total = its.size();
        for (Item i : its) { if (i.done) k.done++; else k.minsLeft += i.mins; }
        k.left = k.total - k.done;
        k.frac = k.total == 0 ? 0 : (float) k.done / k.total;
        long now = today.toEpochDay();
        k.daysLeft = Math.max(0, p.deadline - now);
        k.complete = k.total > 0 && k.left == 0;
        k.overdue = !k.complete && now > p.deadline;
        if (k.total == 0) { k.headline = "Add your first item"; return k; }
        if (k.complete) { k.headline = "All done 🎉"; return k; }
        long dur = Math.max(1, p.deadline - p.start);
        long el = Math.min(dur, Math.max(0, now - p.start));
        int expected = (int) Math.floor((double) k.total * el / dur);
        k.behind = expected - k.done;
        if (k.overdue) {
            k.headline = k.left + " left, past the deadline";
        } else if (k.daysLeft == 0) {
            k.headline = "Finish " + k.left + " today";
        } else {
            double per = (double) k.daysLeft / k.left;
            if (per >= 1.5) k.headline = "1 every " + Math.round(per) + " days";
            else if (per >= 0.75) k.headline = "1 a day";
            else k.headline = (int) Math.ceil(1 / per) + " a day";
            long perDay = (k.minsLeft + k.daysLeft - 1) / k.daysLeft;
            k.sub = "about " + fmtMins((int) perDay) + " a day";
        }
        String st = k.behind >= 1 ? "behind by " + k.behind : -k.behind >= 1 ? "ahead by " + (-k.behind) : "on track";
        k.sub = k.sub.isEmpty() ? st : k.sub + " · " + st;
        return k;
    }

    // ---------- what to do next ----------
    /** Planned items first (soonest deadline), then the oldest saved item from the Inbox. */
    static Item nextUp(Context c) {
        List<Plan> ps = plans(c);
        List<Item> all = items(c);
        ps.sort((a, b) -> Long.compare(a.deadline, b.deadline));
        for (Plan p : ps) for (Item i : all) if (i.planId == p.id && !i.done) return i;
        Item best = null;
        for (Item i : all) {
            boolean orphan = i.planId != 0 && plan(c, i.planId) == null;
            if ((i.planId == 0 || orphan) && !i.done && (best == null || i.added < best.added)) best = i;
        }
        return best;
    }

    static int pending(Context c) {
        int n = 0;
        for (Item i : items(c)) if (!i.done) n++;
        return n;
    }
}
