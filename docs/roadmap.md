# Sevli roadmap

Sevli is a local index of a Minecraft/Fabric 1.20.1 environment (a server's mods, configs, logs and history, the
Minecraft base with Yarn names, and your own mod projects) that coding agents query through MCP or a CLI instead of
unzipping jars, running `javap` or decompiling by hand. It is the first part of a larger goal: one workspace for
modding projects with Claude Code and Codex as first-class tools. The design decisions are in `docs/adr/`.

## Principles
- **The index is the product.** Agents use it from the tools they already run; any future app is a client of it.
- **Efficiency first.** Few tools, compact answers that stay within a budget and say what they cut (ADR 0003, 0005).
- **Read-only toward environments.** Sevli never writes to a server, a share or an instance folder.
- **Index bytecode, decompile in the background.** Jars are indexed once by content hash; sources are decompiled
  after a sync at idle priority (and on demand before that), each jar once (ADR 0011).
- **Measured, not assumed.** Every release passes tool-level checks; milestones are validated with paired agent runs
  (`bench/`).

## Status: 2.0.0 (2026-09-26)
- 0.1–0.2: environment index, eight tools, `mod:` scoping, capped answers that name what they cut, measurement,
  an A/B switch for agents.
- 0.3: history snapshots, `env import`, `env=<name>@<version>`, use-time auto-sync; empty results point to the past
  version that had it.
- 0.4: projects. `env` compares a project's build with the server; `project:` puts the project's build in place of
  the deployed jar in any query.
- 0.5: runtime evidence. Log errors, failed mixins and crashes grouped and resolved to mods (ADR 0008).
- 0.6: pack-update impact. `env filter=diff:A..B` reports what an update means for the project in the working
  directory (ADR 0009).
- 0.7: public readiness. `sevli init` without a Loom cache, portable data home, Linux/macOS launchers, a fixture
  environment that tests the whole pipeline, CI on three OSes (ADR 0010).
- 0.8: stable contracts. The tool surface is snapshotted and documented (`docs/tools.md`), schema upgrades are
  guarded and tested.
- 0.9: weak spots found by agent runs, each fixed with a check that fails on the version before.
- 1.0: final validation. With Sevli, agents used a median of 88% fewer tokens at equal or better correctness on
  like-for-like pairs; CI green on Linux, Windows and macOS (`bench/README.md`).
- 1.1: a stable machine interface for other tools: `sevli api`, JSON lines, versioned, read-only, batch lookups with
  portable identifiers (`docs/api.md`). On a server, nested client-only jars that the loader prints but does not
  load no longer count as loaded.
- 1.2: `refs Class.member` finds a class's own uses and calls made through subclasses, picks the class that has the
  member when several share a name, and marks field uses as reads or writes ("never read" answers need no grep).
  1.2.1: `grep` names files whose path matches (an advancement's id is its file name), accepts paths as answers
  show them (`<mod>:<path>`), and a no-match search spends at most 5 s on past versions.
- 1.3: indexing study decisions (ADR 0011). After a sync, every loaded jar is decompiled in a background process at
  idle priority, so `grep scope=source` searches all code instead of classes read before (it found more in 18 of 24
  logged searches); `sevli decompile --status|--stop`. 1.3.1: mods with a method whose Yarn name collides with an
  inherited Minecraft one (1 in 85 jars) are remapped with that member left under its runtime name instead of failing.
- 1.4: trace mining (ADR 0012): `sevli insights` reads Codex and Claude Code session files in place and reports the
  shell work Sevli could have answered, what agents did right after an Sevli answer, answers that cost a follow-up and
  slow calls (then `traces`). Everyday commands: `sevli` (status), `sevli on|off`, `sevli sync`, `sevli stop`, `sevli setup --path`.
  Code search reads one packed file per decompiled jar: a cold search on a spinning disk went from 21 s to 4 s.
  1.4.1, from the first trace report: resource search reads packed files too (cold 55 s -> 1.6 s); grep says when the
  server's current log has matching lines it did not search; `env filter` says it matches jar file names.
  1.4.2: a sync packs each new jar's resources as it extracts them (no background process needed for that).
  1.4.3: config texts are packed per snapshot; remapped jars are removed once their classes are decompiled (150 MB
  on a large pack); the trace report lists folders where agents worked without Sevli and whether they have Sevli's
  instructions, and counts Forge/NeoForge work separately; first-run status shows the setup steps; the Loom cache is
  found through `GRADLE_USER_HOME`.
- 1.5: the engine side of the desktop app (ADR 0013). `catalog` lists supported baselines and published packs
  with sizes; `catalog install <id>` downloads a baseline from the official sources. Nothing is downloaded or
  created unasked: syncs no longer fetch a baseline by themselves, the Loom cache is no longer read, and the data
  home appears with the first install. Own servers are refused unless they run a supported baseline.
  `sevli app-server` is the app's JSON-lines connection.
- 2.0: renamed from envx to Sevli. Existing data homes and agent connections carry over in place, each agent's on/off
  unchanged. Commands are `sevli <verb>`: `browse`, `add`, `connect`, `list`, `sync`, `history`, `diff`, `stop`,
  `on`/`off`, `setup`, `link`, `insights`, `info`, `check` (`sevli help advanced` for the rest); the forms from before
  2.0 and the `envx` command keep working through 2.x.

## Next: supported-only indexing (ADR 0014; engine part done, ships as 2.1)
The public app indexes only what the maintainer has published as supported: exact jar versions (sha256) from
published packs, listed in a signed, generated `supported.json` in the catalog. An own server needs a supported
baseline, not a published pack; its other jars stay unindexed and are shown as coverage ("480 of 500 jars indexed"),
and answers such as "no callers" say so while jars are unindexed. Nothing unapproved is indexed automatically: newly
supported jars wait for the user's click. Configs and scripts refresh on their own without re-indexing jars. The
maintainer's own install indexes everything and reviews what is not published yet. Support can be retired (keeps
working where accepted) or revoked (hidden). Storage cleanup is optional, previewed and confirmed, and touches only
Sevli's own data.

## Then: the desktop app (ADR 0013)
A Windows app that starts empty and offers a curated catalog: supported baselines and modpacks prepared by the
maintainer, published as recipes (official download sources and hashes, never third-party files) and built on the
user's PC. Milestones: packs (export tool, Modrinth, CurseForge, the user's own copy for blocked files), the Electron
app (catalog, downloads, library, own server, agents, trace report, settings), an unsigned per-user installer with the
engine and its Java runtime inside, draft releases.

## After 1.0
- Adaptive, reusable knowledge: notes anchored to what they describe (a jar hash, an environment, a project) and
  reused while the anchor is unchanged, with indexed facts kept separate from AI-derived interpretation, which
  carries its evidence, provenance and verification state.
- Agent sessions inside the desktop app (embedded Claude Code and Codex terminals), builds and reviews.
- A safe modpack updater: what the server customized relative to the pack, config conflicts on update, backups and
  rollback.

## Not planned
A code editor or IDE, a custom chat UI, writes to environments, a cloud component, embeddings or vector search.
Other loaders or Minecraft versions (NeoForge first) are added when there is a real need; Fabric-specific code stays
behind a clean boundary.
