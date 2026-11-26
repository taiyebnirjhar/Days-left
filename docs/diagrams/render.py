#!/usr/bin/env python3
"""Renders docs/diagrams/src/*.txt (monospace figures) and the architecture figure to PNG. Mermaid sources (*.mmd) are
rendered with:  npx @mermaid-js/mermaid-cli -i src/NN.mmd -o NN.png -s 2 -b white   (see README, chapter XVII)."""
import glob, os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SANSB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
INK, PAPER, LINE = (31, 35, 40), (255, 255, 255), (120, 130, 150)

def text_figure(path, out):
    lines = open(path, encoding="utf-8").read().rstrip("\n").split("\n")
    f = ImageFont.truetype(MONO, 26)
    cw = f.getlength("M"); ch = 38
    w = int(max(len(l) for l in lines) * cw + 64); h = int(len(lines) * ch + 56)
    im = Image.new("RGB", (w, h), PAPER); d = ImageDraw.Draw(im)
    for i, l in enumerate(lines): d.text((32, 28 + i * ch), l, font=f, fill=INK)
    im.save(out)

def architecture(out):
    bands = [
        ("SCREENS", (225, 238, 255), [("MainActivity", "countdowns, settings"), ("ReaderActivity", "PDF, player, dialogs, pill"), ("LearnUi", "plans, inbox, items")]),
        ("SERVICES AND RECEIVERS", (255, 240, 220), [("CountdownService", "status bar, overlay"), ("SyncService", "the background worker"), ("PlaybackService", "audiobook notification"), ("Receivers", "nudge, boot, updater")]),
        ("THE BRAIN: pure Java, runs on a laptop", (225, 247, 232), [("SyncPlanner", "what to hear next"), ("Aligner", "words to book positions"), ("Alignment", "time ↔ word, pauses"), ("BookIndex", "words, boxes, sentences"), ("PageMatcher", "which page, from words"), ("Calc, Version", "dates, versions")]),
        ("TOUCHING THE WORLD", (245, 232, 250), [("AudioClip", "3 parallel decoders"), ("Whisper", "JNI to whisper.cpp"), ("BookText", "PDF text via PDFBox"), ("Player", "MediaPlayer, focus"), ("Store, Learn", "preferences, JSON"), ("Updater", "release → installer")]),
    ]
    arrows = ["asks", "decides", "does"]
    W, M, G, BW, BH, COLS = 1000, 36, 18, 290, 100, 3
    ft, fn, fd = ImageFont.truetype(SANSB, 24), ImageFont.truetype(SANSB, 25), ImageFont.truetype(SANS, 21)
    # height
    H = M; layout = []
    for bi, (title, col, boxes) in enumerate(bands):
        rows = (len(boxes) + COLS - 1) // COLS
        bh = 56 + rows * BH + (rows - 1) * G + 24
        layout.append((H, bh)); H += bh + (70 if bi < len(bands) - 1 else 0)
    H += M
    im = Image.new("RGB", (W, H), PAPER); d = ImageDraw.Draw(im)
    for bi, ((title, col, boxes), (y, bh)) in enumerate(zip(bands, layout)):
        d.rounded_rectangle((M, y, W - M, y + bh), 22, fill=col, outline=LINE, width=2)
        d.text((M + 24, y + 16), title, font=ft, fill=INK)
        per = COLS
        for i, (name, desc) in enumerate(boxes):
            r, c = divmod(i, per)
            n_in_row = min(per, len(boxes) - r * per)
            span = n_in_row * BW + (n_in_row - 1) * G
            x0 = (W - span) // 2 + c * (BW + G); y0 = y + 56 + r * (BH + G)
            d.rounded_rectangle((x0, y0, x0 + BW, y0 + BH), 14, fill=PAPER, outline=LINE, width=2)
            d.text((x0 + 18, y0 + 18), name, font=fn, fill=INK)
            d.text((x0 + 18, y0 + 56), desc, font=fd, fill=(80, 90, 105))
        if bi < len(bands) - 1:
            cx = W // 2; ya = y + bh + 6; yb = y + bh + 64
            d.line((cx, ya, cx, yb), fill=INK, width=4)
            d.polygon([(cx - 12, yb - 12), (cx + 12, yb - 12), (cx, yb + 2)], fill=INK)
            d.text((cx + 22, ya + 20), arrows[bi], font=fd, fill=(80, 90, 105))
    im.save(out)

if __name__ == "__main__":
    for p in sorted(glob.glob(os.path.join(HERE, "src", "*.txt"))):
        text_figure(p, os.path.join(HERE, os.path.basename(p)[:-4] + ".png"))
    architecture(os.path.join(HERE, "11-architecture.png"))
    print("done")
