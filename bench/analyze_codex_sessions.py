"""Phase 0 baseline: measure how much Codex work went into repeated environment discovery.

Reads ~/.codex/sessions/**/*.jsonl locally (nothing leaves the machine) and classifies every
shell command the agent ran into discovery categories the index engine is meant to replace.
Output sizes are used as a proxy for context cost (~4 chars per token).

Usage: python bench/analyze_codex_sessions.py [--sessions DIR] [--out bench/results]
"""
from __future__ import annotations

import argparse
import collections
import json
import re
from pathlib import Path
from urllib.parse import unquote

# Category -> regex over the command text. First match wins, so order from specific to generic.
CATEGORIES: list[tuple[str, re.Pattern]] = [(name, re.compile(rx, re.I)) for name, rx in [
    ("decompile", r"vineflower|fernflower|\bcfr\b|procyon|quiltflower|genSources|ilspy"),
    ("javap", r"\bjavap\b"),
    ("mappings", r"\.tiny\b|intermediary|yarn[-_ ]|mappings\.|class_\d{2,}|method_\d{2,}|field_\d{2,}"),
    ("jar_extract", r"Expand-Archive|ZipFile|System\.IO\.Compression|\bjar\s+-?[xt]f|\bunzip\b|7z\s+[xel]\b|zipfile"),
    ("mixin_inspect", r"mixins?\.json|refmap|@Inject|@Redirect|@ModifyVariable|@WrapOperation|Mixin"),
    ("mod_metadata", r"fabric\.mod\.json|mods\.toml|neoforge\.mods\.toml"),
    ("gradle_cache", r"\.gradle[\\/]caches|loom-cache|fabric-loom|remapped_mods"),
    ("mod_list", r"[\\/]mods[\\/]|\bmods-?list|Get-ChildItem[^|;]*\bmods\b|\*\.jar"),
    ("build", r"gradlew|gradle\s+build|\bbuild\b.*--no-daemon"),
    ("log_read", r"latest\.log|debug\.log|crash-reports|\.log\b"),
    ("spark", r"sparkprofile|spark"),
    ("text_search", r"\brg\b|Select-String|findstr|\bgrep\b"),
    ("file_read", r"Get-Content|\bcat\b|\btype\b|ReadAllText|sed -n|\bhead\b"),
    ("listing", r"Get-ChildItem|\bls\b|\bdir\b|tree\b|Test-Path"),
]]

SECRETISH = re.compile(r"(pass(word)?|secret|token|api[_-]?key|rcon)\S*\s*[:=]\s*\S+", re.I)


def classify(cmd: str) -> str:
    for name, rx in CATEGORIES:
        if rx.search(cmd):
            return name
    return "other"


def command_text(item: dict) -> str:
    parsed = item.get("parsed_cmd")
    if isinstance(parsed, list) and parsed and isinstance(parsed[0], dict) and parsed[0].get("cmd"):
        return " ; ".join(p.get("cmd", "") for p in parsed)
    cmd = item.get("command")
    return cmd[-1] if isinstance(cmd, list) and cmd else str(cmd or "")


def redact(s: str, limit: int = 160) -> str:
    return SECRETISH.sub(lambda m: m.group(1) + "=<redacted>", " ".join(s.split()))[:limit]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", default=str(Path.home() / ".codex" / "sessions"))
    ap.add_argument("--out", default=str(Path(__file__).parent / "results"))
    args = ap.parse_args()

    cat_count = collections.Counter()
    cat_bytes = collections.Counter()
    cat_examples: dict[str, list[str]] = collections.defaultdict(list)
    cwd_count = collections.Counter()
    jar_mentions = collections.Counter()  # which jars get re-inspected across sessions
    jar_sessions: dict[str, set[str]] = collections.defaultdict(set)
    session_tokens: dict[str, int] = {}
    sessions_with_discovery = set()
    total_cmds = 0

    jar_rx = re.compile(r"([\w.+-]+\.jar)\b", re.I)
    discovery = {"decompile", "javap", "mappings", "jar_extract", "mixin_inspect", "mod_metadata", "gradle_cache", "mod_list"}

    for f in Path(args.sessions).rglob("*.jsonl"):
        sid = f.stem
        with f.open(encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if '"CommandExecution"' in line:
                    try:
                        item = json.loads(line)["payload"]["item"]
                    except (json.JSONDecodeError, KeyError, TypeError):
                        continue
                    cmd = command_text(item)
                    out_len = len(item.get("aggregated_output") or "")
                    cat = classify(cmd)
                    total_cmds += 1
                    cat_count[cat] += 1
                    cat_bytes[cat] += out_len
                    if len(cat_examples[cat]) < 6:
                        cat_examples[cat].append(redact(cmd))
                    cwd_count[unquote(str(item.get("cwd", ""))).replace("file:///", "")] += 1
                    if cat in discovery:
                        sessions_with_discovery.add(sid)
                        for jar in set(jar_rx.findall(cmd)):
                            jar_mentions[jar.lower()] += 1
                            jar_sessions[jar.lower()].add(sid)
                elif '"token_count"' in line:
                    try:
                        info = json.loads(line)["payload"].get("info") or {}
                        total = (info.get("total_token_usage") or {}).get("total_tokens")
                    except (json.JSONDecodeError, KeyError, TypeError):
                        continue
                    if total:
                        session_tokens[sid] = max(session_tokens.get(sid, 0), total)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    disc_cmds = sum(cat_count[c] for c in discovery)
    disc_bytes = sum(cat_bytes[c] for c in discovery)
    all_bytes = sum(cat_bytes.values())

    lines = [
        "# Codex discovery baseline",
        "",
        f"- Sessions scanned: {len(list(Path(args.sessions).rglob('*.jsonl')))}; with token data: {len(session_tokens)}",
        f"- Total tokens across sessions (sum of per-session totals): {sum(session_tokens.values()):,}",
        f"- Shell commands: {total_cmds:,}; output returned to the model: {all_bytes/1e6:.1f} MB (~{all_bytes/4/1e6:.1f}M tokens)",
        f"- **Environment-discovery commands: {disc_cmds:,} ({disc_cmds/max(total_cmds,1):.0%})**, "
        f"output {disc_bytes/1e6:.1f} MB (~{disc_bytes/4/1e6:.1f}M tokens), in {len(sessions_with_discovery)} sessions",
        "",
        "## By category",
        "",
        "| Category | Commands | Output MB | ~Tokens (M) | Discovery? |",
        "|---|---:|---:|---:|---|",
    ]
    for cat, n in cat_count.most_common():
        lines.append(f"| {cat} | {n:,} | {cat_bytes[cat]/1e6:.2f} | {cat_bytes[cat]/4/1e6:.2f} | {'yes' if cat in discovery else ''} |")
    lines += ["", "## Jars re-inspected in the most sessions", "", "| Jar | Sessions | Commands |", "|---|---:|---:|"]
    for jar, sess in sorted(jar_sessions.items(), key=lambda kv: -len(kv[1]))[:30]:
        lines.append(f"| {jar} | {len(sess)} | {jar_mentions[jar]} |")
    lines += ["", "## Commands by working directory", ""]
    lines += [f"- {cwd or '(none)'}: {n:,}" for cwd, n in cwd_count.most_common(12)]
    lines += ["", "## Example commands (redacted, truncated)", ""]
    for cat in cat_count:
        lines.append(f"### {cat}")
        lines += [f"- `{ex.replace('`', ' ')}`" for ex in cat_examples[cat]]
        lines.append("")
    (out / "codex_baseline.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:40]))


if __name__ == "__main__":
    main()
