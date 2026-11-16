package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.settings.StvnSettings;

import java.util.List;

/**
 * End-to-end platform integration test suite for Gauntlet V8 Batch 2
 * ({@code B2-01} through {@code B2-06}):
 * <ul>
 *   <li>{@code B2-01}: {@link StvnStringCardinalityInspection} 1:1 Core {@code E008} parity, concrete {@code <N>}
 *       interpolation, {@code N == 1} deduplication, {@code <= 0} {@code #maxSize 0} prohibition safeguard,
 *       and canonical 5-tier insertion.</li>
 *   <li>{@code B2-02}: {@link StvnStringCapacityInspection} derived nominal alias suppression ({@code E040} guard),
 *       {@code #CONST} capacity resolution, and exact {@code RBRACE} 5-tier facet insertion.</li>
 *   <li>{@code B2-03}: {@code E013 / INVALID_REGEX_PATTERN} {@code Remove invalid '#regex' facet} quick-fix,
 *       capacity intention bleed-through isolation, comment-safe sole-entry {@code {}} excision, and {@code #CONST} regex support.</li>
 *   <li>{@code B2-04}: {@code Rule STR-04} untagged &amp; malformed fence quick-fixes ({@link ConvertToFencedStringIntentionAction},
 *       {@link ConvertToSimpleStringIntentionAction}, and {@link StvnFencedStringInspection}).</li>
 *   <li>{@code B2-05}: Constraint-verified {@code :String} bound clamping, Unicode code-point pad/truncate,
 *       and verified exemplar synthesis across quick-fixes and hover cards.</li>
 *   <li>{@code B2-06}: Four-locus {@code :TimeEpoch} suspicious magnitude squiggly &harr; hover parity,
 *       STE {@code "1 digit"} singular grammar, and {@code isOnTheFly} {@code INFORMATION} RHS quick-fix guard.</li>
 * </ul>
 */
