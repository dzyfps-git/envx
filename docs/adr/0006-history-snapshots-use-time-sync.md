# ADR 0006: History snapshots and use-time sync

Status: accepted (2026-09-24, envx 0.3.0)

## Context
Questions about past pack versions (benchmark Q10, Q12) had no answer: envx indexed only the live server.
Every sync already recorded which jars were present, and jars are stored by hash and never deleted. But:
- config/datapack text was kept only as the current copy,
- snapshots had no version label,
- nothing made sure a sync happened before the server changed.

The user makes full backups after most versions but may forget, sometimes switches the server to a different
modpack, and wants envx to become public and cross-platform. So history cannot depend on an OS scheduler or
on the PC being on at a set time.

## Decisions
1. **A snapshot is identified by content.** A fingerprint covers the jars, the stored text and the loader's list.
   A sync that finds the same fingerprint as the last one adds nothing; it only marks the snapshot as checked. So
   frequent syncs are cheap (about 1 s against the live share) and history has one entry per real change.
2. **Text is stored by hash** under `texts/`, after secret redaction, and listed per snapshot (`snapshot_text`).
   The loader's resolved list is stored per snapshot too (`snapshot_loaded`). `envs/<env>/files` stays as a plain
   copy of the current text for people. Four snapshots of Prominence II cost 7.8 MB of text.
3. **Labels and pack identity come from files packs already ship:** `config/bcc.json` (BetterCompatibilityChecker:
   CurseForge project id, pack name, version). A change of pack is noted in the sync summary. The earlier pack
   stays in history.
4. **`envx env import <env> <folder> [--label]`** adds a past version from a server folder (e.g. a full backup).
   It reads exactly what a sync reads: `mods/` jars, text under the config/datapack folders, `server.properties`
   (redacted) and the loader log. Worlds, libraries, backups and other logs are never read. A label already used by
   a different snapshot is refused, so the pre-reset 4.1.0 backup became `4.1.0-pre-reset`.
5. **Current is the default; history is only used when asked for.**
   - The current snapshot is the latest one from the live source (`kind='sync'`). Imports never become current.
   - Any tool accepts `env=<name>@<label>`. Every answer from a past snapshot starts with
     `[myserver@4.0.5: past snapshot N, not the current server]`.
   - `env` shows one line listing the past versions.
   - `env filter=diff:<a>..<b>` compares versions.
   - No new tool or parameter was added. The schemas are the same size as in 0.2.2 (4.25k chars).
6. **Use-time sync instead of OS scheduling.** When an MCP session starts, or a CLI query runs, and the environment
   was last checked more than `autoSyncHours` ago (default 6, 0 = off), envx starts a separate background
   `env sync --auto`.
   - The caller never waits and never writes, so ADR 0002 holds.
   - A lock file allows one sync per environment. Failures are recorded, and `env` shows them.
   - Jars and texts are written atomically, so a killed background sync never leaves a partial file under a hash.
   - OS schedulers stay an optional extra (they can run `envx env sync <env>`).
   - Limit: a version is lost only if the server changes twice with no envx use and no backup in between.
     envx never runs on or writes to the server.
7. **MCP sessions pin the current snapshot** on first use, so a background sync never changes answers mid-session.
8. **Loader list from rotated logs.** `latest.log` rolls over daily and then lacks the loader's startup list. The
   newest `logs/*.log.gz` that has it is used, which restores the loaded/not-loaded resolution on long-running
   servers.

## Consequences
- Imports of 4.0.4, 4.0.5 and 4.1.0-pre-reset took 34 s, 16 s and 1 s. They added about 100 MB of jars and
  36 MB of index. Each version's jars are read once to hash them; only unknown jars are stored and indexed.
- Schema v2 is applied by `envx setup` and by any sync; read-only processes never migrate.
- Sessions still running 0.2.x pick the snapshot with the highest id, which can now be an import. Start new agent
  sessions after upgrading.

## 0.3.1 follow-up: pointing to history from empty results
Q12 (a history-only question, 2 OFF / 2 ON runs) showed agents do not find history from the one `history:` line
in `env`. Both envx runs got empty results on the current server and went to GitHub, and one checked
`env myserver@4.0.5 filter=origins`, where the exact match `origins` hid `origins-plus-plus`. The data was right; the
answers did not lead to it. Fixes, without new tools:
- **Empty results say where the thing was.** `find`, `outline`, `source`, `refs`, `mixins`, `env filter=` and
  `grep` (resources) on the current server check only the jars that past labeled snapshots loaded and the current
  one does not. When one matches, they add: `Not in the current server, but in past versions: origins-plus-plus 2.4
  (4.0.4, 4.0.5, 4.1.0-pre-reset). Ask with env=myserver@4.1.0-pre-reset.` Nothing is added when history has nothing.
- **An exact mod match names other close matches** in one line: `also matching 'origins': origins-plus-plus, …`.
- Validated by replaying the 31 recorded envx calls of both Q12 envx runs: 14 of the 15 empty results now point
  to origins-plus-plus in history (the 15th searched config, which never had it). The exact-match calls name it.
  Calls that had results are unchanged. An agent rerun is deferred to save credits.
