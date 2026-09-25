"""Tier-2 benchmark, headless: paired OFF/ON agent runs with `codex exec` and `claude -p`, collected and graded.

The global switch stays OFF for the whole benchmark (`envx agents off`; `envx agents status` must say
"=> OFF (clean baseline)"), so no run sees an envx registration or instruction block by accident. An ON run adds
envx for that run only: Codex gets `-c mcp_servers.envx...` and the instruction block as `developer_instructions`;
Claude Code gets `--mcp-config` and the block via `--append-system-prompt`. Nothing is written to either tool's
config. OFF runs get nothing extra. Runs alternate OFF, ON, OFF, ON per question and tool.

Each run's envx server gets ENVX_RUN=<run id>, so its calls are found in envx's call log. Tokens and requests come
from the tool's own session files (the same parsers as extract_tokens.py). Answers are graded with the `answer:`
facts of bench/expected/NN.md; a human confirms the grade against the key.

Commands
    python bench/agents.py plan 16 18 [--tools codex,claude] [--n 2]      the runs and commands, no model calls
    python bench/agents.py run 16 18 [--tools codex] [--n 2] [--envx 0.6.0|build]
    python bench/agents.py report [bench/results/agents-<stamp>]          table per question, tool and condition

Codex always runs GPT-6 Sol at High reasoning (CODEX_MODEL, CODEX_EFFORT; passed with -c on every run and checked
afterwards against Codex's thread list); a run that used anything else counts as failed. Claude Code runs at its
normal model and default effort (no override). Codex runs recorded at another effort (the 0.2-0.6 baselines were
Extra High) are historical evidence, not equivalent: the report keeps them out of the table and the ON/OFF numbers
and lists their OFF runs separately, flagging a question for a new High OFF only when the historical comparison does
not settle it (saving under HISTORICAL_MARGIN, or ON less correct).
Both tools use the normal sandbox/permission configuration; Claude Code runs in auto permission mode with prompts denied.
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
import uuid
import pathlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check  # noqa: E402  (answer keys, grading, envx launcher)
import extract_tokens  # noqa: E402  (session parsers)

ROOT = Path(__file__).resolve().parent
HOME = Path.home()
CODEX_MODEL = "gpt-6-sol"
CODEX_EFFORT = "high"  # every Codex benchmark run since the 1.0 pass; older xhigh runs are not equivalent
HISTORICAL_MARGIN = 0.5  # a saving this large against an older-effort OFF is taken as settled direction


def questions() -> dict[str, tuple[str, str]]:
    out = {}
    for line in (ROOT / "questions.md").read_text(encoding="utf-8").splitlines():
        m = re.match(r"\|\s*(\d+)\s*\|\s*(.+?)\s*\|\s*`([^`]+)`\s*\|", line)
        if m:
            cwd = check.workspace(m.group(3))
            if cwd is not None:  # a question whose workspace this machine does not have is left out
                out[m.group(1)] = (m.group(2), cwd)
    return out


def codex_exe() -> str:
    if os.environ.get("CODEX_EXE"):
        return os.environ["CODEX_EXE"]
    found = sorted((HOME / "AppData/Local/OpenAI/Codex/bin").glob("*/codex.exe"), key=lambda p: p.stat().st_mtime)
    return str(found[-1]) if found else "codex"


def claude_exe() -> str:
    if os.environ.get("CLAUDE_EXE"):
        return os.environ["CLAUDE_EXE"]
    p = HOME / ".local/bin/claude.exe"
    return str(p) if p.exists() else "claude"


def lib_of(envx: str | None) -> Path:
    """The envx the ON runs use. Default: the newest installed version (a build dir changes under a running benchmark)."""
    if envx:
        return check.lib_dir(envx)
    def key(p: Path):
        return [int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", p.name)]
    versions = sorted((d for d in (check.data_home() / "app").iterdir() if (d / "lib").is_dir()), key=key)
    if not versions:
        sys.exit("no installed envx version; run setup or pass --envx build")
    return versions[-1] / "lib"


def envx_text(lib: Path, args: list[str], cwd: str) -> str:
    out, _ = check.envx(lib, args, cwd)
    return out


def bench_config(lib: Path, cwd: str) -> dict:
    out = envx_text(lib, ["agents", "bench-config", cwd], cwd)
    if "{" not in out:
        sys.exit(f"envx in {lib} cannot print a bench config (0.6.0 or later needed): {out[:200]}")
    return json.loads(out[out.index("{"):])


def toml_str(s: str) -> str:
    return json.dumps(s)  # a JSON string is a valid TOML basic string


def command(tool: str, cond: str, run_id: str, question: str, cwd: str, cfg: dict | None, answer: Path, session: str) -> list[str]:
    if tool == "codex":
        cmd = [codex_exe(), "exec", "--json", "--skip-git-repo-check", "-C", cwd, "-o", str(answer),
               "-c", "model=" + toml_str(CODEX_MODEL), "-c", "model_reasoning_effort=" + toml_str(CODEX_EFFORT)]
        if cond == "on":
            c = cfg["command"]
            cmd += ["-c", "mcp_servers.envx.command=" + toml_str(c[0]),
                    "-c", "mcp_servers.envx.args=[" + ", ".join(toml_str(a) for a in c[1:]) + "]",
                    "-c", "mcp_servers.envx.enabled=true", "-c", "mcp_servers.envx.startup_timeout_sec=30",
                    "-c", "mcp_servers.envx.env={ ENVX_RUN = " + toml_str(run_id) + " }",
                    "-c", "developer_instructions=" + toml_str(cfg["instructions"])]
        else:
            cmd += ["-c", "mcp_servers.envx.enabled=false"]
        return cmd + [question]
    cmd = [claude_exe(), "-p", question, "--output-format", "json", "--session-id", session,
           "--permission-mode", "auto", "--permission-prompts", "none"]
    if cond == "on":
        c = cfg["command"]
        mcp = {"mcpServers": {"envx": {"command": c[0], "args": c[1:], "env": {"ENVX_RUN": run_id}}}}
        cmd += ["--mcp-config", json.dumps(mcp), "--append-system-prompt", cfg["instructions"]]
    return cmd


def plan(a) -> list[tuple[str, str, str, int]]:
    qs = questions()
    runs = []
    for q in a.questions:
        if q not in qs:
            sys.exit(f"Q{q} is not in questions.md, or its workspace is not in bench/local.json")
        for tool in a.tools.split(","):
            for i in range(1, a.n + 1):
                for cond in a.conds.split(","):
                    runs.append((q, tool, cond, i))
    return runs


def cmd_plan(a) -> None:
    lib = lib_of(a.envx)
    qs = questions()
    for q, tool, cond, i in plan(a):
        question, cwd = qs[q]
        cfg = bench_config(lib, cwd) if cond == "on" else None
        cmd = command(tool, cond, f"q{q}-{tool}-{cond}-{i}", question, cwd, cfg, Path("<answer>"), "<uuid>")
        shown = [x if len(x) < 90 else x[:60] + "…" for x in cmd]
        print(f"Q{q} {tool} {cond} #{i} in {cwd}\n  " + " ".join(shown))
    print(f"{len(plan(a))} runs")


def baseline_ok(lib: Path) -> bool:
    out = envx_text(lib, ["agents", "status"], str(ROOT))
    if "=> OFF (clean baseline)" in out:
        return True
    print(out)
    print("\nHeadless runs need the clean baseline: run `envx agents off` for the benchmark (and `envx agents on` after).")
    print("ON runs add envx per run; nothing else is changed.")
    return False


# Instructions every session of that tool loads, whatever its working folder. `envx agents off` does not manage them
# (they are the owner's), so a mention of envx there reaches OFF runs: on 2026-09-25 an advisory section in the global
# Codex AGENTS.md led a Q4 OFF run to find and call envx through the shell.
GLOBAL_INSTRUCTIONS = {"codex": [HOME / ".codex/AGENTS.md"], "claude": [HOME / ".claude/CLAUDE.md"]}


def global_ok(tools: list[str]) -> bool:
    found = []
    for tool in tools:
        for f in GLOBAL_INSTRUCTIONS.get(tool, []):
            try:
                lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
            except OSError:
                continue
            hits = [i + 1 for i, line in enumerate(lines) if re.search(r"envx", line, re.I)]
            if hits:
                found.append(f"{f} mentions envx on line(s) {', '.join(map(str, hits[:12]))}{' ...' if len(hits) > 12 else ''}")
    if not found:
        return True
    print("\n".join(found))
    print("\nThese global instructions load into every session of that tool, including OFF runs, and would tell the agent")
    print("about envx. Move that text out for OFF runs and put it back afterwards (the harness never edits them), or pass")
    print("--allow-global-mention to accept the risk (an OFF run that then uses envx is marked contaminated and wasted).")
    return False


def run_one(a, lib: Path, out_dir: Path, q: str, tool: str, cond: str, i: int) -> dict:
    question, cwd = questions()[q]
    run_id = f"q{q}-{tool}-{cond}-{i}-{out_dir.name[-6:]}"
    cfg = bench_config(lib, cwd) if cond == "on" else None
    answer = out_dir / f"{run_id}.answer.txt"
    session = str(uuid.uuid4())
    cmd = command(tool, cond, run_id, question, cwd, cfg, answer, session)
    env = {k: v for k, v in os.environ.items() if not k.startswith("CLAUDECODE") and k != "CLAUDE_CODE_ENTRYPOINT"}
    start = dt.datetime.now().astimezone()
    t0 = time.time()
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                           timeout=a.timeout, env=env, stdin=subprocess.DEVNULL)
        stdout, stderr, code = r.stdout, r.stderr, r.returncode
    except subprocess.TimeoutExpired as e:
        stdout, stderr, code = (e.stdout or ""), (e.stderr or "") + "\nTIMEOUT", -1
        stdout = stdout.decode() if isinstance(stdout, bytes) else stdout
        stderr = stderr.decode() if isinstance(stderr, bytes) else stderr
    secs = int(time.time() - t0)
    (out_dir / f"{run_id}.out").write_text(stdout + "\n--- stderr ---\n" + stderr, encoding="utf-8")

    metrics = None
    error = None
    if tool == "codex":
        thread = next((json.loads(l).get("thread_id") for l in stdout.splitlines()
                       if l.startswith("{") and '"thread.started"' in l), None)
        rollout = next((HOME / ".codex/sessions").rglob(f"*{thread}.jsonl"), None) if thread else None
        if rollout:
            model = effort = None
            try:  # the model and effort Codex actually used, from its thread list
                import sqlite3
                con = sqlite3.connect(f"file:{HOME / '.codex/state_5.sqlite'}?mode=ro", uri=True)
                model, effort = con.execute("select model, reasoning_effort from threads where id=?", (thread,)).fetchone() or (None, None)
            except Exception:
                pass
            metrics = extract_tokens.codex_rollout(rollout, cwd, model, effort, question, start)
        if code != 0 or metrics is None:
            # Codex reports failures (usage limit, auth) as JSON events on stdout; stderr's last line is only noise.
            failed = [json.loads(l) for l in stdout.splitlines() if l.startswith("{") and ('"turn.failed"' in l or '"type":"error"' in l)]
            msg = next((f.get("message") or (f.get("error") or {}).get("message") for f in reversed(failed)
                        if f.get("message") or (f.get("error") or {}).get("message")), None)
            error = msg or next((l for l in reversed(stderr.splitlines()) if l.strip()), f"exit {code}, no session data")
        elif not comparable({"tool": "codex", **metrics}):
            # A changed Codex default or config must not slip into the measurement; stop instead.
            error = f"ran {metrics.get('model')} {metrics.get('effort')}, not {CODEX_MODEL} {CODEX_EFFORT}"
    else:
        try:
            result = json.loads(stdout[stdout.index("{"):])
            if result.get("is_error"):
                error = result.get("result") or result.get("terminal_reason") or "error"
            else:
                answer.write_text(result.get("result") or "", encoding="utf-8")
        except ValueError:
            error = next((l for l in reversed(stderr.splitlines()) if l.strip()), f"exit {code}, no JSON result")
        transcript = next((HOME / ".claude/projects").glob(f"*/{session}.jsonl"), None)
        if transcript and error is None:
            metrics = extract_tokens.claude_transcript(transcript)
    text = answer.read_text(encoding="utf-8") if answer.exists() else ""
    spec = check.keys([q]).get(q)
    facts = spec["answer"] if spec else []
    hits = [f for f in facts if check.found(f, text)]
    calls = envx_calls(run_id, start)
    rec = {"run": run_id, "q": q, "tool": tool, "cond": cond, "i": i, "exit": code, "secs": secs, "error": error,
           "facts": len(facts), "facts_stated": len(hits), "missing": [f for f in facts if f not in hits],
           "envx_calls": len(calls), "envx_chars": sum(c.get("chars", 0) for c in calls),
           **({k: v for k, v in metrics.items() if not k.startswith("_")} if metrics else {"total_tokens": None})}
    if cond == "off" and not rec["error"] and max(len(calls), rec.get("envx_calls") or 0) > 0:
        rec["error"] = f"contaminated: the OFF run used envx ({max(len(calls), rec.get('envx_calls') or 0)} call(s))"
    with (out_dir / "runs.jsonl").open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(rec) + "\n")
    return rec


def envx_calls(run_id: str, since: dt.datetime) -> list[dict]:
    out = []
    for f in sorted((check.data_home() / "logs").glob("calls-*.jsonl")):
        for line in f.open(encoding="utf-8"):
            if run_id in line:
                o = json.loads(line)
                if o.get("run") == run_id:
                    out.append(o)
    return out


def cmd_run(a) -> None:
    lib = lib_of(a.envx)
    # Only OFF runs are harmed by a mention of envx (ON runs have envx anyway); --allow-global-mention accepts the risk,
    # and an OFF run that then uses envx is still marked contaminated.
    offs = "off" in a.conds.split(",")
    if not baseline_ok(lib) or (offs and not a.allow_global_mention and not global_ok(a.tools.split(","))):
        sys.exit(2)
    out_dir = ROOT / "results" / ("agents-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    out_dir.mkdir(parents=True)
    (out_dir / "meta.json").write_text(json.dumps({"envx": check.version_of(lib), "questions": a.questions, "tools": a.tools,
                                                   "codex": f"{CODEX_MODEL} {CODEX_EFFORT}", "claude": "default effort",
                                                   "n": a.n, "started": dt.datetime.now().isoformat(timespec="seconds")}), encoding="utf-8")
    runs = plan(a)
    stopped: dict[str, str] = {}
    for k, (q, tool, cond, i) in enumerate(runs, 1):
        if tool in stopped:
            continue
        print(f"[{k}/{len(runs)}] Q{q} {tool} {cond} #{i} ...", flush=True)
        r = run_one(a, lib, out_dir, q, tool, cond, i)
        if r["error"]:
            # A failure without token data (login, quota, a broken flag) would fail every later run the same way.
            print(f"    FAILED: {r['error'][:300]}\n    no more {tool} runs in this benchmark", flush=True)
            stopped[tool] = r["error"]
            continue
        facts = f"{r['facts_stated']}/{r['facts']}" if r["facts"] else "no key"
        print(f"    {r['secs']} s, {r.get('total_tokens')} tokens, {r.get('requests')} requests, "
              f"{r['envx_calls']} envx calls, facts {facts}", flush=True)
    for tool, err in stopped.items():
        print(f"{tool}: stopped after a failed run ({err[:200]})")
    print(f"saved {out_dir.relative_to(ROOT.parent)}")
    report([out_dir], [])


def comparable(r: dict) -> bool:
    """Codex runs count only at the benchmark's model and effort; Claude Code runs at its default are all comparable."""
    return r.get("tool") != "codex" or (r.get("model"), r.get("effort")) == (CODEX_MODEL, CODEX_EFFORT)


