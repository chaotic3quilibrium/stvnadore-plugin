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
    * [2. Visual Inlay Badging & Per-Hop Navigation](#2-visual-inlay-badging--per-hop-navigation)
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
* **Trap 2 Map Auto-Healer (`Alt+Enter`)**: Detects flat lists authored in `:Map` slots and atomically converts them to canonical paired bracket syntax (`{["key" "val"]}`).

### 2. Visual Inlay Badging & Per-Hop Navigation

* **Unified Renamed `:use` Alias Trajectories**: Starts `:body` inlay badges at the local renamed `UseMapAlias` token across scalar, `:Enum`, temporal, algebraic sum, and composite slots (for example `:ValveMode (-> :cooling/manifold/CoolantValveMode -> :Enum)`) while collapsing unrenamed `#strip` and identity `:Port :Port` projections cleanly without duplicate hops.
* **Per-Hop `Ctrl+Click` Type Navigation**: Binds each nominal hop in an inlay badge independently so clicking `:ValveMode` jumps to the local `:use` alias declaration and clicking `:cooling/manifold/CoolantValveMode` jumps to the upstream `:package` `TypeDefinition`.
* **Compiler-Inferred Variant Badges**: Annotates unbracketed literals with inferred variant tags (for example `"ready"` renders `:Option [#Some]`) and materializes inferred tags on click.
* **Closing Container Signatures**: Displays type signatures on closing delimiters (for example `):Tuple(:Int :String)`).

### 3. Sub-Token Precision Diagnostics

* Underlines only the specific offending leaf literal when type mismatches occur.
* Enforces the **Structural Immunity Invariant**: errors in `:body` literals never highlight the root enclosure `{ ... }` or `:defs` block, and errors in included modules route directly via `StvnDiagnostic.sourcePath()` to the matching `:include` path literal.

### 4. Workspace Flattening & Schema Publishing

* **3-Submenu VOP Projection Matrix (`STVN >` & `BuildMenu`)**: Separates non-destructive hermetic `.stvn_d` schema extraction (`Pretty` & `Compact CAS`), non-destructive hermetic `.stvn` payload extraction (`Pretty` & `Compact`), and undoable in-place buffer canonicalization (`Pretty IR` & `Compact IR`) with `ActionUpdateThread.BGT` self-overwrite guards.
* **Publish Schema Action**: Publishes the active schema directly to the configured STVN Schema Repository (`POST /api/v1/schemas/{name.stvn_d}`, `Content-Type: application/stvn; charset=utf-8`) with balloon notification feedback.

### 5. Enum Subset Filtering & Derivation

* **Syntax Support (`#filterIncl`, `#filterExcl`)**: Author nominal type aliases that constrain an existing base `:Enum` or intermediate subset.
* **Inclusive Filtering (`#filterIncl`)**: Explicitly declares the exact permitted variant list.
* **Exclusive Filtering (`#filterExcl`)**: Subtracts specific variants from the parent enum definition.
* **Transitive Derivation**: Supports multi-tier subset specialization chains across local and imported modules.

```stvn
:defs {
  :Environment :Enum[#LOCAL #DEV #STAGING #CANARY #PROD]
  :DeployEnv   { #filterIncl [#DEV #STAGING #CANARY] } :Environment
  :CanaryOnly  { #filterExcl [#DEV #STAGING] } :DeployEnv
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

* **Bracketed Filter Authoring**: Inside `#filterIncl [<caret>]` or `#filterExcl [<caret>]`, completion suggests only valid variants from the parent enum, suppresses already declared variants to avoid duplicates, and sorts items by root enum order.
* **Subset Payload Domain Filtering**: In `:body` or data expressions expecting an enum subset, completion restricts suggestions strictly to valid subset variants, suppressing excluded variants across all ancestor tiers.

### 8. Enhanced Hover Documentation

* **Effective Variant Count**: Displays `Variant Count: N` reflecting the filtered subset cardinality rather than the root enum count.
* **Unrolled Structure**: Displays the active allowed variants unrolled as `:Enum[#A #B ...]`.
* **Derivation Lineage**: Details the immediate parent type and filter facet (for example `Parent: :PieceRole via #filterExcl [#PAWN #KING]`).
* **Resolution Path**: Traces transitive type aliases back to the root declaration (`:Terminal -> :Intermediate -> :RootEnum`).

### 8b. Polyglot Fenced Strings & Delimiter Invariants (Rule STR-04)

* **Standard Delimiters**: Standard opening fences follow canonical `"""[TAG]` (Rule STR-04), followed by optional horizontal whitespace and a newline, and close with matching `[TAG]"""`.
* **Symmetrical Recursive Nesting**: Exact-match scanning allows arbitrary nesting of inner fenced strings without premature termination.
* **Strict Language Discriminators**: Enforces character class `^[a-zA-Z0-9_.-]{1,256}$`, prohibiting whitespace, empty tags, quotes, and brackets.
* **Mismatched Tag Detection**: Identifies asymmetric closing tags (`"""[SQL]` ... `[JSON]"""`) and reports actionable errors.
* **In-Editor Quick-Fixes (`Alt+Enter`)**:
  * Balances mismatched delimiter tags bidirectionally from either opening or closing fence lines.
  * Repositions the editor caret directly onto the body line between delimiters following fix execution.
  * Pairs nested fenced strings via depth-aware sequential scanning.
  * Strips illegal characters and whitespace from tags.
  * Atomically supplies default tag `[FENCE]` and closes unclosed delimiters on the next line.
  * Deterministically inserts missing closing delimiters directly on the next line without swallowing downstream tokens.
* **Configurable Enter-Key Auto-Closing**: Automatically generates symmetrical `[TAG]"""` closing delimiters when pressing `Enter` after `"""[TAG]` under configurable `EXPANDED_THREE_LINE` or `TIGHT_TWO_LINE` shapes.
* **Interactive Live Template Launch**: Typing `[` immediately after three double-quote characters launches the `fence` Live Template with synchronized dual-tag variables.
* **Symmetric Simple, Escaped & Fenced String Conversion Intentions (`Alt+Enter`, `Priority.TOP`)**: Converts escape-free `"..."` literals (`Convert string literal to fenced string block`) and escaped `"..."` literals (`Convert escaped string to fenced string`) with strict mutual exclusion into canonical `"""[RAW]...[RAW]"""` or `"""[REGEX]...[REGEX]"""` blocks with collision-safe tags, converts valid `"""[TAG]...[TAG]"""` blocks back to canonically escaped `"..."` strings (`Convert fenced string to escaped string`) with context-sensitive `#preserveIndent` decoding, heals incomplete opening fences into `"""[FENCE]...[FENCE]"""`, and ranks leaf string conversions #1 ahead of enclosing container layout actions.
* **Live Template (`fence`)**: Expands full fenced string templates with synchronized tag variables.
* **In-Editor `Shift+F6` Synchronized Tag Renaming**: Place caret on opening or closing tag brackets and press `Shift+F6` to launch live linked editing across both delimiters.
* **AST Fracture & Delimiter Collision Guard**: Rejects rename transactions colliding with delimiter sequences inside the payload (`[TAG]"""`, `"""[TAG]`) or violating the tag character class, preserving document integrity.

### 9. Byte 4 Wire Framing Awareness

* **Control Byte Inspection**: Verifies binary headers against the 1:3:4 bitwise layout of Byte 4 (`T` trailer flag, `STRAT` encoding strategy, `SCHEMA` identity strategy).
* **Hardware-Accelerated CRC-32C Trailer Detection**: Recognizes Bit 7 (`0x80`), validating 4-byte Little-Endian CRC-32C trailers appended at `limit - 4` to protect against payload corruption and truncation.
* **Encoding Strategy Sentinel `0x7` Handling**: Enforces protocol boundaries on Bits 6..4 (`0x70`), detecting sentinel `0x7` reserved for multibyte header extension frames.

### 10. Package Enclaves & Lexical Scoping (STVN 2.0)

* **`:package` Enclaves**: Author modular package enclosures (`:package :acme/domain { ... }`) inside `:defs` schemas, establishing formal multi-file lexical boundaries.
* **Lexical Imports (`:use`)**: Selectively import types and constants using scoped `:use` statements inside `:defs` and `:package` blocks with multi-target maps and aliasing (`:use [ :org/stvnadore/prelude { #strip } ]`).
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

* **Native Code Formatter (`Ctrl+Alt+L` / `Cmd+Alt+L`)**: Enforces canonical 2-space indentation hierarchy, column alignment, and the Zero-Tab Invariant across all files and selections.
* **Immutable Leaf Preservation**: Preserves user comments (`// ...`) and multiline fenced string bodies (`"""[TAG]...[TAG]"""`) without text distortion.
* **3-Submenu VOP Projection & Canonicalization Matrix (`STVN >`)**:
  * **Extract Hermetic Schema (`.stvn_d` — `#DEFS` Only)**: Copies (`Pretty` or `Compact CAS`) to clipboard or exports sibling `<stem>.stvn_d` without modifying source buffers.
  * **Extract Hermetic Payload (`.stvn` — Includes `:body`)**: Normalizes `#kind #BODY` and copies (`Pretty` or `Compact`) to clipboard or exports sibling `<stem>.stvn` from `.stvn_i` modules.
  * **Canonicalize Active Buffer In-Place**: Replaces active buffer with `Pretty IR` or `Compact IR` while preserving extension-compatible `#kind` (undoable via `Ctrl+Z`).
* **Comprehensive Authoring Loss Guard**: Intercepts in-place canonicalization when documents contain user comments, `:include` directives, `:package` enclaves, `:use` aliases, payload `#CONST` declarations, or unreferenced definitions, presenting a confirmation dialog with persistent project suppression.

### 14. First-Class Rename Refactoring (Shift+F6)

* **Unified Symbol Renaming**: Press `Shift+F6` on any nominal type definition, constant declaration, or package enclave `:use` alias to trigger synchronized in-place rename refactoring across the document and included files.
* **Value-Oriented Programming (VOP) Architecture**: Strictly decouples mutable identifier strings from immutable syntax kinds.
* **Bare Identifier Input**: Text input field initializes with bare names (`AccountHolder`, `DEFAULT_PORT`), eliminating sigil corruption (`::`, `:#`, `#:`) by design.
* **Canonical Presentation Projection**: Dialog header titles (`Rename Type Declaration ':AccountHolder'`), Find Usages trees, and Project Structure project canonical syntax (`:Type`, `#CONST`).
* **Fail-Closed Mutator Perimeter**: Rejects permissive parsing; invoking `setName()` or `handleElementRename()` with leading `:` or `#` immediately throws `IncorrectOperationException`.
* **Real-time Rename Validation**: `StvnRenameInputValidator` blocks sigil prefixes in real time with explicit footer errors, disabling the Refactor action until a bare identifier is entered.
* **Dual Search Everywhere Indexing**: Indexes symbols by both bare name and canonical prefixed name for zero-friction `Ctrl+N` / `Shift+Shift` navigation.
* **Precise Caret Anchoring**: Mixins override `getTextOffset()`, guaranteeing the refactoring template highlights the exact identifier token rather than enclosing block delimiters.
* **Atomic Cross-File Mutation**: Renaming declarations in modular schema files (`.stvn_di`) propagates atomically across all consumer documents (`.stvn`, `.stvn_i`).

### 15. Interactive Namespace Dependency Browser

* **Gutter Navigation Markers**: Displays interactive gutter icons on section headers (`:defs`, `:type`, `:body`). Clicking an icon opens the popup initialized to the target section scope.
* **Global Keyboard Shortcut (`Ctrl+Alt+N` / `Cmd+Alt+N`)**: Resolves the editor caret to determine the enclosing section scope, falling back to `:defs` scope when invoked outside `:type` or `:body`.
* **Structured 3-Row Header, Flush-Left Bold Prefixes & Type-Anywhere Filter**: Displays a static **`STVN Namespace Dependencies`** window caption above three flush-left (`x = 0`) header rows with bold prefixes (`**File:**`, `**Scope:**`, `**Scope:**`) and regular-weight values: Row 1 (`**File:** <filename>` with project-relative path tooltip + `Show Standard Library Prelude`), Row 2 (`**Scope:** <scope> — <title> (<count> symbols)` dynamic summary), and Row 3 (`**Scope:**` + clean `:defs` / `:type` / `:body` radio buttons with popup-scoped `Alt+1..3` shortcuts and STE tooltips + live `SearchTextField` with `Alt+F` / `Ctrl+F` / `Cmd+F` focus-and-select-all shortcuts, type-anywhere `[A-Za-z0-9:#/_.-]` and `BackSpace` auto-focus, `Up`/`Down`/`PageUp`/`PageDown`/`Enter` table navigation, and prioritized `Name -> Upstream Target -> Source -> Resolved Type` match ranking). Sections absent from `#DEFS` / `#DEFS_INCLUDE` schemas remain disabled (`setEnabled(false)`) with explanatory STE tooltips.
* **5-Column Spreadsheet Grid (`JBTable` & `JBPopup`)**: Configured with `JTable.AUTO_RESIZE_OFF`, horizontal scrolling as needed, full-value hover tooltips on truncated cells, a default-locked right-aligned `Use Depth` column with compact header lock indicator, double-click header divider right-sizing (deconflicted from sort toggles), a popup-scoped `Alt+R` shortcut, and a right-click header width-lock menu (`Lock Column Width`, `Right-Size Column`, `Right-Size All Unlocked Columns (Alt+R)`):
  1. `Name`: In-scope local `:Type`, `#CONST`, or `UseMapAlias` token (enforcing § 4.2 `:use` eviction).
  2. `Source`: Origin file name, `:package` enclave path, or `prelude.stvn_core.stvn_d`.
  3. `Upstream Target`: Canonical upstream FQNI (`:cooling/manifold/CoolantValveMode`, `#cooling/manifold/DEFAULT_VALVE_MODE`), or `—` when declared directly in root `:defs`.
  4. `Resolved Type`: Compact canonical STVN 2.0.0 underlying schema (`:Tuple(:ValveMode :LoopTempC :Int :Boolean :ZoneFlowMap)`, `{ #minSize 4 #maxSize 24 #minIncl "PKT-0000" #maxIncl "PKT-ZZZZ" #preserveIndent #TRUE #regex "^PKT-[A-Z0-9-]+$" } :String`) sorted strictly in § 6.1 5-tier decimal facet order (`1.1.1` through `5.0`).
  5. `Use Depth`: Shortest-path symbol-hop depth (`0 -> 1 -> 2`), traversing inline anonymous constructors (`:Tuple`, `:Seq`, `:Set`, `:Map`, `:Option`, `:Either`, `:Union`) and inline `MetadataMap` blocks at `+0` hop cost and incrementing by `+1` strictly when crossing into a named `:Type` or `#CONST`.
* **Synthetic Prelude Navigation**: Double-clicking or pressing `Enter` on a prelude symbol opens a read-only `LightVirtualFile` (`prelude.stvn_core.stvn_d`) positioned at the nominal declaration.

---

## Action Registrations

| Action ID                                                            | Name                                                   | Menu Location                     | Shortcut / Trigger                                |
|:---------------------------------------------------------------------|:-------------------------------------------------------|:----------------------------------|:--------------------------------------------------|
| `org.stvnadore.plugin.actions.StvnBuildExportSchemaCompactCasAction` | **Export Hermetic STVN Schema (.stvn_d, Compact CAS)** | Build Menu                        | Active `.stvn_di`, `.stvn_i`, or `.stvn` file     |
| `org.stvnadore.plugin.actions.PublishSchemaAction`                   | **Publish Schema to STVN Repository**                  | Project View Popup / Editor Popup | Context menu on `.stvn_d` or `.stvn_di`           |
| `org.stvnadore.plugin.actions.StvnExtractSchemaMenuGroup`            | **Extract Hermetic Schema (.stvn_d — #DEFS Only)**     | `STVN >` Submenu 1                | Clipboard & Sibling `.stvn_d` (`Pretty` / `CAS`)  |
| `org.stvnadore.plugin.actions.StvnExtractPayloadMenuGroup`           | **Extract Hermetic Payload (.stvn — Includes :body)**  | `STVN >` Submenu 2                | Clipboard & Sibling `.stvn` (`Pretty` / `Compact`)|
| `org.stvnadore.plugin.actions.StvnCanonicalizeInPlaceMenuGroup`      | **Canonicalize Active Buffer In-Place**                | `STVN >` Submenu 3                | `Replace Buffer with Pretty IR` / `Compact IR`    |
| `org.stvnadore.plugin.browser.StvnBrowseNamespaceAction`             | **Browse Namespace Dependencies**                      | Editor Popup Menu (`STVN`)        | `Ctrl+Alt+N` (macOS: `Cmd+Alt+N`)                 |
| `StvnBootstrapDocumentIntentionAction`                               | **Initialize STVN document structure**                 | Editor Intention                  | `Alt+Enter` (macOS: `⌥Enter`) on empty file       |
| `StvnAddMetaBlockIntention`                                          | **Add root :meta header**                              | Editor Intention                  | `Alt+Enter` when root `:meta` is absent           |
| `StvnSchemaSkeletonIntentionAction`                                  | **Generate schema data skeleton**                      | Editor Intention                  | `Alt+Enter` (macOS: `⌥Enter`) on `:body`          |
| `StvnCreateFencedStringIntention`                                    | **Convert string literal to fenced string block**      | Editor Intention                  | `Alt+Enter` on escape-free `"..."` or opener      |
| `StvnConvertEscapedStringToFencedIntention`                          | **Convert escaped string to fenced string**            | Editor Intention                  | `Alt+Enter` on escaped `"..."` string             |
| `StvnConvertFencedStringToSimpleIntention`                           | **Convert fenced string to escaped string**            | Editor Intention                  | `Alt+Enter` on `"""[TAG]...[TAG]"""` fenced block |
| `StvnExpandContainerIntention`                                       | **Expand container to multiple lines**                 | Editor Intention                  | `Alt+Enter` on single-line container              |
| `StvnCollapseContainerIntention`                                     | **Collapse container to single line**                  | Editor Intention                  | `Alt+Enter` on comment/fence-free container       |
| `StvnCollapseChildElementsIntention`                                 | **Collapse child elements to single lines**            | Editor Intention                  | `Alt+Enter` on multi-line parent container        |
| `StvnReorderEnumFilterVariantsQuickFix`                              | **Sort variants to match root enum declaration order** | Quick-Fix Intention               | `Alt+Enter` on out-of-order filter list           |

---

## Inspection Registrations

All **27** `<localInspection>` registrations declared in [`plugin.xml`](file:///c:/Projects/Java/stvnadore/ij_stvnadore_plugin/src/main/resources/META-INF/plugin.xml):

| Inspection Class                              | Short Name                        | Display Name                                          | Group Path                      | Default Level  |
|:----------------------------------------------|:----------------------------------|:------------------------------------------------------|:--------------------------------|:--------------:|
| `StvnVariantStyleInspection`                  | `StvnVariantStyle`                | Variant tag style inspection                          | `STVN / Style`                  |   `WARNING`    |
| `StvnBooleanValidityInspection`               | `StvnBooleanValidity`             | Boolean validity inspection                           | `STVN / Validity`               |    `ERROR`     |
| `StvnMapStructuralInspection`                 | `StvnMapStructural`               | Flat list in map slot inspection                      | `STVN / Structural`             |    `ERROR`     |
| `StvnDegradedSchemaInspection`                | `StvnDegradedSchema`              | Degraded schema payload evaluation                    | `STVN / Quality & Semantics`    | `WEAK WARNING` |
| `StvnDegenerateCompositeInspection`           | `StvnDegenerateComposite`         | Degenerate arity-1 composite schema                   | `STVN / Schema Design`          |   `WARNING`    |
| `StvnEnumSubsetInspection`                    | `StvnEnumSubset`                  | Enum subset filtering inspection                      | `STVN / Semantics`              |    `ERROR`     |
| `StvnFencedStringInspection`                  | `StvnFencedString`                | Fenced string delimiter inspection                    | `STVN / Syntax`                 |    `ERROR`     |
| `StvnLhsReservedTypeInspection`               | `StvnLhsReservedType`             | LHS reserved type inspection                          | `STVN / Semantics`              |    `ERROR`     |
| `StvnConstantRangeInspection`                 | `StvnConstantRange`               | Constant integer range inspection                     | `STVN / Validity`               |    `ERROR`     |
| `StvnFlatDocumentIncludeInspection`           | `StvnFlatDocumentInclude`         | Flat document include inspection                      | `STVN / Structural`             |    `ERROR`     |
| `StvnNestedPackageInspection`                 | `StvnNestedPackage`               | Nested package inspection                             | `STVN / Structural`             |    `ERROR`     |
| `StvnTrailingSlashInspection`                 | `StvnTrailingSlash`               | Trailing slash inspection                             | `STVN / Syntax`                 |    `ERROR`     |
| `StvnStringCapacityInspection`                | `StvnStringCapacity`              | String capacity governance inspection                 | `STVN / Governance`             |   `WARNING`    |
| `StvnExcessiveExactFloatPrecisionInspection`  | `StvnExcessiveExactFloatPrecision`| Excessive exact float mantissa precision inspection   | `STVN / Governance`             |   `WARNING`    |
| `StvnFloatPrecisionLossInspection`            | `StvnFloatPrecisionLoss`          | Continuous float precision loss inspection            | `STVN / Numeric & Floating-Point`|  `WARNING`    |
| `StvnMetadataFacetInspection`                 | `StvnMetadataFacet`               | Metadata facet and block governance inspection        | `STVN / Governance`             |    `ERROR`     |
| `StvnStringCardinalityInspection`             | `StvnStringCardinality`           | String cardinality facet governance inspection        | `STVN / Governance`             |    `ERROR`     |
| `StvnDiscreteIntervalInspection`              | `StvnDiscreteInterval`            | Discrete interval bound governance inspection         | `STVN / Governance`             |    `ERROR`     |
| `StvnTemporalModeInspection`                  | `StvnTemporalMode`                | Temporal mode and unit facet governance inspection    | `STVN / Governance`             |    `ERROR`     |
| `StvnMetadataOrderInspection`                 | `StvnMetadataOrder`               | Metadata facet canonical ordering inspection          | `STVN / Governance`             |    `ERROR`     |
| `StvnRedundantFacetOverrideInspection`        | `StvnRedundantFacetOverride`      | Redundant metadata facet override inspection          | `STVN / Governance`             |   `WARNING`    |
| `StvnUnresolvedTypeInspection`                | `StvnUnresolvedType`              | Unresolved type reference inspection                  | `STVN / Semantics`              |    `ERROR`     |
| `StvnTabCharacterInspection`                  | `StvnTabCharacter`                | Tab character forbidden inspection                    | `STVN / Governance`             |    `ERROR`     |
| `StvnMissingMetaInspection`                   | `StvnMissingMeta`                 | Missing root metadata block inspection                | `STVN / Governance`             |    `ERROR`     |
| `StvnStemMismatchInspection`                  | `StvnStemMismatch`                | Document stem mismatch inspection                     | `STVN / Governance`             |   `WARNING`    |
| `StvnSuspiciousEpochMagnitudeInspection`      | `StvnSuspiciousEpochMagnitude`    | Suspicious epoch magnitude inspection                 | `STVN / Governance`             |   `WARNING`    |
| `StvnStringEscapeScrutinyInspection`          | `StvnStringEscapeScrutiny`        | Hazardous or ambiguous string escape sequence         | `STVN`                          |   `WARNING`    |

---

## IDE Settings & Configuration

Configure STVN options under: **Settings | Languages & Frameworks | STVN** and **Settings | Tools | STVN Schema Repository**.

```
Settings
├── Languages & Frameworks
│   └── STVN
│       ├── [X] Use long-form variants for algebraic sum types (e.g., #Left instead of #L)
│       ├── [X] Show type inlay hints in editor
│       ├── [X] Show type information on hover (Quick Doc)
│       ├── [X] Enable redundant tag warnings (e.g., explicit #Some when implied)
│       ├── [X] Enable tag form discrepancy warnings (e.g., #S vs #Some)
│       ├── [X] Prefer implied algebraic sum types
│       ├── [X] Prompt before in-place canonicalization removes authoring constructs
│       ├── Nominal type alias expansion display mode: FULL | FIRST_HOP | ALIAS_TO_BASE | SYMBOL_ONLY | NONE
│       ├── Fenced string Enter-key auto-closing style: EXPANDED_THREE_LINE | TIGHT_TWO_LINE
│       ├── Reformat Code (Ctrl+Alt+L) engine mode: CORE_SOURCE_FORMATTER | PSI_STRUCTURE_PRESERVING | ...
│       ├── Schema nested container wrapping (:defs & :type): PRESERVE_AUTHORED | EXPAND_NESTED
│       ├── Payload nested container wrapping (:body & #CONST): PRESERVE_AUTHORED | EXPAND_NESTED
│       ├── TimeEpoch Formatting: 12-Hour / 24-Hour UTC preview + suspicious magnitude warning toggle
│       └── Bootstrap Templates: Payload (.stvn) | Payload Include (.stvn_i) | Defs Schema (.stvn_d) | Defs Include (.stvn_di)
└── Tools
    └── STVN Schema Repository
        ├── Repository Endpoint URL: http://localhost:8080
        ├── Request Timeout (ms): 5000
        └── Default Schema Namespace: default
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
- **Unified `:use` Alias Inlay Hints & 3-Scope Namespace Browser:** Prepends renamed `UseMapAlias` head hops with independent per-hop `Ctrl+Click` navigation across all `:body` inlay slots (`State A` and `State B`) and upgrades `STVN Namespace Dependencies` (`Ctrl+Alt+N`) with a live `:defs` / `:type` / `:body` scope switcher, live symbol filter, disabled-section tooltips, and a 5-column zero-bracket-padded STVN 2.0.0 schema view.
- **Pure-IR Architecture:** Coupled exclusively to `stvnadore-core:2.0.0-SNAPSHOT` immutable `org.stvnadore.core.ir.*` records (`StvnDocument`, `StvnDefsCatalog`, `StvnSchema`, `StvnValue`) via `StvnCompiledDocumentCache` with zero ANTLR CST coupling.
- **Canonical 6-File Taxonomy:** Supports `.stvn`, `.stvn_i`, `.stvn_d`, `.stvn_di`, `.stvn_b`, and `.stvn_bd` documents plus reserved multi-part system substrates (`*.stvn_ir.*`, `*.stvn_cas.*`).
- **95-Row Facet Matrix & Zero-Argument Bare Flags:** Enforces 5-tier canonical facet ordering, standalone bare flags (`#unsigned`, `#exact`, `#invertible`, `#ns`, `#us`, `#ms`, `#s`, `#offset`, `#zoned`, `#audited`, `#strip`), and inductive `#equatable` / `#comparable` trait healing.
- **CPU-Bias-Free Expression Space:** Evaluates integer widths, capacities, ordinals, union branch tags, and decimal bounds directly in `BigInteger` and `BigDecimal`.
- **Schema Repository 2.0.0 Publishing:** Publishes flattened `.stvn_d` schemas to `POST /api/v1/schemas/{name.stvn_d}` with structured `canonicalCode` and `symbolicId` HTTP 422 diagnostics.
