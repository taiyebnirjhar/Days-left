package com.taiyeb.daysleft;

/** Dotted version compare ("3.18" vs "3.9"). Pure Java so it can be tested on the host. */
final class Version {
    private Version() {}

    /** Ignores a leading v and any -suffix. Negative if a is older than b. */
    static int compare(String a, String b) {
        int[] x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int[] nums(String s) {
        s = s == null ? "" : s.trim().replaceFirst("^[vV]", "");
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        if (s.isEmpty()) return new int[0];
        String[] p = s.split("\\.");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try { r[i] = Integer.parseInt(p[i].replaceAll("\\D", "")); } catch (Exception e) { r[i] = 0; }
        }
        return r;
    }
}
