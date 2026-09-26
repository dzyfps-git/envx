# Third-party notices

Sevli is MIT-licensed (`LICENSE`). It ships with, or builds on, the components below; their licence texts are in
`licenses/`.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

Sevli contains no Minecraft code or assets. Minecraft files are downloaded from Mojang's own servers onto your PC,
and anything decompiled from them stays on your PC. Mods and modpacks are downloaded from where their authors
publish them. Sevli never redistributes either.

## Bundled with the engine (`lib/`)

| Component | Licence | Text |
|---|---|---|
| Vineflower (decompiler) | Apache-2.0 | `licenses/Apache-2.0.txt` |
| mapping-io (FabricMC) | Apache-2.0 | `licenses/Apache-2.0.txt` |
| tiny-remapper (FabricMC) | LGPL-3.0; shipped unmodified as its own jar, which you may replace. Source: https://github.com/FabricMC/tiny-remapper | `licenses/LGPL-3.0.txt`, `licenses/GPL-3.0.txt` |
| ASM (OW2) | BSD-3-Clause | `licenses/ASM-BSD-3-Clause.txt` |
| sqlite-jdbc (xerial), including SQLite (public domain) | Apache-2.0; parts BSD (zentus) | `licenses/Apache-2.0.txt`, `licenses/sqlite-jdbc-zentus-BSD.txt` |
| Gson (Google) | Apache-2.0 | `licenses/Apache-2.0.txt` |
| Error Prone annotations (Google) | Apache-2.0 | `licenses/Apache-2.0.txt` |

## Data used at run time (downloaded, not bundled)

| Data | Licence |
|---|---|
| Yarn and Intermediary mappings (FabricMC) | CC0-1.0 |
| Minecraft client and server (Mojang) | Minecraft EULA; downloaded from Mojang, never redistributed |

## Bundled with the desktop app

| Component | Licence | Text |
|---|---|---|
| Java runtime (Eclipse Temurin / OpenJDK, trimmed with jlink) | GPL-2.0 with the Classpath Exception | `runtime/legal/` |
| Electron and Chromium | MIT, plus Chromium's licences | `LICENSE.electron.txt`, `LICENSES.chromium.html` |
| React, React DOM | MIT | `licenses/React-MIT.txt` |
| Figtree font | SIL OFL 1.1 | `licenses/OFL-Figtree.txt` |
| JetBrains Mono font | SIL OFL 1.1 | `licenses/OFL-JetBrains-Mono.txt` |

The app's display type uses Bahnschrift from Windows itself; it is not bundled.

Claude Code is a product of Anthropic; Codex is a product of OpenAI. Sevli works with them and is not affiliated with
either.
