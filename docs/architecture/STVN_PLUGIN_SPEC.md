# STVN Architecture Specification: IntelliJ Platform Plugin & Interactive PSI Governance

**Document ID:** `STVN-SPEC-PLUGIN-01`  
**Status:** Canonical Architecture Specification  
**Version:** `2.0.0`  
**Target Repository:** `stvnadore-plugin` (`ij_stvnadore_plugin`)  
**Target Version Baseline:** `2.0.0-SNAPSHOT`  
**Governing Standard:** Simplified Technical English (STE-01 through STE-07)  
**Foundational Baselines:**  
- `STVN_LANGUAGE_SPEC.md` (STVN 2.0.0 baseline)  
- `STVN-VOP-MANIFESTO.md` (Value-Oriented Programming Canonical Foundations)  
- [`docs/architecture/STVN_REPOSITORY_SPEC.md`](file:///c:/Projects/Java/stvnadore/ij_stvnadore_repository/docs/architecture/STVN_REPOSITORY_SPEC.md) (STVN 2.0.0 baseline)  
- [`docs/architecture/STVN_CHESS_SHOWCASE_SPEC.md`](file:///c:/Projects/Java/stvnadore/ij_stvnadore_example_chess/docs/architecture/STVN_CHESS_SHOWCASE_SPEC.md) (STVN 2.0.0 baseline)  
**Toolchain:** Java 21 LTS | Gradle 8.x / 9.x (Configuration Cache Enabled) | IntelliJ Platform SDK 2025.3  

---

## 1. Scope & Dual-Grammar Synchronization Invariant

### 1.1 The Ecosystem Role of the IntelliJ Plugin

The STVN IntelliJ Platform plugin (`stvnadore-plugin`) serves as the interactive developer experience (DX) surface for the STVN ecosystem. While the upstream compiler (`stvnadore-core`) operates in headless, batch, or CLI contexts, the plugin executes inside the interactive IntelliJ editor thread under partial, unclosed, and syntactically incomplete keystroke sequences.

The plugin provides six primary capabilities:
1. **Interactive Lexical & Syntax Highlighting:** Provides token-accurate lexical classification via JFlex (`stvn.flex`) and Grammar-Kit BNF (`stvn.bnf`).
2. **Program Structure Interface (PSI) Tree Construction:** Builds an incremental PSI representation enabling code folding, navigation, and structural search.
3. **Opaque Nominal Reference Resolution:** Resolves cross-file and intra-file type references while strictly preserving opaque nominal type wrappers.
4. **Context-Aware In-Flight Auto-Completion:** Contributes schema-directed value templates, enum variants, and sum constructors without leaking parent container scopes.
5. **Real-Time Inspections & Automated Quick-Fixes:** Flags syntax violations, invalid facets, discrete bound errors, and structural layout mistakes directly in the editor buffer.
6. **Value-Oriented Rename Refactoring:** Executes atomic symbol renaming across schema declarations, usage sites, and standard library namespaces.

```mermaid
flowchart TD
    classDef core fill:#E8F8F5,stroke:#1ABC9C,stroke-width:2px;
    classDef plugin fill:#E1F5FE,stroke:#0288D1,stroke-width:2px;
    classDef idea fill:#FFF3E0,stroke:#F57C00,stroke-width:2px;

    Headless["Batch or CLI Ingress"] --> Core["stvnadore-core (Pure-IR Compiler Pipeline)"]:::core
    Interactive["Interactive Editor Buffer"] --> Plugin["stvnadore-plugin (JFlex and Grammar-Kit BNF)"]:::plugin
    Plugin --> PSI["STVN PSI Tree and StvnCompiledDocumentCache"]:::plugin
    PSI --> IDEA["IntelliJ Platform Engine (Inspections, Completion, Rename)"]:::idea
    Core -.->|"Conformance Test Fixtures"| Plugin
```

### 1.2 The Dual-Grammar Synchronization Invariant

The STVN ecosystem enforces a strict dual-grammar architecture:
- **Headless Authority:** ANTLR4 (`StvnLexer.g4` and `StvnParser.g4` in `stvnadore-core`).
- **Interactive Authority:** Grammar-Kit BNF (`stvn.bnf`) and JFlex (`stvn.flex` in `stvnadore-plugin`).

To prevent architectural drift and syntax divergence, the plugin adheres to the **Dual-Grammar Synchronization Invariant**:

$$\mathcal{T}_{\text{ANTLR4}} \equiv \mathcal{T}_{\text{JFlex}} \quad \land \quad \mathcal{P}_{\text{ANTLR4}}(s) \cong \mathcal{P}_{\text{Grammar-Kit}}(s)$$

For every source document $s \in \Sigma^*$:
1. **Token Taxonomy Identity:** Every terminal token recognized by `StvnLexer.g4` must have an exact equivalent token in `stvn.flex` with identical boundary rules.
2. **Parse Tree Homomorphism:** The hierarchical structure of the ANTLR4 parse tree must map homomorphically to the Grammar-Kit generated PSI tree.
3. **Compound Token Elimination:** All legacy compound tokens removed from ANTLR4 in STVN 2.0.0 (`:Uint4`, `:Int16`, `:String8`, `:StringFixed36`, `:SeqNonEmpty`, `:MapInv`) must be removed from `stvn.bnf` and `stvn.flex`. Both grammars parse base types qualified by metadata facet blocks.
4. **Strict Fenced String Delimiter & `MALFORMED_FENCE` Slot Isolation Invariant (Rule STR-04):** Both ANTLR4 (`StvnLexer.g4` / `StvnParser.g4`) and JFlex / Grammar-Kit (`stvn.flex` / `stvn.bnf`) strictly enforce delimiter pattern `"""[TAG]` without directional arrow syntax. Malformed triple-quoted openings (`"""->[TAG]`, inline `"""..."""`, untagged `"""\n...\n"""`, or invalid `[tag]`) and valid `"""[TAG_A]\n` blocks closed at depth 0 by a mismatched `[TAG_B]"""` delimiter (balancing any nested valid `"""[INNER]\n...[INNER]"""` blocks via a stateless method-local stack and enforcing negated bracket class `~[\r\n\[\]]*` before `]"""`) scan as a single `MALFORMED_FENCE` token admitted into `string_literal` so enclosing `:Tuple`, `:Seq`, `:Map`, and `MetadataMap` slots preserve exact sibling positional arity while unclosed fences reaching `EOF` fail closed with `BAD_CHARACTER`.
5. **27-Keyword `reserved_facet_keyword` Lookahead Boundary:** In `stvn.bnf`, `metadata_value` guards `facet_const_ref` with `!reserved_facet_keyword` across all 24 schema constraint facets plus `#name`, `#domain`, and `#kind`, preventing bare boolean traits (`#preserveIndent`, `#equatable`, `#comparable`) from consuming a following sibling facet as a `#CONST` argument.
6. **Layered Single-Line Escape Highlighting Invariant:** `StvnParserDefinition.createLexer()` preserves single-token `StvnTypes.LITERAL_STRING_SIMPLE` PSI `StringLiteral` nodes for exact AST homology with `StvnParser.g4`, whereas `StvnSyntaxHighlighter.getHighlightingLexer()` wraps `FlexAdapter(_StvnLexer)` in an IntelliJ `LayeredLexer` registering `StvnSimpleStringHighlightingLexer` strictly on `StvnTypes.LITERAL_STRING_SIMPLE`. Valid STVN escapes (`\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX`) emit `StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN` (`VALID_STRING_ESCAPE`), invalid 2-character escapes emit `INVALID_CHARACTER_ESCAPE_TOKEN` (`[i, i + 2)`), malformed `\u` escapes inspect up to `4` characters halting strictly before `\`, `"`, `\r`, `\n`, or `\t` to emit `INVALID_UNICODE_ESCAPE_TOKEN` with 1:1 `[startOffset, endOffset)` span parity with Core `E002` (`STVN_LANGUAGE_SPEC.md` § 5.3.1), and Rule STR-04 verbatim fenced strings (`LITERAL_STRING_FENCED`, `MALFORMED_FENCE`) remain completely unsplit.

```mermaid
flowchart LR
    classDef grammar fill:#F3E5F5,stroke:#7B1FA2,stroke-width:2px;
    classDef check fill:#E8F5E9,stroke:#388E3C,stroke-width:2px;

    ANTLR["ANTLR4 Grammar (StvnLexer and StvnParser)"]:::grammar <-->|"Shared Conformance Suite"| JFlex["Grammar-Kit and JFlex (stvn.bnf and stvn.flex)"]:::grammar
    JFlex --> Assert{"Dual-Grammar Invariant Verification"}:::check
    Assert -- "Pass" --> Build["Clean Headless Build"]:::check
    Assert -- "Drift Detected" --> Fail["Compilation Failure (-Werror)"]:::check
```

---

## 2. PSI Accessor & Opaque Nominal Resolution Governance

### 2.1 The Prohibition of Recursive Nominal Unwrapping

Under Value-Oriented Programming (VOP), nominal types represent distinct domain entities even when their structural definitions are identical:
$$\text{NominalSchema}(:UserId) \neq \text{NominalSchema}(:AccountId)$$

Legacy PSI resolvers implemented recursive schema unwrapping inside `StvnTypeResolver.resolveNominalSchema`. When resolving a nominal alias, the resolver repeatedly traversed the reference chain until reaching a primitive base type. This recursive unwrapping produced three severe architectural defects:
1. **Nominal Erasure:** Collapsed unique domain types into bare primitives (`:String`), destroying nominal boundaries in type inference.
2. **False Type Equivalence:** Treated `:UserId` and `:AccountId` as interchangeable types during auto-completion and validation.
3. **Cyclic Traversal Loops:** Induced editor thread hangs on recursive type definitions.

**Governance Mandate:** Recursive nominal unwrapping is prohibited. `StvnTypeResolver.resolveNominalSchema` must execute shallow resolution:
- When given a nominal reference (e.g. `:UserId`), the resolver must resolve to the authoritative `TypeDefinition` node representing that alias.
- The resolver must retain the nominal wrapper (`:UserId`) as an opaque type brand.
- The underlying structural schema is accessed only when evaluating physical payload conformance or formatting underlying type documentation.

```mermaid
flowchart TD
    classDef allowed fill:#E8F5E9,stroke:#388E3C,stroke-width:2px;
    classDef prohibited fill:#FFEBEE,stroke:#D32F2F,stroke-width:2px;

    Ref["Type Reference: ':UserId'"] --> Resolv{"StvnTypeResolver"}

    Resolv -- "Opaque Resolution (Governed)" --> Shallow["Return TypeDefinition(':UserId') Preserving Nominal Brand"]:::allowed
    Resolv -.->|"Recursive Unwrapping (PROHIBITED)"| Deep["Traverse to Primitive ':String' Erasing Nominal Brand"]:::prohibited
```

### 2.2 PSI Navigation Contracts & Mixin Taxonomy

The plugin structures PSI nodes using dedicated mixins and navigation interfaces:

| Interface / Mixin | Implementing PSI Node | Architectural Role | Navigation & Reference Contract |
|:---|:---|:---|:---|
| `StvnTypeDefinitionMixin` | `TypeDefinition` (`:defs`) | Root type definition in `:defs`. | Implements `PsiNamedElement`, `StvnTypeDefinitionNode`. Provides `getName()`, `setName()`, and `getPresentation()`. |
| `StvnSchemaTypeMixin` | `SchemaType` (`:type`, `:defs`) | Schema expression container. | Resolves underlying constructor (`AtomicType`, `CollectionType`, `ProductType`, `SumType`) or nominal `TypeKeyword`. |
| `StvnConstantDefinitionMixin`| `ConstantDefinition` (`:defs`) | Value constant declaration. | Implements `PsiNameIdentifierOwner`. Enables navigation to constant references in `:body`. |
| `StvnIncludeMapAliasMixin` | `IncludeMapAlias` (`:include`) | Renaming alias in import block. | Implements `PsiNamedElement`. Connects local alias to remote schema target. |
| `StvnUseMapAliasMixin` | `UseMapAlias` (`:use`) | Namespace-level import alias. | Implements `PsiNamedElement`. Maps fully qualified namespace identifiers. |

### 2.3 Pure-IR Compilation Cache & Diagnostic Routing Governance

The plugin enforces a strict pure-IR boundary (`HYGIENE-PLUGIN-03`) between IntelliJ PSI services and `stvnadore-core`:
- **Single-Pass Workspace Snapshot (`StvnCompiledDocumentCache`):** Compiles the active `PsiFile` and unsaved `:include` workspace overlays once per modification stamp into an immutable `StvnCompilationResult<StvnDocument>` snapshot (`StvnDefsCatalog`, `StvnSchema`, `StvnValue`, and `List<StvnDiagnostic>`). Zero ANTLR CST, `core.parser`, `core.ast`, or `core.lowering` classes cross into plugin source sets.
- **Multi-Hop Sum-Type Trajectory (`StvnSumTrajectory` & `StvnTypeResolver`):** Computes arbitrary-depth explicit and implicit `:Option`, `:Either`, and `:Union` step trajectories across both anonymous nested sum constructors (`:DeepTrajectory [#Some] [#Right] [#2] (-> :AckToken -> :String)`) and nominal intermediate coproduct aliases (`:OuterOpt [#Some] (-> :MidEither [#Right] (-> :LeafUnion [#2] (-> :AckToken -> :String)))`) over pure-IR `StvnValue` (`StvnOption`, `StvnEither`, `StvnUnion`) nodes with PSI fallback. Each step records its `anchorPsiValue` so clicking inferred step `k` in `StvnInlayHintsCollector` materializes all unmaterialized prefix steps `0..k` in reverse offset order without triggering `E024 / ERR_AMBIGUOUS_SUM_INFERENCE`.
- **Cross-File Diagnostic Attribution (`StvnDiagnostic.sourcePath()`):** `StvnExternalAnnotator` compares normalized `diag.sourcePath()` against the active editor file path. Diagnostics originating in an included `.stvn_d` or `.stvn_di` module route directly to the matching `:include` `StringLiteral` with `OpenIncludedFileQuickFix`, while `StvnDiagnosticAnnotatorMapping` resolves governing local inspections by prioritizing `diag.symbolicId()` over many-to-one `diag.canonicalCode()` buckets.

### 2.4 Two-Hop `:use` Alias Navigation, Transitive Constant Hover & Nearest-Explicit `#VARIANT` Navigation

- **Two-Hop `:use` & `:include` Alias Navigation (`StvnGotoDeclarationHandler`):**
  - **Hop 1 (Usage Site -> Local Alias):** Invoking `Goto Declaration` (`Ctrl+Click` / `Ctrl+B`) on a renamed `:use` symbol (`#ACTIVE_RELEASE`, `:ReleaseTag`) or renamed `:include` type (`:ConflictTypeB`) across `:defs`, `:type`, or `:body` navigates first to the local `UseMapAlias` or `IncludeMapAlias` RHS (`index 1`) declaration token.
  - **Hop 2 (Alias -> Upstream Declaration):** Invoking `Goto Declaration` on either the LHS (`index 0`) or RHS (`index 1`) token inside `UseMapAlias` or `IncludeMapAlias` navigates directly to the upstream `ConstantDefinition` or `TypeDefinition` across transitive and diamond `:include` module graphs or the Standard Library Prelude.
  - **Diamond Module & § 4.2 Eviction Cascade Parity (`StvnTypeReference` & `StvnConstantReference`):** Per-branch cycle-safe `visited` sets and `PsiManager.areElementsEquivalent` target deduplication resolve diamond `:include` graphs (`10C -> 10A` and `10C -> 10B -> 10A`) unambiguously while enforcing combined `{ #strip } { :Orig :Alias #ORIG #ALIAS }` LHS eviction (Asymmetric Alias vs. Raw and Dual Alias Eviction).
  - **1-Hop `#strip` & FQNI Direct Resolution:** Unrenamed `{ #strip }` projections (`:SettlementEntry`) and package-qualified FQNIs (`#acme/security/SEC_CONTACT`) continue to navigate directly to the upstream declaration in 1 hop.
  - **`PsiReference.resolve()` Caller Isolation:** `StvnTypeReference.resolve()` and `StvnConstantReference.resolve()` continue returning the local `UseMapAlias` RHS token on renamed aliases so `Shift+F6` Rename and `Find Usages` rename only local alias bindings without mutating upstream package declarations. Package-local `:use` directives remain isolated inside their enclosing `PackageEnclosure` and never leak into top-level file lookups.
- **Transitive `:use` Constant & `:body` `#ENUM`/`#CONST` Hover Compliance Parity (`StvnDocumentationProvider`):** Hovering on a `:use`-imported `#CONST` reference displays `Renamed Reference: #LOCAL -> #pkg/REMOTE` (using canonical `#` value sigils via `StvnConstantReference.buildPackageConstantFqni`) chained with the full upstream `ConstantDefinition` documentation card (`Typed Constant`, `Type`, terminal `Value`, and Four-Locus temporal `UTC Preview` / `UTC Time`). When hovering any non-degraded `#ENUM` variant or `#CONST` reference inside `:body`, `StvnDocumentationProvider` renders `<nobr><b>Slot Schema:</b> <code>...</code></nobr>` (formatted with any active multi-hop sum trajectory) and `<b>Compliance Status:</b>` (`Satisfied` in green when clean, or `In Violation` in red with active-file-filtered `StvnDiagnostic` prose and verified `Exemplar Value:` when violated), while strictly omitting `:body` rows on `:defs` declarations and references.
- **Nearest-Explicit `#VARIANT` Navigation Guarded by `#filterExcl` (`StvnTypeResolver.resolveEnumVariantNavigationTarget`):**
  - **Root `:Enum[...]` & Unconstrained Projections:** Navigating from `#VARIANT` in `:body` or `ConstantDefinition` RHS governed by a root `:Enum[...]`, an unconstrained nominal alias, an implicit sum wrapper (`:Option(:SettlementState)`), or a `#strip` import lands directly on the `#VARIANT` `ValueKeyword` inside `:Enum[...]`.
  - **Positive `#filterIncl` Two-Hop Narrowing:** When governed by a schema declaring `#filterIncl [...]` (without `#filterExcl`), Hop 1 lands on the matching `#VARIANT` inside `#filterIncl [...]`; Hop 2 from `#filterIncl [...]` lands on the ancestor `#filterIncl` or root `:Enum[...]` `#VARIANT`.
  - **Negative `#filterExcl` & Degraded Mutex Guard:** When the nearest restricting schema declares `#filterExcl [...]` (or conflicting `#filterIncl` + `#filterExcl`), `#VARIANT` is not positively listed on that subtype, so navigation halts at the restricting `TypeDefinition` (or inline `SchemaType`) rather than bypassing the exclusion boundary.

---

## 3. Interactive In-Flight DX & Caret Resolution Invariants

### 3.1 Strict Upward Traversal Prohibition

During typing, an author frequently leaves tokens in an incomplete state (such as `#`, `:`, `/`, or unclosed quotes `"`). 

**The Strict Upward Traversal Invariant:** When resolving the caret context or hover documentation for an incomplete token, PSI visitors and documentation providers must never climb the AST to parent container nodes.

```mermaid
flowchart TD
    classDef caret fill:#FFF3E0,stroke:#F57C00,stroke-width:2px;
    classDef stop fill:#FFEBEE,stroke:#D32F2F,stroke-width:2px;
    classDef leak fill:#ECEFF1,stroke:#607D8B,stroke-width:2px;

    Caret["Caret at Incomplete Token ('#', ':', '/')"]:::caret --> Check{"Incomplete Token Target?"}
    Check -- "Yes" --> Suppress["Return null Immediately (Upward Climb Prohibited)"]:::stop
    Check -.->|"Climb to Parent Container"| Parent["Leak Parent Container Doc (PROHIBITED)"]:::leak
```

If the element under the caret matches any of the following conditions:
1. An unadorned sigil token (`#` or `:`),
2. A path separator token (`/`),
3. A `TokenType.BAD_CHARACTER`,
4. A `PsiErrorElement`,
5. Interior whitespace within a container literal (`PsiWhiteSpace`),

The resolver must immediately return `null`. It must not ascend to the enclosing `TupleLiteral`, `ListLiteral`, `MapLiteral`, or `BodyEntry`.

### 3.2 Container Documentation Isolation

Hovering over interior whitespace, delimiters, or incomplete elements within a collection literal must never display the documentation of the parent collection. Documentation is isolated strictly to:
- Explicit container boundary delimiters (when hovering directly on `(` or `{`),
- Fully completed inner elements.

### 3.3 Sigil Prefix Matching Discipline

Code completion must adhere to strict sigil prefix matching:
- Typing numeric digits (e.g. `1`, `2`) must never pro-offer `#`-prefixed auto-completion items (such as `#1`, `#2`, or enum variants) unless the author has explicitly typed the `#` sigil.
- The plugin enforces this discipline via `StvnSigilPrefixMatcher`, which wraps the IntelliJ platform `PrefixMatcher` and filters out `#` suggestions for numeric inputs.

### 3.4 Degraded Schema Sentinel Preservation

When a schema in `:defs` contains constraint errors (such as discrete bound violations, conflicting mutually exclusive facets, incomplete trait facets, invalid facets, or syntax errors), the PSI resolver must not crash with `NullPointerException` or discard the schema entirely.

The plugin treats the schema as a **Degraded Schema Sentinel**:
- Concise base-type formatting (`StvnSchemaFormatter.formatDegradedUnderlyingBaseIr` / `formatDegradedUnderlyingBasePsi`) collapses degraded `:Enum` and `:EnumSubset` schemas to `:Enum` without inline variant lists across `PW006`, `:type` warnings, and hover banners.
- Hovering over a `:body` value governed directly by a degraded or ungrounded schema — or nested inside an enclosing `:body` container (`:Seq`, `:Set`, `:Map`, `:Tuple`, `:Option`, `:Either`, `:Union`) whose container schema is degraded — routes `StvnDocumentationProvider` to the governed `Value` compliance card, rendering the yellow **Degraded Schema Warning** banner and `Compliance Status: Suspended (Degraded Schema)` (omitting `Exemplar Value:` and never falsely reporting `Satisfied`) while keeping `PsiReference.resolve()` (`Ctrl+Click`) symbol navigation intact:
  > **⚠️ Degraded Schema Warning**  
  > Nominal type `:<alias>` contains constraint errors in `:defs` (underlying base type: `:<base>`).  
  > Payload evaluation is suspended.
- Payloads in `:body` continue to receive syntax highlighting, multi-hop degraded type traces (`⚠ :Child -> :Parent -> :Enum`), and basic base-type auto-completion based on the fallback schema.

### 3.5 Four-Locus Temporal `UTC Preview` / `UTC Time`, Anti-Wrap `<nobr>` & Concise `Default (:Enum)` Origin Contracts

- **Four-Locus Temporal Hover Parity (`:TimeEpoch` & `:DateTime`):** `StvnDocumentationProvider` renders deterministic ISO-8601 `UTC Preview` and human-readable `UTC Time` (`HOUR_12` / `HOUR_24` user preference via `StvnTimeEpochFormatter.formatInstantHumanUtc`) rows across all four temporal authoring and reference loci:
  1. `:defs` LHS `#CONST` declaration (`ConstantDefinition` card),
  2. `:defs` RHS literal or chained `#CONST` value (`Constant Value` card with `Locus: Constant Definition (:defs clause)`),
  3. `:body` `#CONST` reference across top-level, nested `:Tuple`/`:Seq`/`:Set`/`:Map`, and implicit `:Option`/`:Either`/`:Union` slots (`Slot Schema` + `Temporal Scale` / `Temporal Mode` + `UTC Preview` + `UTC Time`), and
  4. `:body` direct integer or string literal (`Payload Value` card).
- **Four-Locus Suspicious Epoch Magnitude Parity (`StvnSuspiciousEpochMagnitudeInspection` <-> `StvnDocumentationProvider`):** Both the hover `Suspicious Epoch Magnitude Warning` banner and editor `PW006 / WARN_SUSPICIOUS_EPOCH_MAGNITUDE` squigglies delegate to `StvnTimeEpochFormatter.format` (`isMagnitudeMismatch() || isCivilWindowViolation()`, STE singular/plural `"1 digit"` vs. `"<N> digits"`, gated by `StvnSettings.showEpochMagnitudeWarnings`). In `:defs`, warnings pin to the LHS `ConstantDefinition` `#CONST` identifier (with on-the-fly `INFORMATION` rescale/scale fixes on direct RHS integer literals); in `:body`, warnings pin directly to `#CONST` `ValueKeyword` references and `IntegerLiteral` tokens.
- **Schema Header `<nobr>` Anti-Wrap Protection:** Single-line schema metadata rows (`Type:`, `Slot Schema:`, `Temporal Scale:`, `Temporal Mode:`, `Value Type:`, and `Governing Schema:`) wrap their `<b>Label:</b> <code>...</code>` segments in `<nobr>...</nobr>` so Swing `HTMLEditorKit` never splits compound signatures such as `{ #offset } :DateTime` across lines.
- **Canonical Quoted Fenced `[REGEX]` Hover Presentation:** Fenced string `#regex` facets and `:String` bounds (`"""[REGEX]...[REGEX]"""` in `prelude.stvn_core.stvn_d` and user schemas) are decoded via `StvnPsiLiteralEvaluator.decodeStringLiteral(..., false)` (`Rule STR-04`) and rendered in hover tables and constraint headers as compact canonically escaped double-quoted strings via `StvnPsiLiteralEvaluator.formatCanonicalQuotedString(...)`.
- **128-Bit Floor-Adjusted Epoch & DST-Gap Safety:** `StvnTimeEpochFormatter` parses decimal, underscore, hexadecimal (`0x`), binary (`0b`), and octal (`0o`) tick literals via `StvnPsiLiteralEvaluator.parseBigInteger`, floor-adjusts negative sub-second remainders (`-1` `#ms` -> `1969-12-31T23:59:59.999Z`), and converts arbitrary-precision 128-bit ticks into proleptic Gregorian ISO-8601 UTC strings using pure-`BigInteger` 400-year (`146,097`-day) era arithmetic without `Instant` range overflow. For `:DateTime` (`#offset`, `#zoned`, `#audited`), `UTC Preview` and `UTC Time` render strictly when `StvnCompiler.validateDateTimeLiteral(rawQuoted, mode).isEmpty()`, suppressing nonexistent Spring-forward DST-gap timestamps and mismatched `#audited` offsets without throwing exceptions.
- **Concise `Default (:Enum)` Origin:** In `Effective Facets & Traits` hover tables, `formatDefaultOriginBaseType` normalizes `:Enum [...]` constructor signatures to `:Enum` so default trait rows display `Default (:Enum)` (`STE-07`) while `Underlying Structure:` continues to display the full active variant list.

---

## 4. Live Inspection & Intention Quick-Fix Contracts

The plugin provides real-time in-editor inspections and automated intention quick-fixes to maintain language standards:

```mermaid
flowchart TD
    classDef inspect fill:#E1F5FE,stroke:#0288D1,stroke-width:2px;
    classDef fix fill:#E8F8F5,stroke:#1ABC9C,stroke-width:2px;

    Buffer["Editor Document Buffer"] --> Runner["IntelliJ Inspection Engine"]

    Runner --> I1["StvnStringCardinalityInspection"]:::inspect
    I1 --> Q1["Convert to '#minSize / #maxSize' Quick-Fix"]:::fix

    Runner --> I2["StvnDiscreteIntervalInspection"]:::inspect
    I2 --> Q2["Convert to Half-Open Bounds Quick-Fix"]:::fix

    Runner --> I3["StvnTemporalModeInspection"]:::inspect
    I3 --> Q3["Add Temporal Mode Facet Quick-Fix"]:::fix

    Runner --> I4["StvnMapStructuralInspection"]:::inspect
    I4 --> Q4["StvnMapAutoHealerQuickFix"]:::fix

    Runner --> I5["StvnEnumSubsetInspection"]:::inspect
    I5 --> Q5["Reorder Variants Quick-Fix"]:::fix
```

### 4.1 `StvnStringCardinalityInspection`
- **Target Locus:** `TypeDefinition`, `ConstantDefinition`, and inline `SchemaType` nodes resolving to `:String`.
- **Defect Detection:** Detects `#size` applied to `:String` with 1:1 `StvnDefsLowerer` coordinate and STE prose parity (`Facet '#size' is prohibited on :String; use '#minSize' and '#maxSize' (e.g. { #minSize N #maxSize N } :String)`).
- **Diagnostic Code:** `E008 / ERR_STRING_CARDINALITY_PROHIBITED`.
- **Intention Quick-Fixes:** On root `:String` constructors with positive `<N>` (`IntegerLiteral` or `#CONST`), offers concrete interpolated primary `Convert '#size <N>' to '#minSize 1 #maxSize <N>'` (`Priority.HIGH`) and secondary `Convert '#size <N>' to '#minSize <N> #maxSize <N>'` (`Priority.NORMAL`, deduplicated when `<N> == 1`). Under STVN Spec § 6.1 (`#maxSize >= 1`), non-positive `#size 0` / negative values and derived nominal `:String` aliases offer only `Remove prohibited facet '#size ...'` (`Priority.HIGH`, never `#maxSize 0`), while positive conversions place `#minSize` (`1510`) and `#maxSize` (`1520`) at canonical § 6.1 5-tier rank via `StvnMetadataFacetInsertionUtil`.

### 4.2 `StvnDiscreteIntervalInspection`
- **Target Locus:** `TypeDefinition`, `ConstantDefinition`, and inline `SchemaType` nodes declaring discrete or ordered intervals across `:Int`, `:Float` (continuous and `{ #exact }`), `:String` (unsigned UTF-8 byte order), `:TimeEpoch`, and `:DateTime`.
- **Defect Detection:**
  - Flags closed upper bounds (`#maxIncl`) and open lower bounds (`#minExcl`) on discrete domains (`:Int`, `{ #exact } :Float`, `:TimeEpoch`, `:DateTime`). STVN 2.0.0 enforces half-open intervals $[minIncl, maxExcl)$ on discrete types.
  - Flags empty interval domains (`ERR_EMPTY_INTERVAL_DOMAIN`) and inverted bound ranges (`ERR_INVERTED_RANGE`) with 1:1 `StvnDefsLowerer.resolveBoundPairSpan` coordinate pinning: adjacent local bounds (`|minIdx - maxIdx| == 1`) pin `metaMap` sub-range `[earlier .. later)` so `{`, `}`, and sibling facets remain clear; non-adjacent local bounds (`|minIdx - maxIdx| > 1`) pin `later` (`MetadataEntry`) with a silent `INFORMATION` quick-fix anchor on `earlier`.
  - Validates `:DateTime` bounds against effective `DateTimeMode` (`OFFSET`, `ZONED`, `AUDITED`) via `StvnCompiler.validateDateTimeLiteral` prior to instant comparison, deferring mode/format violations cleanly to Core `E019` / `E028`.
- **Diagnostic Codes:** `ERR_DISCRETE_BOUND_KIND_PROHIBITED` (`E011`), `ERR_EMPTY_INTERVAL_DOMAIN` (`E009`), `ERR_INVERTED_RANGE` (`E009`), `MUTUALLY_EXCLUSIVE_BOUNDS` (`E008`).
- **Intention Quick-Fixes (`IntentionPreviewInfo.DIFF` Safe):**
  - Converts `#maxIncl <N>` to `#maxExcl <N+1>` and `#minExcl <N>` to `#minIncl <N+1>`.
  - `SwapInvertedBoundsQuickFix` (`LocalQuickFixAndIntentionActionOnPsiElement`) swaps inverted value bounds (`#minIncl`/`#minExcl` vs. `#maxIncl`/`#maxExcl`) and cardinality bounds (`#minSize` vs. `#maxSize`) in place with full `IntentionPreviewInfo.DIFF` preview support.

### 4.3 `StvnTemporalModeInspection`
- **Target Locus:** Declarations of `:TimeEpoch` and `:DateTime`.
- **Defect Detection:**
  - Flags bare `:TimeEpoch` lacking an explicit standalone scale flag (`#ns`, `#us`, `#ms`, `#s`), pinned directly to the `:TimeEpoch` `AtomicType` token.
  - Flags bare `:DateTime` lacking an explicit mode facet (`#offset`, `#zoned`, `#audited`), pinned directly to the `:DateTime` `AtomicType` token.
  - Flags mutually exclusive temporal scale or mode facets declared simultaneously in the same metadata block, pinning `GENERIC_ERROR` strictly to conflicting facets (`i >= 1`) while exposing silent `INFORMATION`-level `Alt+Enter` fixes on `i == 0`.
- **Diagnostic Codes:** `ERR_MISSING_TEMPORAL_FACET` (`E008`), `MUTUALLY_EXCLUSIVE_BOUNDS` (`E008`).
- **Intention Quick-Fixes:** Inserts default standalone temporal flags (`{ #ms } :TimeEpoch` or `{ #offset } :DateTime`), keeps only the selected facet, or removes a conflicting facet (`Remove conflicting facet '<facet>'`).

### 4.4 `StvnMapStructuralInspection`
- **Target Locus:** Map literals and map collection type declarations.
- **Defect Detection:**
  - Flags flat list literals (`[ k v k v ]`) supplied to map slots.
  - Enforces canonical map literal envelope `{ [ key value ] }`.
  - Enforces `#invertible` dual-set uniqueness on map payloads.
- **Diagnostic Code:** `ERR_MAP_STRUCTURE_INVALID`.
- **Intention Quick-Fix:** `StvnMapAutoHealerQuickFix` automatically restructures flat list entries into paired bracket tuples `[ key value ]` inside outer curly braces.

### 4.5 `StvnEnumSubsetInspection` & Per-Variant Enum Quick-Fixes
- **Target Locus:** Type definitions declaring `#filterIncl` or `#filterExcl`, plus `:body` and `ConstantDefinition` values governed by `:Enum` or `:EnumSubset` schemas.
- **Defect Detection:**
  - Enforces monotonic narrowing (`E014 / ENUM_SUBSET_NOT_SUBSET`), root declaration ordering (`E015 / ENUM_SUBSET_ORDER_VIOLATION`), non-empty active subsets (`E016 / ENUM_SUBSET_EMPTY`), and `#filterIncl` / `#filterExcl` mutual exclusivity (`E008 / MUTUALLY_EXCLUSIVE_BOUNDS` pinned to `i >= 1`).
  - Flags subset-excluded variants with `E012 / ERR_CONSTRAINT_VIOLATION` and undeclared variants with `E028 / ERR_TYPE_MISMATCH` across both `:body` slots and nominal `#CONST` RHS values while exempting declared `#CONST` references.
- **Intention Quick-Fixes:** `StvnReorderEnumFilterVariantsQuickFix` sorts filter variants into root declaration order; `RemoveRedundantFacetQuickFix` (`Remove conflicting facet '<facet>'`) removes a conflicting filter facet; `StvnPayloadValueQuickFixProvider` offers ordered per-variant `Replace with '<#VARIANT>'` actions across all active variants.

### 4.6 `RemoveInvertibleFacetQuickFix`
- **Target Locus:** `#invertible` `MetadataEntry` nodes and non-equatable map values in inverted map declarations.
- **Defect Detection:** Intercepts `ERR_TRAIT_VIOLATION` with diagnostic message `"Inverted map values require types to be #equatable #TRUE"`.
- **Resolution Behavior:** Removes the `#invertible` facet from nominal map definitions or anonymous map constructors.
- **Syntax Hygiene Guard:** If `#invertible` is the sole entry in the metadata block, the fix deletes the entire `{}` block to prevent `ERR_EMPTY_METADATA_BLOCK` (STVN Spec § 3.5.1).
- **Co-Located Availability:** The action is offered both when the caret is on `#invertible` and when the caret is on the offending value.

### 4.7 `AddExactConstraintQuickFix`
- **Target Locus:** Built-in continuous `:Float` `SchemaType` nodes in equatability-requiring positions (`#invertible` map values, standard map keys, and set elements).
- **Defect Detection:** Intercepts `ERR_TRAIT_VIOLATION` targeting continuous `:Float`.
- **Resolution Behavior:** Replaces unadorned `:Float` with `{ #exact } :Float`, or inserts `#exact` into existing metadata blocks.
- **Capability Restoration:** Under STVN Spec § 6.2, `#exact` elevates `:Float` to discrete arbitrary-precision decimal representation, restoring `#equatable #TRUE` without requiring nominal wrapping in `:defs`.
- **Conflict Prevention:** Purges any conflicting `#size` facet per STVN Spec § 6.1 mutual exclusivity rules.

### 4.8 Comment-Preserving `:meta` & `MetadataMap` Reordering and `:meta` Token Authority
- **Target Locus:** Root `:meta` sections (`StvnMetadataOrderInspection` / `StvnReorderMetaEntriesQuickFix`), schema `MetadataMap` constraint blocks (`StvnReorderMetadataFacetsQuickFix`), `Ctrl+Alt+L` pre/post-format processors (`StvnMetadataOrderPreFormatProcessor`, `StvnCoreDelegationPostFormatProcessor`), and root `:meta` identity inspections (`StvnMissingMetaInspection`, `StvnStemMismatchInspection`).
- **Comment & Blank-Line Slice Invariant (Golden Quartet #14 Parity):**
  - Partitioning attaches contiguous standalone `//` comments and same-line `//` comments to each `:meta` or `MetadataMap` entry slice (`CommentedEntrySlice`) while preserving trailing footer `//` comments before `}`.
  - Blank lines emit strictly when `newlinesSincePrevItem >= 2`; consecutive standalone `//` comments (`newlines == 1`) never fabricate blank lines, and enclosure-top blank lines immediately after `{` (`entryIdx == 0`) never fabricate inter-entry blank lines after permutation.
  - In `PSI_STRUCTURE_PRESERVING` mode, `StvnMetadataOrderPreFormatProcessor` snapshots pre-format coordinates so `StvnBlock` and `StvnCoreDelegationPostFormatProcessor.reanchorAlignedTrailingComments` collapse interior multi-spaces and pad aligned trailing comments (`>= 2` authored spaces) to their original target columns, matching `StvnSourceFormatter` (`source_pretty/14_comment_preserving_meta_and_facet_reordering.core_fmt.stvn_i` and `source_pretty/15_const_trait_facets_and_temporal_enum_comments.core_fmt.stvn_i`) byte-for-byte.
  - **Mutex-Group Rank Stabilization (`createMutexStabilizedEntryRanker`):** Across all 6 mutually exclusive facet groups (`#ns..#s`, `#offset..#audited`, `#size`/`#exact`, `#minIncl`/`#minExcl`, `#maxIncl`/`#maxExcl`, `#filterIncl`/`#filterExcl`), `StvnMetadataOrderInspection` suppresses secondary `E037 / ERR_FACET_ORDER_VIOLATION` cascades on 2nd+ co-occurring group members and stabilizes their sort rank to the first encountered group member so `Ctrl+Alt+L` and `StvnReorderMetadataFacetsQuickFix` never flip reverse-ordered mutually exclusive pairs (`{ #s #ms }`).
- **Authoritative 5-Character `KW_META` Pinning & In-Flight `#kind` Suppression:**
  - `W002` (`WARN_DOCUMENT_DOMAIN_OMITTED_IN_META`) and non-empty `:meta` omitted-`#kind` `E031` (`ERR_DOCUMENT_KIND_MISMATCH`) anchor strictly to the 5-character `KW_META` (`:meta`) token across both `StvnExternalAnnotator` (via upstream `StvnDefsLowerer`) and governed local inspections (`StvnStemMismatchInspection`, `StvnMissingMetaInspection`) with zero downstream coordinate clamping and zero duplicate highlights.
  - During active typing inside `:meta`, `StvnMissingMetaInspection` suppresses secondary `E031` cascades whenever `#kind` is pinned without a value (`MetaKindFacet.getMetaKindValue() == null`) or appears as an in-flight `#kind` tag inside `MetaBlock`.

### 4.9 `StvnRedundantFacetOverrideInspection` (`W003 / WARN_REDUNDANT_METADATA_FACET` 1:1 Deconfliction)
- **Target Locus:** All schema `MetadataMap` blocks across `:defs` (`TypeDefinition`, `ConstantDefinition`), root `:type` (`TypeEntry`), and inline schema constructors (`SchemaType`).
- **1:1 Core Deconfliction:** Mapped in `StvnDiagnosticAnnotatorMapping` to `DiagnosticBag.WARN_REDUNDANT_METADATA_FACET` (`W003`). When enabled, `StvnExternalAnnotator` yields `W003` emission exclusively to `StvnRedundantFacetOverrideInspection` (`[stvn-plugin:W003-WARN_REDUNDANT_METADATA_FACET]` + `RemoveRedundantFacetQuickFix`). When disabled in the user's inspection profile, `StvnExternalAnnotator` surfaces the identical `[startOffset, endOffset)` `MetadataEntry` spans and STE prose as `[stvn-core:W003-WARN_REDUNDANT_METADATA_FACET]` with zero stacked warnings (`count(range, code) == 1`).
- **Pure-PSI Fallback:** When unrelated Pass 1 syntax errors prevent full Pass 2 compilation, the inspection evaluates root tautologies (`#size 32` on `:Int`, `#size 64` on continuous `:Float`, `#minSize 0`, `#maxSize 16777216`, `#preserveIndent #FALSE` on `:String`, natural bit-width bounds, intrinsic `#equatable` / `#comparable` traits) and nominal-parent overrides across `:Int`, `:Float`, `:String`, `:TimeEpoch`, `:DateTime`, and `:Enum` `#filterIncl` subsets directly over PSI and `#CONST` chains.

### 4.10 `StvnFlatDocumentIncludeInspection` (Hermetic `:include` `E005` & Scoped `:use` `E035` 1:1 Governance)
- **Target Locus:** `IncludeStmt` and `UseStmt` (`UseTarget`) nodes inside hermetic compilation units (`.stvn`, `.stvn_d`, `.stvn_b`, `.stvn_bd`, or explicit `:meta` `#kind #BODY` / `#DEFS`).
- **Defect Detection & 1:1 Core Parity:**
  - Flags `:include` statements in hermetic units via `prohibitsIncludeStatements(file)` with exact `StvnDefsLowerer` coordinate and STE prose parity (`Hermetic document or schema (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot contain include statements: <fileName>`, `E005 / ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT`), while suppressing secondary duplicate `:defs` cascades via `StvnMetaTemplateUtil.isInSecondaryRootSection`.
  - Flags scoped `:use` directives in hermetic documents targeting packages outside `StvnPrelude.isPreludePackageTarget(target)` and locally declared non-reserved `:defs` packages (`:package :pkg`, `:pkg/Type`, `#pkg/CONST` with hierarchical prefix matching and `StvnNamespaceRules.isReservedPackage` exclusion), pinned strictly to `useStmt.getUseTarget()` (`E035 / ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT`) while suppressing secondary emission when `StvnTrailingSlashInspection.hasTrailingSlash(useTarget)` (`E002`) or incomplete bare `:` tokens are present.
  - Modular include units (`.stvn_i` and `.stvn_di`) remain governed by `StvnExternalAnnotator` (`E030 / UNDEFINED_TYPE`) across transitive `:include` graphs with zero duplicate highlights.
- **Intention Quick-Fixes, `:meta` Synthesis & Preview Guard:**
  - Offers `RemoveIncludeStatementQuickFix` (`Remove :include directive [for '<path>']`, `Priority.HIGH`) on `E005` and `RemoveUseStatementQuickFix` (`Remove :use directive for '<target>'`, `Priority.HIGH`) on `E035`. Both share comment-preserving line deletion that omits comment-free sole-element `:defs` sections in `#BODY` documents (`.stvn`) while retaining canonical `:defs {\n  }` enclosures in `#DEFS` schemas (`.stvn_d`, `.stvn_bd`).
  - Offers `StvnConvertToModularQuickFix` (`Convert document/schema to modular format (.stvn_i / .stvn_di)`, `Priority.LOW`) strictly on textual `.stvn` and `.stvn_d` units when the target modular filename does not collide in the directory (suppressing binary `.stvn_b` / `.stvn_bd` units). Conversion updates `#kind` (`#BODY_INCLUDE` / `#DEFS_INCLUDE`), inserts missing `#kind` in out-of-order or partial `:meta` blocks, synthesizes a `Rule STR-STEM` `:meta` section when converting `:meta`-less 1-stem or 2-stem files, commits buffer edits prior to `VirtualFile.rename`, and renders a VFS-safe `IntentionPreviewInfo.CustomDiff` preview (`<oldName> -> <newName>`). `StvnPackageCompletionContributor` filters `:use` completion items in hermetic units to Prelude and local non-reserved `:defs` packages.

### 4.11 `StvnConvertEscapedStringToFencedIntention` (1-Click Escaped-to-Fenced String Conversion)
- **Target Locus:** Escaped simple `StringLiteral` (`LITERAL_STRING_SIMPLE`) tokens in `MetadataEntry` string facets (`#regex`, `#minIncl`, `#minExcl`, `#maxIncl`, `#maxExcl`), `ConstantDefinition` RHS values, and `:body` payload values.
- **Availability & Lossless Round-Trip Soundness Guards (`Rule STR-04`):**
  - Strictly excludes root `:meta` entries (`MetaEntry`), `:include` path strings (`IncludeStmt`), and `:DateTime` bounds/literals (which require simple double-quoted literals under the STVN grammar).
  - Decodes simple string escapes strictly across the STVN escape set (`\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX`) via `StvnPsiLiteralEvaluator.decodeStringLiteral` and evaluates `StvnPsiLiteralEvaluator.shouldEmitFencedPretty(decoded, preserveIndent)` (`1:1` parity with `AstPrettyPrinter`: requires `\n`, `\t`, `\"`, or `\\`, rejects `\r` and control chars `< 0x20` other than `\n`/`\t`, and under `#preserveIndent #FALSE` rejects ASCII-space-only strings, space-only interior lines, and strings where `trimCommonIndent(decoded)` strips common leading spaces).
- **Tag Selection, Local Formatting & Sibling Isolation:**
  - Selects `"""[REGEX]` on `#regex` facets and `*REGEX` / `*_REGEX` constants (`StvnPsiLiteralEvaluator.isRegexConstantIdentifier`), and `"""[RAW]` elsewhere, escalating deterministically via `StvnPsiLiteralEvaluator.chooseSafeFenceTag` (`[RAW_1]`, `[REGEX_1]`, ...) when `[TAG]"""` collides with content.
  - Expands enclosing single-line `MetadataMap` (`{ #regex "..." }`) or single-line payload containers (`[...]`, `(...)`, `{ [ ... ] }`) directly with canonical `+2`-space STVN indentation (leaving unrelated sibling declarations and out-of-order metadata blocks in `:defs` byte-for-byte untouched), and aligns the body margin to `ownerIndent + 2` (`#preserveIndent #FALSE`) or verbatim `0` (`#preserveIndent #TRUE`).

### 4.12 3-Submenu VOP Projection & Canonicalization Menu Matrix (`StvnEditorPopupMenuGroup` & `StvnVopProjectionService`)
- **Orthogonal 3-Submenu Topology (`EditorPopupMenu`, `ProjectViewPopupMenu` & `BuildMenu`):** `StvnEditorPopupMenuGroup` (`STVN >`, `/icons/stvn_file.svg`, `ActionUpdateThread.BGT`) is visible strictly on textual STVN units (`.stvn`, `.stvn_i`, `.stvn_d`, `.stvn_di`) and hidden on binary archives (`.stvn_b`, `.stvn_bd`) and non-STVN files. Literal `.stvn_d` labels escape underscores as `.stvn__d` in `plugin.xml` and invoke `Presentation.setText(exactText, false)` to prevent `TextWithMnemonic` from stripping `_d`. Below `StvnBrowseNamespaceAction` (`Browse Namespace Dependencies`, `Ctrl+Alt+N`), three `<separator/>`-divided submenus separate Artifact Kind, Format Profile, and Output Sink across 10 explicit actions:
  1. **Submenu 1 — `Extract Hermetic Schema (.stvn_d — #DEFS Only)` (`StvnExtractSchemaMenuGroup`):** `Copy to Clipboard (Pretty)`, `Copy to Clipboard (Compact CAS)`, `<separator/>`, `Export Sibling .stvn_d File (Pretty)`, and `Export Sibling .stvn_d File (Compact CAS)` (plus `BuildMenu` entry `StvnBuildExportSchemaCompactCasAction` labeled `Export Hermetic STVN Schema (.stvn_d, Compact CAS)`). Resolves workspace `:include` DAGs via `StvnSchemaFlattener.flatten`, validates via `StvnCompiler.compileToResult` (aborting with `showEmptySchemaDefsWarning` when zero non-prelude `:defs` exist to prevent invalid `E002` `:defs{}` files), and enforces the `.stvn_d` Self-Overwrite Guard (`enabled = false` on `.stvn_d` for sibling file exports).
  2. **Submenu 2 — `Extract Hermetic Payload (.stvn — Includes :body)` (`StvnExtractPayloadMenuGroup`):** `Copy to Clipboard (Pretty)`, `Copy to Clipboard (Compact)`, `<separator/>`, `Export Sibling .stvn File (Pretty)`, and `Export Sibling .stvn File (Compact)`. Disabled on `.stvn_d` and `.stvn_di` schemas; enforces the `.stvn` Self-Overwrite Guard on `.stvn` for sibling exports; reconstructs `StvnDocument` via pure `org.stvnadore.core.ir` records (`StvnDocumentKind.BODY` when `meta.isPresent()`, `hasIncludes = false`) so `.stvn_i -> .stvn` projections never leak `#kind #BODY_INCLUDE` (`E031`).
  3. **Submenu 3 — `Canonicalize Active Buffer In-Place` (`StvnCanonicalizeInPlaceMenuGroup`):** `Replace Buffer with Pretty IR` and `Replace Buffer with Compact IR`. Disabled on read-only and synthetic Prelude files; guarded by `StvnCanonicalizationGuard` (`PsiComment`, `IncludeStmt`, `PackageEnclosure`, `UseStmt`, payload `ConstantDefinition`, and unreferenced `:defs`); stages multi-root unsaved workspace `:include` graphs; preserves the active file's extension-compatible `#kind`; verifies post-printed IR via `StvnCompiler.compileToResult` before mutating `document` (aborting with `showEmptyInPlaceDefsWarning` if desugaring empties `:defs`); and mutates via undoable `WriteCommandAction` (`Ctrl+Z`) in both `EditorPopupMenu` and `ProjectViewPopupMenu`.
- **Atomic VFS Persistence & Action-Specific Feedback:** All `.stvn_d` and `.stvn` sibling exports execute on the EDT inside `WriteAction.run` with UTF-8 LF normalization, `FileDocumentManager` sync, `VfsUtil.markDirty`, and `FileBasedIndex.getInstance().requestReindex` (`0` `ProjectIndexableFilesFilterHealthCheck` warnings), and emit exact artifact/kind/format/sink balloons via `StvnWorkspaceNotificationHelper`.

### 4.13 `StvnStringCapacityInspection` Root-Constructor Scope, 5-Tier Placement & `E013` `#regex` Removal
- **Derived Nominal Alias Suppression (`E040` Guard):** Suppresses `PW001`–`PW003` capacity warnings and `Set nominal capacity to ...` quick-fixes whenever `schemaType.getSchemaConstructor() == null` (`:Child ... :ParentNominal` or `:type :ParentNominal`), because Tier-1 `#minSize`/`#maxSize` are permanently locked on the root constructor (`STVN_LANGUAGE_SPEC.md` § 6.1 & § 9.1.1).
- **Immutable `ConstantDefinition` Unadorned Suppression:** Suppresses unadorned capacity warnings (`PW001`/`PW002`) on `ConstantDefinition` inline schemas (`#FRAME_ID_REGEX :String "..."`, `#CONST :Seq(:Int) [...]`, both at top-level `:defs` and inside `:package`) because constant payloads have fixed compile-time cardinality, while retaining `PW003` when explicit `#maxSize` exceeds `thresholdCapacity`.
- **Facet Bleed-Through Isolation & Canonical 5-Tier Insertion:** Pins capacity descriptors strictly to LHS `TypeDefTarget` and RHS constructor tokens (`:String`, `:Seq`, `:Set`, `:Map`)—never to `MetadataMap`—and inserts `#minSize`/`#maxSize` at canonical § 6.1 rank (`1510`/`1520`) before Tier 2–5 facets (`#equatable`, `#minIncl`..`#maxExcl`, `#preserveIndent`, `#regex`) using exact `RBRACE` token offset tracking in `StvnMetadataFacetInsertionUtil`.
- **`E013 / INVALID_REGEX_PATTERN` Quick-Fix:** `StvnExternalAnnotator` attaches `Remove invalid '#regex' facet` (`Priority.HIGH`, `IntentionPreviewInfo.DIFF`) across inline and `#CONST` `#regex` entries, excising comment-free sole-entry `{}` blocks to prevent `E003`.

### 4.14 Rule `STR-04` Untagged & Malformed Fence Quick-Fixes (`ConvertToFencedStringIntentionAction` & `ConvertToSimpleStringIntentionAction`)
- **Strict Prefix Partition:** `StvnFencedStringInspection` governs `"""[` and `"""->` tokens (including unclosed opening `[` and inline `"""[TAG]...` missing mandatory `\n`); untagged `"""..."""` tokens route to `StvnExternalAnnotator` (`E025 / FENCE_DELIMITER_MISMATCH`) with zero duplicate highlights.
- **Rule `STR-04` Fenced & Simple String Conversions:** `ConvertToFencedStringIntentionAction` (`Priority.HIGH`) selects a collision-safe tag via `chooseSafeFenceTag`, emits mandatory `\n` after `"""[TAG]`, indents body lines per effective `#preserveIndent`, and places `[TAG]"""` inline when the inner payload lacks a trailing `\n` (or on the next line when empty or `\n`-terminated). Single-line untagged `"""inline..."""` tokens additionally offer `ConvertToSimpleStringIntentionAction` (`Convert to simple string '"inline..."'`, `Priority.NORMAL`).

### 4.15 Constraint-Verified `:String` Payload Clamping & Exemplar Synthesis (`StvnStringConstraintUtil`)
- **Direction-Ordered Bound & Length Clamping (`E012`):** `StvnPayloadValueQuickFixProvider` computes Unicode code-point `#maxSize` truncations, `#minSize` paddings, inclusive `#minIncl`/`#maxIncl` bounds, and printable ASCII `#minExcl` lexical successors (`"B0"` -> `"B1"`) / `#maxExcl` lexical predecessors (`"ZZ99"` -> `"ZZ98"`), ordered by violation direction and verified 100% against `StringConstraintProfile` (including compiled `#regex`).
- **Verified `:String` Exemplar Generation (`E012`, `E013`, `E028` & Hover):** `StvnExemplarValueGenerator` synthesizes inhabitants across bound neighbors, Prelude presets, and anchored regex patterns via `StvnStringConstraintUtil`, returning `null` when uninhabited so invalid `"example"` strings are never offered or displayed.

### 4.16 Metadata Ordering Quick-Fix Anchor Coverage (`StvnMetadataOrderInspection`)
- **Visible `E008` Span Invariance:** Preserves `ProblemHighlightType.GENERIC_ERROR` (`E008 / ERR_FACET_ORDER_VIOLATION`) strictly on the 2nd+ out-of-order `MetadataEntry` and `MetaElement` nodes for 1:1 `0`-vs-`26` highlight parity.
- **Silent `isOnTheFly` `INFORMATION` Anchors & `IntentionPreviewInfo.DIFF`:** Whenever a `MetadataMap` (without illegal facets) or `:meta` `MetaBlock` contains at least one `E008` order violation and `isOnTheFly` is `true`, attaches `ProblemHighlightType.INFORMATION` descriptors sharing a single `QuickFix` instance on the owning declaration target (`TypeDefTarget`, `ConstantDefinition` `ValueKeyword`, `TypeEntry` `KW_TYPE`, or `MetaEntry` `KW_META`), `{` (`LBRACE`), `}` (`RBRACE`), and all unflagged entries (including index `0`), resolving target blocks cleanly in `IntentionPreviewInfo.DIFF`.

### 4.17 `StvnVariantStyleInspection` & Full-Spine Pure-IR Sum-Tag Redundancy Soundness (`PW006`)
- **Target Locus:** Explicit `:Option`, `:Either`, and `:Union` variant tags (`ExplicitOptionValue`, `ExplicitEitherValue`, `ExplicitUnionValue`) and `:Boolean` literals (`BooleanValue`) across primary `:body` slots and primary `:defs` `ConstantDefinition` RHS values (excluding synthetic Prelude files and secondary duplicate root sections).
- **Full-Spine Batched Pure-IR Equivalence (`StvnSumTagRedundancyEngine` & `StvnSumTagRedundancyPsiBridge`):** Evaluates candidate tag removals over the complete enclosing slot `Value` spine $E_{\text{slot}}$ governed by $S_{\text{root}}$ only when $E_{\text{slot}}$ has `0` compilation errors, `0` `StvnErrorValue` nodes, and a non-poisoned schema. Groups candidate probes across the file by `(StvnSchema, constantBindingSignature)` into a single cached in-memory `:Tuple(:Seq(:Tuple(<S_g>)) ...)` `StvnCompiler.compile` invocation per modification stamp (`< 5 ms` on `sum_type_torture.stvn`).
- **Inner-to-Outer Composable Multi-Tag Invariant:** Selects tag index $i$ (iterating innermost to outermost, skipping non-inferable `#Left`/`#L` and direct control-keyword string or enum collisions such as `#Right "#Left"` and `#2 #1` on `:Enum`) strictly when singleton `{i}`, joint `R U {i}`, all ascending prefixes, and all descending suffixes compile with `0` errors to $V_{\text{cand}}$ satisfying `V_cand.equals(V_orig)`. Irreducible chains (`#Some #None`, `#Right #None`, `#Right #Left "..."`) emit `0` warnings; over-specified chains (`#Some #Right #None` -> `[#Right]`, `#Some #Right #Some #Left "..."` -> `[1st #Some, 2nd #Some]`) flag only a batch-composable subset aligned 1:1 with canonical `AstPrettyPrinter` retention.
- **Live `applyFix`, `BatchQuickFix` & `IntentionPreviewInfo.DIFF` Guard:** `StripVariantTagQuickFix` implements `BatchQuickFix` (applying descriptors innermost-to-outermost in descending offset order so outer `parent.replace(inner)` calls never detach inner tags), re-verifies live Pure-IR equivalence on both physical buffers and non-physical `IntentionPreviewInfo.DIFF` preview copies before mutating PSI, and hoists any interior `//` `PsiComment` nodes between a stripped tag and its child `Value`. `StvnVopProjectionService` and `StvnCoreDelegationPostFormatProcessor` additionally enforce post-print `isDocumentsPayloadAndConstantsEquivalent` parity before mutating buffers or exporting files.

### 4.18 Simple String Multi-Escape Recovery, Corrupt Literal Hover & 26-Inspection Cascade Suppression (§ 5.3.1)
- **1:1 `LayeredLexer` & `StvnExternalAnnotator` Span Parity (`TB-01`):** `StvnSimpleStringHighlightingLexer` and `StvnExternalAnnotator` pin every invalid escape inside `LITERAL_STRING_SIMPLE` to identical disjoint `[startOffset, endOffset)` spans (`[i, i + 2)` on illegal `\x`, and `[i, j)` up to `4` post-`\u` characters halting strictly before `\`, `"`, `\r`, `\n`, `\t`, and token end) while guaranteeing strict `tokenEnd > tokenStart` lexer progress.
- **Fail-Closed Corrupt String Hover & Decoder Discipline (`TB-02`):** `StvnPsiLiteralEvaluator.decodeStringLiteral` rejects raw control characters (`< 0x20`), unescaped inner quotes, and non-ASCII-hex `\u` digits, returning `Optional.empty()`. Callers (`StvnDocumentationProvider`, `StvnStringCapacityInspection`, `StvnPayloadValueQuickFixProvider`, `StvnExemplarValueGenerator`, `StvnConvertEscapedStringToFencedIntention`) never strip outer quotes from undecodable `Optional.empty()` strings to claim capacity or regex compliance. When a scalar `Value` (`:String` or `:DateTime` in `:body` or `:defs` `ConstantDefinition` RHS, or a `:body` `#CONST` reference to a corrupt constant) fails decoding or contains sub-token `DiagnosticSeverity.ERROR` diagnostics within `[range.getStartOffset(), range.getEndOffset())`, `StvnDocumentationProvider` renders red `Violated` / `In Violation` status with all active-file error messages in source order while preserving `Suspended (Ungrounded Schema)` and `Suspended (Degraded Schema)` precedence on lexically valid `:body` literals. Valid escaped `:DateTime` literals decode via `decodeStringLiteral` prior to `#minIncl`/`#maxExcl` instant bound checks.
- **26-Local-Inspection Cascade Suppression (`TB-03`):** `StvnStemMismatchInspection` and `StvnMetadataFacetInspection` decode `:meta` `#name` and `#domain` literals via `StvnPsiLiteralEvaluator.decodeStringLiteral(..., true)` and bail out on `Optional.empty()`, `StvnExternalAnnotator` skips its `:DateTime` recovery loop when a simple string is lexically corrupt or already carries a contained `ERROR`, and `StvnTypeReference.resolveIncludeFile` fails closed on undecodable `:include` paths, maintaining 1:1 `0`-vs-`26` local-inspection parity with `stvnadore-core`.

### 4.19 Gauntlet v9 Batch 2 — Contiguous Bound Pinning, Mismatched Fence Recovery & Tripartite `:DateTime` Suspension Parity (`PB-01` .. `PB-04`)
- **Surgical `E009` Bound-Pair Coordinate Pinning & `TC-BIJECT-07` Overlap (`PB-01`):** `StvnDiscreteIntervalInspection` mirrors `StvnDefsLowerer.resolveBoundPairSpan` (`STVN_LANGUAGE_SPEC.md` § 7.3), pinning contiguous local bound pairs (`|minIdx - maxIdx| == 1`) to `TextRange(earlier.startOffset - mapStart, later.endOffset - mapStart)` on `metaMap` and non-adjacent local pairs (`|minIdx - maxIdx| > 1`) strictly to `later` (`MetadataEntry`) with a non-empty `ProblemHighlightType.INFORMATION` quick-fix anchor on `earlier`. `StvnExternalAnnotator` attaches `SwapInvertedBoundsQuickFix(..., cardinalityBounds = true)` on `#minSize > #maxSize` (`E009`) with a deferred `HighlightSeverity.INFORMATION` anchor on non-adjacent earlier `#minSize`/`#maxSize` entries. `StvnRedundantFacetOverrideInspection.hasOverlappingFatalDiagnostic` suppresses co-located `W003` on adjacent `[#minIncl .. #maxExcl)` spans while preserving `W003` on un-pinned earlier bounds when non-adjacent across both cached-IR and pure-PSI fallback paths.
- **Stateless Depth-Aware JFlex Mismatched Fence Recovery (`PB-02`):** `_StvnLexer` (`stvn.flex`) remains strictly single-state (`<YYINITIAL>`) for incremental re-lexing safety while `findDepthZeroCandidateCloseEnd` scans `zzBuffer[zzMarkedPos .. zzEndRead)` using a method-local `ArrayDeque<String>` to balance nested well-formed `"""[INNER]\n...[INNER]"""` blocks and match depth-0 candidate closing delimiters via negated bracket class `~[\r\n\[\]]*` before `]"""`, emitting `StvnTypes.MALFORMED_FENCE` to preserve sibling `:body` slot arity. `StvnFencedStringInspection` gates `BalanceOpeningTagQuickFix` on `VALID_TAG_PATTERN` and shares `LocalQuickFixAndIntentionActionOnPsiElement` balance quick-fixes with `StvnExternalAnnotator` in `Pass A`.
- **Tripartite `:DateTime` Schema Bound Mode Parity & `:body` Suspension (`PB-03`):** `StvnTypeResolver.FATAL_SCHEMA_DIAGNOSTIC_CODES` includes `E019`–`E022` and all `:DateTime` bound mode/format symbolic IDs while preserving non-poisoning `ERR_FACET_ORDER_VIOLATION` (`STVN_LANGUAGE_SPEC.md` § 7.3.5 Item 4). `StvnExternalAnnotator` and `StvnDocumentationProvider` guard `:body` `:DateTime` validation and hover rendering via `StvnTypeResolver.isDegradedOrUngroundedSchema` and `hasDegradedEnclosingValueAncestor`, suspending `:body` validation across nominal, adorned, inline, and container-enclosed poisoned `:DateTime` schemas.

### 4.20 Gauntlet v9 Batch 3 — Constructor Homology, Multi-Element `:use` PSI Governance & Stage 7 Degraded Hover Parity (`PB3-01` .. `PB3-04`)
- **Zero-Cascade Schema Constructor Homology (`PB3-01` / `EC-01`):** `stvn.bnf` aligns all 7 parenthesized schema constructors (`:Seq`, `:Set`, `:Map`, `:Tuple`, `:Option`, `:Either`, `:Union`) to `ctor_schema_arg*` (guarded by `!directive_or_section_boundary` so unclosed constructors halt before `:meta`, `:defs`, `:type`, `:body`, `:include`, `:package`, and `:use`), `:Enum[...]` to `value_keyword*`, and `tuple_literal` to `value*`. `StvnExternalAnnotator` routes authoritative `[ctor.start, ctor.end)` `E010 / MALFORMED_SCHEMA` and `E002 / STVN_SYNTAX_ERROR` diagnostics directly without legacy Pass 1 range expansion.
- **Multi-Element `:use [ use_element+ ]` PSI, Completion & Formatter Parity (`PB3-02` / `EC-02`):** `stvn.bnf` defines `use_stmt ::= KW_USE LBRACK use_element+ RBRACK {pin=1}` and `use_option ::= KW_STRIP standalone_facet_illegal_arg? {pin=1}` (symmetrically with `include_option`). All reference resolvers (`StvnTypeReference`, `StvnConstantReference`), hover providers, inspections (`StvnFlatDocumentIncludeInspection`, `StvnMetadataFacetInspection`, `StvnStringCapacityInspection`, `StvnUnresolvedTypeInspection`), Namespace Browser collectors, quick-fixes (`StvnImportUseQuickFix`), `StvnPackageCompletionContributor` (`:package` path, `{ #strip }`, and exported `:Old` / `#OLD` LHS completion on 1st, 2nd, ... `UseElement`), and pure-PSI formatters (`StvnBlock`, `StvnFormattingModelBuilder` across Layout 1 and Layout 2 for Golden Quartet `#19`) evaluate `UseElement` entries in left-to-right declaration order with per-element fault isolation.
- **Stage 7 Degraded Hover Suppression, Coordinate-Overlap `Status: Invalid` & Inductive Constituent Traits (`PB3-03` / `EC-03`):** `StvnExemplarValueGenerator` and `StvnDocumentationProvider` suppress `Exemplar Value` across both Case A ungrounded and Case B grounded `PoisonedSchema` definitions (preserving guarded recursive `:TreeNode` back-edges) while synthesizing `minSize` compliant inhabitants on valid `:Seq`, `:Set`, and `:Map` collections. `buildEffectiveFacetsTable` marks facets `Invalid` via active-file `ERROR` coordinate overlap, packaged FQNI matching, and pure-PSI bound inversion fallback, and evaluates inductive constituent `#equatable` / `#comparable` traits across `:Set`, `:Seq`, `:Tuple`, `:Option`, `:Either`, `:Union`, and `:Map` (`invertible ? keyEq && valEq : keyEq`) when `irSchema == null`.

---

## 5. Benchmarking & Quality Assurance Gates

### 5.1 Compilation & Verification Standards
- **Compiler Compliance:** Java 21 LTS (`<release>21</release>`), `-Werror`, `-Xlint:all`.
- **Platform Compatibility:** IntelliJ Platform SDK 2025.3 (`sinceBuild = "253.0"`).
- **Javadoc Validation:** `<doclint>all</doclint>`, `<failOnError>true</failOnError>`.

### 5.2 Performance & Responsiveness Invariants
- **Typing Latency:** Highlight passes on keystrokes must complete in $< 50\text{ ms}$ for documents up to 5,000 lines.
- **Completion Response:** In-flight completion results must return within $< 30\text{ ms}$.
- **Memory Footprint:** Cached AST and diagnostic values must use soft references (`CachedValuesManager`) to avoid memory leaks during long-running IDE sessions.

### 5.3 Sandbox Classpath Synchronization & Configuration Cache Invariants (`BS-01` .. `BS-05`)
- **Zero-`ZipFileSystem` Streaming Replacement (`BS-01`):** `SyncSiblingCoreTask` never invokes `FileSystems.newFileSystem(URI.create("jar:..."))`, preventing JVM-wide static `ZipFileSystemProvider` map leaks (`FileSystemAlreadyExistsException`) and `zipfstmp*.tmp` pollution inside long-lived Gradle daemons on Windows. Updates stream existing `META-INF/` entries and sibling `../ij_stvnadore_core/target/classes` files through `java.util.zip.ZipFile` and `ZipOutputStream` to a sibling `<jar>.sync-<uuid>.tmp` file, close all streams, replace the target JAR via `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` (with `REPLACE_EXISTING` fallback), and delete `<jar>.sync-<uuid>.tmp` inside `finally`.
- **Byte-Exact Content-Diff Pre-Check (`BS-02`):** Before opening any output stream, `SyncSiblingCoreTask` compares every file in `../ij_stvnadore_core/target/classes` byte-for-byte (`InputStream.readNBytes` + `Arrays.equals`) against `ZipFile(coreJar)` read-only. When `0` entries are missing or differ, the task performs zero disk writes, allowing `buildPlugin`, `check`, and `test` to succeed while `runIde` holds a shared read handle on `plugins/`.
- **Main vs. Test Sandbox Decoupling & Sequential Ordering (`BS-03`):** `:syncSiblingCoreToSandbox` scopes exclusively to `plugins/` (`prepareSandbox` -> `syncSiblingCoreToSandbox` -> `buildPlugin` / `runIde`), while `:syncSiblingCoreToTestSandbox` scopes exclusively to `plugins-test/` (`prepareTestSandbox` -> `syncSiblingCoreToTestSandbox` -> `prepareTest` / `test`), pruning `system/`, `system-test/`, `config*`, and `log*` subtrees.
- **Actionable Windows Lock Diagnostics (`BS-04`):** When `>= 1` entries differ and `Files.move` throws `AccessDeniedException` or `FileSystemException` on Windows, `SyncSiblingCoreTask` deletes the sibling `.tmp` file cleanly and throws an STE `GradleException` naming the locked JAR path and instructing the developer to close `runIde` and re-run the task.
