# ADR 0011: Indexing study — decisions

Status: accepted (envx 1.2.0 – 1.3.0)

## Context
After 1.0, envx's indexing approach (bytecode index, lazy decompile, intermediary names, per-snapshot scopes) was
compared with the other common approach: decompile everything up front, parse the source into a symbol table, keep
data files in a full-text index and search code with ripgrep. The question was which parts help agents, measured on
real agent calls (the local call log, benchmark calls excluded) rather than argued. Each axis was decided by
experiment; the machine interface (`envx api` v1) hides the internals, so none of this changed its contract.

## Decisions
1. **Member references: fix `refs`, no new index.** Agents grepped source for "who reads this field" because `refs`
   missed a class's own uses, calls through subclasses (bytecode names the receiver's static type) and picked the
   first class of a shared simple name. The bytecode scan already had what was needed; 1.2.0 fixed all three and
   marks field reads and writes. A replay of the recorded calls lost no call site.
2. **Data files: no full-text index.** Resource greps that missed did so because the answer was a file name, paths
   copied from answers did not filter, or the thing existed only in past versions — never because of word forms.
   1.2.1 fixed those (path matches, `<mod>:<path>` filters, a time-capped past scan). A stemmed full-text index would
   add a second copy of all extracted text for no measured gain; revisit only if logs show conceptual searches failing.
3. **Code search: decompile every loaded jar, in the background.** `grep scope=source` searched only classes someone
   had read before, so it missed code that exists. An experiment decompiled Minecraft and the 15 mods agents had
   looked into (2 threads, idle priority, 3 GB heap, one jar at a time): 4 minutes, host CPU load unchanged; on the
   logged searches plus typical keyword searches the full source found more files in 18 of 24, answered 2 of the 4
   that had returned nothing, and never found less. From 1.3.0:
   - after a sync, `envx decompile <env>` starts as a separate process at idle priority (`decompileAll`,
     `decompileThreads`, `decompileMemoryMb` in `config.json`) and decompiles each loaded jar not done yet;
   - it fills the same content-addressed cache `source` uses (`decomp/<jar hash>-<mappings>-<decompiler>/`), marking a
     jar's folder complete, so each jar is decompiled once and a pack update adds only its new jars;
   - `grep scope=source` searches the cache folders of the scope's loaded jars only (past versions stay out), names
     files `<mod>:<path>`, says how many jars are fully decompiled while the run is incomplete, and stops collecting
     after 20,000 matching lines;
   - `envx decompile --status` and `--stop`; a stopped or failed jar is retried after the next sync.
   Like the on-demand cache, the run never writes the index (ADR 0002).
4. **Base symbols from source (tree-sitter): not adopted.** The bytecode index already answers symbol queries with
   Yarn names; line ranges from parsed source would duplicate it.

## Consequences
- A large server pack (about 600 jars with code) costs roughly 20–30 minutes of idle-priority CPU once and a few
  hundred MB under `decomp/`; later syncs add minutes at most.
- `decomp/` keeps folders of jars no longer loaded (past versions). They are a cache and can be deleted; no pruning
  is automatic yet.
- If searching all decompiled code becomes slow, a trigram or symbol index over `decomp/` is the next step; not
  needed at the measured sizes.
