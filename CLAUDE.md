# Working on this repo

This is the envx engine (`docs/roadmap.md`). Read `docs/adr/` before changing the architecture.
Machine-specific rules and paths (which shares are writable, denied folders, JDK path, data home) are in
`CLAUDE.local.md`, which is not tracked. Read it too when it exists; it adds to the rules below.

## Rules
- **The tool surface is a contract** (`docs/tools.md`). `ToolContractTest` fails on any change to tool names,
  parameters, descriptions or server instructions, and on growth past its size limit; update
  `src/test/resources/tool-contract.txt` only on purpose.
- **Efficiency is the product.** Every tool answer goes through `query/Out` and must stay within its budget.
  Keep tool descriptions to one line and do not add tools lightly; each schema is re-sent on every agent request.
- **Read-only toward environments.** `EnvironmentSource` implementations must never write to a source, whether
  that is the live server share, SSH, or instance folders. Never use a writable path to a live server.
- **Secrets.** Synced text goes through `env/SecretFilter`. Never read inside `denyRoots` (the data home's `config.json`).
- **Names.** Runtime (intermediary) names are canonical in storage. Yarn is stored alongside for display. Answers show Yarn first and runtime in brackets.
- MCP processes open the DB read-only. Only `envx env sync`/`env import`/`project index` write to it (ADR 0002). Use-time
  auto-sync starts a *separate* sync process; never write from query or MCP code (ADR 0006).
- History: the current snapshot is the latest `kind='sync'`; imports are only used via `env=<name>@<label>`, and
  such answers must stay marked as past. Schema changes go through `Db.migrate` (run by `setup` and syncs).
- Server logs and crash reports live in a file mirror (`envs/<env>/runtime`, ADR 0008), outside snapshots. Query
  processes may refresh it (it is a cache, like `decomp/`), never the index.
- Plain Java 21 with no frameworks. Keep it that way unless an ADR says otherwise.
- **One codebase.** Machine- and server-specific paths and settings live in ignored local files (the data home's
  `config.json`, `CLAUDE.local.md`, `bench/local.json`), never in tracked files or code defaults (ADR 0010).

## Build and test
- `./gradlew installDist test` in `engine/` (JDK 21).
- `FixtureEnvTest` runs the whole pipeline on a made-up environment (no index, no network); extend it when a
  feature should be covered on CI. Golden tests (local, not tracked) use a real synced index and `bench/local.json`, and skip without them.
- `installDist` fails while an `envx` process (a sync or an MCP server) has the jars open. Stop it first.
  Registered agents run the copy in `<data home>/app/<version>`, not `build/`.
- Before a release, `python bench/check.py run` must find every fact (tier 1; `bench/README.md`). Agent OFF/ON runs
  are for milestones only (`bench/agents.py`; it needs the user to switch envx off first, and costs credits).
- To release: bump `dev.envx.Version` (and the `version =` line in build.gradle; a blanket replace also hits dependency versions), `installDist`, then run `envx setup --claude --codex`
  from the new build. setup installs into a new `app/<version>` folder and repoints both agents **without** changing
  whether envx is on or off. Old version folders are kept, because running sessions still use them.
- Never switch the user's envx on or off yourself: `envx agents on|off` is their A/B control.
- Remote: `origin` = github.com/dzyfps-git/envx. Push `main` after committing. Keep machine-
  and server-specific details (LAN addresses, share names, drive letters, user paths) out of tracked files.
- Commits use only the owner's configured Git identity: no `Co-Authored-By`, "Generated with" or other AI
  attribution. The repository starts from a squashed 1.0 commit (ADR 0010).
- Java text blocks strip trailing spaces. SQL built as `"""...AND """ + "x"` silently becomes `ANDx`.
