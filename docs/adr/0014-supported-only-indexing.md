# ADR 0014: Supported-only indexing, owner review, optional cleanup

Status: accepted; engine part from Sevli 2.1.0, app screens and the catalog's generator in later milestones

## Context
ADR 0013 made the public app offer only supported baselines and packs, but an own server on a supported baseline was
still indexed jar by jar, whatever it ran. The maintainer wants the public app to index only what they have chosen to
support and published, never anything unapproved, while users can still connect their own servers without matching a
published pack. The maintainer's own install must index everything, to test new jars before publishing them.

## Decisions
1. **Supported means an exact jar.** The catalog publishes `supported.json` next to `index.json`: jars by sha256, with
   mod id, version, the packs that list them, a status (`active`, `retired`, `revoked`) and `since`, the list version
   that added them. It is generated from the published pack recipes, never edited by hand, and signed with the
   maintainer's Ed25519 key (`supported.json.sig`); Sevli carries the public key and never uses a list that does not
   verify. The last good copy is kept in `<home>/catalog/` so syncs work offline. `sevli catalog keygen|sign` are the
   maintainer's tools.
2. **The gate is in the sync.** Every jar on a server is hashed (read-only; unchanged jars by the size/mtime cache).
   Only jars the environment's accepted list supports are stored and indexed; the rest are recorded in
   `snapshot_unindexed` (an added table) with id, version and reason, and count in the snapshot's identity. Nested jars
   belong to the jar that bundles them; the baseline and the user's own project builds (`project:`) are exempt.
3. **Nothing unapproved is indexed automatically.** Connecting a server accepts the list of that moment
   (`supportAccepted`). Automatic syncs keep running for servers the user added, but jars a newer list adds wait for
   the user's click (`sevli accept <name>`, the app's notification).
4. **The rule holds at query time too** (`query/Visibility`). Jars indexed before this rule, or revoked since, are
   hidden from every query of that environment, current and past snapshots alike, with the jars nested only in them.
   Hidden data is not deleted. **Retired** jars keep working where they were accepted and are not offered to newly
   connected servers; only **revoked** jars (an explicit decision, with a reason) disappear everywhere.
5. **Answers stay honest with partial coverage.** While jars on the server are not indexed, `refs`, `mixins`,
   `check_mixins`, `grep` and every "No ..." answer end with one note naming how many jars were not looked into and a
   few of them; `env` shows "N of M jars indexed". Answers with full coverage are unchanged, and no tool, parameter or
   description changed (ToolContractTest).
6. **Configs refresh without jar work.** A config-only change makes a new snapshot without indexing a jar; the snapshot's
   `checked_at` is when configs were last checked. Copying logs and crash reports is a per-server switch, on by
   default (`sevli logs <name> on|off`).
7. **The maintainer's install indexes everything** (`supportPolicy: "all"`, `sevli owner on`, not offered in the app).
   `sevli review` (app: Review) lists what it runs that the public list does not cover: new mods, new versions of
   supported mods, retired and revoked jars. Publishing stays a catalog change the maintainer makes and signs.
8. **Cleanup is optional and previewed.** `sevli clean` offers only what nothing uses (history of removed servers,
   snapshots the user names, the jar data only they use, leftover temporary files), never a current snapshot, a
   baseline or a project build. Each jar is labelled by exact hash: can be rebuilt (Modrinth serves that file), recovery
   not verified (the check could not run), or cannot be recovered. A final confirmation lists sizes, the space freed
   and a history-loss warning; files go to the Recycle Bin. `sevli remove <name>` removes a server's settings, links and
   Sevli's copies for it; its history and jar data stay. Nothing outside the data home is ever touched.
9. **Everything indexed stays on the user's PC.** Nothing is uploaded; the catalog is written only by the maintainer.

## Consequences
- A public install indexes nothing from a server until a signed list is published; the baseline itself still works.
- The new table is an addition, not a new schema version: agent sessions still running an older release keep reading
  the index after an upgrade.
- Display names are still stored once per jar. When a second baseline arrives, names move to a per-baseline table
  (the same jar under two mappings); decompiled source and remapped jars are already keyed by jar and baseline.
