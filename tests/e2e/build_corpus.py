#!/usr/bin/env python3
"""
Builds a synthetic audiobook with a KNOWN timeline from real book text, for the end-to-end test (tests/e2e/run.sh).

  text (public-domain book) --> sentences --> neural or robotic voice, one sentence at a time
        --> pauses, chapter gaps, an intro, background noise --> AAC (m4a) --> 16 kHz wav the test reads

What makes it realistic (each of these broke something real at some point, or could):
  * the book text has things that are never spoken: footnote numbers glued to sentences, page numbers, and some sentences the
    narrator skips (an abridged recording);
  * the audio has things that are not in the book: a spoken intro after a stretch of room noise, chapter pauses;
  * numbers and abbreviations are WRITTEN with digits but SPOKEN as words ("1984", "Dr.", "$5.50", "25%");
  * the narrator's pace changes from paragraph to paragraph;
  * background noise, then AAC compression, like a real audiobook file.

Output (in --out): audio16k.wav, audio.m4a, truth.json  (the "book" is truth.json["elements"]: text + spoken? + start/end seconds).
"""
import argparse, json, os, random, re, subprocess, sys, wave
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

TEMPLATES = [
    ("In 1984, Dr. Smith paid $5.50 for 3 books.", "In nineteen eighty-four, Doctor Smith paid five dollars and fifty cents for three books."),
    ("About 25% of the 12 members agreed on Mon. the 3rd.", "About twenty-five percent of the twelve members agreed on Monday the third."),
    ("Mr. Brown walked 4 miles in 2 hours, i.e. 2 mph.", "Mister Brown walked four miles in two hours, that is two miles per hour."),
    ("The 1st chapter has 14 pages and the 2nd has 9.", "The first chapter has fourteen pages and the second has nine."),
    ("By 1999 St. John's had 300 students, 40% of them from abroad.", "By nineteen ninety-nine Saint John's had three hundred students, forty percent of them from abroad."),
]

