# STVN IntelliJ Platform Plugin (`stvnadore-plugin`)

[![STVN IntelliJ Platform Plugin](https://img.shields.io/badge/STVN-1.1.1--SNAPSHOT-blue.svg)](https://github.com/chaotic3quilibrium/stvnadore-plugin/blob/main/docs/STVN_IDE_AUTHORING_GUIDE.md)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2025.3-blue.svg)](https://plugins.jetbrains.com/)
[![Gradle IntelliJ Plugin](https://img.shields.io/badge/Gradle%20IntelliJ%20Plugin-2.16.0-green.svg)]()
[![Grammar-Kit](https://img.shields.io/badge/Grammar--Kit-2023.3.0.3-orange.svg)]()
[![Null Safety](https://img.shields.io/badge/NullMarked-JSpecify%201.0.0-brightgreen.svg)]()

Language support plugin for **Strongly Typed Value Notation (STVN)** in JetBrains IntelliJ IDEA and compatible IDEs. The plugin provides sub-token precision diagnostics, type inlay hint badges, live template skeleton generation, structural map auto-healing, module flattening, enum subset filtering, and remote schema repository integration.

---

- Version: 1.1.1-SNAPSHOT - 2026.09.07

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
    * [5. Enum Subset Filtering & Derivation](#5-enum-subset-filtering--derivation)
    * [6. Semantic Inspections & Quick-Fixes](#6-semantic-inspections--quick-fixes)
    * [7. Context-Aware Code Completion](#7-context-aware-code-completion)
    * [8. Enhanced Hover Documentation](#8-enhanced-hover-documentation)
    * [8b. Polyglot Fenced Strings & Delimiter Invariants (Rule STR-04)](#8b-polyglot-fenced-strings--delimiter-invariants-rule-str-04)
    * [9. Byte 4 Wire Framing Awareness](#9-byte-4-wire-framing-awareness)
  * [Action Registrations](#action-registrations)
  * [Inspection Registrations](#inspection-registrations)
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
* [Version History](#version-history)
  * [v1.1.0](#v110)
  * [v1.0.2](#v102)
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

### 5. Enum Subset Filtering & Derivation
* **Syntax Support (`#filterIncl`, `#filterExcl`)**: Author nominal type aliases that constrain an existing base `:Enum` or intermediate subset.
* **Inclusive Filtering (`#filterIncl`)**: Explicitly declares the exact permitted variant list.
* **Exclusive Filtering (`#filterExcl`)**: Subtracts specific variants from the parent enum definition.
* **Transitive Derivation**: Supports multi-tier subset specialization chains across local and imported modules.

```stvn
:defs {
  :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
  :DeployEnv   { #filterIncl [ #DEV #STAGING #CANARY ] } :Environment
  :CanaryOnly  { #filterExcl [ #DEV #STAGING ] } :DeployEnv
}
```

### 6. Semantic Inspections & Quick-Fixes
* **`StvnEnumSubset` Inspection**: Continuously validates enum subset rules with `ERROR` severity:
  1. **Relative Root Ordering**: Variants in filter lists must match the relative declaration order of the root `:Enum`.
  2. **Monotonic Narrowing**: Filter variants must exist in the immediate parent type.
  3. **Mutual Exclusivity**: `#filterIncl` and `#filterExcl` cannot coexist in the same metadata block.
  4. **Target Attachment Constraints**: Filter facets can attach only to nominal aliases of `:Enum` or existing subsets.
  5. **Non-Empty Constraints**: Filter brackets cannot be empty, and `#filterExcl` cannot remove all parent variants.
  6. **Payload Verification**: Leaf value literals must belong to the active subset variant set.
* **In-Place Reorder Quick-Fix (`Alt+Enter`)**: Offers `"Sort variants to match root enum declaration order"` to rewrite out-of-order variants into canonical root enum order automatically.

### 7. Context-Aware Code Completion
* **Bracketed Filter Authoring**: Inside `#filterIncl [ <caret> ]` or `#filterExcl [ <caret> ]`, completion suggests only valid variants from the parent enum, suppresses already declared variants to avoid duplicates, and sorts items by root enum order.
* **Subset Payload Domain Filtering**: In `:body` or data expressions expecting an enum subset, completion restricts suggestions strictly to valid subset variants, suppressing excluded variants across all ancestor tiers.

### 8. Enhanced Hover Documentation
* **Effective Variant Count**: Displays `Variant Count: N` reflecting the filtered subset cardinality rather than the root enum count.
* **Unrolled Structure**: Displays the active allowed variants unrolled as `:Enum [ #A #B ... ]`.
* **Derivation Lineage**: Details the immediate parent type and filter facet (e.g., `Parent: :PieceRole via #filterExcl [ #PAWN #KING ]`).
* **Resolution Path**: Traces transitive type aliases back to the root declaration (`:Terminal -> :Intermediate -> :RootEnum`).

### 8b. Polyglot Fenced Strings & Delimiter Invariants (Rule STR-04)
* **Standard Delimiters**: Standard opening fences follow canonical `"""[TAG]`. The legacy directional arrow `"""->[TAG]` is **deprecated as of 1.1.1** (scheduled for removal in 2.0.0) and generates an in-editor deprecation diagnostic.
* **Symmetrical Recursive Nesting**: Exact-match scanning allows arbitrary nesting of inner fenced strings without premature termination.
* **Strict Language Discriminators**: Enforces character class `^[a-zA-Z0-9_-]{1,256}$`, prohibiting whitespace, empty tags, quotes, and punctuation.
* **Mismatched Tag Detection**: Identifies asymmetric closing tags (`"""[SQL]` ... `[JSON]"""`) and reports actionable errors.
* **In-Editor Quick-Fixes (`Alt+Enter`)**:
  * Strips deprecated `->` opening delimiter arrow via `"Remove deprecated '->' arrow"` with full IntelliJ Code Cleanup batch support.
  * Balances mismatched delimiter tags bidirectionally from either opening or closing fence lines.
  * Repositions the editor caret directly onto the body line between delimiters following fix execution.
  * Pairs nested fenced strings via depth-aware sequential scanning.
  * Strips illegal characters and whitespace from tags.
  * Atomically supplies default tag `[FENCE]` and closes unclosed delimiters on the next line.
  * Deterministically inserts missing closing delimiters directly on the next line without swallowing downstream tokens.
* **Configurable Enter-Key Auto-Closing**: Automatically generates symmetrical closing delimiters for both bare `"""` and fenced `"""[TAG]` blocks under configurable `EXPANDED_THREE_LINE` or `TIGHT_TWO_LINE` shapes.
* **Interactive Live Template Launch**: Typing `[` after `"""` launches the `fence` Live Template with synchronized dual-tag variables.
* **Conversion Intention Action**: Press `Alt+Enter` on bare `"""` to convert to a fenced string block.
* **Live Template (`fence`)**: Expands full fenced string templates with synchronized tag variables.
* **In-Editor `Shift+F6` Synchronized Tag Renaming**: Place caret on opening or closing tag brackets and press `Shift+F6` to launch live linked editing across both delimiters.
* **AST Fracture & Delimiter Collision Guard**: Rejects rename transactions colliding with delimiter sequences inside the payload (`[TAG]"""`, `"""[TAG]`, `"""->[TAG]`) or violating character class `^[a-zA-Z0-9_-]{1,256}$`, preserving document integrity.

### 9. Byte 4 Wire Framing Awareness
* **Control Byte Inspection**: Verifies binary headers against the 1:3:4 bitwise layout of Byte 4 (`T` trailer flag, `STRAT` encoding strategy, `SCHEMA` identity strategy).
* **Hardware-Accelerated CRC-32C Trailer Detection**: Recognizes Bit 7 (`0x80`), validating 4-byte Little-Endian CRC-32C trailers appended at `limit - 4` to protect against payload corruption and truncation.
* **Encoding Strategy Sentinel `0x7` Handling**: Enforces protocol boundaries on Bits 6..4 (`0x70`), detecting sentinel `0x7` reserved for multi-byte header extension frames.

---

## Action Registrations

| Action ID                                                 | Name                                                   | Menu Location                     | Shortcut / Trigger                       |
|:----------------------------------------------------------|:-------------------------------------------------------|:----------------------------------|:-----------------------------------------|
| `org.stvnadore.plugin.actions.StvnFlattenWorkspaceAction` | **Flatten STVN Workspace**                             | Project View Popup / Build Menu   | Context menu on `.stvn` or `.stvn_incl`  |
| `org.stvnadore.plugin.actions.PublishSchemaAction`        | **Publish Schema to STVN Repository**                  | Project View Popup / Editor Popup | Context menu on `.stvn` or `.stvn_inclf` |
| `StvnSchemaSkeletonIntentionAction`                       | **Generate schema data skeleton**                      | Editor Intention                  | `Alt+Enter` (macOS: `⌥Enter`) on `:body` |
| `StvnReorderEnumFilterVariantsQuickFix`                   | **Sort variants to match root enum declaration order** | Quick-Fix Intention               | `Alt+Enter` on out-of-order filter list  |

---

## Inspection Registrations

| Inspection Class                    | Short Name                | Display Name                        | Group Path                   | Default Level  |
|:------------------------------------|:--------------------------|:------------------------------------|:-----------------------------|:--------------:|
| `StvnEnumSubsetInspection`          | `StvnEnumSubset`          | Enum subset filtering inspection    | `STVN / Semantics`           |    `ERROR`     |
| `StvnVariantStyleInspection`        | `StvnVariantStyle`        | Variant tag style inspection        | `STVN / Style`               |   `WARNING`    |
| `StvnBooleanValidityInspection`     | `StvnBooleanValidity`     | Boolean validity inspection         | `STVN / Validity`            |    `ERROR`     |
| `StvnFencedStringInspection`        | `StvnFencedString`        | Fenced string delimiter inspection  | `STVN / Syntax`              |    `ERROR`     |
| `StvnMapStructuralInspection`       | `StvnMapStructural`       | Flat list in map slot inspection    | `STVN / Structural`          |    `ERROR`     |
| `StvnDegradedSchemaInspection`      | `StvnDegradedSchema`      | Degraded schema payload evaluation  | `STVN / Quality & Semantics` | `WEAK WARNING` |
| `StvnDegenerateCompositeInspection` | `StvnDegenerateComposite` | Degenerate arity-1 composite schema | `STVN / Schema Design`       |   `WARNING`    |

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
* Local installation of `stvnadore-core:1.1.0` (`mvn clean install` in `ij_stvnadore_core`)

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

---

# Version History

## v1.1.0

- 2026.09.06
- Implemented enum subset filtering with transitive chaining
- Added Control Byte 4 bitwise partitioning (1:3:4) for CRC-32C, SchemaIdentityStrategy, and BinaryEncodingStrategy

## v1.0.2

- 2026.09.04
- Initial release across all four repositories
