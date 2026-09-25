# envx tools and query syntax (the 1.0 contract)

Agents reach envx through eight MCP tools; the CLI has the same queries (`envx <tool> ...`, `env` is `envx env-info`).
Names, parameters and descriptions are snapshotted in `engine/src/test/resources/tool-contract.txt`; changing them is a
deliberate, reviewed change. Every answer is size-capped (6000 characters by default, `--budget` on the CLI) and
says what it left out and how to narrow.

## Names
Any of these resolve to the same class or member:
- Yarn: `LivingEntity.tick`, `net.minecraft.entity.LivingEntity`
- intermediary (what runs on the server): `class_1309.method_5773`, `net.minecraft.class_1309`
- a stack frame: `net.minecraft.class_1309.method_5773(class_1309.java:1234)`
- mod classes by simple or full name. A wrong package still finds the class by its simple name.

Answers show Yarn first and the runtime name in brackets.

## Qualifiers (in the main argument of find, outline, source, refs, mixins, grep)
| Qualifier | Meaning |
|---|---|
| `mod:<id>[,<id>]` | Results only from these mods (and their nested jars). Targets still resolve against the whole environment. `mod:<id>` alone describes the mod (find, outline) or lists its injections (mixins). |
| `project:` or `project:<id>` | In a mod project folder: use its built jar in place of the server's copy of the same mod. The answer starts with a `[with project build ...]` line. |

## Environments and history (`env` parameter on every tool)
- Default: the environment linked to the working directory (`envx link`), else the default one.
- `name@<label>` (for example `myserver@4.0.5`) queries a past snapshot; such answers start with a `[... past snapshot ...]`
  line. `name@current` is the live one. Server logs exist only for the current snapshot.

## Tools
| Tool | Main argument | Other parameters |
|---|---|---|
| `env` | `filter`: empty = summary (plus a project section in a mod project); `a,b` = mods (one exact id: its details, including the server files named for it, since 0.9.4); `diff:<from>..<to>` = what changed between pack versions and for the current project (`<to>` defaults to current); `errors[:text]` = server log errors, failed mixins and crashes (a date or range in the text, `09-10` or `09-01..09-10`, selects by time; since 0.9.3) | `env` |
| `find` | `query`: a name, a wildcard (`*Tick*`) or, with `mod:`, a keyword (then also the mod's calls to other code named like it and its data files mentioning it; since 0.9.4) | `env` |
| `outline` | `target`: a class, or `mod:<id>` | `filter` (members containing it, inherited ones too), `env` |
| `source` | `target`: `Class.member`, `Class.a,b,c` or `Class` | `lines` (`a-b`), `env` |
| `refs` | `target`: `Class.member` (call sites and overriders; field uses marked read or write, with a count; calls through subclasses and the class's own uses included, since 1.2.0) or `Class` (who uses it) | `env` |
| `mixins` | `target`: `Class`, `Class.method`, or `mod:<id>` | `includeAccessors`, `env` |
| `check_mixins` | `project`: a mod project folder (default: working directory; needs a built jar) | `env` |
| `grep` | `pattern`: a case-insensitive Java regex (in JSON files a match also shows its whole small object or array, lines marked `N-`; since 0.9.8) | `scope` (`config`, `resources`, `source`, `logs`; comma-separated), `path` (paths containing it), `env` |

## Guarantees
- Read-only toward every environment source. Only `envx env sync`, `env import` and `project index` write the index.
- Folders in `denyRoots` (the data home's `config.json`) are never read. Synced text and log output pass a secret filter.
- Mixins are *declared* (from mod jars); a mixin the server log shows failing to apply is marked `FAILED to apply`.
