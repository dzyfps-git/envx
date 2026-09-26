"""Per-session token and tool usage for benchmark runs (local files only, nothing is sent anywhere).

Codex:       ~/.codex/state_5.sqlite (thread list) + each thread's rollout .jsonl
Claude Code: ~/.claude/projects/*/*.jsonl transcripts
sevli:        <SEVLI_HOME>/logs/calls-*.jsonl (with --sevli-log)

At Extra High reasoning the cost of a run is roughly (model requests) x (context size), so the
report shows requests next to tokens. Archive benchmark threads instead of deleting them, or
extract before deleting: a deleted Codex thread takes its token data with it.

Examples:
  python bench/extract_tokens.py --since 2026-09-24 --match "ServerChunkManager.tick"
  python bench/extract_tokens.py --since 2026-09-24T18:00 --tool codex --csv bench/results/runs.csv
  python bench/extract_tokens.py --since 2026-09-24 --sevli-log
"""
from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import json
import os
import sqlite3
from pathlib import Path

HOME = Path.home()


def parse_time(s: str | None) -> dt.datetime | None:
    if not s:
        return None
    t = dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
    return t if t.tzinfo else t.astimezone()  # naive input = local time


def ts(value) -> dt.datetime | None:
    try:
        return parse_time(value) if isinstance(value, str) else None
    except ValueError:
        return None


def row(tool, start, end, cwd, prompt, model, effort, total, uncached, cached, output, requests, sevli, sevli_chars, shell, other):
    return {
        "tool": tool, "start": start.astimezone().strftime("%Y-%m-%d %H:%M") if start else "",
        "span_s": int((end - start).total_seconds()) if start and end else "",
        "cwd": cwd or "", "prompt": " ".join((prompt or "").split())[:70], "model": model or "", "effort": effort or "",
        "total_tokens": total, "uncached_in": uncached, "cached_in": cached, "output": output, "requests": requests,
        "sevli_calls": sevli, "sevli_chars": sevli_chars, "shell_calls": shell, "other_tools": other,
        "tool_lookups": 0,
    }


# ---------------------------------------------------------------- Codex

def codex_sessions(since, until):
    db = HOME / ".codex" / "state_5.sqlite"
    if not db.exists():
        return []
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    q = "select rollout_path, cwd, model, reasoning_effort, first_user_message, created_at_ms from threads where source not like '%subagent%'"
    out = []
    for path, cwd, model, effort, first, created in con.execute(q):
        start = dt.datetime.fromtimestamp(created / 1000).astimezone()
        if (since and start < since) or (until and start > until) or not path or not os.path.exists(path):
            continue
        out.append(codex_rollout(Path(path), cwd, model, effort, first, start))
    return [r for r in out if r]


