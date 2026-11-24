package com.taiyeb.daysleft;

import java.util.*;

/**
 * Finds which page of a book a short piece of text comes from.
 * Pure Java on purpose, so it can be tested away from the phone.
 * Idea: every pair of neighbouring words is a fingerprint. Rare pairs count more than common ones.
 */
class PageMatcher {
    static class Result {
        int page = -1, second = -1;
        double score, secondScore;
        int matched, words;
        boolean sure;
        boolean spansTwo; // the clip probably crosses a page break
    }

    private final Map<Long, int[]> index = new HashMap<>();
    final int pages;

    PageMatcher(List<String> pageTexts) {
        pages = pageTexts.size();
        Map<Long, ArrayList<Integer>> tmp = new HashMap<>();
        for (int p = 0; p < pages; p++) {
            List<String> w = words(pageTexts.get(p));
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i + 1 < w.size(); i++) {
                long k = key(w.get(i), w.get(i + 1));
                if (seen.add(k)) tmp.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
            }
        }
        for (Map.Entry<Long, ArrayList<Integer>> e : tmp.entrySet()) {
            int[] a = new int[e.getValue().size()];
            for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
            index.put(e.getKey(), a);
        }
    }

    static long key(String a, String b) {
        long h = 1125899906842597L;
        for (int i = 0; i < a.length(); i++) h = 31 * h + a.charAt(i);
        h = 31 * h + ' ';
        for (int i = 0; i < b.length(); i++) h = 31 * h + b.charAt(i);
        return h;
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '’' || c == '‘') c = '\'';
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
            else if (c == '\'') { /* drop apostrophes: "couldn't" and "couldnt" must agree */ }
            else if (b.length() > 0) { out.add(b.toString()); b.setLength(0); }
        }
        if (b.length() > 0) out.add(b.toString());
        return out;
    }

    Result match(String transcript) {
        Result r = new Result();
        List<String> w = words(transcript);
        r.words = w.size();
        if (w.size() < 8) return r;
        double[] score = new double[pages];
        int[] hit = new int[pages];
        for (int i = 0; i + 1 < w.size(); i++) {
            int[] ps = index.get(key(w.get(i), w.get(i + 1)));
            if (ps == null || ps.length > 12) continue;
            double wt = 1.0 / ps.length;
            for (int p : ps) { score[p] += wt; hit[p]++; }
        }
        int best = -1, sec = -1;
        for (int p = 0; p < pages; p++) {
            if (score[p] <= 0) continue;
            if (best < 0 || score[p] > score[best]) { sec = best; best = p; }
            else if (sec < 0 || score[p] > score[sec]) sec = p;
        }
        if (best < 0) return r;
        r.page = best; r.score = score[best]; r.matched = hit[best];
        if (sec >= 0) { r.second = sec; r.secondScore = score[sec]; }
        boolean adj = sec >= 0 && Math.abs(sec - best) == 1 && r.secondScore >= 0.3 * r.score;
        r.spansTwo = adj;
        r.sure = r.matched >= 4 && (sec < 0 || r.score >= 1.6 * r.secondScore || adj);
        return r;
    }
}
