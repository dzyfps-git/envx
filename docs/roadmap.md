# envx roadmap

envx is a local index of a Minecraft/Fabric 1.20.1 environment (a server's mods, configs, logs and history, the
Minecraft base with Yarn names, and your own mod projects) that coding agents query through MCP or a CLI instead of
unzipping jars, running `javap` or decompiling by hand. It is the first part of a larger goal: one workspace for
modding projects with Claude Code and Codex as first-class tools. The design decisions are in `docs/adr/`.

## Principles
- **The index is the product.** Agents use it from the tools they already run; any future app is a client of it.
- **Efficiency first.** Few tools, compact answers that stay within a budget and say what they cut (ADR 0003, 0005).
- **Read-only toward environments.** envx never writes to a server, a share or an instance folder.
- **Index bytecode, decompile lazily.** Jars are indexed once by content hash; sources are decompiled on demand.
- **Measured, not assumed.** Every release passes tool-level checks; milestones are validated with paired agent runs
  (`bench/`).

## Status: 1.2.0 (2026-09-25)
- 0.1–0.2: environment index, eight tools, `mod:` scoping, capped answers that name what they cut, measurement,
  an A/B switch for agents.
- 0.3: history snapshots, `env import`, `env=<name>@<version>`, use-time auto-sync; empty results point to the past
  version that had it.
- 0.4: projects. `env` compares a project's build with the server; `project:` puts the project's build in place of
  the deployed jar in any query.
- 0.5: runtime evidence. Log errors, failed mixins and crashes grouped and resolved to mods (ADR 0008).
- 0.6: pack-update impact. `env filter=diff:A..B` reports what an update means for the project in the working
  directory (ADR 0009).
- 0.7: public readiness. `envx init` without a Loom cache, portable data home, Linux/macOS launchers, a fixture
  environment that tests the whole pipeline, CI on three OSes (ADR 0010).
- 0.8: stable contracts. The tool surface is snapshotted and documented (`docs/tools.md`), schema upgrades are
  guarded and tested.
- 0.9: weak spots found by agent runs, each fixed with a check that fails on the version before.
- 1.0: final validation. With envx, agents used a median of 88% fewer tokens at equal or better correctness on
  like-for-like pairs; CI green on Linux, Windows and macOS (`bench/README.md`).
- 1.1: a stable machine interface for other tools: `envx api`, JSON lines, versioned, read-only, batch lookups with
  portable identifiers (`docs/api.md`). On a server, nested client-only jars that the loader prints but does not
  load no longer count as loaded.
- 1.2: `refs Class.member` finds a class's own uses and calls made through subclasses, picks the class that has the
  member when several share a name, and marks field uses as reads or writes ("never read" answers need no grep).

## After 1.0
- A baseline and indexing optimization study: compare envx's approach with other environment indexes on the same
  questions (agent efficiency, context use, query speed, index size, decompilation strategy) and adopt what wins.
- Adaptive, reusable knowledge: notes anchored to what they describe (a jar hash, an environment, a project) and
  reused while the anchor is unchanged, with indexed facts kept separate from AI-derived interpretation, which
  carries its evidence, provenance and verification state.
- A desktop client ("mission control") for environments, projects, agent sessions, builds and reviews.
- A safe modpack updater: what the server customized relative to the pack, config conflicts on update, backups and
  rollback.

## Not planned
A code editor or IDE, a custom chat UI, writes to environments, a cloud component, embeddings or vector search.
Other loaders or Minecraft versions (NeoForge first) are added when there is a real need; Fabric-specific code stays
behind a clean boundary.
