# ADR 0005: Capped answers name what they cut; server searches hide client code

Status: accepted (2026-09-24, envx 0.2.1)

## Context
The 0.2.0 rerun (Codex, GPT-6 Sol at Extra High, 2 runs per cell) cut model requests by 41% and uncached input by
59% against no envx. Q14 ("which classes in open-parties-and-claims handle chunk claim ticking?") stayed
incomplete in every run, with or without envx.

Both envx runs searched `find "*Tick* mod:openpartiesandclaims"`. The answer listed matches alphabetically and was
cut at the 6000-character cap with "… 30 more line(s) omitted; narrow the pattern or add mod:<id>". The cut part
held the expected classes (`PlayerClaimReplaceSpreadoutTask.onTick`, `ServerSpreadoutTaskHandler.onTick`,
`ChunkProtection.onServerTick`), while 13 lines of client-only code (`xaero.pac.client.*`) used up space on a
server environment. The hint also suggested `mod:`, which the query already had. Q14's two envx runs had 11
answers at the cap.

## Decisions
1. **A capped answer lists what it dropped, by name.** `Out.line(text, group, name)` keeps a third of the budget
   for a closing summary: `… N more line(s) omitted: Class(method, method, +2 fields); …`. Search lists methods
   by name and counts fields; outline lists member names. A class is listed whole or not at all, and whatever
   does not fit is counted ("+5 more names in 3 more classes"). Same cap, same schema, no new tool.
2. **Name searches on a server environment leave out client-only packages** (`/client/` in the Yarn name), with
   a count in the header. This matches `mixins`, which already hides client-only mixins. Exact lookups still
   find client classes.
3. **Search names the mod once** in the header when every result is in one mod, instead of on each line.
4. **Refinement hints do not suggest what the query already has.**
5. **Measurement:** the call log records a short hash of each answer, so `extract_tokens.py` can count repeated
   answers to differently worded calls (`*Tick*` and `*tick*` returned identical 6k answers in one Q14 run).
   It also counts capped answers, and Codex tool-catalog lookups per session.

## Not done (considered)
- A "calls into" footer on `source` (interfaces called and their implementations) would have shortened Q14's
  path, but grows every `source` answer. Revisit if the 0.2.1 rerun still misses dispatch through interfaces.
- Shortening the MCP server instructions (Codex repeats them in every tool's catalog entry). Left unchanged
  so the 0.2.1 rerun measures only the changes above.

## Consequences
- An overflowing answer shows fewer full lines (two thirds of the budget) but never hides a match completely.
- Verification: `find "*Tick* mod:openpartiesandclaims"` now names all three expected spread-out classes
  within ~6.1k characters (GoldenIndexTest).

## 0.2.2 follow-up (after the 0.2.1 rerun)
The rerun (Q14 ×2 ON, Q16 OFF/ON ×2) showed two more avoidable costs. Both are fixed without new tools:
- **`grep` groups matches by file:** the path once, then `NN: text` in line order. Agents read whole data files
  with `grep "." path=<file>`. 0.2.1 repeated a ~70-char path on every line and sorted line 1 after line 19.
  Replaying the 59 recorded Q16 grep calls against 0.2.2: 138.6k → 80.6k chars (−42%), same matches.
- **A class name with a wrong package falls back to the class's own name** (dropped-name summaries list names
  without packages, and Q14 guessed `…server.spreadout.ServerSpreadoutTaskHandler`). Two recorded failed
  lookups now resolve.

Validation is by replaying recorded calls and by the call log in normal use, not a new agent benchmark: both
changes alter answer format and lookup, not what an agent can find. The next agent benchmark comes with snapshots.
