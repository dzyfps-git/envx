# ADR 0007: Projects — does my mod fit this environment?

Status: accepted (Sevli 0.4.0)

## Context
The baseline and environment layers are strong; the project layer was only `check_mixins` on a built jar. A survey
of the user's disk found 52 Gradle projects:
- nearly all Fabric 1.20.1 / Yarn build.10,
- many copied version folders of the same mod (17 copied folders of one mod, 12 backups of another),
- and many folders holding other mods' sources kept for reference (`audit-reference/`, `jar-inspection/`).
Several build against Loader 0.16.10 while the server runs 0.19.3.

## Decisions (step 1)
1. **A project is found from the working directory, never by scanning drives.** It is the nearest folder at or
   above the cwd with a Gradle build and `src/main/resources/fabric.mod.json`. Scanning would pick up reference
   copies of other people's mods.
2. **Identity is the mod id** from `fabric.mod.json`. Copied version folders of one mod are the same project at
   different versions. Which one is deployed is detected, not configured: the server's jar for that mod id is
   compared with the folder's built jar (by hash) and version.
3. **The built jar is the one whose `fabric.mod.json` has the project's id** (builds also produce harness or side
   jars; the newest jar is not necessarily the mod). `check_mixins` uses the same rule. A jar counts as stale only
   when `src/main` changed after it was built.
4. **Surface: the `env` summary**, when the cwd is in a project, adds a short section. No new tool or parameter.
   - Build settings vs what the server runs: Minecraft, Loader, Fabric API and Java from the loader log. Yarn is
     compared with Sevli's mappings, and Loom/Gradle are listed.
   - Whether this build is deployed (same jar, same version with a different jar, another version, or not
     deployed, with the past versions that had it).
   - Declared dependencies checked against the server with Fabric-style version ranges.
   - Mixin configs, pointing to `check_mixins`.
   Against a past snapshot (`env=myserver@4.0.5`) the same comparison uses that version's server.

   Soft relations are checked too: a `suggests`/`recommends` mod missing from the server is named with the past
   versions that had it (a compat mod whose target mod was removed is dead weight), and a `breaks` mod present
   on the server is flagged.

## Decisions (step 2)
5. **`project:` is opt-in.** Added to a `find`/`outline`/`source`/`refs`/`mixins`/`grep` query, it puts the
   project's built jar (and its nested jars) in place of the deployed copy of the same mod; alone it means the
   project itself, like `mod:<its id>`. Without it answers stay the server's truth, so an agent never mistakes
   an undeployed build for what runs. Answers carry a banner (`[with project build X in place of myserver's X
   1.5.0-rc1]`), like past snapshots do; it combines with `env=myserver@<label>`.
   Automatic overlay inside project folders was rejected: most questions asked from a project folder are
   about the server, and a silently swapped jar would make those answers wrong.
6. **A build is indexed by a separate process.** Query and MCP processes stay read-only (ADR 0002): the first
   `project:` query on a new build runs `sevli project index` and waits for it (about 3 s for a small mod,
   mostly loading mappings). The jar is content-addressed like any mod jar, so an unchanged build is never
   indexed twice and a build identical to the deployed jar is the same artifact.
7. **Builds are not history.** They belong to no snapshot. The newest 3 unreferenced builds per mod id are kept
   (copied version folders share an id); older ones are deleted with their decompile/remap caches.
8. No new tool or parameter: the qualifier lives in the query string like `mod:`. It is announced where it is
   useful (the `env` project section and the project's CLAUDE.md/AGENTS.md block), not in the MCP server
   instructions, which some clients repeat per tool.

## Validation
Tier 1 (`bench/check.py`, see `bench/README.md`): Q8 (`check_mixins` on a mod project) and Q15 (retargeted to a
project with an undeployed build) pass on 0.4.0; Q15 fails 7/7 on 0.3.1. Writing the Q15 key found two bugs, fixed
in 0.4.0: Loom set through `version "${loom_version}"` was not read, and `check_mixins` repeated identical
rival lines (one project: 38 omitted lines → 17). No agent runs for 0.4.0; the paired OFF/ON benchmark is kept
for milestones. A new agent question for `project:` is left until a real task provides one.