def baseline_rows(path: str) -> list[dict]:
    """OFF runs recorded earlier, reused as baselines (OFF does not depend on envx): a results folder (its OFF runs
    only) or an extract_tokens.py CSV (runs without envx calls, matched to questions by their prompt)."""
    if pathlib.Path(path).is_dir():
        return [dict(r, run="baseline:" + r["run"]) for r in graded(pathlib.Path(path)) if r.get("cond") == "off"]
    import csv
    csv_path = path
    prompts = {q: " ".join(text.split()).lower()[:60] for q, (text, _) in questions().items()}
    rows = []
    for r in csv.DictReader(open(csv_path, encoding="utf-8")):
        if str(r.get("envx_calls", "0")) not in ("0", ""):
            continue
        p = " ".join((r.get("prompt") or "").split()).lower()
        q = next((k for k, v in prompts.items() if p[:40] and v.startswith(p[:40])), None)
        if q is None:
            continue
        rows.append({"run": f"baseline:{pathlib.Path(csv_path).name}:{r.get('start')}", "q": q, "tool": r["tool"], "cond": "off",
                     "exit": 0, "facts": 0, "facts_stated": 0, "model": r.get("model"), "effort": r.get("effort"),
                     "secs": int(r["span_s"]) if str(r.get("span_s", "")).isdigit() else None,
                     **{k: int(r[k]) for k in ("total_tokens", "uncached_in", "requests", "shell_calls", "envx_calls") if str(r.get(k, "")).isdigit()}})
    return rows


