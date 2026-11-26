package com.taiyeb.daysleft;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import javax.imageio.ImageIO;

/**
 * Looks at WordFlow on a real PDF page: re-wraps the words at a zoom and draws each word as a crop of the page render, exactly what the reader does.
 * Usage: FlowDemo file.pdf page zoom lineWidthPx out.png   (needs pdftotext and pdftoppm)
 */
public class FlowDemo {
    public static void main(String[] a) throws Exception {
        String pdf = a[0]; int page = Integer.parseInt(a[1]); float z = Float.parseFloat(a[2]); float lineW = Float.parseFloat(a[3]); String out = a[4];
        Path tmp = Files.createTempDirectory("flow");
        run("pdftotext", "-bbox", "-f", "" + page, "-l", "" + page, pdf, tmp + "/w.html");
        String html = new String(Files.readAllBytes(Paths.get(tmp + "/w.html")), "UTF-8");
        Matcher pm = Pattern.compile("<page width=\"([\\d.]+)\" height=\"([\\d.]+)\"").matcher(html); pm.find();
        float pw = Float.parseFloat(pm.group(1)), ph = Float.parseFloat(pm.group(2)), aspect = ph / pw;
        List<float[]> boxes = new ArrayList<>(); List<String> toks = new ArrayList<>(); List<Byte> fl = new ArrayList<>();
        Matcher wm = Pattern.compile("<word xMin=\"([\\d.]+)\" yMin=\"([\\d.]+)\" xMax=\"([\\d.]+)\" yMax=\"([\\d.]+)\">(.*?)</word>").matcher(html);
        while (wm.find()) {
            float x0 = Float.parseFloat(wm.group(1)), y0 = Float.parseFloat(wm.group(2)), x1 = Float.parseFloat(wm.group(3)), y1 = Float.parseFloat(wm.group(4));
            String t = wm.group(5).replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">");
            // what BookText stores: y0 = baseline - h, y1 = baseline + 0.22 h  (h is unknown to the flow; here 0.55 em)
            float hh = y1 - y0, em = hh / 1.11f, base = y0 + 0.80f * hh, h = 0.55f * em;
            boxes.add(new float[]{x0 / pw, (base - h) / ph, x1 / pw, (base + 0.22f * h) / ph});
            toks.add(t);
            char c = t.replaceAll("[\"'”’)\\]]+$", "").isEmpty() ? ' ' : t.replaceAll("[\"'”’)\\]]+$", "").charAt(t.replaceAll("[\"'”’)\\]]+$", "").length() - 1);
            fl.add((byte) (c == '.' || c == '!' || c == '?' ? 1 : 0));
        }
        int n = boxes.size();
        float[] box = new float[n * 4]; byte[] flag = new byte[n];
        for (int i = 0; i < n; i++) { System.arraycopy(boxes.get(i), 0, box, 4 * i, 4); flag[i] = fl.get(i); }
        float[] ext = WordFlow.extent(box, 0, n, aspect);
        float sc = lineW / (ext[1] - ext[0]) * z;
        WordFlow.Flow f = WordFlow.layout(box, toks.toArray(new String[0]), flag, 0, n, aspect, lineW, sc, 0);
        run("pdftoppm", "-png", "-f", "" + page, "-l", "" + page, "-scale-to-x", "" + Math.round(sc), "-scale-to-y", "-1", pdf, tmp + "/pg");
        File png = new File(tmp.toFile().listFiles((d, nm) -> nm.startsWith("pg"))[0].getPath());
        BufferedImage src = ImageIO.read(png);
        int pad = 20;
        BufferedImage img = new BufferedImage((int) lineW + 2 * pad, (int) f.height + 2 * pad, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xF4ECD8)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int i = 0; i < f.n; i++) {
            int sx0 = Math.max(0, Math.round(f.sx0[i] * sc) - 1), sx1 = Math.min(src.getWidth(), Math.round(f.sx1[i] * sc) + 1);
            int sy0 = Math.max(0, Math.round(f.sy0[i] * sc)), sy1 = Math.min(src.getHeight(), Math.round(f.sy1[i] * sc));
            if (sx1 <= sx0 || sy1 <= sy0) continue;
            // multiply blend so white page pixels take the paper colour, like the reader's colour filter
            BufferedImage crop = src.getSubimage(sx0, sy0, sx1 - sx0, sy1 - sy0);
            int dx = Math.round(pad + f.x[i]) - 1, dy = Math.round(pad + f.y[i]) + (sy0 - Math.round(f.sy0[i] * sc));
            for (int yy = 0; yy < crop.getHeight(); yy++) for (int xx = 0; xx < crop.getWidth(); xx++) {      // multiply: white = the paper, like the reader's colour filter
                int X = dx + xx, Y = dy + yy; if (X < 0 || Y < 0 || X >= img.getWidth() || Y >= img.getHeight()) continue;
                int sp = crop.getRGB(xx, yy), bg = img.getRGB(X, Y);
                int r = ((sp >> 16) & 255) * ((bg >> 16) & 255) / 255, gg = ((sp >> 8) & 255) * ((bg >> 8) & 255) / 255, b = (sp & 255) * (bg & 255) / 255;
                img.setRGB(X, Y, (r << 16) | (gg << 8) | b);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.printf("words %d, items %d, text width %.2f of the page, scale %.0f px, result %dx%d%n", n, f.n, ext[1] - ext[0], sc, img.getWidth(), img.getHeight());
    }

    static void run(String... c) throws Exception {
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new RuntimeException(String.join(" ", c));
    }
}
