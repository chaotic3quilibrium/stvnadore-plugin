# STVN Language Support for IntelliJ IDEA (`ij_stvnadore_plugin`)

[![STVN IntelliJ Platform Plugin](https://img.shields.io/badge/STVN-2.0.0-blue.svg)](https://github.com/chaotic3quilibrium/stvnadore-plugin/blob/main/docs/STVN_IDE_AUTHORING_GUIDE.md)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2025.3-blue.svg)](https://plugins.jetbrains.com/)
[![Gradle IntelliJ Plugin](https://img.shields.io/badge/Gradle%20IntelliJ%20Plugin-2.16.0-green.svg)]()
[![Grammar-Kit](https://img.shields.io/badge/Grammar--Kit-2023.3.0.3-orange.svg)]()
[![Null Safety](https://img.shields.io/badge/NullMarked-JSpecify%201.0.0-brightgreen.svg)]()

Provides clean-sheet **STVN 2.0.0** language support, pure-IR compiler diagnostics, CPU-bias-free validation, interactive refactoring, and schema registry publishing for IntelliJ IDEA `2025.3+`.

---

- Version: `2.0.0-SNAPSHOT`

---

**Table of Contents**

<!-- TOC -->
* [STVN Language Support for IntelliJ IDEA (`ij_stvnadore_plugin`)](#stvn-language-support-for-intellij-idea-ij_stvnadore_plugin)
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
    * [10. Package Enclaves & Lexical Scoping (STVN 2.0)](#10-package-enclaves--lexical-scoping-stvn-20)
    * [11. Canonical 6-File Taxonomy (`.stvn`, `.stvn_i`, `.stvn_d`, `.stvn_di`, `.stvn_b`, `.stvn_bd`)](#11-canonical-6-file-taxonomy-stvn-stvn_i-stvn_d-stvn_di-stvn_b-stvn_bd)
    * [12. String Capacity Governance (STVN 2.0)](#12-string-capacity-governance-stvn-20)
    * [13. Native Code Formatter & Dual Projections (STVN 2.0)](#13-native-code-formatter--dual-projections-stvn-20)
    * [14. First-Class Rename Refactoring (Shift+F6)](#14-first-class-rename-refactoring-shiftf6)
    * [15. Interactive Namespace Dependency Browser](#15-interactive-namespace-dependency-browser)
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
  * [Release Notes](#release-notes)
    * [2.0.0-SNAPSHOT (Clean-Sheet Pure-IR Baseline)](#200-snapshot-clean-sheet-pure-ir-baseline)
<!-- TOC -->

---

## Key Features

### 1. Interactive Authoring & Scaffolding

* **Schema Skeleton Generator (`Alt+Enter` on `:body`)**: Automatically generates a complete, valid data skeleton matching the document's resolved `:type` contract, attaching tab-stops to each mock literal for rapid data entry.
* **Trap 2 Map Auto-Healer (`Alt+Enter`)**: Detects flat lists authored in `:Map` slots and atomically converts them to canonical paired bracket syntax (`{ [ key val ] }`).

### 2. Visual Inlay Badging

* **Compiler-Inferred Variant Badges**: Annotates unbracketed literals with inferred variant tags (e.g., `"ready"` renders with inlay badge `:Option [#Some]`).
* **Closing Container Signatures**: Displays type signatures on closing delimiters (e.g., `):Tuple( :Int :String )`).

### 3. Sub-Token Precision Diagnostics

* Underlines only the specific offending leaf literal when type mismatches occur.
* Enforces the **Structural Immunity Invariant**: errors in `:body` literals never highlight the root enclosure `{ ... }` or `:defs` block, and errors in included modules route directly via `StvnDiagnostic.sourcePath()` to the matching `:include` path literal.

### 4. Workspace Flattening & Schema Publishing

* **Flatten STVN Workspace Action**: Ingests modular multi-file schemas (`.stvn_di`), validates DAG dependencies, resolves aliases, strips comments, and exports a standalone `.stvn_d` file.
* **Publish Schema Action**: Publishes the active schema directly to the configured STVN Schema Repository (`POST /api/v1/schemas/{name.stvn_d}`, `Content-Type: application/stvn; charset=utf-8`) with balloon notification feedback.

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

* **Standard Delimiters**: Standard opening fences follow canonical `"""[TAG]` (Rule STR-04), followed by optional horizontal whitespace and a newline.
* **Symmetrical Recursive Nesting**: Exact-match scanning allows arbitrary nesting of inner fenced strings without premature termination.
* **Strict Language Discriminators**: Enforces character class `^[a-zA-Z0-9_-]{1,256}$`, prohibiting whitespace, empty tags, quotes, and punctuation.
* **Mismatched Tag Detection**: Identifies asymmetric closing tags (`"""[SQL]` ... `[JSON]"""`) and reports actionable errors.
* **In-Editor Quick-Fixes (`Alt+Enter`)**:
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
* **AST Fracture & Delimiter Collision Guard**: Rejects rename transactions colliding with delimiter sequences inside the payload (`[TAG]"""`, `"""[TAG]`) or violating character class `^[a-zA-Z0-9_-]{1,256}$`, preserving document integrity.

### 9. Byte 4 Wire Framing Awareness

* **Control Byte Inspection**: Verifies binary headers against the 1:3:4 bitwise layout of Byte 4 (`T` trailer flag, `STRAT` encoding strategy, `SCHEMA` identity strategy).
* **Hardware-Accelerated CRC-32C Trailer Detection**: Recognizes Bit 7 (`0x80`), validating 4-byte Little-Endian CRC-32C trailers appended at `limit - 4` to protect against payload corruption and truncation.
* **Encoding Strategy Sentinel `0x7` Handling**: Enforces protocol boundaries on Bits 6..4 (`0x70`), detecting sentinel `0x7` reserved for multibyte header extension frames.

### 10. Package Enclaves & Lexical Scoping (STVN 2.0)

* **`:package` Enclaves**: Author modular package enclosures (`:package :acme/domain { ... }`) inside `:defs` schemas, establishing formal multi-file lexical boundaries.
* **Lexical Imports (`:use`)**: Selectively import types using scoped `:use` statements inside `:defs` and `:package` blocks with multi-target maps and aliasing (`:use [ :org/stvnadore/prelude { #strip } ]`).
* **Cross-File Scoped Resolution**: Resolves relative, absolute, and nested package identifiers seamlessly with full IDE navigation and type completion.

### 11. Canonical 6-File Taxonomy (`.stvn`, `.stvn_i`, `.stvn_d`, `.stvn_di`, `.stvn_b`, `.stvn_bd`)

* **Textual Payload Documents (`.stvn`, `.stvn_i`)**: First-class support for hermetic payload documents (`.stvn`, `#kind #BODY`) and modular payload documents with `:include` directives (`.stvn_i`, `#kind #BODY_INCLUDE`).
* **Textual Definitions Schemas (`.stvn_d`, `.stvn_di`)**: First-class support for hermetic definitions schemas (`.stvn_d`, `#kind #DEFS`) and modular definitions schemas with `:include` directives (`.stvn_di`, `#kind #DEFS_INCLUDE`).
* **Binary Payloads & Definitions (`.stvn_b`, `.stvn_bd`)**: Recognizes binary payload documents (`.stvn_b`) and binary definitions archives (`.stvn_bd`), plus reserved multi-part system substrates (`*.stvn_ir.*`, `*.stvn_cas.*`).

### 12. String Capacity Governance (STVN 2.0)

* **Pure Facet Governance**: Governs string capacities via canonical metadata facets (`{ #minSize 1 #maxSize N } :String`).
* **Dual QuickFix Protocol**: In `WARNING` mode, provides primary QuickFix to apply configured threshold (`{ #minSize 1 #maxSize 4096 } :String`) and secondary QuickFix to apply default capacity (`{ #minSize 1 #maxSize 16777216 } :String`).
* **Error Suppression Guard**: Under `ERROR` severity, suppresses the secondary default QuickFix to prevent re-triggering error conditions.
* **Payload Scope Verification**: Optional inspection of `:body` string literals against active schema capacity bounds, offering atomic `Truncate` and `Widen` quick-fixes.

### 13. Native Code Formatter & Dual Projections (STVN 2.0)

* **Native Code Formatter (`Ctrl+Alt+L` / `Cmd+Alt+L`)**: Enforces canonical 2-space indentation hierarchy and the Zero-Tab Invariant across all files and selections.
* **Immutable Leaf Preservation**: Preserves user comments (`// ...`) and multiline fenced string bodies (`"""[TAG]...[TAG]"""`) without text distortion.
* **Dual Projection Actions**:
  * **Canonicalize & Pretty Print**: Lowers active document into canonical 2-space indented representation with long-form keywords (`#TRUE`, `#FALSE`, `#Some`, `#None`).
  * **Canonicalize & Compact Print**: Lowers active document into canonical single-line representation with short-form keywords (`#T`, `#F`, `#S`, `#N`).
  * **Copy Canonical Pretty Print to Clipboard**: Compiles active document and copies canonical pretty-printed format to clipboard without document buffer mutation.
  * **Copy Canonical Compact Print to Clipboard**: Compiles active document and copies canonical compact format to clipboard without document buffer mutation.
* **Comprehensive Authoring Loss Guard**: Intercepts in-place canonicalization when documents contain user comments, package enclaves, scoped import aliases, or unreferenced definitions, presenting a confirmation dialog with persistent project suppression.

### 14. First-Class Rename Refactoring (Shift+F6)

* **Unified Symbol Renaming**: Press `Shift+F6` on any nominal type definition, constant declaration, include alias (`:include`), or package enclave use alias (`:use`) to trigger synchronized in-place rename refactoring across the document and included files.
* **Value-Oriented Programming (VOP) Architecture**: Strictly decouples mutable identifier strings from immutable syntax kinds.
* **Bare Identifier Input**: Text input field initializes with bare names (`AccountHolder`, `DefaultPort`), eliminating sigil corruption (`::`, `:#`, `#:`) by design.
* **Canonical Presentation Projection**: Dialog header titles (`Rename Type Declaration ':AccountHolder'`), Find Usages trees, and Project Structure project canonical syntax (`:Type`, `#Constant`).
* **Fail-Closed Mutator Perimeter**: Rejects permissive parsing; invoking `setName()` or `handleElementRename()` with leading `:` or `#` immediately throws `IncorrectOperationException`.
* **Real-time Rename Validation**: `StvnRenameInputValidator` blocks sigil prefixes in real time with explicit footer errors, disabling the Refactor action until a bare identifier is entered.
* **Dual Search Everywhere Indexing**: Indexes symbols by both bare name and canonical prefixed name for zero-friction `Ctrl+N` / `Shift+Shift` navigation.
* **Precise Caret Anchoring**: Mixins override `getTextOffset()`, guaranteeing the refactoring template highlights the exact identifier token rather than enclosing block delimiters.
* **Atomic Cross-File Mutation**: Renaming declarations in modular schema files (`.stvn_di`) propagates atomically across all consumer documents (`.stvn`, `.stvn_i`).

### 15. Interactive Namespace Dependency Browser

* **Gutter Navigation Markers**: Displays interactive gutter icons on section headers (`:defs`, `:type`, `:body`). Clicking an icon launches the browser filtered to the target section scope.
* **Global Keyboard Shortcut (`Ctrl+Alt+N` / `Cmd+Alt+N`)**: Resolves the editor caret to determine the enclosing section scope, falling back to full `:defs` scope when invoked outside section blocks.
* **Searchable Grid (`JBTable` & `JBPopup`)**: Backed by `ListTableModel` with multi-column sorting:
  1. `Name`: Symbol identifier token (alphabetical sort).
  2. `Root Namespaced Source`: Origin file path, package enclave URI, or prelude standard library URI (alphabetical sort).
  3. `Type`: Terminal schema structure or constructor layout (alphabetical sort).
  4. `Use Depth`: Resolution depth as integer (alias hop count in `:defs`, derivation depth in `:type`, nesting depth in `:body`; numeric sort).
* **Zero-Latency Speed Search**: Attached via `TableSpeedSearch` for immediate keyboard-driven filtering.
* **Synthetic Prelude Navigation**: Double-clicking or pressing `Enter` on a prelude symbol opens a synthetic, read-only `LightVirtualFile` (`prelude.stvn_core.stvn_d`) positioned at the nominal declaration.

---

## Action Registrations

| Action ID                                                          | Name                                                   | Menu Location                     | Shortcut / Trigger                               |
|:-------------------------------------------------------------------|:-------------------------------------------------------|:----------------------------------|:-------------------------------------------------|
| `org.stvnadore.plugin.actions.StvnFlattenWorkspaceAction`          | **Flatten STVN Workspace**                             | Project View Popup / Build Menu   | Context menu on `.stvn`, `.stvn_d`, or `.stvn_di`|
| `org.stvnadore.plugin.actions.PublishSchemaAction`                 | **Publish Schema to STVN Repository**                  | Project View Popup / Editor Popup | Context menu on `.stvn_d` or `.stvn_di`          |
| `org.stvnadore.plugin.actions.StvnConvertToPrettyPrintAction`      | **Canonicalize & Pretty Print**                        | Editor Popup Menu (`STVN`)        | Context menu in STVN editor                      |
| `org.stvnadore.plugin.actions.StvnConvertToCompactPrintAction`     | **Canonicalize & Compact Print**                       | Editor Popup Menu (`STVN`)        | Context menu in STVN editor                      |
| `org.stvnadore.plugin.actions.StvnCopyCanonicalPrettyPrintAction`  | **Copy Canonical Pretty Print to Clipboard**           | Editor Popup Menu (`STVN`)        | Context menu in STVN editor                      |
| `org.stvnadore.plugin.actions.StvnCopyCanonicalCompactPrintAction` | **Copy Canonical Compact Print to Clipboard**          | Editor Popup Menu (`STVN`)        | Context menu in STVN editor                      |
| `org.stvnadore.plugin.browser.StvnBrowseNamespaceAction`           | **Browse Namespace Dependencies**                      | Editor Popup Menu (`STVN`)        | `Ctrl+Alt+N` (macOS: `Cmd+Alt+N`)                |
| `StvnSchemaSkeletonIntentionAction`                                | **Generate schema data skeleton**                      | Editor Intention                  | `Alt+Enter` (macOS: `⌥Enter`) on `:body`         |
| `StvnReorderEnumFilterVariantsQuickFix`                            | **Sort variants to match root enum declaration order** | Quick-Fix Intention               | `Alt+Enter` on out-of-order filter list          |

---

## Inspection Registrations

| Inspection Class                    | Short Name                | Display Name                                | Group Path                   | Default Level  |
|:------------------------------------|:--------------------------|:--------------------------------------------|:-----------------------------|:--------------:|
| `StvnEnumSubsetInspection`          | `StvnEnumSubset`          | Enum subset filtering inspection            | `STVN / Semantics`           |    `ERROR`     |
| `StvnVariantStyleInspection`        | `StvnVariantStyle`        | Variant tag style inspection                | `STVN / Style`               |   `WARNING`    |
| `StvnBooleanValidityInspection`     | `StvnBooleanValidity`     | Boolean validity inspection                 | `STVN / Validity`            |    `ERROR`     |
| `StvnFencedStringInspection`        | `StvnFencedString`        | Fenced string delimiter inspection          | `STVN / Syntax`              |    `ERROR`     |
| `StvnMapStructuralInspection`       | `StvnMapStructural`       | Flat list in map slot inspection            | `STVN / Structural`          |    `ERROR`     |
| `StvnDegradedSchemaInspection`      | `StvnDegradedSchema`      | Degraded schema payload evaluation          | `STVN / Quality & Semantics` | `WEAK WARNING` |
| `StvnDegenerateCompositeInspection` | `StvnDegenerateComposite` | Degenerate arity-1 composite schema         | `STVN / Schema Design`       |   `WARNING`    |
| `StvnNestedPackageInspection`       | `StvnNestedPackage`       | Nested package declaration                  | `STVN / Semantics`           |    `ERROR`     |
| `StvnTrailingSlashInspection`       | `StvnTrailingSlash`       | Trailing slash in type target               | `STVN / Syntax`              |    `ERROR`     |
| `StvnLhsReservedTypeInspection`     | `StvnLhsReservedType`     | Reserved keyword in LHS definition          | `STVN / Validity`            |    `ERROR`     |
| `StvnFlatDocumentIncludeInspection` | `StvnFlatDocumentInclude` | Include directive in hermetic document      | `STVN / Validity`            |    `ERROR`     |
| `StvnConstantRangeInspection`       | `StvnConstantRange`       | Inverted numeric constant range             | `STVN / Semantics`           |    `ERROR`     |
| `StvnStringCapacityInspection`      | `StvnStringCapacity`      | String capacity governance inspection       | `STVN / Governance`          |   `WARNING`    |
| `StvnMetadataFacetInspection`       | `StvnMetadataFacet`       | Metadata facet and block governance         | `STVN / Governance`          |    `ERROR`     |
| `StvnStringCardinalityInspection`   | `StvnStringCardinality`   | String cardinality facet governance         | `STVN / Governance`          |    `ERROR`     |
| `StvnDiscreteIntervalInspection`    | `StvnDiscreteInterval`    | Discrete interval bound governance          | `STVN / Governance`          |    `ERROR`     |
| `StvnTemporalModeInspection`        | `StvnTemporalMode`        | Temporal mode and scale facet governance    | `STVN / Governance`          |    `ERROR`     |
| `StvnMetadataOrderInspection`       | `StvnMetadataOrder`       | Metadata facet canonical ordering (5 tiers) | `STVN / Governance`          | `WEAK WARNING` |

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
* Local installation of `stvnadore-core:2.0.0-SNAPSHOT` (`mvn clean install` in `ij_stvnadore_core`)

### Build Commands

```bash
# Build plugin archive
./gradlew buildPlugin

# Run headless unit & architectural hygiene tests
./gradlew unitTest

# Run full verification, platform conformance & Javadoc gates
./gradlew check javadoc

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

## Release Notes

### 2.0.0-SNAPSHOT (Clean-Sheet Pure-IR Baseline)
- **Pure-IR Architecture:** Coupled exclusively to `stvnadore-core:2.0.0-SNAPSHOT` immutable `org.stvnadore.core.ir.*` records (`StvnDocument`, `StvnDefsCatalog`, `StvnSchema`, `StvnValue`) via `StvnCompiledDocumentCache` with zero ANTLR CST coupling.
- **Canonical 6-File Taxonomy:** Supports `.stvn`, `.stvn_i`, `.stvn_d`, `.stvn_di`, `.stvn_b`, and `.stvn_bd` documents plus reserved multi-part system substrates (`*.stvn_ir.*`, `*.stvn_cas.*`).
- **95-Row Facet Matrix & Zero-Argument Bare Flags:** Enforces 5-tier canonical facet ordering, standalone bare flags (`#unsigned`, `#exact`, `#invertible`, `#ns`, `#us`, `#ms`, `#s`, `#offset`, `#zoned`, `#audited`, `#strip`), and inductive `#equatable` / `#comparable` trait healing.
- **CPU-Bias-Free Expression Space:** Evaluates integer widths, capacities, ordinals, union branch tags, and decimal bounds directly in `BigInteger` and `BigDecimal`.
- **Schema Repository 2.0.0 Publishing:** Publishes flattened `.stvn_d` schemas to `POST /api/v1/schemas/{name.stvn_d}` with structured `canonicalCode` and `symbolicId` HTTP 422 diagnostics.
