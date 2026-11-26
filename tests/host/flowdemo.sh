#!/bin/sh
# Looks at the zoomed text layout on a REAL PDF page with the app's REAL extraction (BookText.BoxStripper, run on desktop PDFBox 2.0.27):
#   tests/host/flowdemo.sh file.pdf page zoom out.png      then open out.png
# Why: PDFBox hands over one printed LINE per call; a layout tested on made-up per-word boxes passed while the app broke (v3.44).
# Needs: java, python3, curl (downloads the PDFBox jars once into $FLOWDEMO_CACHE), pdftoppm.
set -e
cd "$(dirname "$0")/../.."
PDF=$1; PAGE=${2:-1}; Z=${3:-1.6}; OUT=${4:-/tmp/flowdemo.png}
C=${FLOWDEMO_CACHE:-/tmp/flowdemo-cache}; mkdir -p "$C/src"
M=https://repo1.maven.org/maven2
for j in org/apache/pdfbox/pdfbox/2.0.27/pdfbox-2.0.27.jar org/apache/pdfbox/fontbox/2.0.27/fontbox-2.0.27.jar commons-logging/commons-logging/1.2/commons-logging-1.2.jar; do
  [ -f "$C/$(basename $j)" ] || curl -sfL -o "$C/$(basename $j)" "$M/$j"; done
CP="$C/pdfbox-2.0.27.jar:$C/fontbox-2.0.27.jar:$C/commons-logging-1.2.jar"
SRC=app/src/main/java/com/taiyeb/daysleft
python3 - "$SRC" "$C/src/IndexDump.java" <<'P'
import sys
src=open(sys.argv[1]+'/BookText.java').read()
i=src.index("        @Override protected void writeString(String text, List<TextPosition> pos) throws IOException {")
j=src.index("        static float clamp(float v)")
body=src[i:j]
cl=src[j:src.index("        static boolean endsSentence")]
ends=src[src.index("        static boolean endsSentence"):src.index("    /** Blocking. Reads the PDF")]
ends=ends[:ends.rindex("    }")]
open(sys.argv[2],'w').write('''package com.taiyeb.daysleft;
import org.apache.pdfbox.pdmodel.*; import org.apache.pdfbox.pdmodel.common.PDRectangle; import org.apache.pdfbox.text.*; import java.io.*; import java.util.*;
public class IndexDump extends PDFTextStripper {
    static final Set<String> ABBR = new HashSet<>(Arrays.asList("dr","mr","mrs","ms","st","vs","jr","sr","prof","fig","vol","inc","no","eg","ie","etc","cf"));
    final ArrayList<String> words = new ArrayList<>(), disp = new ArrayList<>(); final ArrayList<float[]> boxes = new ArrayList<>(); final ArrayList<Byte> flags = new ArrayList<>(); final ArrayList<int[]> look = new ArrayList<>();
    float pw = 612, ph = 792;
    IndexDump() throws IOException { super(); }
    @Override protected void startPage(PDPage page) throws IOException { PDRectangle r = page.getCropBox(); pw = r.getWidth(); ph = r.getHeight(); super.startPage(page); }
'''+body+cl+ends+'''
    public static void main(String[] a) throws Exception {
        try (PDDocument d = PDDocument.load(new File(a[0]))) {
            IndexDump s = new IndexDump(); s.setStartPage(Integer.parseInt(a[1])); s.setEndPage(Integer.parseInt(a[1])); s.getText(d);
            try (PrintWriter o = new PrintWriter(a[2], "UTF-8")) { o.println(s.pw + " " + s.ph);
                for (int i = 0; i < s.words.size(); i++) { float[] b = s.boxes.get(i); o.println(s.disp.get(i).isEmpty() ? "-" : s.disp.get(i)); o.println(b[0] + " " + b[1] + " " + b[2] + " " + b[3] + " " + s.flags.get(i)); } }
        }
    }
}
''')
P
javac -cp "$CP" -d "$C/src" "$C/src/IndexDump.java" $SRC/PageMatcher.java 2>&1 | grep -v Picked || true
java -cp "$C/src:$CP" com.taiyeb.daysleft.IndexDump "$PDF" "$PAGE" "$C/idx.txt" 2>&1 | grep -v "WARN\|INFO\|PM\|AM\|Picked" || true
javac -d "$C/src" $SRC/WordFlow.java tests/host/FlowDemo.java 2>&1 | grep -v Picked || true
java -cp "$C/src" com.taiyeb.daysleft.FlowDemo "$PDF" "$PAGE" "$Z" 1000 "$OUT" "$C/idx.txt" 2>&1 | grep -v Picked
