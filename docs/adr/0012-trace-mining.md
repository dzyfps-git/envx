# ADR 0012: Trace mining

Status: accepted (envx 1.4.0)

## Context
envx exists to stop agents rediscovering an environment by hand. Whether it does is visible in the agents' own
session files: every `javap`, jar listing, decompiler run or log read an agent made instead of asking envx. Reading
those files turns anecdotes into a ranked list of what to improve, and costs agents nothing (unlike asking them to
report gaps). Later, the same mining finds conclusions worth keeping as notes; engine fixes come first because they
help every mod, not one.

## Decisions
1. **`envx traces [--days 30]`**, a read-only CLI report (no MCP tool, so no schema cost for agents).
2. **Mine in place, store nothing.** Codex rollouts (`$CODEX_HOME/sessions`, else `~/.codex/sessions`) and Claude Code
   transcripts (`$CLAUDE_CONFIG_DIR/projects`, else `~/.claude/projects`) are read where the tools keep them; files not
   modified within the window are not opened. Nothing is copied, cached or kept after the report prints. A full
   month of sessions reads in about 30 s, so incremental offsets are not needed yet.
3. **Left out:** headless runs (`codex exec`, `claude -p`: benchmarks and scripts), sessions whose working directory
   is inside a `denyRoots` folder (only their header is read), and sessions in envx's own repository.
4. **What counts as rediscovery:** shell commands in categories envx answers (javap, decompilers, reading jars,
   mapping names, mixin configs, `fabric.mod.json`, Gradle caches, searching mod code, listing mods, and a server's own
   logs). A project's own test runs (`run/logs`, harness folders) are the agent checking its work and do not count.
   Each category names the envx way, what it was about (jar names without versions; words that are class names in the
   index) and the two largest recent examples.
5. **Sections:** shell work envx could have answered; shell work within three calls after an envx answer (the answer
   did not settle it); envx answers that cost a follow-up (empty, capped then asked again, errors, repeats); slow envx
   calls from envx's own call log, with the version that answered; and the sessions with the most shell discovery.
   Counts since envx was first used are shown separately, because earlier work could not have used it.
6. **Privacy:** the report is printed locally. Commands are cut to one line, secret-looking values are redacted with
   the same filter as synced text, and the home folder is shown as `~`. Provenance is a pointer (tool, date, project
   folder name, short session id), never a copy.

## Consequences
- The first report on a real machine showed most rediscovery predates envx; since envx was first used, the largest
  remaining categories were mapping lookups, searching mod code and javap, and agents still listed mod folders or
  read the server's log directly right after envx answers. Those feed the next engine improvements.
- Findings are not stored yet. When the note store exists (knowledge design), mining feeds it proposals with
  evidence and provenance; until then this report is the whole feature.