@NullMarked
public final class StvnGauntletV8Batch2StringAndEpochDxTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider docProvider = new StvnDocumentationProvider();

    private static InspectionProfileEntry[] all26Inspections() {
        return new InspectionProfileEntry[]{
            new StvnVariantStyleInspection(),
            new StvnBooleanValidityInspection(),
            new StvnMapStructuralInspection(),
            new StvnDegradedSchemaInspection(),
            new StvnDegenerateCompositeInspection(),
            new StvnEnumSubsetInspection(),
            new StvnFencedStringInspection(),
            new StvnLhsReservedTypeInspection(),
            new StvnConstantRangeInspection(),
            new StvnFlatDocumentIncludeInspection(),
            new StvnNestedPackageInspection(),
            new StvnTrailingSlashInspection(),
            new StvnStringCapacityInspection(),
            new StvnExcessiveExactFloatPrecisionInspection(),
            new StvnMetadataFacetInspection(),
            new StvnStringCardinalityInspection(),
            new StvnDiscreteIntervalInspection(),
            new StvnTemporalModeInspection(),
            new StvnMetadataOrderInspection(),
            new StvnRedundantFacetOverrideInspection(),
            new StvnUnresolvedTypeInspection(),
            new StvnTabCharacterInspection(),
            new StvnMissingMetaInspection(),
            new StvnStemMismatchInspection(),
            new StvnSuspiciousEpochMagnitudeInspection(),
            new StvnFloatPrecisionLossInspection()
        };
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;
        StvnSettings.getInstance(getProject()).getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_24;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;
        } finally {
            super.tearDown();
        }
    }

    private void enableAllInspections() {
        myFixture.enableInspections(all26Inspections());
    }

    private void assertCompilesWithZeroErrors(String text, String fileName) {
        var res = StvnCompiler.compileToResult(text, fileName, StvnParserConfig.DEFAULT);
        List<StvnDiagnostic> errors = res.diagnostics().stream()
            .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
            .toList();
        assertTrue("Expected 0 Core compilation errors in " + fileName + ", got: " + errors + "\nSource:\n" + text,
            errors.isEmpty());
    }

    private String getHoverDocAtOffset(int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = docProvider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Doc target at offset " + offset + " must not be null", docElem);
        var doc = docProvider.generateDoc(docElem, elem);
        assertNotNull("Generated HTML doc at offset " + offset + " must not be null", doc);
        return doc;
    }

    private static String stripDiagnosticSuffix(String description) {
        int bracket = description.lastIndexOf(" [stvn-");
        return bracket >= 0 ? description.substring(0, bracket) : description;
    }

    /**
     * B2-01: Verifies 0-vs-26 exact Core parity on #size on :String, concrete <N> interpolation,
     * Priority.HIGH (1..N) ahead of Priority.NORMAL (N..N), N == 1 deduplication, Safeguard 1
     * (never generate illegal #maxSize 0 on <= 0), derived nominal alias removal, and canonical 5-tier placement.
     */
    public void testB201StringCardinalityConcreteInterpolationOrderingDeduplicationAndZeroGuard() {
        String paritySource = """
            {
              :meta {
                #name "b201_parity"
                #kind #DEFS
              }
              :defs {
                :TrapStringBitSize { #size 16 } :String
              }
            }
            """;

        // 0-inspection Core baseline
        myFixture.configureByText("b201_parity.stvn_d", paritySource);
        List<HighlightInfo> coreErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected 1 Core E008 error when inspections disabled", 1, coreErrors.size());
        HighlightInfo coreErr = coreErrors.get(0);
        assertTrue(coreErr.getDescription().contains("[stvn-core:E008-ERR_STRING_CARDINALITY_PROHIBITED]"));

        // 26-inspection governed parity
        enableAllInspections();
        myFixture.configureByText("b201_parity.stvn_d", paritySource);
        List<HighlightInfo> pluginErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected strictly 1 governed E008 error with 0 duplicates: " + pluginErrors, 1, pluginErrors.size());
        HighlightInfo pluginErr = pluginErrors.get(0);
        assertTrue(pluginErr.getDescription().contains("[stvn-plugin:E008-ERR_STRING_CARDINALITY_PROHIBITED]"));
        assertEquals("Exact startOffset parity", coreErr.getStartOffset(), pluginErr.getStartOffset());
        assertEquals("Exact endOffset parity", coreErr.getEndOffset(), pluginErr.getEndOffset());
        assertEquals("Exact STE prose parity",
            stripDiagnosticSuffix(coreErr.getDescription()),
            stripDiagnosticSuffix(pluginErr.getDescription()));

        // Ordering, concrete interpolation, and IntentionPreviewInfo (Safeguard 4)
        myFixture.getEditor().getCaretModel().moveToOffset(paritySource.indexOf("#size 16"));
        List<IntentionAction> allIntentions = myFixture.getAvailableIntentions();
        List<String> labels = allIntentions.stream().map(IntentionAction::getText).toList();
        String expectedPrimary = "Convert '#size 16' to '#minSize 1 #maxSize 16'";
        String expectedFixed = "Convert '#size 16' to '#minSize 16 #maxSize 16'";
        assertTrue("Expected primary 1..16 fix in: " + labels, labels.contains(expectedPrimary));
        assertTrue("Expected secondary 16..16 fix in: " + labels, labels.contains(expectedFixed));
        assertTrue("Primary 1..16 must rank ahead of fixed 16..16: " + labels,
            labels.indexOf(expectedPrimary) < labels.indexOf(expectedFixed));
        assertFalse("Placeholder '<N>' must never appear: " + labels,
            labels.stream().anyMatch(l -> l.contains("<N>")));

        IntentionAction primaryAction = myFixture.findSingleIntention(expectedPrimary);
        String previewText = myFixture.getIntentionPreviewText(primaryAction);
        assertNotNull("IntentionPreviewInfo must produce non-null diff text", previewText);
        assertTrue("Preview must show converted bounds", previewText.contains("#minSize 1 #maxSize 16"));

        // Canonical 5-tier placement before #equatable and #regex
        String multiTierSource = """
            {
              :meta {
                #name "b201_tier"
                #kind #DEFS
              }
              :defs {
                :TieredStr { #equatable #TRUE #size 16 #regex "^[a-z]+$" } :String
              }
            }
            """;
        myFixture.configureByText("b201_tier.stvn_d", multiTierSource);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(multiTierSource.indexOf("#size 16"));
        myFixture.launchAction(myFixture.findSingleIntention("Convert '#size 16' to '#minSize 16 #maxSize 16'"));
        String tieredHealed = myFixture.getFile().getText();
        assertTrue("Expected canonical 5-tier facet order, got:\n" + tieredHealed,
            tieredHealed.contains("{ #minSize 16 #maxSize 16 #equatable #TRUE #regex \"^[a-z]+$\" } :String"));
        assertCompilesWithZeroErrors(tieredHealed, "b201_tier.stvn_d");
        assertTrue("Formatter must parse healed output cleanly",
            StvnSourceFormatter.tryFormat(tieredHealed).isPresent());

        // N == 1 deduplication, Safeguard 1 (#size 0 / #size -4 / #size #ZERO_LEN), #CONST interpolation, derived alias
        String edgeMatrixSource = """
            {
              :meta {
                #name "b201_edges"
                #kind #DEFS
              }
              :defs {
                #MAX_LEN :Int 32
                #ZERO_LEN :Int 0
                :ParentStr { #maxSize 64 } :String
                :SingleChar { #size 1 } :String
                :ZeroSize { #size 0 } :String
                :NegSize { #size -4 } :String
                :ZeroConstSize { #size #ZERO_LEN } :String
                :PosConstSize { #size #MAX_LEN } :String
                :DerivedSize { #size 16 } :ParentStr
              }
            }
            """;
        myFixture.configureByText("b201_edges.stvn_d", edgeMatrixSource);
        myFixture.doHighlighting();

        // N == 1 deduplication
        myFixture.getEditor().getCaretModel().moveToOffset(edgeMatrixSource.indexOf("#size 1"));
        var singleFixes = myFixture.filterAvailableIntentions("Convert '#size 1' to '#minSize 1 #maxSize 1'");
        assertEquals("N == 1 must deduplicate to strictly 1 conversion fix", 1, singleFixes.size());

        // Safeguard 1: #size 0 -> ONLY Remove prohibited facet, NEVER #maxSize 0
        myFixture.getEditor().getCaretModel().moveToOffset(edgeMatrixSource.indexOf("#size 0"));
        List<String> zeroLabels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("Expected 'Remove prohibited facet' on #size 0, got: " + zeroLabels,
            zeroLabels.stream().anyMatch(l -> l.contains("Remove prohibited facet '#size 0'")));
        assertFalse("Safeguard 1: must NEVER offer illegal '#maxSize 0': " + zeroLabels,
            zeroLabels.stream().anyMatch(l -> l.contains("#maxSize 0")));

        // Safeguard 1: #size -4 -> ONLY Remove prohibited facet
        myFixture.getEditor().getCaretModel().moveToOffset(edgeMatrixSource.indexOf("#size -4"));
        List<String> negLabels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("Expected 'Remove prohibited facet' on #size -4",
            negLabels.stream().anyMatch(l -> l.contains("Remove prohibited facet '#size -4'")));
        assertFalse("Must not offer Convert on negative size: " + negLabels,
            negLabels.stream().anyMatch(l -> l.startsWith("Convert '#size -4'")));

        // Safeguard 1: #size #ZERO_LEN -> ONLY Remove prohibited facet, NEVER #maxSize #ZERO_LEN
        myFixture.getEditor().getCaretModel().moveToOffset(edgeMatrixSource.indexOf("#size #ZERO_LEN"));
        List<String> zeroConstLabels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("Expected 'Remove prohibited facet' on #size #ZERO_LEN, got: " + zeroConstLabels,
            zeroConstLabels.stream().anyMatch(l -> l.contains("Remove prohibited facet '#size #ZERO_LEN'")));
        assertFalse("Safeguard 1: must NEVER offer '#maxSize #ZERO_LEN': " + zeroConstLabels,
            zeroConstLabels.stream().anyMatch(l -> l.contains("#maxSize #ZERO_LEN")));

        // Positive #CONST interpolation
        myFixture.getEditor().getCaretModel().moveToOffset(edgeMatrixSource.indexOf("#size #MAX_LEN"));
        assertEquals(1, myFixture.filterAvailableIntentions("Convert '#size #MAX_LEN' to '#minSize 1 #maxSize #MAX_LEN'").size());
        assertEquals(1, myFixture.filterAvailableIntentions("Convert '#size #MAX_LEN' to '#minSize #MAX_LEN #maxSize #MAX_LEN'").size());

        // Derived nominal alias -> Remove prohibited facet (prevents E040)
        myFixture.getEditor().getCaretModel().moveToOffset(edgeMatrixSource.indexOf("#size 16"));
        List<String> derivedLabels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("Derived alias must offer Remove prohibited facet",
            derivedLabels.stream().anyMatch(l -> l.contains("Remove prohibited facet '#size 16'")));
        assertFalse("Derived alias must never offer #minSize/#maxSize conversion",
            derivedLabels.stream().anyMatch(l -> l.startsWith("Convert '#size 16'")));
    }

    /**
     * B2-02: Verifies derived nominal aliases suppress false-positive capacity warnings and E040-inducing fixes,
     * #CONST capacity arguments resolve cleanly, and Safeguard 2 exact RBRACE offset tracking inserts #maxSize
     * at canonical 5-tier rank even when fenced [REGEX] and interior // comments contain '}'.
     */
    public void testB202NominalCapacitySuppressesDerivedAliasesAndInsertsCanonical5TierOrder() {
        enableAllInspections();
        String derivedSource = """
            {
              :meta {
                #name "b202_derived"
                #kind #DEFS
              }
              :defs {
                #CAP :Int 64
                :ConstBounded { #maxSize #CAP } :String
                :BoundedCallsign { #minSize 4 #maxSize 8 #minIncl "AA00" #maxExcl "ZZ99" #regex "^[A-Z]{2}[0-9]{2,6}$" } :String
                :CleanDerivedAlias { #minIncl "BB00" } :BoundedCallsign
                :TrapOverrideMinSize { #minSize 6 } :BoundedCallsign
                :TrapOverrideMaxSize { #maxSize 10 } :BoundedCallsign
              }
            }
            """;
        myFixture.configureByText("b202_derived.stvn_d", derivedSource);
        List<HighlightInfo> highlights = myFixture.doHighlighting();

        List<HighlightInfo> capacityWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING || h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("capacity"))
            .toList();
        assertTrue("Derived nominal aliases and #CONST-bounded types must emit 0 capacity warnings, got: " + capacityWarnings,
            capacityWarnings.isEmpty());

        List<HighlightInfo> e040Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E040"))
            .toList();
        assertEquals("Expected E040 on both illegal Tier-1 derived overrides", 2, e040Errors.size());

        myFixture.getEditor().getCaretModel().moveToOffset(derivedSource.indexOf(":TrapOverrideMinSize"));
        assertTrue("Must offer 0 'Set nominal capacity' actions on derived alias",
            myFixture.filterAvailableIntentions("Set nominal capacity").isEmpty());

        // Safeguard 2: Exact RBRACE tracking with '{2,10}' inside fenced [REGEX] and '}' inside // comment
        String fencedBraceSource = """
            {
              :meta {
                #name "b202_rbrace"
                #kind #DEFS
              }
              :defs {
                :FencedRegexStr {
                  // interior comment with closing brace }
                  #equatable #TRUE
                  #regex \"\"\"[REGEX]
                    ^[A-Z]{2,10}$[REGEX]\"\"\"
                } :String
              }
            }
            """;
        myFixture.configureByText("b202_rbrace.stvn_d", fencedBraceSource);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(fencedBraceSource.indexOf(":FencedRegexStr"));
        IntentionAction setCapFix = myFixture.findSingleIntention("Set nominal capacity to 4096");
        assertNotNull(myFixture.getIntentionPreviewText(setCapFix));
        myFixture.launchAction(setCapFix);

        String healed = myFixture.getFile().getText();
        int maxSizeIdx = healed.indexOf("#maxSize 4096");
        int eqIdx = healed.indexOf("#equatable #TRUE");
        int regexIdx = healed.indexOf("#regex");
        assertTrue("Expected #maxSize 4096 inserted before #equatable and #regex:\n" + healed,
            maxSizeIdx > 0 && maxSizeIdx < eqIdx && eqIdx < regexIdx);
        assertTrue("Fenced quantifier {2,10} and comment '}' must remain intact:\n" + healed,
            healed.contains("^[A-Z]{2,10}$[REGEX]\"\"\"") && healed.contains("// interior comment with closing brace }"));
        assertCompilesWithZeroErrors(healed, "b202_rbrace.stvn_d");
    }

    /**
     * B2-03: Verifies E013 / INVALID_REGEX_PATTERN offers 'Remove invalid '#regex' facet' without
     * capacity intention bleed-through, excises comment-free sole-entry {} blocks cleanly (0 E003),
     * preserves interior // comments, and supports #CONST regex facets.
     */
    public void testB203InvalidRegexQuickFixIsolationSoleBlockExcisionAndConstRegexSafety() {
        enableAllInspections();
        String soleBrokenRegex = """
            {
              :meta {
                #name "b203_sole"
                #kind #DEFS
              }
              :defs {
                :TrapInvalidRegex { #regex "[unclosed_char_class" } :String
              }
            }
            """;
        myFixture.configureByText("b203_sole.stvn_d", soleBrokenRegex);
        myFixture.doHighlighting();

        myFixture.getEditor().getCaretModel().moveToOffset(soleBrokenRegex.indexOf("[unclosed_char_class"));
        List<String> caretIntentions = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();
        assertTrue("Expected 'Remove invalid '#regex' facet', got: " + caretIntentions,
            caretIntentions.contains("Remove invalid '#regex' facet"));
        assertFalse("Capacity quick-fixes must NOT bleed onto MetadataMap facets: " + caretIntentions,
            caretIntentions.stream().anyMatch(t -> t.startsWith("Set nominal capacity")));

        IntentionAction removeRegex = myFixture.findSingleIntention("Remove invalid '#regex' facet");
        String preview = myFixture.getIntentionPreviewText(removeRegex);
        assertNotNull(preview);
        assertTrue("Preview must excise empty {} block", preview.contains(":TrapInvalidRegex :String"));

        myFixture.launchAction(removeRegex);
        String healedSole = myFixture.getFile().getText();
        assertTrue("Expected clean ':TrapInvalidRegex :String' with 0 E003, got:\n" + healedSole,
            healedSole.contains(":TrapInvalidRegex :String"));
        assertCompilesWithZeroErrors(healedSole, "b203_sole.stvn_d");

        // Mixed metadata block + comment-bearing block + #CONST regex
        String mixedAndConstRegex = """
            {
              :meta {
                #name "b203_mixed"
                #kind #DEFS
              }
              :defs {
                #BROKEN_PAT { #maxSize 64 } :String "[unclosed"
                :MixedInvalid { #maxSize 64 #regex "[unclosed" } :String
                :CommentedInvalid {
                  // preserve comment
                  #regex "[unclosed"
                } :String
                :ConstInvalid { #maxSize 64 #regex #BROKEN_PAT } :String
              }
            }
            """;
        myFixture.configureByText("b203_mixed.stvn_d", mixedAndConstRegex);
        myFixture.doHighlighting();

        myFixture.getEditor().getCaretModel().moveToOffset(mixedAndConstRegex.indexOf("#regex #BROKEN_PAT"));
        assertEquals("Expected 'Remove invalid '#regex' facet' on #CONST regex",
            1, myFixture.filterAvailableIntentions("Remove invalid '#regex' facet").size());

        myFixture.getEditor().getCaretModel().moveToOffset(mixedAndConstRegex.indexOf("// preserve comment") + 28);
        myFixture.launchAction(myFixture.findSingleIntention("Remove invalid '#regex' facet"));
        String afterCommentExcision = myFixture.getFile().getText();
        assertTrue("Interior comment inside {} must be preserved:\n" + afterCommentExcision,
            afterCommentExcision.contains("// preserve comment"));

        myFixture.getEditor().getCaretModel().moveToOffset(afterCommentExcision.indexOf("#regex \"[unclosed\""));
        myFixture.launchAction(myFixture.findSingleIntention("Remove invalid '#regex' facet"));
        assertTrue(myFixture.getFile().getText().contains(":MixedInvalid { #maxSize 64 } :String"));
    }

    /**
     * B2-04: Verifies untagged inline and multi-line triple-quoted blocks emit strictly 1 E025 highlight,
     * offer primary Rule STR-04 fenced conversion (mandatory newline after """[TAG], collision-safe tag,
     * #preserveIndent-aware indentation, inline closing tag for single-line payloads) and secondary simple
     * string conversion, and compile with 0 errors in StvnCompiler.
     */
    public void testB204MalformedInlineAndMultiLineFenceConversionsCompileCleanlyWithZeroE025() {
        enableAllInspections();
        String inlineUntagged = """
            {
              :meta {
                #name "b204_inline"
                #kind #BODY
              }
              :defs {
                :Banner { #maxSize 128 } :String
              }
              :type :Banner
              :body \"\"\"inline_untagged_triple_quote\"\"\"
            }
            """;
        myFixture.configureByText("b204_inline.stvn", inlineUntagged);
        List<HighlightInfo> e025Highlights = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Untagged triple-quoted string must emit strictly 1 E025 highlight: " + e025Highlights,
            1, e025Highlights.size());
        assertTrue(e025Highlights.get(0).getDescription().contains("[stvn-core:E025-FENCE_DELIMITER_MISMATCH]"));

        myFixture.getEditor().getCaretModel().moveToOffset(inlineUntagged.indexOf("inline_untagged_triple_quote"));
        List<String> labels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        String fencedLabel = "Convert to fenced string '\"\"\"[FENCE]...[FENCE]\"\"\"'";
        String simpleLabel = "Convert to simple string '\"inline_untagged_triple_quote\"'";
        assertTrue("Expected primary fenced conversion in: " + labels, labels.contains(fencedLabel));
        assertTrue("Expected secondary simple string conversion in: " + labels, labels.contains(simpleLabel));
        assertTrue("Fenced conversion must rank ahead of simple conversion: " + labels,
            labels.indexOf(fencedLabel) < labels.indexOf(simpleLabel));

        IntentionAction fencedAction = myFixture.findSingleIntention(fencedLabel);
        assertNotNull(myFixture.getIntentionPreviewText(fencedAction));
        myFixture.launchAction(fencedAction);

        String convertedFenced = myFixture.getFile().getText();
        assertCompilesWithZeroErrors(convertedFenced, "b204_inline.stvn");
        var compiled = StvnCompiler.compileToResult(convertedFenced, "b204_inline.stvn", StvnParserConfig.DEFAULT);
        assertTrue(compiled.document().isPresent() && compiled.document().get().payload().isPresent());
        StvnValue loweredBody = compiled.document().get().payload().get();
        if (loweredBody instanceof StvnValue.StvnString strVal) {
            assertEquals("Decoded payload must preserve exact single-line string without trailing newline",
                "inline_untagged_triple_quote",
                strVal.value());
        } else {
            fail("Expected StvnValue.StvnString, got: " + loweredBody);
        }

        // Secondary simple string conversion + [item] strict prefix partition
        String bracketUntagged = """
            {
              :meta {
                #name "b204_bracket"
                #kind #BODY
              }
              :defs {
                :Banner { #maxSize 128 } :String
              }
              :type :Banner
              :body \"\"\"inline [item]\"\"\"
            }
            """;
        myFixture.configureByText("b204_bracket.stvn", bracketUntagged);
        List<HighlightInfo> bracketErrs = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Untagged block containing interior '[item]' must emit strictly 1 E025 error: " + bracketErrs,
            1, bracketErrs.size());
        myFixture.getEditor().getCaretModel().moveToOffset(bracketUntagged.indexOf("inline [item]"));
        myFixture.launchAction(myFixture.findSingleIntention("Convert to simple string '\"inline [item]\"'"));
        assertCompilesWithZeroErrors(myFixture.getFile().getText(), "b204_bracket.stvn");

        // #preserveIndent #TRUE column-0 conversion
        String preserveSource = """
            {
              :meta {
                #name "b204_preserve"
                #kind #BODY
              }
              :defs {
                :VerbatimStr { #maxSize 128 #preserveIndent #TRUE } :String
              }
              :type :VerbatimStr
              :body \"\"\"verbatim_line\"\"\"
            }
            """;
        myFixture.configureByText("b204_preserve.stvn", preserveSource);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(preserveSource.indexOf("verbatim_line"));
        myFixture.launchAction(myFixture.findSingleIntention(fencedLabel));
        String healedPreserve = myFixture.getFile().getText();
        assertTrue("#preserveIndent #TRUE must emit column-0 body line:\n" + healedPreserve,
            healedPreserve.contains("\"\"\"[FENCE]\nverbatim_line[FENCE]\"\"\""));
        assertCompilesWithZeroErrors(healedPreserve, "b204_preserve.stvn");

        // Collision-safe tag escalation when chooseSafeFenceTag detects [FENCE]"""
        assertEquals("FENCE_1",
            org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator.chooseSafeFenceTag("body [FENCE]\"\"\" end", "FENCE"));

        // Inline tagged fence missing mandatory newline after """[FENCE]
        String missingNewlineFence = """
            {
              :meta {
                #name "b204_nl"
                #kind #BODY
              }
              :defs {
                :Banner { #maxSize 128 } :String
              }
              :type :Banner
              :body \"\"\"[FENCE]inline_tagged[FENCE]\"\"\"
            }
            """;
        myFixture.configureByText("b204_nl.stvn", missingNewlineFence);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(missingNewlineFence.indexOf("inline_tagged"));
        IntentionAction insertNl = myFixture.findSingleIntention("Insert newline after opening fence '\"\"\"[FENCE]'");
        assertNotNull(myFixture.getIntentionPreviewText(insertNl));
        myFixture.launchAction(insertNl);
        assertCompilesWithZeroErrors(myFixture.getFile().getText(), "b204_nl.stvn");
    }

    /**
     * B2-05: Verifies constraint-verified :String length pad/truncate, inclusive/exclusive UTF-8
     * lexical bound clamps ("B0" -> "B1", "ZZ99" -> "ZZ98"), E013 exemplar gating, and hover exemplar parity.
     */
    public void testB205StringPayloadBoundClampingLengthPadTruncateAndVerifiedExemplars() {
        enableAllInspections();
        String payloadMatrix = """
            {
              :meta {
                #name "b205_matrix"
                #kind #BODY
              }
              :defs {
                :BoundedCallsign { #minSize 4 #maxSize 8 #minIncl "AA00" #maxExcl "ZZ99" #regex "^[A-Z]{2}[0-9]{2,6}$" } :String
                :OpenLexicalCode { #minSize 2 #maxSize 6 #minExcl "B0" #maxIncl "M9" #regex "^[A-Z][0-9]+$" } :String
              }
              :type :Tuple( :BoundedCallsign :BoundedCallsign :OpenLexicalCode :BoundedCallsign :BoundedCallsign )
              :body (
                "AA1"
                "ZZ99"
                "B0"
                "AB_BAD"
                "AA12345678"
              )
            }
            """;
        myFixture.configureByText("b205_matrix.stvn", payloadMatrix);
        myFixture.doHighlighting();

        // Hover card on :BoundedCallsign must synthesize verified exemplar "AA00" (never invalid "example")
        String callsignDoc = getHoverDocAtOffset(payloadMatrix.indexOf(":BoundedCallsign"));
        assertTrue("Hover exemplar on :BoundedCallsign must be \"AA00\":\n" + callsignDoc,
            callsignDoc.contains("\"AA00\""));
        assertFalse("Hover must never emit regex-violating \"example\":\n" + callsignDoc,
            callsignDoc.contains("\"example\""));

        // Slot 1: "AA1" (length underflow < 4) -> Pad '"AA10"', Clamp min '"AA00"', Clamp max '"ZZ98"'
        myFixture.getEditor().getCaretModel().moveToOffset(payloadMatrix.indexOf("\"AA1\"") + 1);
        List<String> slot1 = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("Expected Pad to '\"AA10\"' in: " + slot1,
            slot1.contains("Pad string to minimum length 4 '\"AA10\"'"));
        assertTrue("Expected Clamp min '\"AA00\"' in: " + slot1,
            slot1.contains("Clamp value to minimum bound '\"AA00\"'"));
        assertTrue("Expected Clamp max '\"ZZ98\"' (never exclusive '\"ZZ99\"') in: " + slot1,
            slot1.contains("Clamp value to maximum bound '\"ZZ98\"'"));
        assertFalse("Must never offer exclusive upper bound '\"ZZ99\"': " + slot1,
            slot1.stream().anyMatch(s -> s.contains("'\"ZZ99\"'")));

        // Slot 2: "ZZ99" (violates #maxExcl "ZZ99") -> Max-first '"ZZ98"' ahead of Min '"AA00"'
        myFixture.getEditor().getCaretModel().moveToOffset(payloadMatrix.indexOf("  \"ZZ99\"") + 3);
        List<String> slot2 = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        int idxMax = slot2.indexOf("Clamp value to maximum bound '\"ZZ98\"'");
        int idxMin = slot2.indexOf("Clamp value to minimum bound '\"AA00\"'");
        assertTrue("Max clamp must rank ahead of min clamp on upper violation: " + slot2,
            idxMax >= 0 && idxMin >= 0 && idxMax < idxMin);

        // Slot 3: "B0" (violates #minExcl "B0") -> Min-first '"B1"' ahead of Max '"M9"'
        myFixture.getEditor().getCaretModel().moveToOffset(payloadMatrix.indexOf("  \"B0\"") + 3);
        List<String> slot3 = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        int idxOpenMin = slot3.indexOf("Clamp value to minimum bound '\"B1\"'");
        int idxOpenMax = slot3.indexOf("Clamp value to maximum bound '\"M9\"'");
        assertTrue("Min clamp '\"B1\"' must rank ahead of max clamp '\"M9\"' on lower violation: " + slot3,
            idxOpenMin >= 0 && idxOpenMax >= 0 && idxOpenMin < idxOpenMax);
        assertFalse("Must never offer exclusive lower bound '\"B0\"': " + slot3,
            slot3.stream().anyMatch(s -> s.contains("'\"B0\"'")));

        // Slot 4: "AB_BAD" (E013 regex mismatch) -> Verified exemplar '"AA00"'
        myFixture.getEditor().getCaretModel().moveToOffset(payloadMatrix.indexOf("\"AB_BAD\"") + 1);
        List<String> slot4 = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("E013 regex mismatch must offer verified exemplar '\"AA00\"': " + slot4,
            slot4.contains("Replace with exemplar value '\"AA00\"'"));
        assertFalse("Must never offer invalid '\"example\"': " + slot4,
            slot4.stream().anyMatch(s -> s.contains("'\"example\"'")));

        // Slot 5: "AA12345678" (length overflow > 8) -> Truncate to '"AA123456"'
        myFixture.getEditor().getCaretModel().moveToOffset(payloadMatrix.indexOf("\"AA12345678\"") + 1);
        List<String> slot5 = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("Length overflow must offer verified truncation '\"AA123456\"': " + slot5,
            slot5.contains("Truncate string to maximum length 8 '\"AA123456\"'"));

        // Apply all 5 slot fixes sequentially and assert 0 Core compilation errors!
        myFixture.launchAction(myFixture.findSingleIntention("Truncate string to maximum length 8 '\"AA123456\"'"));
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("\"AB_BAD\"") + 1);
        myFixture.launchAction(myFixture.findSingleIntention("Replace with exemplar value '\"AA00\"'"));
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("  \"B0\"") + 3);
        myFixture.launchAction(myFixture.findSingleIntention("Clamp value to minimum bound '\"B1\"'"));
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("  \"ZZ99\"") + 3);
        myFixture.launchAction(myFixture.findSingleIntention("Clamp value to maximum bound '\"ZZ98\"'"));
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("\"AA1\"") + 1);
        myFixture.launchAction(myFixture.findSingleIntention("Pad string to minimum length 4 '\"AA10\"'"));
        assertCompilesWithZeroErrors(myFixture.getFile().getText(), "b205_matrix.stvn");
    }

    /**
     * B2-06: Verifies 4-locus suspicious epoch magnitude parity (:defs ConstantDefinition LHS/RHS and
     * :body #CONST / literal), STE singular "1 digit" grammar, Safeguard 3 INFORMATION RHS quick-fix
     * registration, Prelude read-only protection, and showEpochMagnitudeWarnings setting toggle.
     */
    public void testB206SuspiciousEpochMagnitudeFourLocusParityAndSingularDigitGrammar() {
        enableAllInspections();
        String epochSource = """
            {
              :meta {
                #name "b206_epoch"
                #kind #BODY
              }
              :defs {
                :package :acme/temporal {
                  :EpochNs { #ns } :TimeEpoch
                  :EpochMs { #ms } :TimeEpoch
                  :EpochSec { #s } :TimeEpoch
                }
                :use [ :acme/temporal { #strip } ]
                :LocalSec { #s } :TimeEpoch
                #NEG_ONE_NS :EpochNs -1
                #NEG_ONE_MS :EpochMs -1
                #BEYOND_64BIT_NS :EpochNs 100000000000000000000
                #CHAIN_NS :EpochNs #NEG_ONE_NS
                #DIRECT_MS_IN_SEC :LocalSec 1704067200000
                #EPOCH_ZERO :EpochNs 0
                #HEX_VALID :EpochSec 0x6AF5_F798
              }
              :type :Tuple( :EpochNs :EpochNs :LocalSec :EpochNs :EpochSec )
              :body (
                #NEG_ONE_NS
                -1
                1704067200000
                0
                0x6AF5_F798
              )
            }
            """;
        myFixture.configureByText("b206_epoch.stvn", epochSource);
        List<HighlightInfo> pw006Warnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_SUSPICIOUS_EPOCH_MAGNITUDE"))
            .toList();

        // Expected 8 warning squigglies: 5 in :defs (#NEG_ONE_NS, #NEG_ONE_MS, #BEYOND_64BIT_NS, #CHAIN_NS, #DIRECT_MS_IN_SEC)
        // + 3 in :body (#NEG_ONE_NS, -1, 1704067200000). Zero warnings on #EPOCH_ZERO, #HEX_VALID, 0, 0x6AF5_F798.
        assertEquals("Expected 8 PW006 warnings across :defs and :body, got: " + pw006Warnings,
            8, pw006Warnings.size());

        // STE-03 singular grammar: exact " 1 digit", zero " 1 digits"
        for (HighlightInfo w : pw006Warnings) {
            assertFalse("STE-03 violation (' 1 digits') in editor warning: " + w.getDescription(),
                w.getDescription().contains(" 1 digits"));
        }
        assertTrue("Expected 'has 1 digit,' in #NEG_ONE_NS editor warning",
            pw006Warnings.stream().anyMatch(w -> w.getDescription().contains("constant '#NEG_ONE_NS' ('-1') has 1 digit,")));

        // Verify all 4 hover loci also display Suspicious Epoch Magnitude Warning with " 1 digit" (zero " 1 digits")
        int defsLhsOffset = epochSource.indexOf("#NEG_ONE_NS :EpochNs");
        int defsRhsOffset = epochSource.indexOf("-1\n    #NEG_ONE_MS");
        int bodyConstOffset = epochSource.indexOf("    #NEG_ONE_NS\n    -1") + 4;
        int bodyLitOffset = epochSource.indexOf("    -1\n    1704067200000") + 4;
        for (int locusOffset : new int[]{defsLhsOffset, defsRhsOffset, bodyConstOffset, bodyLitOffset}) {
            String doc = getHoverDocAtOffset(locusOffset);
            assertTrue("Expected Suspicious Epoch Magnitude Warning in hover at offset " + locusOffset + ":\n" + doc,
                doc.contains("Suspicious Epoch Magnitude Warning"));
            assertTrue("Expected singular 'has 1 digit' in hover HTML:\n" + doc, doc.contains("has 1 digit"));
            assertFalse("Must never render ' 1 digits' in hover HTML:\n" + doc, doc.contains(" 1 digits"));
        }

        // Prelude read-only guard: #NEG_ONE_NS (:EpochNs) must never offer Update schema scale flag on Prelude
        myFixture.getEditor().getCaretModel().moveToOffset(defsLhsOffset);
        assertTrue("Prelude :EpochNs must not offer Update schema scale flag",
            myFixture.filterAvailableIntentions("Update schema scale flag").isEmpty());

        // Safeguard 3: Direct RHS integer literal in :defs (#DIRECT_MS_IN_SEC :LocalSec 1704067200000)
        // offers both Rescale and Update schema scale flag via non-empty INFORMATION descriptor + IntentionPreviewInfo
        int rhsMsOffset = epochSource.indexOf("1704067200000\n    #EPOCH_ZERO");
        myFixture.getEditor().getCaretModel().moveToOffset(rhsMsOffset);
        IntentionAction rescaleRhs = myFixture.findSingleIntention("Rescale literal to '1704067200' (match declared '#s' scale)");
        IntentionAction updateLocalSec = myFixture.findSingleIntention("Update schema scale flag to '#ms'");
        assertNotNull(myFixture.getIntentionPreviewText(rescaleRhs));
        assertNotNull(myFixture.getIntentionPreviewText(updateLocalSec));

        // Setting toggle suppression parity: showEpochMagnitudeWarnings = false suppresses both squigglies & hover
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = false;
        myFixture.configureByText("b206_disabled.stvn", epochSource);
        List<HighlightInfo> suppressedWarnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_SUSPICIOUS_EPOCH_MAGNITUDE"))
            .toList();
        assertTrue("Expected 0 PW006 squigglies when showEpochMagnitudeWarnings = false", suppressedWarnings.isEmpty());
        String suppressedHover = getHoverDocAtOffset(defsLhsOffset);
        assertFalse("Expected hover warning banner suppressed when showEpochMagnitudeWarnings = false",
            suppressedHover.contains("Suspicious Epoch Magnitude Warning"));
    }
}