def graded(d: Path) -> list[dict]:
    out = []
    try:
        envx = json.loads((d / "meta.json").read_text(encoding="utf-8")).get("envx")
    except (OSError, ValueError):
        envx = None
    for l in (d / "runs.jsonl").open(encoding="utf-8"):
        r = json.loads(l)
        r.setdefault("envx", envx)
        answer = d / f"{r['run']}.answer.txt"
        spec = check.keys([r["q"]]).get(r["q"])
        if spec and answer.exists():  # grade saved answers with the current keys (keys get fixed after runs)
            text = answer.read_text(encoding="utf-8")
            r["facts"] = len(spec["answer"])
            r["facts_stated"] = sum(1 for f in spec["answer"] if check.found(f, text))
        out.append(r)
    return out


def report(dirs: list[Path], baselines: list[str]) -> None:
    all_rows = [r for d in dirs for r in graded(d)]
    for b in baselines:
        all_rows += baseline_rows(b)
    for r in all_rows:  # runs recorded before the harness checked this
        if r.get("cond") == "off" and not r.get("error") and (r.get("envx_calls") or 0) > 0:
            r["error"] = f"contaminated: the OFF run used envx ({r['envx_calls']} call(s))"
    ok = [r for r in all_rows if not r.get("error") and r.get("exit") == 0 and r.get("total_tokens")]
    rows = [r for r in ok if comparable(r)]
    # A weak spot fixed and rerun supersedes the runs on older envx: each ON cell counts only its newest envx version
    # (OFF runs do not depend on envx).
    def ver(r):
        return [int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", r.get("envx") or "0")]
    newest = {}
    for r in rows:
        if r["cond"] == "on":
            k = (r["q"], r["tool"])
            newest[k] = max(newest.get(k, ver(r)), ver(r))
    superseded = [r for r in rows if r["cond"] == "on" and ver(r) < newest[(r["q"], r["tool"])]]
    rows = [r for r in rows if r not in superseded]
    other = collections.Counter(f"{r['tool']} {r.get('model') or '?'} {r.get('effort') or ''}".strip() for r in ok if not comparable(r))
    cells = collections.defaultdict(list)
    for r in rows:
        cells[(r["q"], r["tool"], r["cond"])].append(r)

    def mean(rs, k):
        v = [r[k] for r in rs if isinstance(r.get(k), (int, float))]
        return sum(v) / len(v) if v else None

    def fmt(x, pct=False):
        return "-" if x is None else f"{x:,.0f}"

    print("| Q | tool | cond | n | facts stated | total tokens | uncached in | requests | shell calls | envx calls | wall s |")
    print("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for (q, tool, cond), rs in sorted(cells.items(), key=lambda x: (int(x[0][0]), x[0][1], x[0][2] != "off")):
        facts = f"{mean(rs, 'facts_stated'):.1f}/{rs[0]['facts']}" if rs[0]["facts"] else "no key"
        print(f"| {q} | {tool} | {cond} | {len(rs)} | {facts} | {fmt(mean(rs, 'total_tokens'))} | {fmt(mean(rs, 'uncached_in'))} | "
              f"{fmt(mean(rs, 'requests'))} | {fmt(mean(rs, 'shell_calls'))} | {fmt(mean(rs, 'envx_calls'))} | {fmt(mean(rs, 'secs'))} |")
    print("\nON vs OFF (mean total tokens):")
    for (q, tool, cond), rs in sorted(cells.items()):
        if cond != "on" or (q, tool, "off") not in cells:
            continue
        on, off = mean(rs, "total_tokens"), mean(cells[(q, tool, "off")], "total_tokens")
        if on and off:
            print(f"  Q{q} {tool}: {on / off - 1:+.0%}")
    # Older Codex OFF runs at another effort are evidence, not a measurement: shown only where no equivalent OFF exists.
    hist = collections.defaultdict(list)
    for r in ok:
        if not comparable(r) and r["cond"] == "off":
            hist[r["q"]].append(r)
    lines = []
    for q, hs in sorted(hist.items(), key=lambda x: int(x[0])):
        on = cells.get((q, "codex", "on"))
        if not on or (q, "codex", "off") in cells:
            continue
        saving = mean(on, "total_tokens") / mean(hs, "total_tokens") - 1
        graded_off = [h for h in hs if h.get("facts")]
        less_correct = graded_off and mean(on, "facts_stated") < mean(graded_off, "facts_stated")
        facts = (f"facts ON {mean(on, 'facts_stated'):.1f} vs OFF {mean(graded_off, 'facts_stated'):.1f}" if graded_off
                 else "OFF facts not recorded (bench/findings.md)")
        need = saving > -HISTORICAL_MARGIN or less_correct
        lines.append(f"  Q{q} codex: {saving:+.0%} vs {hs[0].get('effort')} OFF (n={len(hs)}), {facts}"
                     + ("  -> needs a High OFF (Stage 2)" if need else "  -> direction settled"))
    if lines:
        print(f"\nHistorical, NOT equivalent (Codex {CODEX_EFFORT} ON vs older OFF at another effort; not in the 1.0 median;"
              f" a High OFF is needed only if the saving is under {HISTORICAL_MARGIN:.0%} or ON is less correct):")
        print("\n".join(lines))
    models = collections.Counter(f"{r['tool']} {r.get('model') or '?'} {r.get('effort') or ''}".strip() for r in rows)
    print("\nmodels: " + ", ".join(f"{k} x{v}" for k, v in models.items()) + " (keep one setting per tool across a benchmark)")
    if superseded:
        print("superseded (an ON run on a newer envx exists for the same question and tool): "
              + ", ".join(f"{r['run']} ({r.get('envx')})" for r in superseded))
    if other:
        print(f"not equivalent, outside the table (Codex measures only at {CODEX_MODEL} {CODEX_EFFORT}): "
              + ", ".join(f"{k} x{v}" for k, v in other.items()))
    failed = [f"{r['run']} ({(r.get('error') or 'no token data')[:80]})" for r in all_rows if r not in ok]
    if failed:
        print("runs that failed or have no token data: " + ", ".join(failed))
    print("\nFacts are a string match; confirm grades against bench/expected/NN.md (answers: <run>.answer.txt).")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("plan", cmd_plan), ("run", cmd_run)):
        p = sub.add_parser(name)
        p.add_argument("questions", nargs="+")
        p.add_argument("--tools", default="codex,claude")
        p.add_argument("--n", type=int, default=1, help="rounds per question and tool (add repeats only where needed)")
        p.add_argument("--conds", default="off,on", help="off,on (default) or on: reuse existing OFF baselines")
        p.add_argument("--envx", default=None, help="an installed version (default: the newest), build, or a lib dir")
        p.add_argument("--timeout", type=int, default=2400, help="seconds per run")
        p.add_argument("--allow-global-mention", action="store_true",
                       help="run OFF runs although global instructions mention envx (risk: contaminated, wasted runs)")
        p.set_defaults(fn=fn)
    p = sub.add_parser("report")
    p.add_argument("dirs", nargs="*", help="result folders to combine (stages); default: the newest")
    p.add_argument("--baseline", action="append", default=[],
                   help="results folder (its OFF runs) or extract_tokens.py CSV (runs without envx calls) "
                        "reused as OFF baselines (repeatable)")
    p.set_defaults(fn=lambda a: report([Path(d) for d in a.dirs] or [sorted((ROOT / "results").glob("agents-*"))[-1]], a.baseline))
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
