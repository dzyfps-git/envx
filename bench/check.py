"""Tier-1 benchmark checks: does envx surface the facts each benchmark question needs? No agent runs, no credits.

Each answer key in bench/expected/NN.md may hold a ```check block:

    cwd: {my-mod}                       working directory for the calls (project detection, default env); a
                                        {name} is a workspace from bench/local.json (machine-specific, not tracked)
    until: 2027-01-01                   skip after this date (runtime evidence such as crash reports ages out)
    run: check_mixins                   an envx CLI command line, as typed after `envx` (quote args with spaces)
    expect: 0 error(s)                  must appear in the combined output (case-insensitive); re:<regex> for a regex
    expect-not: xaero.pac.client.       must not appear
    answer: ServerTickHandler           a fact a correct agent answer states (for `grade`); re:<regex> allowed

Commands
    python bench/check.py run [Q ...] [--envx build|<version>|<lib dir>]   run the checks; saves results/checks-<version>.json
    python bench/check.py compare <old.json> <new.json>                    answer sizes and failures across envx versions
    python bench/check.py grade <Q> <answer.txt>                           which required facts an agent answer states
    python bench/check.py replay [--session S ...] [--since T] [--via mcp] re-run recorded envx calls, old vs new size

`--envx build` (default) uses engine/build/install; a version uses <data home>/app/<version>. Calls are tagged
"bench" in envx's call log so they never mix with real agent sessions.
"""
from __future__ import annotations

import argparse
import datetime as dt
import glob
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
PRIMARY = {"grep": "pattern", "find": "query", "env": "filter", "outline": "target", "source": "target",
           "refs": "target", "mixins": "target", "check_mixins": "project"}


def data_home() -> Path:  # same order as dev.envx.Config.resolveHome
    if os.environ.get("ENVX_HOME"):
        return Path(os.environ["ENVX_HOME"])
    location = Path.home() / ".envx" / "location"  # one line: a data home kept elsewhere
    if location.is_file() and location.read_text(encoding="utf-8").strip():
        return Path(location.read_text(encoding="utf-8").strip())
    return Path.home() / ".envx"


def lib_dir(envx: str) -> Path:
    if envx == "build":
        return REPO / "engine" / "build" / "install" / "envx" / "lib"
    if re.fullmatch(r"\d+\.\d+\.\d+.*", envx):
        return data_home() / "app" / envx / "lib"
    return Path(envx)


def version_of(lib: Path) -> str:
    jars = sorted(lib.glob("envx-*.jar"))
    if not jars:
        sys.exit(f"no envx jar in {lib}")
    return jars[0].stem.removeprefix("envx-")


def java() -> str:
    if os.environ.get("ENVX_JAVA"):
        return os.environ["ENVX_JAVA"]
    if os.environ.get("JAVA_HOME") and Path(os.environ["JAVA_HOME"], "bin").is_dir():
        return str(Path(os.environ["JAVA_HOME"], "bin", "java"))
    return "java"


def envx(lib: Path, args: list[str], cwd: str) -> tuple[str, int]:
    cmd = [java(), "-Xss4m", "-Denvx.via=bench", "-cp", str(lib / "*"), "dev.envx.cli.Main", *args]
    t0 = time.time()
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    err = "\n".join(l for l in r.stderr.splitlines() if not l.startswith("WARNING:"))
    return (r.stdout + err).strip(), int((time.time() - t0) * 1000)


def local() -> dict:
    """bench/local.json: this machine's workspace folders and environment name (see local.example.json)."""
    f = ROOT / "local.json"
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else {}


def workspace(cwd: str) -> str | None:
    """A {name} placeholder resolved through bench/local.json; plain paths pass through; None when unknown."""
    names = local().get("workspaces", {})
    out = re.sub(r"\{([\w-]+)\}", lambda m: names.get(m.group(1), "\0"), cwd)
    return None if "\0" in out else out


def keys(only: list[str]) -> dict[str, dict]:
    out = {}
    for f in sorted((ROOT / "expected").glob("*.md")):
        q = f.stem.lstrip("0") or "0"
        if only and q not in only and f.stem not in only:
            continue
        m = re.search(r"```check\n(.*?)```", f.read_text(encoding="utf-8"), re.S)
        if not m:
            continue
        spec = {"file": f.name, "cwd": None, "until": None, "run": [], "expect": [], "expect-not": [], "answer": []}
        for line in m.group(1).splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            k, _, v = line.partition(":")
            k, v = k.strip(), v.strip()
            if k in ("cwd", "until"):
                spec[k] = v
            elif k in spec:
                spec[k].append(v)
            else:
                sys.exit(f"{f.name}: unknown check line '{line}'")
        out[q] = spec
    return out


def split_args(line: str) -> list[str]:
    """Whitespace-separated, "double quotes" group; backslashes are kept (regexes, Windows paths)."""
    return [q if q else w for q, w in re.findall(r'"([^"]*)"|(\S+)', line)]


def found(pattern: str, text: str) -> bool:
    if pattern.startswith("re:"):
        return re.search(pattern[3:], text, re.I | re.S) is not None
    return pattern.lower() in text.lower()


