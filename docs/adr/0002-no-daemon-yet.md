# ADR 0002: No daemon in v1; SQLite WAL with a single writer

Status: accepted (2026-09-23). This departs from the original plan.

## Context
The plan proposed one resident daemon, with every agent session reaching it through a thin MCP shim. The reason
was to avoid several JVMs writing to SQLite at once.

## Decision
Build no daemon for now. Only `envx env sync`, run by the user or later the desktop app, writes to the index.
Every MCP server process opens `index.sqlite` **read-only**. SQLite in WAL mode lets any number of readers run
while one writer works.

The only thing an MCP process writes is the on-disk decompile cache. Each cache file is written to a temp file
and then atomically renamed, so two processes decompiling the same class at once is harmless.

## Consequences
- Each MCP process starts its own JVM, which uses roughly 100–200 MB. That is acceptable for a few sessions at once.
- Revisit this once the desktop app exists. The app will need a long-lived process anyway, and could host the
  engine and serve MCP over HTTP.
