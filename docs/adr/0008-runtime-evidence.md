# ADR 0008: Runtime evidence from server logs and crash reports

Status: accepted (Sevli 0.5.0)

## Context
The index says what mods *declare*. What actually happens on the server is in its logs and crash reports.
In past Codex sessions, agents ran 342 commands that read logs (23 sessions, ~5.5M characters of output) and 25
that read crash reports. The live server writes about 1.3M log lines in two weeks: most warnings repeat thousands
of times (one ability warning 75k times), and log lines use intermediary names (`class_1657.method_6073`) with no
logger name, so the owning mod is not visible. Crash reports are already Yarn-named (the server runs
StackDeobfuscator) but name today's mods, not the versions that crashed.

Spark accounted for even more agent output (707 commands, ~14M characters), but a separate Spark analyzer already
archives and attributes profiles. Spark stays out of Sevli (see Next).

## Decisions
1. **A local log mirror, not snapshot content.** `envs/<env>/runtime/` holds `logs/latest.log`, rotated
   `logs/*.log.gz` (last `logDays`, default 14) and `crash-reports/*.txt` (60 days), copied read-only from the
   environment's source. Rotated logs and crash reports never change and are copied once. Logs are not part of
   snapshots or fingerprints: they change constantly and are not pack state.
2. **Fresh when asked.** Sync refreshes the mirror, and a log query refreshes it when it is more than 2 minutes old,
   so "the server just crashed" is answerable. The mirror is a file cache like decompiled sources: query and MCP
   processes may write it, never the index (ADR 0002 still holds). An unreachable source falls back to the copy,
   with its age stated.
3. **`env filter=errors[:text]`**, no new tool (the env description grows by one clause):
   - crashes, newest first, identical crashes merged, with the mod versions each report lists;
   - mixins that failed to apply (Mixin's own "Mixin apply for mod X failed" lines), once per mixin;
   - errors and warnings grouped by signature: level, message with numbers, UUIDs, hashes and resource-id paths
     normalized, exception type, and the first mod frame (else the first non-library frame). Each kind shows its
     count, first and last time, variant count and up to three frames worth reading (mod code and mixin handlers,
     resolved to Yarn names and owning mods).
   - Kinds first seen after the current pack version are marked NEW; blame on kinds last seen before it says the
     mods may have been older.
   - `:text` narrows by message, class, or mod id.
4. **Per-file summaries are cached** (`runtime/.parsed/`, versioned). A warm answer takes ~0.6 s, a first one ~3 s.
   Sync warms the cache. Redaction (`SecretFilter`) is applied to what is shown, not to 1M parsed lines.
5. **`grep scope=logs`** searches the mirrored logs (gzip included) and crash reports for the lines around one error.
6. **Declared → applied, first step.** `mixins` and `check_mixins` mark a mixin as
   `FAILED to apply on the server (log <date>)` when the logs say so. This reads the mirror as it is (no refresh)
   from the cached summaries.
7. Past snapshots have no logs: `env=<name>@<label> filter=errors` says so instead of answering from today's logs.

## Validation
Tier 1: Q17 ("why did the server crash on 2026-09-10, and which mod?") with a key written from the crash reports;
its check applies until the reports leave the 60-day window (`until:` in the check block). Found while building:
the first version blamed today's mod version for a crash of an older one (fixed with the reports' own mod lists), and
two watchdog crashes with different culprits merged because both stacks start in Minecraft code (fixed: signatures
key on the first mod frame).

## Next
- Spark: not in Sevli. Profile analysis belongs to a separate tool, and a batch frame-resolution interface
  would serve only that one consumer. Revisit if several projects need the same resolver. Single frames already
  resolve through `find` (intermediary or Yarn, with owning mod).
- Applied mixins beyond failures (e.g. Mixin's audit output) only if agents need it.