def codex_rollout(path: Path, cwd, model, effort, first, start):
    total = None
    requests = records = 0
    sevli = sevli_chars = shell = other = lookups = 0
    end = start
    with path.open(encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            end = ts(o.get("timestamp")) or end
            p = o.get("payload") or {}
            kind = p.get("type") if isinstance(p, dict) else None
            if kind == "custom_tool_call" and "ALL_TOOLS" in str(p.get("input")):
                lookups += 1  # Codex code mode: the model searches the tool catalog before its first MCP call
            if o.get("type") == "token_usage_record":
                records += 1
            elif kind == "token_count" and p.get("info"):
                total = p["info"].get("total_token_usage") or total
                requests += 1
            elif kind == "item_completed":
                it = p.get("item") or {}
                t = it.get("type")
                if t == "McpToolCall":
                    if it.get("server") == "sevli":
                        sevli += 1
                        sevli_chars += len(json.dumps(it.get("result") or ""))
                    else:
                        other += 1
                elif t == "CommandExecution":
                    cmd = json.dumps(it.get("command"))
                    if "sevli.cmd" in cmd or "sevli.bat" in cmd:
                        sevli += 1
                        sevli_chars += len(it.get("aggregated_output") or "")
                    else:
                        shell += 1
                elif t in ("DynamicToolCall", "WebSearch", "FileChange"):
                    other += 1
    if not total:
        return None
    cached = total.get("cached_input_tokens", 0)
    r = row("codex", start, end, cwd, first, model, effort, total.get("total_tokens", 0),
               total.get("input_tokens", 0) - cached, cached, total.get("output_tokens", 0),
               records or requests, sevli, sevli_chars, shell, other)
    r["tool_lookups"] = lookups
    return r


# ---------------------------------------------------------------- Claude Code

def claude_sessions(since, until):
    out = []
    for f in (HOME / ".claude" / "projects").glob("*/*.jsonl"):
        mtime = dt.datetime.fromtimestamp(f.stat().st_mtime).astimezone()
        if since and mtime < since:
            continue
        r = claude_transcript(f)
        if r and (not since or r["_start"] >= since) and (not until or r["_start"] <= until):
            out.append({k: v for k, v in r.items() if not k.startswith("_")})
    return out


def claude_transcript(f: Path):
    usage_by_msg = {}
    tool_ids = {}
    first = cwd = model = None
    start = end = None
    sevli_chars = 0
    sevli_ids = set()
    with f.open(encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = ts(o.get("timestamp"))
            if t:
                start = start or t
                end = t
            cwd = cwd or o.get("cwd")
            msg = o.get("message") or {}
            if o.get("type") == "user" and first is None and not o.get("isMeta"):
                c = msg.get("content")
                text = c if isinstance(c, str) else " ".join(b.get("text", "") for b in c or [] if isinstance(b, dict) and b.get("type") == "text")
                if text and not text.lstrip().startswith("<"):
                    first = text
            if o.get("type") == "user" and isinstance(msg.get("content"), list):
                for b in msg["content"]:
                    if isinstance(b, dict) and b.get("type") == "tool_result" and b.get("tool_use_id") in sevli_ids:
                        sevli_chars += len(json.dumps(b.get("content")))
            if o.get("type") == "assistant":
                model = msg.get("model") or model
                if msg.get("id") and msg.get("usage"):
                    usage_by_msg[msg["id"]] = msg["usage"]
                for b in msg.get("content") or []:
                    if isinstance(b, dict) and b.get("type") == "tool_use":
                        name = b.get("name", "")
                        inp = json.dumps(b.get("input"))
                        if name.startswith("mcp__sevli__") or (name in ("Bash", "PowerShell") and "sevli.cmd" in inp):
                            kind = "sevli"
                            sevli_ids.add(b.get("id"))
                        elif name in ("Bash", "PowerShell"):
                            kind = "shell"
                        else:
                            kind = "other"
                        tool_ids[b.get("id")] = kind
    if not usage_by_msg:
        return None
    u = collections.Counter()
    for us in usage_by_msg.values():
        for k in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens"):
            u[k] += us.get(k) or 0
    kinds = collections.Counter(tool_ids.values())
    total = sum(u.values())
    r = row("claude", start, end, cwd, first, model, "", total, u["input_tokens"] + u["cache_creation_input_tokens"],
            u["cache_read_input_tokens"], u["output_tokens"], len(usage_by_msg), kinds["sevli"], sevli_chars, kinds["shell"], kinds["other"])
    r["_start"] = start
    return r


# ---------------------------------------------------------------- sevli call log

CAPPED_CHARS = 5800  # answers this long hit (or nearly hit) sevli's default 6000-char cap


def sevli_log(since, until):
    from check import data_home  # same resolution as sevli itself
    home = data_home()
    sessions = collections.defaultdict(list)
    for f in sorted((home / "logs").glob("calls-*.jsonl")):
        for line in f.open(encoding="utf-8"):
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = ts(o.get("ts"))
            if (since and t and t < since) or (until and t and t > until):
                continue
            sessions[o.get("session")].append(o)
    print("\n## sevli call log\n")
    print("| session | via | cwd | calls | by tool | answer chars | repeated identical calls | repeated answers | capped answers | errors |")
    print("|---|---|---|---:|---|---:|---:|---:|---:|---:|")
    for sid, calls in sessions.items():
        by_tool = collections.Counter(c["tool"] for c in calls)
        keys = collections.Counter((c["tool"], json.dumps(c.get("args"), sort_keys=True)) for c in calls)
        repeats = sum(n - 1 for n in keys.values() if n > 1)
        # Same answer text to differently worded calls (e.g. *Tick* and *tick*); logged since 0.2.1.
        hashes = collections.Counter(c["hash"] for c in calls if c.get("hash") and not c.get("error"))
        same_answer = sum(n - 1 for n in hashes.values() if n > 1)
        capped = sum(1 for c in calls if c.get("chars", 0) >= CAPPED_CHARS)
        print(f"| {sid} | {calls[0].get('via')} | {Path(calls[0].get('cwd', '')).name} | {len(calls)} | "
              f"{', '.join(f'{k} {v}' for k, v in by_tool.most_common())} | {sum(c.get('chars', 0) for c in calls):,} | "
              f"{repeats} | {same_answer} | {capped} | {sum(1 for c in calls if c.get('error'))} |")


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--since", help="local date/time, e.g. 2026-09-24 or 2026-09-24T18:00")
    ap.add_argument("--until")
    ap.add_argument("--match", help="substring of the first prompt (case-insensitive)")
    ap.add_argument("--cwd", help="substring of the working directory")
    ap.add_argument("--tool", choices=["codex", "claude", "both"], default="both")
    ap.add_argument("--csv", help="also write rows to this CSV file")
    ap.add_argument("--sevli-log", action="store_true", help="also summarize sevli's own call log")
    a = ap.parse_args()
    since, until = parse_time(a.since), parse_time(a.until)

    rows = []
    if a.tool in ("codex", "both"):
        rows += codex_sessions(since, until)
    if a.tool in ("claude", "both"):
        rows += claude_sessions(since, until)
    if a.match:
        rows = [r for r in rows if a.match.lower() in r["prompt"].lower()]
    if a.cwd:
        rows = [r for r in rows if a.cwd.lower() in r["cwd"].lower()]
    rows.sort(key=lambda r: r["start"])

    cols = ["tool", "start", "span_s", "prompt", "total_tokens", "uncached_in", "output", "requests", "sevli_calls", "shell_calls", "other_tools", "tool_lookups"]
    print("| " + " | ".join(cols) + " |")
    print("|" + "---|" * len(cols))
    for r in rows:
        print("| " + " | ".join(f"{r[c]:,}" if isinstance(r[c], int) else str(r[c]).replace("|", "/") for c in cols) + " |")
    if not rows:
        print("(no sessions matched)")
    if a.csv:
        Path(a.csv).parent.mkdir(parents=True, exist_ok=True)
        with open(a.csv, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()) if rows else ["tool"])
            w.writeheader()
            w.writerows(rows)
    if a.sevli_log:
        sevli_log(since, until)


if __name__ == "__main__":
    main()
