# STVN IntelliJ Platform Plugin (`stvnadore-plugin`)

[![STVN IntelliJ Platform Plugin](https://img.shields.io/badge/STVN-1.0.0-blue.svg)](https://github.com/chaotic3quilibrium/stvnadore-plugin/tree/main/docs/PLUGIN_DEVELOPER_GUIDE.md)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2025.3-blue.svg)](https://plugins.jetbrains.com/)
[![Gradle IntelliJ Plugin](https://img.shields.io/badge/Gradle%20IntelliJ%20Plugin-2.16.0-green.svg)]()
[![Grammar-Kit](https://img.shields.io/badge/Grammar--Kit-2023.3.0.3-orange.svg)]()
[![Null Safety](https://img.shields.io/badge/NullMarked-JSpecify%201.0.0-brightgreen.svg)]()

Rich IDE language support plugin for **Strongly Typed Value Notation (STVN)** in JetBrains IntelliJ IDEA and compatible IDEs. Provides sub-token precision diagnostics, type inlay hint badges, live template skeleton generation, structural map auto-healing, module flattening, and remote schema repository integration.

---

- Version: 1.0.0 - 2026.08.31

---

# Table of Contents <!-- omit in toc -->

<!-- TOC -->
* [STVN IntelliJ Platform Plugin (`stvnadore-plugin`)](#stvn-intellij-platform-plugin-stvnadore-plugin)
* [Table of Contents <!-- omit in toc -->](#table-of-contents----omit-in-toc---)
  * [Key Features](#key-features)
    * [1. Interactive Authoring & Scaffolding](#1-interactive-authoring--scaffolding)
    * [2. Visual Inlay Badging](#2-visual-inlay-badging)
    * [3. Sub-Token Precision Diagnostics](#3-sub-token-precision-diagnostics)
    * [4. Workspace Flattening & Schema Publishing](#4-workspace-flattening--schema-publishing)
  * [Action Registrations](#action-registrations)
  * [IDE Settings & Configuration](#ide-settings--configuration)
  * [Building and Verification](#building-and-verification)
    * [Prerequisites](#prerequisites)
    * [Build Commands](#build-commands)
* [Support](#support)
  * [License](#license)
    * [GNU AFFERO GENERAL PUBLIC LICENSE](#gnu-affero-general-public-license)
    * [REALLY HATE the GNU AFFERO GENERAL PUBLIC LICENSE, a.k.a. AGPLv3?](#really-hate-the-gnu-affero-general-public-license-aka-agplv3)
    * [FYI, I'd prefer to move stvnadore-plugin to an Apache 2.0 license](#fyi-id-prefer-to-move-stvnadore-plugin-to-an-apache-20-license)
    * [I'm not looking to win the lottery, I just don't want to work for free](#im-not-looking-to-win-the-lottery-i-just-dont-want-to-work-for-free)
<!-- TOC -->

---

## Key Features

### 1. Interactive Authoring & Scaffolding
* **Schema Skeleton Generator (`Alt+Enter` on `:body`)**: Automatically generates a complete, valid data skeleton matching the document's resolved `:type` contract, attaching tab-stops to each mock literal for rapid data entry.
* **Trap 2 Map Auto-Healer (`Alt+Enter`)**: Detects flat lists authored in `:Map` slots and atomically converts them to canonical paired bracket syntax (`{ [ key val ] }`).

### 2. Visual Inlay Badging
* **Compiler-Inferred Variant Badges**: Annotates unbracketed literals with inferred variant tags (e.g., `"ready"` renders with inlay badge `:Option [#Some]`).
* **Closing Container Signatures**: Displays type signatures on closing delimiters (e.g., `):Tuple( :Int32 :String )`).

### 3. Sub-Token Precision Diagnostics
* Underlines only the specific offending leaf literal when type mismatches occur.
* Enforces the **Structural Immunity Invariant**: errors in `:body` literals never highlight the root enclosure `{ ... }` or `:defs` block.

### 4. Workspace Flattening & Schema Publishing
* **Flatten STVN Workspace Action**: Ingests modular multi-file schemas (`.stvn_incl`), validates DAG dependencies, resolves aliases, strips comments, and exports a standalone `.stvn_inclf` file.
* **Publish Schema Action**: Publishes the active schema directly to the configured STVN Schema Repository server with balloon notification feedback.

---

## Action Registrations

| Action ID                                                 | Name                                  | Menu Location                     | Shortcut / Trigger                       |
|:----------------------------------------------------------|:--------------------------------------|:----------------------------------|:-----------------------------------------|
| `org.stvnadore.plugin.actions.StvnFlattenWorkspaceAction` | **Flatten STVN Workspace**            | Project View Popup / Build Menu   | Context menu on `.stvn` or `.stvn_incl`  |
| `org.stvnadore.plugin.actions.PublishSchemaAction`        | **Publish Schema to STVN Repository** | Project View Popup / Editor Popup | Context menu on `.stvn` or `.stvn_inclf` |
| `StvnSchemaSkeletonIntentionAction`                       | **Generate schema data skeleton**     | Editor Intention                  | `Alt+Enter` (macOS: `⌥Enter`) on `:body` |

---

## IDE Settings & Configuration

Configure STVN options under: **Settings | Languages & Frameworks | STVN** and **Settings | Tools | STVN Schema Repository**.

```
Settings
├── Languages & Frameworks
│   └── STVN
│       ├── [X] Show Type Inlay Hints
│       ├── [X] Use Long-Form Sum Types (#Some / #Right vs. #S / #R)
│       └── [X] Show Hover Documentation
└── Tools
    └── STVN Schema Repository
        ├── Repository URL: http://localhost:8080
        └── Timeout (ms): 5000
```

---

## Building and Verification

### Prerequisites
* JDK 21 LTS
* Local installation of `stvnadore-core:1.0.0-SNAPSHOT` (`mvn clean install` in `ij_stvnadore_core`)

### Build Commands
```bash
# Build plugin archive
./gradlew buildPlugin

# Run unit tests
./gradlew unitTest

# Run full plugin verification test suite
./gradlew test

# Launch sandboxed IntelliJ IDEA instance with STVN plugin loaded
./gradlew runIde
```

---

# Support

**Website:** <https://github.com/chaotic3quilibrium/stvnadore-plugin>

**Email:** [jim.oflaherty.jr@gmail.com](mailto:jim.oflaherty.jr+sprms@gmail.com)

---

## License

### [GNU AFFERO GENERAL PUBLIC LICENSE](https://github.com/chaotic3quilibrium/stvnadore-plugin/blob/main/LICENSE.md)

The stvnadore-plugin files are free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the [GNU Affero General Public License](https://www.gnu.org/licenses/agpl-3.0.en.html) along with this program. If not, see <https://www.gnu.org/licenses/>.

---

### REALLY HATE the GNU AFFERO GENERAL PUBLIC LICENSE, a.k.a. AGPLv3?

- It was chosen entirely because of Amazon's/AWS's (and many other wealthy corporations) historic abuses and exploitation of FOSS (Free Open Source Software)
- No Worries, I'd Love to Work with You

If the AGPLv3 doesn't work for you, I would LOVE to work with you to generate a **custom/different/commercial/non-profit/government license** for stvnadore-plugin.

Please email: <jim.oflaherty.jr+sprml@gmail.com>, letting us know what license you would prefer. I am happy to discuss this with you.

---

### FYI, I'd prefer to move stvnadore-plugin to an Apache 2.0 license

---

### I'm not looking to win the lottery, I just don't want to work for free