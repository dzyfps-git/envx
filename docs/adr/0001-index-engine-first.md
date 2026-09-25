# ADR 0001: Index engine first, Java 21, bytecode-first indexing

Status: accepted (2026-09-23)

## Context
The goal is less AI usage when working on Minecraft mods. The Phase 0 baseline (`bench/analyze_codex_sessions.py`)
found that 39% of the shell commands Codex ran across past sessions were environment discovery: `javap` on the
Yarn Minecraft jar, unzipping mod jars, reading mappings and mixin configs, listing mods. That output was about 17M tokens.

## Decision
- Ship a standalone **index engine** (`envx`) with a CLI and an MCP server before any desktop UI. It works from
  the Claude Code and Codex installs the user already has.
- Write it in **plain Java 21**. The key libraries (ASM, mapping-io, tiny-remapper, Vineflower) are Java, and so
  are the user's mods. The code is mostly AI-written, so it sticks to one simple language with no Kotlin.
- **Index bytecode, not decompiled source.** ASM gives classes, members, inheritance, class-level references and
  mixin annotations in minutes. Source is decompiled lazily, one class at a time, and cached forever.
- **Content-addressed artifacts.** Each jar, including jars nested in other jars, is indexed once, keyed by SHA-256.
  An environment snapshot is a set of artifact ids, so "layers" are views and nothing is copied.
- **Intermediary names are canonical** because they are what runs on the server and what Spark reports. Yarn is
  stored alongside for display.

## Consequences
- Adding NeoForge or another Minecraft version later means a new `FabricBase`-style provider and mapping loader.
  The storage and query layers stay the same.
- Answers about mixins describe what mods *declare*. Checking what is actually *applied* needs a runtime probe (Phase 3).
