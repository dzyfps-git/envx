# ADR 0010: Public readiness

Status: accepted (envx 0.7.0); published at 1.0.0 (see Publishing)

## Context
Until 0.6 envx assumed one machine: the base came only from a Loom cache, the data home was found by probing a
drive letter, launchers and Java paths were Windows-only, and every end-to-end test needed the owner's synced index,
so a fresh checkout or CI could only run unit tests.

## Decisions
1. **Base without Loom.** `envx init` (and the first sync) takes Minecraft + Yarn from the Loom cache when present,
   else downloads the client and server jars from Mojang (SHA-1 from Mojang's version metadata, server bundler
   entries by their SHA-256) and Yarn from the Fabric maven (the maven's `.sha256`/`.sha1`), then merges and remaps
   locally with tiny-remapper. A mismatch refuses the file. For 1.20.1 / Yarn build.10 the result has the same 7436
   classes, members, supertypes and resources as Loom's merged jar; the Yarn javadoc and unpick files are identical.
   The merge keeps client classes and adds server-only members, without Loom's `@Environment` marks (envx decides
   client-only code by package).
2. **Data home:** `envx.home` property, `ENVX_HOME`, then `~/.envx/location` (one line, for a home on another
   drive), then `~/.envx`. No drive-letter probing.
3. **Cross-platform:** launcher `app/envx.cmd` on Windows, `app/envx` (sh) elsewhere; `java`/`claude` executables
   per OS; Codex config honors `CODEX_HOME`.
4. **Denied roots are enforced for projects too:** `check_mixins <dir>`, `project:` and `project index` never read a
   folder inside `denyRoots`, including when the agent works inside one.
5. **Fixture environment.** `FixtureEnvTest` builds a two-class "Minecraft", two mods injecting at the same method,
   a log and a mod project in a temp home, then syncs and queries it (names, mixins, check_mixins, the project
   section, log errors, on-demand source, config grep, and a pack update through `diff:`). It needs no network and no
   real index, so CI tests the whole pipeline.
6. **CI** on Linux, Windows and macOS. While the repository is private it runs only when started by hand (private
   Actions minutes are billed, macOS at 10x); once public it runs on every push and pull request.

## Machine-specific settings (decided after 0.7.0)
One codebase that the owner uses and later publishes; no separate personal and public versions. Safety features stay
in code, their machine-specific values move to ignored local files:
- `denyRoots` defaults to empty in code; the owner's folders are listed in the data home's `config.json`.
- `CLAUDE.md`/`AGENTS.md` hold the project rules; `CLAUDE.local.md` (ignored) holds this machine's shares, denied
  files, JDK path and data home. Both agent files point to it.
- Benchmark questions and keys name workspaces as placeholders (`{workspace}`, `{my-mod}`, ...); `bench/local.json`
  (ignored; `local.example.json` is tracked) maps them to folders and names the golden tests' environment. Golden
  tests read it and skip without it.
- The original plan's machine inventory moved to `docs/plan.local.md` (ignored).
- At 1.0 the owner's benchmark (questions, keys, findings, runbook) and the golden tests stayed local: they describe
  a private server and its mods. The public repository has the harness and the summary numbers (`bench/README.md`).

## Publishing (1.0.0)
- License: MIT.
- The public repository starts from one commit of the 1.0 tree under the owner's identity. The development history
  before it (older commits carried machine paths and AI co-author trailers) is kept privately and not rewritten.
- Before that commit the tree was checked for machine- and server-specific details and secrets. Personal planning
  notes became local files (`docs/*.local.md`, ignored); `docs/roadmap.md` is the public summary.
