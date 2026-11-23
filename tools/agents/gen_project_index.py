#!/usr/bin/env python3
"""يولّد docs/PROJECT_INDEX.md — الأجزاء الآليّة من فهرس المشروع (لا يكتب الحالة البشرية).

يُشغَّل من جذر المستودع: python3 tools/agents/gen_project_index.py [--check]
بلا تبعيّات؛ الناتج حتميّ (لا وقت توليد ولا سجلّ إيداعات) فلا يتغيّر إلا بتغيّر البنية.
--check: يخرج بـ1 إن كان الفهرس قديماً (للمراجعة قبل الدمج).
"""
import os, re, subprocess, sys, collections

OUT = "docs/PROJECT_INDEX.md"

def files():
    r = subprocess.run(["git", "ls-files"], capture_output=True, text=True)
    return [f for f in r.stdout.splitlines() if f]

def read(p):
    try: return open(p, encoding="utf-8").read()
    except Exception: return ""

def gradle_version():
    rows = []
    for f in sorted(p for p in FILES if p.endswith("build.gradle.kts") and p.count("/") == 1):
        t = read(f)
        app = re.search(r'applicationId\s*=\s*"([^"]+)"', t)
        vc = re.search(r"versionCode\s*=\s*(\d+)", t)
        vn = re.search(r'versionName\s*=\s*"([^"]+)"', t)
        if app or vc:
            rows.append(f"| `{f}` | `{app.group(1) if app else '—'}` | {vc.group(1) if vc else '—'} | {vn.group(1) if vn else '—'} |")
    return rows

def modules():
    t = read("settings.gradle.kts")
    return re.findall(r'include\(\s*"([^"]+)"', t) + re.findall(r"include\s+'([^']+)'", t)

def workflows():
    rows = []
    for f in sorted(p for p in FILES if p.startswith(".github/workflows/") and p.endswith((".yml", ".yaml"))):
        t = read(f)
        name = (re.search(r"^name:\s*(.+)$", t, re.M) or [None, os.path.basename(f)])[1].strip().strip("'\"")
        trig = []
        on = t.split("\njobs:")[0]
        for k in ("push", "pull_request", "schedule", "workflow_dispatch", "workflow_run", "issues", "repository_dispatch"):
            if re.search(rf"^\s+{k}:|^on:\s*\[?.*\b{k}\b", on, re.M): trig.append(k)
        crons = re.findall(r"cron:\s*['\"]([^'\"]+)['\"]", on)
        live_crons = [c for c in crons if not re.search(rf"^\s*#.*{re.escape(c)}", on, re.M)]
        runner = "self-hosted" if "self-hosted" in t else ("ubuntu" if "ubuntu" in t else "?")
        rows.append(f"| `{os.path.basename(f)}` | {name[:60]} | {', '.join(trig) or '—'} | {' · '.join(live_crons) or '—'} | {runner} |")
    return rows

def skills():
    out = []
    for d in sorted({p.split("/")[2] for p in FILES if p.startswith(".agents/skills/") and p.count("/") >= 3}):
        kind = "مركزية" if f".agents/skills/{d}/.agent-skills-source" in FILES else "محلّية"
        out.append(f"`{d}` ({kind})")
    return out

def tree():
    c = collections.Counter(p.split("/")[0] for p in FILES if "/" in p)
    return [f"| `{d}/` | {n} |" for d, n in sorted(c.items()) if not d.startswith(".") or d in (".github", ".agents")]

def docs():
    return sorted(p for p in FILES if p.startswith("docs/") and p.count("/") == 1 and p.endswith(".md") and p != OUT)

FILES = files()
repo = os.path.basename(os.getcwd())
md = [f"# فهرس المشروع الآليّ — `{repo}`", "",
      "> ⛔ مولَّد آلياً بـ`tools/agents/gen_project_index.py` (سير عمل `project-index` عند الدفع إلى الفرع الرئيسيّ). لا تعدّله يدوياً.",
      "> الحالة والخطة والعمل المفتوح في `docs/PROJECT_STATE.md` (يحدّثها الوكلاء في كل تغيير).", ""]
gv = gradle_version()
if gv: md += ["## الإصدار", "| الملف | الحزمة | versionCode | versionName |", "|---|---|---|---|", *gv, ""]
mods = modules()
if mods: md += ["## وحدات Gradle", " · ".join(f"`{m}`" for m in mods), ""]
md += ["## البنية (عدد الملفات المتتبَّعة)", "| المجلد | الملفات |", "|---|---|", *tree(), ""]
wf = workflows()
if wf: md += [f"## سير العمل ({len(wf)})", "| الملف | الاسم | المحفّزات | الجدول (cron UTC) | المشغّل |", "|---|---|---|---|---|", *wf, ""]
sk = skills()
if sk: md += [f"## المهارات ({len(sk)}) — `.agents/skills/` (و`.claude/skills/` روابط لها)", " · ".join(sk), ""]
dc = docs()
if dc: md += ["## وثائق `docs/`", " · ".join(f"[`{os.path.basename(d)}`]({os.path.basename(d)})" for d in dc), ""]
text = "\n".join(md)
old = read(OUT)
if "--check" in sys.argv:
    sys.exit(0 if old == text else 1)
if old != text:
    os.makedirs("docs", exist_ok=True); open(OUT, "w", encoding="utf-8").write(text); print("حُدّث", OUT)
else:
    print("لا تغيير")
