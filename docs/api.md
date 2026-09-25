# Machine interface (`envx api`, version 1)

Status: **implemented in envx 1.1.0.** Within version 1, operations and fields are only added.

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
`{"api":1, "id": ..., "ok": false, "error": {"code": "...", "message": "..."}}`. `result` is always an object; an
operation that answers per item (`owner`, `mixins`) puts them in `result.results`, one per item, in request order.
A line that is not JSON gets `"id": null`.

Error codes: `bad_request`, `unknown_op`, `unknown_env`, `unknown_snapshot`, `too_many` (over 5000 items in one
request), `internal`. One failed request does not stop the batch.

**Compatibility.** Within version 1, operations and fields are only added, never removed or changed in meaning.
Clients ignore fields they do not know. A breaking change would be `"api": 2`, served next to version 1.

## Identifiers
- `env`: an environment name (default: the data home's `defaultEnv`, or the only environment).
- A **snapshot** is one observed state of an environment. `fingerprint` (sha256 over its jars, synced config text
  and the loader's printed mod list) is the durable key: store it with anything you cache. `id` is a shorter handle
  valid in this data home. `owner` and `mixins` take `"snapshot": <id or fingerprint>` or `"fingerprint": "<sha256>"`;
  without either, the environment's current snapshot is used.
- `modset`: sha256 over the mods the loader actually loaded, as sorted lines `<mod id>@<version>` joined by `\n`
  (ids lowercased, versions as printed, build metadata after `+` kept), including `java`, `minecraft` and
  `fabricloader`. This is the list whose size is the log's `Loading N mods` and what `FabricLoader.getAllMods()`
  returns (spark's list, for example): the loader's printed tree also shows nested client-only jars that a dedicated
  server does not load, and envx leaves those out. Snapshots that differ only in configs share a modset: the code is
  the same. Snapshots without a loader list (before envx 0.3, or an imported backup without logs) have `"modset":
  null` and never match.
- Classes use runtime (intermediary) names, dotted or slashed (`net.minecraft.class_1309` or
  `net/minecraft/class_1309`); inner classes with `$`. Methods are name plus JVM descriptor. Answers are dotted.
- A **jar** is `{"mod","version","sha256","file"}`. Minecraft is `"mod":"minecraft"` at the environment's version; a
  library without `fabric.mod.json` has `"mod":null` and is identified by `sha256` and its `nested_in`.
- A **snapshot reference** in answers is `{"id","env","fingerprint","modset"}`.

## Operations

### `snapshots`
`{"op":"snapshots","env":"myserver"}` →
`{"env":"myserver","snapshots":[{"id":12,"fingerprint":"…","modset":"…","label":"4.1.0","kind":"sync","taken_at":"…","checked_at":"…","current":true}, …]}`,
newest first. `kind` is `sync` (seen on the live source) or `import` (a past pack folder). `taken_at` is when envx
first saw it, not when the server started with it: envx sees a change only at its next sync.

### `match`
Which snapshots ran this mod set?
`{"op":"match","env":"myserver","mods":[["lithium","0.11.2"],["fabric-api","0.92.2+1.20.1"], …]}` (the full list
as the caller has it, e.g. `getAllMods()`; `{"id":..,"version":..}` objects are accepted too) →
- `{"modset":"…","status":"match","snapshots":[12,9]}`: every snapshot with exactly this list, newest first;
- `{"modset":"…","status":"none","closest":{"snapshot":12,"only_in_request_count":1,"only_in_request":[["x","1.0"]],"only_in_snapshot_count":2,"only_in_snapshot":[["y","2.0"], …]}}`:
  no snapshot has it (lists capped at 50). `closest` is for display, never an answer: lookups against it are not
  about what ran. Without any snapshot that has a loader list, `closest` is absent.

### `owner`
Who defines these methods?
`{"op":"owner","snapshot":12,"keys":[{"class":"net.minecraft.class_1309","method":"method_5773","desc":"()V"}, …]}`
(`method` and `desc` optional) →
```
{"snapshot":{"id":12,"env":"myserver","fingerprint":"…","modset":"…"},
 "results":[
  {"status":"probable",
   "candidates":[{"mod":"minecraft","version":"1.20.1","sha256":"…","file":"…","loaded":true,"nested_in":[]}],
   "class_found":true, "member_found":true,
   "yarn":{"class":"net.minecraft.entity.LivingEntity","method":"tick","desc":"()V"},
   "mixin":null},
  …]}
```
- `candidates`: every jar in the snapshot that defines the class; `loaded` is false for copies the server does not
  load (an older nested duplicate, a client-only library on a server); `nested_in` lists the jars bundling it
  (`{"mod","version","sha256"}`).
- `status`: `probable` (exactly one loaded candidate), `ambiguous` (several loaded; all listed, none preferred),
  `none` (no loaded candidate). envx never answers `exact`: it knows what the jars contain, not which bytes the JVM
  loaded.
- `member_found`: null when no `method` was given. `yarn` fields are null when unknown.
- Hidden lambda classes (`…$$Lambda$N/0x…`) resolve as their host class, with `"hidden_lambda":true`. Synthetic
  `lambda$…` methods are members of their class and resolve normally.
- **Merged mixin methods.** Mixin renames injected handlers into the target class as
  `<kind>$<hash>$<modid>$<handler>` (handler, redirect, modify, wrapOperation, localvar, …). The hash changes between
  runs, so envx ignores it and matches (target class, mod id, handler name) against the declared mixins. Then
  `candidates` is that mixin's jar, `yarn.method` is the handler, and
  `"mixin":{"mixin_class","config","kind","handler","target","at","priority","cancellable","side","failed","mod","version","sha256"}`
  (the same fields as `mixins` below).

### `mixins`
Which mixins target these classes or methods?
`{"op":"mixins","snapshot":12,"targets":[{"class":"net.minecraft.class_1309","method":"method_5773","desc":"()V"}, …]}`
(`method`/`desc` optional: without them, every mixin into the class) →
```
{"snapshot":{…},
 "results":[
  {"mixins":[{"mod":"…","version":"…","sha256":"…","nested_in":[],"mixin_class":"…","config":"….mixins.json",
              "kind":"Inject","handler":"…","target":"method_5773()V","at":"HEAD","priority":1000,
              "cancellable":false,"side":"common","failed":false}, …]},
  …]}
```
These are **declared** mixins of loaded jars (client-only mixins are left out on a server). `failed` is true when the
current server's log reported that the mixin did not apply, false when it did not, and null for past snapshots (no
log evidence). An `Overwrite` replaces the method's body under its vanilla name, so ask for every hot vanilla method,
not only merged frames.

### `diff`
What changed between two snapshots? `from` and `to` are snapshot ids or fingerprints.
`{"op":"diff","from":9,"to":12}` →
`{"from":{…},"to":{…},"added":[{"mod","version","sha256","file"}],"removed":[…],"changed":[{"mod":"…","from":"1.0","to":"1.1","from_sha256":"…","to_sha256":"…"}]}`.
Loaded jars with a mod id, compared by mod id.

## Not in this interface
Anything written by envx on request (sync, import, setup), source code and decompilation, and any storage of the
caller's data. Callers keep their own results, keyed by `fingerprint` or `modset`.
