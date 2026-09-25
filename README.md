# AI-First Modding Workspace

The long-term goal is one workspace for modding projects, with Claude Code and Codex as first-class tools.
This repo currently contains **Phase 1: `envx`**, a local index of a Minecraft/Fabric environment that agents
query instead of unzipping jars, running `javap`, or decompiling things by hand.

Status and roadmap: `docs/roadmap.md`. Design decisions: `docs/adr/`. Query reference: `docs/tools.md`.

**Does it help?** In the 1.0 benchmark (18 real questions about a 446-mod Fabric server, GPT-6 Sol at High, the same
question with and without envx), agents used a median of 88% fewer tokens at equal or better correctness. The
questions describe a private server and are not published; the harness is in `bench/` (`bench/README.md`).

## What envx indexes
- Minecraft 1.20.1: intermediary runtime names plus Yarn 1.20.1+build.10, copied from your Loom cache when it has
  them, otherwise downloaded from Mojang and the Fabric maven (hash-checked) and merged and remapped locally.
- Every jar in the server's `mods/` folder, including jar-in-jar. Each jar is indexed once, keyed by its content hash.
  Only mods that the server's `latest.log` shows as loaded count as active.
- Classes, members, inheritance, class-level references, `fabric.mod.json`, and mixins (configs + refmaps).
- Mod data files (recipes, tags, functions, ...) and the server's config/datapack text, with secrets redacted.
- Sources are decompiled **on demand**, one class at a time, with Vineflower, and cached.
- The server's recent logs and crash reports, copied read-only and summarized on demand (not part of snapshots).

## Build
Needs a JDK 21 (Windows, Linux or macOS).
```
cd engine
./gradlew installDist test          # gradlew.bat on Windows
```

## Getting started
```
engine/build/install/envx/bin/envx init              # Minecraft + Yarn base (Loom cache, else ~70 MB download)
envx env add myserver /path/to/server                # or \\host\share, or ssh://host/path (read-only)
envx env sync myserver                               # index its mods, configs and logs
envx link /path/to/my-mod myserver                   # queries from that project use this environment
envx setup --claude --codex                          # install; register the MCP server with Claude Code and Codex
```
Data lives in `~/.envx`. To keep it elsewhere, set `ENVX_HOME` or put the path on one line in `~/.envx/location`.

## Use
```
envx env add myserver \\server\share-readonly ssh://host/path/to/server
envx env sync myserver                    # read-only; tries each source in order
envx link /path/to/my-mod myserver
envx find LivingEntity.tick               # Yarn, intermediary or stack-frame names
envx find "tick mod:opac"                 # mod:<id>[,<id>] scopes any query to those mods
envx mixins LivingEntity.tick             # or: mixins mod:lithium
envx source ServerChunkManager.tick,tickChunks
envx outline MobEntity --filter despawn   # includes inherited members
envx refs "ServerTickEvents mod:openpartiesandclaims"
envx check_mixins /path/to/my-mod
envx setup --claude --codex               # install this version; keeps each tool's on/off state
envx agents on|off|status                 # switch envx for Codex + Claude Code + AGENTS.md/CLAUDE.md

envx env import myserver /path/to/backup       # add a past version from a server folder (label from config/bcc.json)
envx env history myserver                      # snapshots, labels, which one is current
envx env diff myserver 4.0.5 4.1.0             # mod changes between versions
envx find Power --env myserver@4.0.5           # any query against a past snapshot (answers are marked)

cd /path/to/my-mod                             # inside a mod project:
envx env-info                                  # adds how the project builds vs what the server runs
envx mixins "LivingEntity.damage project:"     # project: uses this build's jar in place of the server's copy
envx env-info --filter diff:4.0.5..            # what a pack update means for this project: mixin targets, new
                                               # competing injections, calls into mods that no longer resolve,
                                               # dependencies, its configs, new log errors since the update

envx env-info --filter errors                  # server log errors, failed mixins, crashes: grouped, with owning mods
envx env-info --filter errors:necronomicon     # narrowed to a mod, class or message
envx grep "Watchdog" --scope logs              # the raw lines (logs and crash reports)
```
History is kept automatically: when envx is used and the environment was last checked more than
`autoSyncHours` ago (config.json, default 6, 0 = off), a read-only sync runs in the background. Unchanged
syncs only record the check; a changed pack becomes a new, labeled snapshot. See ADR 0006.
Every tool call is logged locally to
`logs/calls-YYYY-MM.jsonl`. `bench/extract_tokens.py` turns Codex and Claude Code session files, plus that
log, into per-session token, request and tool-call numbers (see `bench/README.md`).
`bench/check.py run` checks, without any agent run, that envx answers contain the facts each benchmark question
needs (the `check` blocks of your answer keys in `bench/expected/`), and compares answer sizes across envx versions.
`bench/agents.py run <Q...>` runs the paired OFF/ON agent benchmark headless (`codex exec`, `claude -p`) with envx
added per run, and reports tokens, requests and answer facts (see `bench/README.md`).

## Layout
- `engine/`: Java 21 engine, split into packages:
  - `index`: jar parsing
  - `env`: sync and snapshots
  - `query`: tool implementations
  - `tools`: MCP server and tool registry
  - `cli`: commands and agent setup
- `bench/`: the benchmark questions, answer keys, tool-level checks (`check.py`), headless agent runs (`agents.py`) and token extraction.
- `docs/`: roadmap, ADRs and the query reference.

## License
MIT (see `LICENSE`).