def cmd_run(a) -> None:
    lib = lib_dir(a.envx)
    ver = version_of(lib)
    results = {"envx": ver, "at": dt.datetime.now().isoformat(timespec="seconds"), "questions": {}}
    total_fail = 0
    for q, spec in keys(a.questions).items():
        if spec["until"] and dt.date.today().isoformat() > spec["until"]:
            print(f"Q{q}: skipped, its evidence is only kept until {spec['until']}")
            continue
        cwd = workspace(spec["cwd"]) if spec["cwd"] else str(REPO)
        if cwd is None or not Path(cwd).is_dir():
            print(f"Q{q}: skipped, {spec['cwd']} is not a folder on this machine (bench/local.json)")
            continue
        calls, text = [], ""
        for line in spec["run"]:
            out, ms = envx(lib, split_args(line), cwd)
            calls.append({"run": line, "chars": len(out), "ms": ms, "first": out.splitlines()[0][:160] if out else ""})
            text += "\n" + out
        fails = [e for e in spec["expect"] if not found(e, text)] + [f"not {e}" for e in spec["expect-not"] if found(e, text)]
        n = len(spec["expect"]) + len(spec["expect-not"])
        chars = sum(c["chars"] for c in calls)
        ms = sum(c["ms"] for c in calls)
        print(f"Q{q}: {n - len(fails)}/{n} facts, {len(calls)} calls, {chars} chars, {ms / 1000:.1f} s" + ("" if not fails else "  FAIL"))
        for f in fails:
            print(f"    missing: {f}")
        if a.verbose:
            print(text)
        total_fail += len(fails)
        results["questions"][q] = {"facts": n, "failed": fails, "calls": calls, "chars": chars, "ms": ms}
    out = ROOT / "results" / f"checks-{ver}.json"
    out.parent.mkdir(exist_ok=True)
    if a.questions and out.exists():  # a partial run updates its questions and keeps the others
        old = json.loads(out.read_text(encoding="utf-8"))
        results["questions"] = {**old.get("questions", {}), **results["questions"]}
    out.write_text(json.dumps(results, indent=1), encoding="utf-8")
    print(f"envx {ver}: {'all facts found' if not total_fail else f'{total_fail} missing'}; saved {out.relative_to(REPO)}")
    sys.exit(1 if total_fail else 0)


def cmd_compare(a) -> None:
    old, new = (json.loads(Path(p).read_text(encoding="utf-8")) for p in (a.old, a.new))
    print(f"| Q | {old['envx']} chars | {new['envx']} chars | change | {old['envx']} failed | {new['envx']} failed |")
    print("|---|---|---|---|---|---|")
    for q in sorted(set(old["questions"]) | set(new["questions"]), key=lambda x: int(x) if x.isdigit() else 0):
        o, n = old["questions"].get(q), new["questions"].get(q)
        oc, nc = (o or {}).get("chars"), (n or {}).get("chars")
        change = f"{(nc - oc) / oc:+.0%}" if oc and nc else "-"
        print(f"| {q} | {oc or '-'} | {nc or '-'} | {change} | {len((o or {}).get('failed', [])) if o else '-'} | "
              f"{len((n or {}).get('failed', [])) if n else '-'} |")


def cmd_grade(a) -> None:
    spec = keys([a.question]).get(a.question.lstrip("0"))
    if not spec or not spec["answer"]:
        sys.exit(f"no answer facts for Q{a.question}")
    text = Path(a.answer).read_text(encoding="utf-8")
    hits = [f for f in spec["answer"] if found(f, text)]
    for f in spec["answer"]:
        print(("  ok    " if f in hits else "  MISS  ") + f)
    print(f"Q{a.question}: {len(hits)}/{len(spec['answer'])} required facts stated. "
          f"A human confirms the grade against {spec['file']} (facts can be stated wrongly or in other words).")


def cmd_replay(a) -> None:
    lib = lib_dir(a.envx)
    since = dt.datetime.fromisoformat(a.since).astimezone() if a.since else None
    rows = []
    for f in sorted(glob.glob(str(data_home() / "logs" / "calls-*.jsonl"))):
        for line in open(f, encoding="utf-8"):
            r = json.loads(line)
            if r.get("via") != a.via or (a.session and r["session"] not in a.session):
                continue
            if since and dt.datetime.fromisoformat(r["ts"].replace("Z", "+00:00")) < since:
                continue
            rows.append(r)
    if not rows:
        sys.exit("no recorded calls match")
    was = now = 0
    for r in rows:
        args = dict(r["args"])
        tool = r["tool"]
        primary = args.pop(PRIMARY.get(tool, ""), None)
        cmd = ["env-info" if tool == "env" else tool]
        if primary is not None:
            cmd += ["--filter", primary] if tool == "env" else [primary]
        for k, v in args.items():
            cmd += ["--" + k, v]
        out, _ = envx(lib, cmd, r["cwd"])
        was += r["chars"]
        now += len(out)
        first = out.splitlines()[0] if out else ""
        print(f"{r['session'][-4:]} {tool:<12} {json.dumps(r['args'])[:80]}")
        print(f"      {r['chars']} -> {len(out)} chars: {first[:120]}")
    print(f"{len(rows)} calls: {was} -> {now} chars ({(now - was) / max(was, 1):+.0%}) with envx {version_of(lib)}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("run")
    p.add_argument("questions", nargs="*")
    p.add_argument("--envx", default="build")
    p.add_argument("-v", "--verbose", action="store_true", help="print the envx answers")
    p.set_defaults(fn=cmd_run)
    p = sub.add_parser("compare")
    p.add_argument("old")
    p.add_argument("new")
    p.set_defaults(fn=cmd_compare)
    p = sub.add_parser("grade")
    p.add_argument("question")
    p.add_argument("answer")
    p.set_defaults(fn=cmd_grade)
    p = sub.add_parser("replay")
    p.add_argument("--session", action="append", help="session id from envx's call log (repeatable)")
    p.add_argument("--since", help="local date/time, e.g. 2026-09-24T18:00")
    p.add_argument("--via", default="mcp")
    p.add_argument("--envx", default="build")
    p.set_defaults(fn=cmd_replay)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
