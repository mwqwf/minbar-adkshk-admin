#!/usr/bin/env python3
"""فحص سلامة النصّ العربيّ/القرآنيّ: يكشف ما يُفسد النصّ دون أن يغيّر شيئاً.

الاستعمال:
  python3 check_arabic_text.py PATH [PATH...] [--reference REF] [--ext .txt,.json,.xml,.md,.csv,.tsv]
رمز الخروج 0 = سليم، 1 = مخالفات.
"""
import argparse, os, sys, unicodedata

TATWEEL = "\u0640"
BIDI = set("\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069\u061c")
ODD_SPACES = set("\u00a0\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u202f\u205f\u3000\ufeff")

def presentation(ch):
    o = ord(ch)
    return 0xFB50 <= o <= 0xFDFF or 0xFE70 <= o <= 0xFEFF and o != 0xFEFF

def scan(path, text, issues):
    for ln, line in enumerate(text.splitlines(), 1):
        for col, ch in enumerate(line, 1):
            kind = None
            if ch == TATWEEL: kind = "تطويل U+0640"
            elif presentation(ch): kind = f"شكل عرض U+{ord(ch):04X}"
            elif ch in BIDI: kind = f"علامة اتجاه مخزّنة U+{ord(ch):04X}"
            elif ch in ODD_SPACES: kind = f"مسافة غير قياسية U+{ord(ch):04X}"
            if kind: issues.append(f"{path}:{ln}:{col}: {kind}")
        if any("\u0600" <= c <= "\u06ff" for c in line) and unicodedata.normalize("NFC", line) != line:
            issues.append(f"{path}:{ln}: تنبيه — السطر ليس بصيغة NFC (لا تطبّعه آلياً؛ تحقّق من المصدر)")

def compare(path, text, ref, issues, limit=20):
    a, b = text, ref
    if a == b: return
    n = 0
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            line = a.count("\n", 0, i) + 1
            issues.append(f"{path}: فرق عند الحرف {i} (السطر {line}): U+{ord(x):04X} مقابل المرجع U+{ord(y):04X}")
            n += 1
            if n >= limit: break
    if len(a) != len(b):
        issues.append(f"{path}: الطول {len(a)} مقابل المرجع {len(b)}")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--reference")
    ap.add_argument("--ext", default=".txt,.json,.xml,.md,.csv,.tsv")
    a = ap.parse_args()
    exts = tuple(a.ext.split(","))
    files = []
    for p in a.paths:
        if os.path.isdir(p):
            for root, _, fs in os.walk(p):
                files += [os.path.join(root, f) for f in fs if f.endswith(exts)]
        else:
            files.append(p)
    ref = open(a.reference, encoding="utf-8").read() if a.reference else None
    issues = []
    for f in files:
        try: t = open(f, encoding="utf-8").read()
        except Exception as e:
            issues.append(f"{f}: تعذّرت القراءة ({e})"); continue
        scan(f, t, issues)
        if ref is not None: compare(f, t, ref, issues)
    for i in issues: print(i)
    print(f"فُحص {len(files)} ملفاً · مخالفات: {len(issues)}")
    sys.exit(1 if issues else 0)

if __name__ == "__main__":
    main()
