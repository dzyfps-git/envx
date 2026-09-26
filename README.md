# AI-First Modding Workspace

The long-term goal is one workspace for modding projects, with Claude Code and Codex as first-class tools.
This repo currently contains **Phase 1: `sevli`**, a local index of a Minecraft/Fabric environment that agents
query instead of unzipping jars, running `javap`, or decompiling things by hand.

Status and roadmap: `docs/roadmap.md`. Design decisions: `docs/adr/`. Query reference: `docs/tools.md`. Programs
(not agents) use the versioned JSON interface `sevli api` (`docs/api.md`).

**Does it help?** In the 1.0 benchmark (18 real questions about a 446-mod Fabric server, GPT-6 Sol at High, the same
question with and without Sevli), agents used a median of 88% fewer tokens at equal or better correctness. The
questions describe a private server and are not published; the harness is in `bench/` (`bench/README.md`).

## What Sevli indexes
- A supported baseline, when you install it: Minecraft 1.20.1 with intermediary runtime names plus Yarn
  1.20.1+build.10, downloaded from Mojang and the Fabric maven (hash-checked) and merged and remapped locally.
  Nothing is downloaded until you choose it (ADR 0013).
- The jars in the server's `mods/` folder that are publicly supported: exact versions (by content hash) from the
  published packs, listed in a signed catalog file. Other jars are hashed, never stored or indexed, and shown as
  coverage ("480 of 500 jars indexed"); answers such as "no callers" say which jars they could not look into. Jars
  that become supported later are indexed only when you accept them (`sevli accept`; ADR 0014). Each jar, with its
  jar-in-jar, is indexed once, keyed by its content hash. Only mods that the server's `latest.log` shows as loaded
  count as active.
- Classes, members, inheritance, class-level references, `fabric.mod.json`, and mixins (configs + refmaps).
- Mod data files (recipes, tags, functions, ...) and the server's config/datapack text, with secrets redacted.
- Sources are decompiled with Vineflower and cached: on demand for a class, and every loaded jar in a background
  process after a sync (idle priority, 2 threads; `decompileAll` in `config.json`), so code search covers all of it.
- The server's recent logs and crash reports, copied read-only and summarized on demand (not part of snapshots).

## Build
Needs a JDK 21 (Windows, Linux or macOS).
```
cd engine
./gradlew installDist test          # gradlew.bat on Windows
```

## Getting started
```
engine/build/install/sevli/bin/sevli add fabric-1.20.1   # the baseline: Minecraft 1.20.1 + Yarn (~75 MB)
sevli connect myserver /path/to/server                # or \\host\share, or ssh://host/path (read-only)
sevli link /path/to/my-mod myserver                   # queries from that project use this server
sevli setup                   # install; register with Claude Code and Codex; put `sevli` on PATH
```
After that, `sevli` in a new terminal shows the status and the everyday commands. After the first sync, Sevli
decompiles every loaded jar once in the background (idle priority, 2 threads, about 10 minutes for a 600-jar pack;
`decompileAll` in `config.json` turns it off), so code search covers everything.
Data lives in `~/.sevli`. To keep it elsewhere, set `SEVLI_HOME` or put the path on one line in `~/.sevli/location`.

## Everyday
```
sevli                                      # status: agents on/off, how fresh each server is, background work
sevli on | off                             # switch Sevli on/off for Codex and Claude Code (new sessions)
sevli sync                                 # pull the default environment's mods and configs now
sevli stop                                 # stop the background decompile (the next sync continues it)
sevli accept myserver                      # index jars that became publicly supported (never done unasked)
sevli insights                             # what agents still rediscovered by hand, from their session files (ADR 0012)
sevli clean                                # free space: previews what can go and what can be rebuilt, then asks
sevli help                                 # every command (sevli help advanced for the rest)
```

## Use
```
sevli connect myserver \\server\share-readonly ssh://host/path/to/server
sevli sync myserver                    # read-only; tries each source in order
sevli link /path/to/my-mod myserver
sevli find LivingEntity.tick               # Yarn, intermediary or stack-frame names
sevli find "tick mod:opac"                 # mod:<id>[,<id>] scopes any query to those mods
sevli mixins LivingEntity.tick             # or: mixins mod:lithium
sevli source ServerChunkManager.tick,tickChunks
sevli outline MobEntity --filter despawn   # includes inherited members
sevli refs "ServerTickEvents mod:openpartiesandclaims"
sevli check /path/to/my-mod
sevli setup                            # install this version; keeps each agent's on/off state
sevli on | off                         # switch Sevli for Codex + Claude Code + AGENTS.md/CLAUDE.md
sevli agents                           # each part of that switch in detail
sevli remove myserver                  # stop following a server; its history stays, the server is never touched

sevli import myserver /path/to/backup       # add a past version from a server folder (label from config/bcc.json)
sevli history myserver                      # snapshots, labels, which one is current
sevli diff myserver 4.0.5 4.1.0             # mod changes between versions
sevli find Power --env myserver@4.0.5           # any query against a past snapshot (answers are marked)

cd /path/to/my-mod                             # inside a mod project:
sevli info                                  # adds how the project builds vs what the server runs
sevli mixins "LivingEntity.damage project:"     # project: uses this build's jar in place of the server's copy
sevli info --filter diff:4.0.5..            # what a pack update means for this project: mixin targets, new
                                               # competing injections, calls into mods that no longer resolve,
                                               # dependencies, its configs, new log errors since the update

sevli info --filter errors                  # server log errors, failed mixins, crashes: grouped, with owning mods
sevli info --filter errors:necronomicon     # narrowed to a mod, class or message
sevli grep "Watchdog" --scope logs              # the raw lines (logs and crash reports)
```
History is kept automatically: when Sevli is used and the environment was last checked more than
`autoSyncHours` ago (config.json, default 6, 0 = off), a read-only sync runs in the background. Unchanged
syncs only record the check; a changed pack becomes a new, labeled snapshot. See ADR 0006.
Every tool call is logged locally to
`logs/calls-YYYY-MM.jsonl`. `bench/extract_tokens.py` turns Codex and Claude Code session files, plus that
log, into per-session token, request and tool-call numbers (see `bench/README.md`).
`bench/check.py run` checks, without any agent run, that Sevli answers contain the facts each benchmark question
needs (the `check` blocks of your answer keys in `bench/expected/`), and compares answer sizes across Sevli versions.
`bench/agents.py run <Q...>` runs the paired OFF/ON agent benchmark headless (`codex exec`, `claude -p`) with Sevli
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
MIT (see `LICENSE`). Bundled third-party components and their licences: `THIRD-PARTY-NOTICES.md`.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
