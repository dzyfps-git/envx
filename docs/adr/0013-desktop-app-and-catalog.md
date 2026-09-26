# ADR 0013: Desktop app and curated catalog

Status: accepted; engine part from Sevli 1.5.0, app and packs in later milestones

## Context
Sevli was usable only by building it from source and setting it up on the command line. To share it, it becomes a
Windows desktop app (one installer, unsigned; no macOS build) that starts **empty**. People choose what to download
from a catalog of supported baselines (a Minecraft version with its loader and mappings) and modpacks that the
maintainer has prepared. Nothing is downloaded, and no data folder is created, until they choose something; nothing
is forced: no server, no Minecraft installation, no setup questions at start.

## Decisions
1. **Recipes, not redistributed content.** Minecraft's EULA and most mods' licenses ("All Rights Reserved") forbid
   redistributing their files, code or decompiled source. The catalog therefore publishes recipes only: which files a
   baseline or pack version consists of, their official download sources (Mojang, the Fabric maven, Modrinth,
   CurseForge), sha256 hashes and sizes. Each user's Sevli downloads from those sources, verifies every file against
   the recipe and builds the index locally. An index is only ever built from exactly the supported files.
2. **Supported baselines are built in.** `dev.sevli.catalog.Baselines` lists what this build can index (today
   `fabric-1.20.1`, Yarn build.10). The published catalog may list more; one this build does not know is shown as
   needing a newer Sevli.
3. **Nothing implicit.** A baseline is downloaded only by `sevli catalog install <id>` (or the app). A sync whose
   baseline is missing fails with the command that installs it. Loom's Gradle cache is no longer read (ADR 0010
   decision 1 is superseded): results no longer depend on what else is on the machine. Loading the configuration
   creates nothing; the data home appears with the first install (the app first shows the estimated size and the
   default location, with a Change button).
4. **Own servers only on a supported baseline.** Connecting one's own server stays optional. Sevli reads what the
   server runs from its log (Fabric names the Minecraft version and loader on startup; Forge/NeoForge name theirs)
   and refuses anything that is not a supported baseline before indexing it. A server without a log yet is allowed.
5. **`sevli app-server`**: the desktop app's connection to the engine, JSON lines like `sevli api` (`dev.sevli.app`),
   versioned separately (`app:1`) because it changes with the app. Long operations stream progress events; writes run
   as separate Sevli processes as on the command line (ADR 0002). The public `sevli api` v1 is unchanged.
6. **Packs:** Modrinth (open API) and CurseForge (the maintainer's API key, kept in the app build, never in the
   repository). Files whose authors disallow third-party downloads come from the user's own copy of the pack, matched
   by hash. The maintainer builds recipes from a clean pack instance with an export tool that refuses files it cannot
   trace to a public source, so nothing private is published.
7. **App:** Electron + React + TypeScript in the maintainer's design system; NSIS per-user installer; the engine and
   its own Java runtime ship inside the app. Agent terminals inside the app come later.

## Consequences
- The command line keeps working for everyone; `sevli init` installs the default baseline.
- The catalog lives in its own public repository and contains only public URLs, ids, hashes and sizes.
- A published pack recipe changes only when the maintainer publishes a new version; each version becomes a labeled
  snapshot, so pack updates get the same impact report as server updates (ADR 0009).
