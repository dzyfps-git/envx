# Machine interface (`envx api`, version 1)

Status: **draft contract, not implemented yet** (planned for envx 1.1). Tools can be built against it now with test
data; details may still change until 1.1 is released, after which version 1 only grows.

The CLI and MCP answers are compact text written for agents and are not a stable format. Programs use this
interface instead, and never read envx's database, whose schema changes through migrations.

## Transport
- `envx api` reads requests as JSON lines on stdin and writes one JSON line per request to stdout, in order, then
  exits at end of input. Batch many lookups into one process: each start costs a JVM launch (about 0.5-1 s).
- `envx api --version` prints `{"api":1,"envx":"1.1.0"}`.
- Read-only: the index is opened read-only, and an api call never starts a sync, even when the environment is due
  for one. Nothing sent to envx is stored.

## Envelope
Request: `{"id": <any JSON value, echoed>, "op": "<operation>", ...parameters}`

Response: `{"api":1, "id": ..., "ok": true, "result": {...}}`, or
`{"api":1, "id": ..., "ok": false, "error": {"code": "...", "message": "..."}}`.

Error codes: `bad_request`, `unknown_op`, `unknown_env`, `unknown_snapshot`, `too_many` (over 5000 items in one
request), `internal`. One failed request does not stop the batch.

**Compatibility.** Within version 1, operations and fields are only added, never removed or changed in meaning.
Clients ignore fields they do not know. A breaking change would be `"api": 2`, served next to version 1.

## Identifiers
- `env`: an environment name (default: the data home's `defaultEnv`).
- A **snapshot** is one observed state of an environment. `fingerprint` (sha256 over its jars, synced config text
  and the loader's resolved mod list) is the durable key: store it with anything you cache. `id` is a shorter handle
  valid in this data home. Requests take either `"snapshot": <id>` or `"fingerprint": "<sha256>"`; without either,
  the environment's current snapshot is used.
- `modset`: sha256 over the loader's resolved list, as sorted lines `<mod id>@<version>` (mod ids lowercased; the
  `java` entry left out). Snapshots that differ only in configs share a modset: the code is the same. Snapshots
  from before envx 0.3 have neither (`null`) and never match. Other tools may list mods slightly differently (a
  profiler's list can leave out entries the loader prints): `match` reports `missing`/`extra`, and the
  normalization above is settled once against real lists before 1.1.
- Classes use runtime (intermediary) names, dotted or slashed (`net.minecraft.class_1309` or
  `net/minecraft/class_1309`); inner classes with `$`. Methods are name plus JVM descriptor. Answers are dotted.
- Artifacts (jars) are identified by `sha256`, with `mod`, `version` and `file` for display.

## Operations

### `snapshots`
`{"op":"snapshots","env":"myserver"}` → every snapshot, newest first:
`{"snapshots":[{"id":12,"fingerprint":"…","modset":"…","label":"4.1.0","kind":"sync","taken_at":"…","checked_at":"…","current":true}]}`.
`kind` is `sync` (seen on the live source) or `import` (a past pack folder). `taken_at` is when envx first saw it,
not when the server started with it: envx sees a change only at its next sync.

### `match`
Which snapshots ran this mod set?
`{"op":"match","env":"myserver","mods":[["lithium","0.11.2"],["fabric-api","0.92.2+1.20.1"], …]}` →
- `{"status":"match","modset":"…","snapshots":[12,9]}`: every snapshot with exactly this resolved list;
- `{"status":"none","modset":"…","closest":{"snapshot":12,"missing":[["x","1.0"]],"extra":[["y","2.0"]]}}`: no
  snapshot has it. `closest` is for display, never an answer: lookups against it are not about what ran.

### `owner`
Who defines these methods?
`{"op":"owner","snapshot":12,"keys":[{"class":"net.minecraft.class_1309","method":"method_5773","desc":"()V"}, …]}` →
one result per key, in order:
```
{"status":"probable",
 "candidates":[{"mod":"minecraft","version":"1.20.1","sha256":"…","file":"…","loaded":true,"nested_in":[]}],
 "class_found":true, "member_found":true,
 "yarn":{"class":"net.minecraft.entity.LivingEntity","method":"tick","desc":"()V"},
 "mixin":null}
```
- `status`: `probable` (one candidate, or one that the server log shows as loaded among several copies),
  `ambiguous` (several; all listed, none preferred), `none` (unknown). envx never answers `exact`: it knows what the
  jars contain, not which bytes the JVM loaded.
- `loaded`: the copy the loader's resolved list names (for libraries bundled by several mods).
- Hidden lambda classes (`…$$Lambda$N/0x…`) resolve as their host class, with `"hidden_lambda":true`. Synthetic
  `lambda$…` methods are members of their class and resolve normally.
- **Merged mixin methods.** Mixin renames injected handlers into the target class as
  `<kind>$<hash>$<modid>$<handler>` (handler, redirect, modify, wrapOperation, localvar, …). The hash changes between
  runs, so envx ignores it and matches (target class, kind, mod id, handler name) against the declared mixins:
  `"mixin":{"mod":"…","version":"…","sha256":"…","mixin_class":"…","kind":"Inject","handler":"…","target":"…"}`, and
  the candidates are that mod's jar.

### `mixins`
Which mixins target these classes or methods?
`{"op":"mixins","snapshot":12,"targets":[{"class":"net.minecraft.class_1309","method":"method_5773","desc":"()V"}, …]}`
(`method`/`desc` optional: without them, every mixin into the class) → per target:
`{"mixins":[{"mod":"…","version":"…","sha256":"…","mixin_class":"…","kind":"Overwrite","handler":"…","at":"INVOKE …","priority":1000,"cancellable":false,"side":"common","failed":false}]}`.
These are **declared** mixins; `failed` is true when the server log reported that it did not apply. An `Overwrite`
replaces the method's body under its vanilla name, so ask for every hot vanilla method, not only merged frames.

### `diff`
What changed between two snapshots?
`{"op":"diff","from":9,"to":12}` →
`{"added":[{"mod":"…","version":"…","sha256":"…"}],"removed":[…],"changed":[{"mod":"…","from":"1.0","to":"1.1","from_sha256":"…","to_sha256":"…"}]}`.

## Not in this interface
Anything written by envx on request (sync, import, setup), source code and decompilation, and any storage of the
caller's data. Callers keep their own results, keyed by `fingerprint` or `modset`.
