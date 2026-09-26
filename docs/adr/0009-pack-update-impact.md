# ADR 0009: Pack-update impact and headless benchmark runs

Status: accepted (Sevli 0.6.0)

## Context
When the pack updates, the question is never only "which mods changed" (the 0.3 diff answers that). It is "does my
mod still work": do its mixin targets still exist, does another mod now inject at the same methods, does it call
mod code that is gone, did a dependency move or leave, did its config change, and did the server log anything new
since. Answering by hand means diffing mod lists, unzipping changed jars and reading refmaps.

The 0.6 milestone also needs paired OFF/ON agent runs (ADR 0007's tier 2). Doing them by hand, with the global
switch flipped between runs, was tedious and error-prone.

## Decisions
1. **`env filter=diff:A..B` becomes the update report**, with no new tool (the env description grows by a clause).
   In a project folder it adds a project section first, so it survives the size cap:
   - **mixins**: every injection point of the project's built jar is checked against both snapshots
     (`MixinCheck.evaluate`, the same logic as `check_mixins`): newly broken, fixed, and other mods' injections at
     the same members that are new or gone. Rivals are keyed by mod id, mixin class and handler, so a mod update
     that keeps the same handler is not "new".
   - **calls into other mods**: the jar's method calls and field accesses to classes outside the project, Minecraft
     and common libraries, resolved in both snapshots (with inheritance). One that resolves in A but not in B is a
     NoSuchMethodError/NoClassDefFoundError waiting to happen.
   - **dependencies**: `depends`/`suggests`/`breaks` whose server version changed, with range checks for B.
   - **configs**: config files whose path names the mod id, as removed/added lines.
2. For everyone: mod lists (dropped by name when cut), config and datapack changes grouped by folder, and when B is
   the current server, what its logs show since B's snapshot (crashes; error, warning and failed-mixin kinds first
   seen since; those involving the project).
3. The env summary's history line says the diff includes the project's impact when run in a project.
4. **Headless tier 2 (`bench/agents.py`).** The global switch stays OFF for the whole benchmark. ON runs add Sevli per
   run through each tool's own flags: Codex `-c mcp_servers.sevli.*` plus `developer_instructions`, Claude Code
   `--mcp-config` plus `--append-system-prompt`. `sevli agents bench-config <dir>` prints the MCP command and the
   instruction block, so both come from Sevli itself. Each ON run's server gets `SEVLI_RUN`, recorded in the call
   log. Tokens come from the tools' session files; answers are graded with the keys' `answer:` facts, then by a
   human. The harness refuses to start unless `sevli agents status` reports the clean baseline.

## Consequences
- The report runs in about 1–3 s: two mixin evaluations and up to 3000 reference checks against SQLite.
- ON runs get the instruction block as a system/developer instruction instead of a CLAUDE.md/AGENTS.md section.
  That is close to, not identical with, daily use; OFF runs are cleaner than before (no stray blocks possible).
- Q18 (what 4.0.5 → current means for one of the owner's mod projects) is the 0.6 tier-1 and tier-2 question.

## Validation
Tier 1: Q18's key was written from the project's sources, the 4.0.5 backup and the live jars; `check.py` finds all
facts on 0.6.0 and none on 0.5.0. Tier 2 (the 0.6 milestone): `python bench/agents.py run 16 17 18`, run by the
user with Sevli switched off globally.