def load_text(path, max_words):
    raw = open(path, encoding="utf-8", errors="ignore").read()
    raw = raw.split("\n", 1)[1] if raw.startswith("[") else raw          # the [title] line
    paras = [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n", raw)]
    paras = [p for p in paras if p]
    out, words = [], 0
    for p in paras:
        out.append(p); words += len(p.split())
        if words >= max_words: break
    return out

def clauses(s, limit=150):
    """Long sentences are cut at the clause break nearest the middle, so the truth timeline is fine-grained (a stricter test)."""
    if len(s) <= limit: return [s]
    cuts = [m.end() for m in re.finditer(r"[;:,]\s+|\s--\s|[.!?][\"'”’]?\s+", s) if 25 < m.end() < len(s) - 25]
    if not cuts: return [s]
    c = min(cuts, key=lambda x: abs(x - len(s) / 2))
    return clauses(s[:c].strip(), limit) + clauses(s[c:].strip(), limit)

def sentences(p):
    parts = re.split(r'(?<=[.!?])(?:["”’\']?)\s+(?=["“‘\']?[A-Z])', p)
    out = []
    for q in parts:
        q = q.strip()
        if len(q) > 1: out += clauses(q)
    return out

def spoken_form(text):
    """What a narrator says for this text: chapter headings are spoken in words, a few written forms are expanded."""
    t = text
    t = re.sub(r"\bCHAPTER ([IVXL]+)\.", lambda m: "Chapter " + str(roman(m.group(1))) + ".", t)
    return t

def roman(r):
    v = {"I": 1, "V": 5, "X": 10, "L": 50}; n = 0
    for i, c in enumerate(r):
        n += -v[c] if i + 1 < len(r) and v[c] < v[r[i + 1]] else v[c]
    return n

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=True)          # path to a .txt
    ap.add_argument("--out", required=True)
    ap.add_argument("--voice", default="piper:voice.onnx")   # piper:<model.onnx> or espeak
    ap.add_argument("--words", type=int, default=12000)
    ap.add_argument("--snr", type=float, default=28)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--numbers", type=float, default=0.03)  # share of sentences replaced by number/abbreviation sentences
    ap.add_argument("--skip", type=float, default=0.015)    # share of sentences the narrator skips
    ap.add_argument("--footnotes", type=float, default=0.06)
    ap.add_argument("--skip-runs", type=float, default=0.0)   # chance per sentence that the narrator starts skipping a RUN of 5-40 sentences (an abridged recording)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rnd = random.Random(a.seed)
    np_rng = np.random.default_rng(a.seed)

    # ---- the book: elements in order ----
    elements = []        # dict(text, spoken_text, kind, spoken, para_end, chapter_start)
    paras = load_text(a.source, a.words)
    words_since_page = 0; page = 11; skip_left = 0
    for p in paras:
        sents = sentences(p)
        for si, s in enumerate(sents):
            kind = "heading" if re.match(r"^CHAPTER [IVXL]+\.", s) else "sentence"
            text, said = s, spoken_form(s)
            if kind == "sentence" and rnd.random() < a.numbers:
                text, said = rnd.choice(TEMPLATES)
            spoken = not (kind == "sentence" and rnd.random() < a.skip)
            if skip_left > 0 and kind == "sentence": spoken = False; skip_left -= 1
            elif kind == "sentence" and a.skip_runs > 0 and rnd.random() < a.skip_runs: skip_left = rnd.randint(5, 40); spoken = False
            if kind == "sentence" and rnd.random() < a.footnotes:
                text = text + str(rnd.randint(2, 140))        # a footnote number glued on, never spoken
            elements.append(dict(text=text, said=said, kind=kind, spoken=spoken, para_end=(si == len(sents) - 1), chapter=(kind == "heading")))
            words_since_page += len(text.split())
            if words_since_page > 250:
                words_since_page = 0; page += 1
                elements.append(dict(text="— %d —" % page, said="", kind="pagenum", spoken=False, para_end=False, chapter=False))

    # ---- the voice ----
    sr_out = 22050
    if a.voice.startswith("piper:"):
        from piper import PiperVoice, SynthesisConfig
        model = a.voice.split(":", 1)[1]
        voice = PiperVoice.load(model)
        sr_out = voice.config.sample_rate
        def say(text, pace):
            tmp = os.path.join(a.out, "_s.wav")
            with wave.open(tmp, "wb") as w:
                voice.synthesize_wav(text, w, syn_config=SynthesisConfig(length_scale=pace))
            with wave.open(tmp, "rb") as w:
                return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    else:
        def say(text, pace):
            tmp = os.path.join(a.out, "_s.wav")
            subprocess.run(["espeak-ng", "-v", "en-us", "-s", str(int(165 / pace)), "-w", tmp, text], check=True, capture_output=True)
            with wave.open(tmp, "rb") as w:
                x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
                sr = w.getframerate()
            if sr != sr_out:   # simple resample
                n = int(len(x) * sr_out / sr); x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
            return x

    def silence(sec): return np.zeros(int(sec * sr_out), dtype=np.float32)

    # ---- the timeline ----
    chunks, t = [], 0.0
    def add(x):
        nonlocal t
        chunks.append(x); t += len(x) / sr_out
    # intro: room noise, then a spoken line that is NOT in the book, then a breath
    add(silence(18)); intro_start = t
    add(say("This is the audiobook edition, read by your narrator.", 1.0)); add(silence(3.0))
    pace = 1.0
    for e in elements:
        if e["chapter"]: add(silence(6.0))
        if not e["spoken"]:
            e["start"] = e["end"] = -1.0
            continue
        if e["kind"] == "pagenum": continue
        if rnd.random() < 0.12 or e["chapter"]:
            pace = rnd.uniform(0.86, 1.14)               # the narrator speeds up and slows down by paragraph
        x = say(e["said"], pace)
        e["start"] = round(t, 3); add(x); e["end"] = round(t, 3)
        add(silence(rnd.uniform(0.8, 1.4) if e["para_end"] else rnd.uniform(0.28, 0.55)))
    add(silence(4.0))
    for e in elements:
        if e["kind"] == "pagenum": e["start"] = e["end"] = -1.0
    audio = np.concatenate(chunks)
    # background noise (pink-ish), then normalise
    noise = np_rng.standard_normal(len(audio)).astype(np.float32)
    k = np.exp(-np.arange(64) / 18.0); noise = np.convolve(noise, k / k.sum(), mode="same")
    sig_p = float(np.mean(audio[np.abs(audio) > 0.01] ** 2)) if np.any(np.abs(audio) > 0.01) else 1e-4
    noise *= np.sqrt(sig_p / (10 ** (a.snr / 10)) / max(1e-9, float(np.mean(noise ** 2))))
    audio = audio + noise
    audio = audio / max(1.0, float(np.max(np.abs(audio)))) * 0.9

    # ---- like a real audiobook file: AAC, then decode to 16 kHz like the phone does ----
    raw = os.path.join(a.out, "raw.wav")
    with wave.open(raw, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr_out)
        w.writeframes((audio * 32767).astype(np.int16).tobytes())
    m4a = os.path.join(a.out, "audio.m4a")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", raw, "-c:a", "aac", "-b:a", "48k", "-ar", "22050", "-ac", "1", m4a], check=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", m4a, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", os.path.join(a.out, "audio16k.wav")], check=True)
    os.remove(raw); os.path.exists(os.path.join(a.out, "_s.wav")) and os.remove(os.path.join(a.out, "_s.wav"))

    truth = dict(source=os.path.basename(a.source), voice=a.voice, snr=a.snr, seed=a.seed, duration=round(t, 3), intro_end=round(intro_start + 6, 1),
                 elements=[dict(text=e["text"], kind=e["kind"], spoken=e["spoken"], start=e["start"], end=e["end"]) for e in elements])
    json.dump(truth, open(os.path.join(a.out, "truth.json"), "w"), ensure_ascii=False)
    n_words = sum(len(e["text"].split()) for e in elements)
    print("built %s: %.1f min of audio, %d elements, %d words, %d spoken" % (a.out, t / 60, len(elements), n_words, sum(1 for e in elements if e["spoken"])))

if __name__ == "__main__":
    main()
