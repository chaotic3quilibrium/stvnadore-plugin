package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.browser.StvnNamespaceBrowserPopup;
import org.stvnadore.plugin.browser.StvnNamespaceScope;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.intentions.StvnBootstrapDocumentIntentionAction;
import org.stvnadore.plugin.settings.StvnSettings;

import javax.swing.Action;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * End-to-end IntelliJ Platform regression suite for Gauntlet V11 Batch 1 plugin remediation:
 * <ul>
 *   <li>Target 1: 8-character keyword-only {@code E011} pinning, disjoint {@code #CONST} hover, and
 *       zero-shift leaf-level bound quick-fixes across Pass A and Pass B (including {@code DIFF} preview).</li>
 *   <li>Target 2: Unicode {@code [0, ∞)} {@code W003} prose parity and zero residual {@code \infty} in {@code src/main}.</li>
 *   <li>Target 3: Bare un-sigiled and sigiled near-miss boolean governance, toggle-ordered quick-fixes,
 *       {@code DIFF} previews, and {@code Pass A == 1} / {@code Pass B == 1} deconfliction.</li>
 *   <li>Target 4: {@code StvnBootstrapDocumentIntentionAction} {@code "Initialize STVN document structure"} label parity.</li>
 *   <li>Target 5: {@code STVN Namespace Dependencies} popup-scoped {@code Alt+R} shortcut and menu label across all focus targets.</li>
 * </ul>
 */
@NullMarked
public final class StvnGauntletV11Batch1PluginRemediationTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");

    private record ContractRow(
        String canonicalCode,
        String symbolicId,
        int startOffset,
        int endOffset,
        String messageSubstring
    ) {}

    @Override
    protected void tearDown() throws Exception {
        try {
            StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = true;
        } finally {
            super.tearDown();
        }
    }

    private void setUseLongFormSumTypes(boolean useLong) {
        StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = useLong;
    }

    private void enableAll27StvnInspections() {
        myFixture.enableInspections(
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
            new StvnFloatPrecisionLossInspection(),
            new StvnStringEscapeScrutinyInspection()
        );
    }

    /**
     * Target 1: Verifies 8-character keyword-only E011 pinning, clean #CONST bound hover without E011
     * pollution, and zero-shift leaf-level quick-fixes (live Document + IntentionPreviewInfo.DIFF)
     * across both Pass A (0 inspections) and Pass B (27 inspections).
     */
    public void testTarget1_DiscreteBoundKeywordOnlyPinningDisjointConstHoverAndZeroShiftQuickFixes() {
        String pumpDoc = """
            {
              :meta {
                #name "pump_bounds"
                #kind #DEFS
              }
              :defs {
                #MAX_PUMP_MA_EXCL :Int 1200
                :PumpCurrentMa { #minIncl 0 #maxIncl #MAX_PUMP_MA_EXCL } :Int
              }
            }
            """;

        // 1A. Pass A (0 inspections) vs Pass B (27 inspections): exact 8-char #maxIncl span & clean #MAX_PUMP_MA_EXCL hover
        for (boolean passB : List.of(false, true)) {
            if (passB) {
                enableAll27StvnInspections();
            }
            PsiFile file = myFixture.configureByText("pump_bounds.stvn_d", pumpDoc);
            List<HighlightInfo> errors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertEquals("Expected strictly 1 E011 error in " + (passB ? "Pass B" : "Pass A") + ": " + errors, 1, errors.size());

            int maxInclIdx = pumpDoc.indexOf("#maxIncl");
            HighlightInfo e011 = errors.get(0);
            assertEquals(maxInclIdx, e011.getStartOffset());
            assertEquals(maxInclIdx + StvnVocabulary.FACET_KW_MAX_INCL.length(), e011.getEndOffset());
            assertTrue(e011.getDescription().contains("E011-"));

            int constRefIdx = pumpDoc.lastIndexOf("#MAX_PUMP_MA_EXCL");
            int constRefEnd = constRefIdx + "#MAX_PUMP_MA_EXCL".length();
            boolean overlapsConst = errors.stream().anyMatch(
                h -> h.getStartOffset() < constRefEnd && h.getEndOffset() > constRefIdx
            );
            assertFalse("#MAX_PUMP_MA_EXCL must have zero overlapping error highlights", overlapsConst);

            var docProvider = new StvnDocumentationProvider();
            PsiElement leafAtConst = file.findElementAt(constRefIdx + 2);
            assertNotNull(leafAtConst);
            PsiElement hoverTarget = docProvider.getCustomDocumentationElement(
                myFixture.getEditor(), file, leafAtConst, constRefIdx + 2
            );
            PsiElement resolvedTarget = hoverTarget != null ? hoverTarget : leafAtConst.getParent();
            String hoverHtml = docProvider.generateDoc(resolvedTarget, leafAtConst);
            assertNotNull("Expected documentation hover on #MAX_PUMP_MA_EXCL", hoverHtml);
            assertTrue("Hover must describe #MAX_PUMP_MA_EXCL:\n" + hoverHtml, hoverHtml.contains("#MAX_PUMP_MA_EXCL"));
            assertFalse("Hover on #MAX_PUMP_MA_EXCL must not contain E011:\n" + hoverHtml, hoverHtml.contains("E011"));
        }

        // 1B. Multi-space alignment & trailing comment preservation on :OpticalGainDb in Pass A and Pass B
        String opticalTemplate = """
            {
              :meta {
                #name "optical_gain"
                #kind #DEFS
              }
              :defs {
                :OpticalGainDb {
                  #exact
                  #minIncl  -10.0      // lower optical gain floor
                  #maxIncl  35.0       // upper optical gain ceiling
                } :Float
              }
            }
            """;
        String expectedOpticalAfter = opticalTemplate.replace(
            "#maxIncl  35.0       // upper optical gain ceiling",
            "#maxExcl  35.0       // upper optical gain ceiling"
        );

        // Verify in Pass B (already enabled) and reset for Pass A check via fresh check
        for (String mode : List.of("PassB", "PassA")) {
            if ("PassA".equals(mode)) {
                myFixture.disableInspections(new StvnDiscreteIntervalInspection());
            }
            myFixture.configureByText("optical_gain.stvn_d", opticalTemplate);
            myFixture.getEditor().getCaretModel().moveToOffset(opticalTemplate.indexOf("#maxIncl") + 2);
            myFixture.doHighlighting();

            String replaceTitle = "Replace '#maxIncl' with half-open bound '#maxExcl'";
            List<IntentionAction> fixes = myFixture.filterAvailableIntentions(replaceTitle);
            assertFalse("Expected '" + replaceTitle + "' in " + mode, fixes.isEmpty());

            String preview = myFixture.getIntentionPreviewText(fixes.get(0));
            assertEquals("IntentionPreview DIFF must preserve 2-space gap and trailing comment column in " + mode,
                expectedOpticalAfter, preview);

            myFixture.launchAction(fixes.get(0));
            assertEquals("Live quick-fix must preserve 2-space gap and trailing comment column in " + mode,
                expectedOpticalAfter, myFixture.getFile().getText());

            List<HighlightInfo> postErrors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertTrue("Expected 0 errors after replacing #maxIncl with #maxExcl in " + mode + ": " + postErrors,
                postErrors.isEmpty());
        }

        // 1C. Re-enable StvnDiscreteIntervalInspection and verify N -> N+1 conversion & SwapInvertedBounds preserve alignment
        enableAll27StvnInspections();
        String intBoundsDoc = """
            {
              :meta {
                #name "int_bounds"
                #kind #DEFS
              }
              :defs {
                :ChannelIndex {
                  #minExcl  0        // exclusive floor
                  #maxIncl  41       // inclusive ceiling
                } :Int
                :InvertedWindow { #minIncl  500 #maxExcl  50 } :Int
              }
            }
            """;
        myFixture.configureByText("int_bounds.stvn_d", intBoundsDoc);
        myFixture.getEditor().getCaretModel().moveToOffset(intBoundsDoc.indexOf("#minExcl") + 2);
        myFixture.doHighlighting();

        var minConvertFixes = myFixture.filterAvailableIntentions("Convert '#minExcl <N>' to '#minIncl <N+1>'");
        assertFalse(minConvertFixes.isEmpty());
        assertTrue(myFixture.getIntentionPreviewText(minConvertFixes.get(0))
            .contains("#minIncl  1        // exclusive floor"));
        myFixture.launchAction(minConvertFixes.get(0));

        String afterMin = myFixture.getFile().getText();
        assertTrue(afterMin.contains("#minIncl  1        // exclusive floor"));

        myFixture.getEditor().getCaretModel().moveToOffset(afterMin.indexOf("#maxIncl") + 2);
        myFixture.doHighlighting();
        var maxConvertFixes = myFixture.filterAvailableIntentions("Convert '#maxIncl <N>' to '#maxExcl <N+1>'");
        assertFalse(maxConvertFixes.isEmpty());
        assertTrue(myFixture.getIntentionPreviewText(maxConvertFixes.get(0))
            .contains("#maxExcl  42       // inclusive ceiling"));
        myFixture.launchAction(maxConvertFixes.get(0));

        String afterMax = myFixture.getFile().getText();
        assertTrue(afterMax.contains("#maxExcl  42       // inclusive ceiling"));

        myFixture.getEditor().getCaretModel().moveToOffset(afterMax.indexOf("#minIncl  500") + 2);
        myFixture.doHighlighting();
        var swapFixes = myFixture.filterAvailableIntentions("Swap inverted bounds ('#minIncl 50' and '#maxExcl 500')");
        assertFalse(swapFixes.isEmpty());
        assertTrue(myFixture.getIntentionPreviewText(swapFixes.get(0))
            .contains(":InvertedWindow { #minIncl  50 #maxExcl  500 } :Int"));
        myFixture.launchAction(swapFixes.get(0));
        assertTrue(myFixture.getFile().getText().contains(":InvertedWindow { #minIncl  50 #maxExcl  500 } :Int"));
    }

    /**
     * Target 2: Verifies Unicode [0, ∞) W003 prose parity in Pass A and Pass B and zero residual \infty in src/main.
     *
     * @throws IOException if source tree walking fails
     */
    public void testTarget2_RedundantUnsignedMinZeroEmitsUnicodeInfinityAndZeroResidualLatexInMain() throws IOException {
        String unsignedDoc = """
            {
              :meta {
                #name "unsigned_min_zero"
                #kind #DEFS
              }
              :defs {
                :PortNumber { #unsigned #minIncl 0 } :Int
              }
            }
            """;

        for (boolean passB : List.of(false, true)) {
            if (passB) {
                enableAll27StvnInspections();
            }
            myFixture.configureByText("unsigned_min_zero.stvn_d", unsignedDoc);
            List<HighlightInfo> warnings = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
                .toList();
            assertEquals("Expected strictly 1 W003 warning in " + (passB ? "Pass B" : "Pass A") + ": " + warnings,
                1, warnings.size());

            HighlightInfo w003 = warnings.get(0);
            int entryIdx = unsignedDoc.indexOf("#minIncl 0");
            assertEquals(entryIdx, w003.getStartOffset());
            assertEquals(entryIdx + "#minIncl 0".length(), w003.getEndOffset());
            assertTrue("W003 must contain Unicode [0, ∞): " + w003.getDescription(),
                w003.getDescription().contains("bounds the domain to [0, \u221E)"));
            assertFalse("W003 must not contain raw \\infty: " + w003.getDescription(),
                w003.getDescription().contains("\\infty"));
            String expectedOriginTag = passB
                ? "[stvn-plugin:W003-WARN_REDUNDANT_METADATA_FACET]"
                : "[stvn-core:W003-WARN_REDUNDANT_METADATA_FACET]";
            assertTrue("Expected " + expectedOriginTag + " in " + w003.getDescription(),
                w003.getDescription().contains(expectedOriginTag));
        }

        Path mainDir = Paths.get("src", "main");
        assertTrue("Expected src/main directory: " + mainDir.toAbsolutePath(), Files.isDirectory(mainDir));
        List<String> latexHits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".java") || name.endsWith(".properties") || name.endsWith(".html")) {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    if (content.contains("\\infty")) {
                        latexHits.add(p.toString());
                    }
                }
            }
        }
        assertTrue("Expected zero residual \\infty occurrences in src/main, found: " + latexHits, latexHits.isEmpty());
    }

    /**
     * Target 3: Verifies mirrored bare_boolean_literals_all_contexts.{stvn,contract.stvn_i} across Pass A and Pass B,
     * all bare boolean value/facet contexts, toggle-ordered quick-fixes + DIFF previews, structural non-value preservation,
     * and sigiled wrong-case boolean quick-fixes + deconfliction.
     *
     * @throws Exception if fixture loading fails
     */
    public void testTarget3_BareAndSigiledBooleanAllContextsQuickFixesAndPassAPassBDeconfliction() throws Exception {
        Path fixturePath = SHARED_FIXTURES_ROOT.resolve("syntax/invalid/lexical/bare_boolean_literals_all_contexts.stvn");
        Path contractPath = SHARED_FIXTURES_ROOT.resolve("syntax/invalid/lexical/bare_boolean_literals_all_contexts.contract.stvn_i");
        assertTrue("Mirrored bare boolean fixture must exist: " + fixturePath, Files.isRegularFile(fixturePath));
        assertTrue("Mirrored bare boolean contract must exist: " + contractPath, Files.isRegularFile(contractPath));

        List<ContractRow> expectedRows = loadContractRows(contractPath);
        assertEquals("Expected 4 rows in bare_boolean_literals_all_contexts.contract.stvn_i", 4, expectedRows.size());
        String fixtureSrc = Files.readString(fixturePath, StandardCharsets.UTF_8);

        // 3A. Verify Pass A (0 inspections) == 4 and Pass B (27 inspections) == 4 with 0 stacked E008 errors
        for (boolean passB : List.of(false, true)) {
            if (passB) {
                enableAll27StvnInspections();
            }
            myFixture.configureByText("bare_boolean_literals_all_contexts.stvn", fixtureSrc);
            List<HighlightInfo> errors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertEquals("Expected exact 4 ERROR highlights in " + (passB ? "Pass B" : "Pass A") + ": " + errors,
                4, errors.size());

            String expectedSuffix = passB
                ? "[stvn-plugin:E002-ERR_BARE_BOOLEAN_PROHIBITED]"
                : "[stvn-core:E002-ERR_BARE_BOOLEAN_PROHIBITED]";
            for (ContractRow row : expectedRows) {
                HighlightInfo match = errors.stream()
                    .filter(h -> h.getStartOffset() == row.startOffset() && h.getEndOffset() == row.endOffset())
                    .findFirst()
                    .orElse(null);
                assertNotNull("Missing E002 highlight at [" + row.startOffset() + ", " + row.endOffset() + ") in "
                    + (passB ? "Pass B" : "Pass A") + ": " + errors, match);
                assertTrue(match.getDescription().contains(row.messageSubstring()));
                assertTrue(match.getDescription().contains(expectedSuffix));
            }
        }

        // 3B. Multi-context bare booleans (scalar, tuple, list, map, #Some, #Left, #1, #comparable, #BAD_INT :Int true)
        String allSlotsDoc = """
            {
              :meta {
                #name "bare_slots_matrix"
                #kind #BODY
              }
              :defs {
                :package :org/true/demo {
                  #false :Boolean #FALSE
                }
                :ComparableId { #comparable True } :Int
                #BAD_INT :Int true
                :PayloadMatrix :Tuple(
                  :Boolean
                  :Seq( :Boolean )
                  :Map( :String :Boolean )
                  :Option( :Boolean )
                  :Either( :Boolean :Int )
                  :Union( :Boolean :String )
                  :Boolean
                )
              }
              :type :PayloadMatrix
              :body (
                true
                [ false ]
                { [ "k" True ] }
                #Some FALSE
                #Left false
                #1 true
                #org/true/demo/false
              )
            }
            """;

        myFixture.configureByText("bare_slots_matrix.stvn", allSlotsDoc);
        List<HighlightInfo> slotErrorsPassB = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected exact 8 bare-boolean errors (0 false positives on :org/true/demo or #org/true/demo/false): "
            + slotErrorsPassB, 8, slotErrorsPassB.size());
        assertTrue("All 8 errors in Pass B must carry [stvn-plugin:E002-ERR_BARE_BOOLEAN_PROHIBITED]",
            slotErrorsPassB.stream().allMatch(
                h -> h.getDescription() != null && h.getDescription().contains("[stvn-plugin:E002-ERR_BARE_BOOLEAN_PROHIBITED]")
            ));

        // Verify quick-fix ordering (Long Form vs Short Form), DIFF preview, and #BAD_INT :Int true -> #TRUE -> E028
        setUseLongFormSumTypes(true);
        int badIntTrueOffset = allSlotsDoc.indexOf("true\n    :PayloadMatrix");
        myFixture.getEditor().getCaretModel().moveToOffset(badIntTrueOffset + 1);
        myFixture.doHighlighting();
        List<IntentionAction> longOrderedFixes = myFixture.filterAvailableIntentions("Replace 'true' with '");
        assertEquals(
            List.of("Replace 'true' with '#TRUE'", "Replace 'true' with '#T'"),
            longOrderedFixes.stream().map(IntentionAction::getText).toList()
        );
        assertTrue("DIFF preview on #BAD_INT :Int true must replace 'true' with '#TRUE'",
            myFixture.getIntentionPreviewText(longOrderedFixes.get(0)).contains("#BAD_INT :Int #TRUE"));

        setUseLongFormSumTypes(false);
        myFixture.configureByText("bare_slots_matrix_short.stvn", allSlotsDoc);
        myFixture.getEditor().getCaretModel().moveToOffset(badIntTrueOffset + 1);
        myFixture.doHighlighting();
        List<IntentionAction> shortOrderedFixes = myFixture.filterAvailableIntentions("Replace 'true' with '");
        assertEquals(
            List.of("Replace 'true' with '#T'", "Replace 'true' with '#TRUE'"),
            shortOrderedFixes.stream().map(IntentionAction::getText).toList()
        );

        // Apply Replace 'true' with '#TRUE' on #BAD_INT :Int true and :body (12 true) and verify semantic type-checking surfaces cleanly
        setUseLongFormSumTypes(true);
        String badConstDoc = """
            {
              :meta {
                #name "bad_const_bool"
                #kind #BODY
              }
              :defs {
                #BAD_INT :Int true
              }
              :type :Tuple( :Int :Int )
              :body ( 12 false )
            }
            """;
        myFixture.configureByText("bad_const_bool.stvn", badConstDoc);
        myFixture.getEditor().getCaretModel().moveToOffset(badConstDoc.indexOf("true") + 1);
        myFixture.doHighlighting();
        IntentionAction toTrueFix = myFixture.findSingleIntention("Replace 'true' with '#TRUE'");
        myFixture.launchAction(toTrueFix);

        String midText = myFixture.getFile().getText();
        assertTrue(midText.contains("#BAD_INT :Int #TRUE"));
        myFixture.getEditor().getCaretModel().moveToOffset(midText.indexOf("false") + 1);
        myFixture.doHighlighting();
        IntentionAction toFalseFix = myFixture.findSingleIntention("Replace 'false' with '#FALSE'");
        myFixture.launchAction(toFalseFix);
        assertTrue(myFixture.getFile().getText().contains(":body ( 12 #FALSE )"));

        List<HighlightInfo> afterHealErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertFalse("Zero ERR_BARE_BOOLEAN_PROHIBITED syntax errors must remain after healing: " + afterHealErrors,
            afterHealErrors.stream().anyMatch(
                h -> h.getDescription() != null && h.getDescription().contains("ERR_BARE_BOOLEAN_PROHIBITED")
            ));
        assertEquals("Healing Pass 1 syntax errors on non-:Boolean slots must unblock Pass 3 evaluation on both slots: "
            + afterHealErrors, 2, afterHealErrors.size());
        assertTrue("Pass 3 evaluation on #TRUE/#FALSE in :Int slots must report [stvn-core:E030-UNDEFINED_CONSTANT]: "
            + afterHealErrors, afterHealErrors.stream().allMatch(
                h -> h.getDescription() != null && h.getDescription().contains("[stvn-core:E030-UNDEFINED_CONSTANT]")
            ));

        // Verify structural non-value position (:defs { true :Int }) stays rendered by StvnExternalAnnotator in Pass B
        String structuralDoc = """
            {
              :meta {
                #name "structural_bare_bool"
                #kind #DEFS
              }
              :defs {
                true :Int
              }
            }
            """;
        myFixture.configureByText("structural_bare_bool.stvn_d", structuralDoc);
        List<HighlightInfo> structuralErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertFalse("Structural bare 'true' in :defs must not be dropped in Pass B", structuralErrors.isEmpty());
        assertTrue("Structural bare 'true' must retain Core E002-ERR_BARE_BOOLEAN_PROHIBITED: " + structuralErrors,
            structuralErrors.stream().anyMatch(
                h -> h.getDescription() != null && h.getDescription().contains("[stvn-core:E002-ERR_BARE_BOOLEAN_PROHIBITED]")
            ));

        // 3C. Sigiled near-miss booleans (#true, #false, #True, #False) across :body, #CONST, and boolean metadata facets
        String sigiledDoc = """
            {
              :meta {
                #name "sigiled_near_miss"
                #kind #BODY
              }
              :defs {
                :IndentNote { #preserveIndent #true } :String
                :RawCounter { #equatable #false } :Int
                #PUMP_ACTIVE :Boolean #False
              }
              :type :Boolean
              :body #True
            }
            """;
        for (String mode : List.of("PassB", "PassA")) {
            if ("PassA".equals(mode)) {
                myFixture.disableInspections(new StvnBooleanValidityInspection());
            }
            setUseLongFormSumTypes(true);
            myFixture.configureByText("sigiled_near_miss.stvn", sigiledDoc);
            List<HighlightInfo> sigiledErrors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertEquals("Expected exact 4 errors (0 duplicates) on sigiled wrong-case booleans in " + mode + ": "
                + sigiledErrors, 4, sigiledErrors.size());

            // Repair #preserveIndent #true -> #TRUE (preview + live)
            myFixture.getEditor().getCaretModel().moveToOffset(sigiledDoc.indexOf("#true") + 2);
            myFixture.doHighlighting();
            IntentionAction fixPreserve = myFixture.findSingleIntention("Change tag to #TRUE");
            assertTrue(myFixture.getIntentionPreviewText(fixPreserve).contains("{ #preserveIndent #TRUE }"));
            myFixture.launchAction(fixPreserve);

            // Repair #equatable #false -> #FALSE
            String step2 = myFixture.getFile().getText();
            myFixture.getEditor().getCaretModel().moveToOffset(step2.indexOf("#false") + 2);
            myFixture.doHighlighting();
            myFixture.launchAction(myFixture.findSingleIntention("Change tag to #FALSE"));

            // Repair #PUMP_ACTIVE :Boolean #False -> #FALSE
            String step3 = myFixture.getFile().getText();
            myFixture.getEditor().getCaretModel().moveToOffset(step3.indexOf("#False") + 2);
            myFixture.doHighlighting();
            myFixture.launchAction(myFixture.findSingleIntention("Change tag to #FALSE"));

            // Repair :body #True -> #TRUE
            String step4 = myFixture.getFile().getText();
            myFixture.getEditor().getCaretModel().moveToOffset(step4.indexOf("#True") + 2);
            myFixture.doHighlighting();
            myFixture.launchAction(myFixture.findSingleIntention("Change tag to #TRUE"));

            List<HighlightInfo> postSigiledErrors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();
            assertTrue("Expected 0 errors after repairing all 4 sigiled near-miss booleans in " + mode + ": "
                + postSigiledErrors, postSigiledErrors.isEmpty());
        }

        // Verify genuine undeclared constant in #preserveIndent #MISSING_SWITCH retains E030 + Declare constant
        enableAll27StvnInspections();
        String missingConstDoc = """
            {
              :meta {
                #name "missing_const_trait"
                #kind #DEFS
              }
              :defs {
                :DocNote { #preserveIndent #MISSING_SWITCH } :String
              }
            }
            """;
        myFixture.configureByText("missing_const_trait.stvn_d", missingConstDoc);
        myFixture.getEditor().getCaretModel().moveToOffset(missingConstDoc.indexOf("#MISSING_SWITCH") + 2);
        List<HighlightInfo> missingErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals(1, missingErrors.size());
        assertTrue(missingErrors.get(0).getDescription().contains("E030-"));
    }

    /**
     * Target 4: Verifies 1:1 parity between getText() and getFamilyName() ("Initialize STVN document structure").
     */
    public void testTarget4_BootstrapDocumentIntentionLabelParityAndZeroByteExecution() {
        var action = new StvnBootstrapDocumentIntentionAction();
        assertEquals("Initialize STVN document structure", action.getText());
        assertEquals(action.getFamilyName(), action.getText());

        myFixture.configureByText("fresh_doc.stvn", "");
        assertTrue("Legacy 'Bootstrap STVN Document' label must no longer appear",
            myFixture.filterAvailableIntentions("Bootstrap STVN Document").isEmpty());

        IntentionAction discovered = myFixture.findSingleIntention("Initialize STVN document structure");
        assertNotNull(discovered);
        String preview = myFixture.getIntentionPreviewText(discovered);
        assertTrue("Preview must scaffold :meta block:\n" + preview, preview.contains(":meta"));

        myFixture.launchAction(discovered);
        List<HighlightInfo> errors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Initialized document must compile with 0 errors: " + errors, errors.isEmpty());
    }

    /**
     * Target 5: Verifies "Right-Size All Unlocked Columns (Alt+R)" menu label and popup-scoped Alt+R
     * shortcut across loading state, all-locked state, SearchTextField, JBTable, scope radios, and Prelude checkbox.
     *
     * @throws Exception if background snapshot analysis times out
     */
    public void testTarget5_NamespaceBrowserAltRShortcutAndHeaderMenuLabelAcrossAllFocusTargets() throws Exception {
        assertEquals(
            "Right-Size All Unlocked Columns (Alt+R)",
            StvnBundle.message("stvn.browser.header.menu.rightSizeAllUnlocked")
        );

        String browserDoc = """
            {
              :meta {
                #name "browser_alt_r"
                #kind #BODY
              }
              :defs {
                :package :telemetry/optics/amplifier {
                  :GainWindow { #exact #minIncl -10.0 #maxExcl 35.0 } :Float
                  #DEFAULT_GAIN_DB :GainWindow 12.5
                }
                :use [
                  :telemetry/optics/amplifier {
                    :GainWindow :AmplifierGainWindow
                    #DEFAULT_GAIN_DB #NOMINAL_GAIN
                  }
                ]
                :EnvelopeFrame :Tuple( :AmplifierGainWindow :String )
              }
              :type :EnvelopeFrame
              :body ( #NOMINAL_GAIN "active" )
            }
            """;
        PsiFile file = myFixture.configureByText("browser_alt_r.stvn", browserDoc);

        var disposable = Disposer.newDisposable("GauntletV11Target5AltRTest");
        try {
            var ui = StvnNamespaceBrowserPopup.createBrowserContent(
                getProject(), file, StvnNamespaceScope.BODY, disposable
            );

            // 1. Before snapshot finishes (loading), presentation MUST stay enabled (never leaks to Run menu) and be a no-op
            assertTrue("Alt+R action presentation must stay enabled during initial loading",
                isPopupActionPresentationEnabled(ui.rightSizeAllUnlockedAction()));
            firePopupAction(ui.rightSizeAllUnlockedAction());

            ui.waitForAnalysis(5, TimeUnit.SECONDS);
            ui.panel().setSize(new Dimension(920, 440));
            doLayoutRecursively(ui.panel());

            var colMgr = ui.columnManager();
            var colModel = ui.table().getColumnModel();

            // 2. Verify header context menu item text & InputMap registration for Alt+R
            var headerMenu = colMgr.buildHeaderContextMenu(4);
            var rightSizeAllItem = (JMenuItem) headerMenu.getComponent(2);
            assertEquals("Right-Size All Unlocked Columns (Alt+R)", rightSizeAllItem.getText());

            KeyStroke altR = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK);
            var inputMap = ui.panel().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            Object actionKey = inputMap.get(altR);
            assertNotNull("Alt+R must be bound in WHEN_ANCESTOR_OF_FOCUSED_COMPONENT InputMap", actionKey);
            Action swingAltRAction = ui.panel().getActionMap().get(actionKey);
            assertNotNull("Alt+R ActionMap entry must exist", swingAltRAction);

            // 3. Default Use Depth (col 4) is locked; also lock Name (col 0) at custom 210px
            int exactUseDepthWidth = colModel.getColumn(4).getWidth();
            assertTrue(colMgr.isViewColumnLocked(4));
            colModel.getColumn(0).setPreferredWidth(210);
            colModel.getColumn(0).setWidth(210);
            var col0LockItem = (JCheckBoxMenuItem) colMgr.buildHeaderContextMenu(0).getComponent(0);
            col0LockItem.doClick();
            assertTrue(colMgr.isViewColumnLocked(0));

            // 4. Focus Target A: SearchTextField.getTextEditor() (must resize unlocked cols 1..3 and NEVER insert 'r' or '®')
            var editor = ui.filterField().getTextEditor();
            ui.filterField().setText(":Amplifier");
            editor.setCaretPosition(ui.filterField().getText().length());

            shrinkUnlockedColumns(colModel, 42);
            dispatchAltRFromFocusOrigin(ui, swingAltRAction, editor);
            assertEquals("Alt+R inside SearchTextField must not insert 'r' or '®'",
                ":Amplifier", ui.filterField().getText());
            assertEquals("Locked Name column (0) must remain at 210px", 210, colModel.getColumn(0).getWidth());
            assertEquals("Locked Use Depth column (4) must remain at exactUseDepthWidth",
                exactUseDepthWidth, colModel.getColumn(4).getWidth());
            assertTrue("Unlocked Source column (1) must right-size > 42px", colModel.getColumn(1).getWidth() > 42);
            assertTrue("Unlocked Upstream Target column (2) must right-size > 42px", colModel.getColumn(2).getWidth() > 42);
            assertTrue("Unlocked Resolved Type column (3) must right-size > 42px", colModel.getColumn(3).getWidth() > 42);

            ui.filterField().setText("");

            // 5. Focus Targets B..F: JBTable, :defs/:type/:body radio buttons, and Show Standard Library Prelude checkbox
            List<Component> otherOrigins = List.of(
                ui.table(),
                ui.scopeButtons().get(StvnNamespaceScope.DEFS),
                ui.scopeButtons().get(StvnNamespaceScope.TYPE),
                ui.scopeButtons().get(StvnNamespaceScope.BODY),
                ui.showPreludeCheckBox()
            );
            for (Component origin : otherOrigins) {
                shrinkUnlockedColumns(colModel, 42);
                if (origin == ui.table()) {
                    firePopupAction(ui.rightSizeAllUnlockedAction());
                    dispatchAltRKeyListeners(origin);
                } else {
                    dispatchAltRFromFocusOrigin(ui, swingAltRAction, origin);
                }
                assertEquals("", ui.filterField().getText());
                assertEquals(210, colModel.getColumn(0).getWidth());
                assertEquals(exactUseDepthWidth, colModel.getColumn(4).getWidth());
                assertTrue("Unlocked col 1 must right-size from origin " + origin.getClass().getSimpleName(),
                    colModel.getColumn(1).getWidth() > 42);
                assertTrue("Unlocked col 3 must right-size from origin " + origin.getClass().getSimpleName(),
                    colModel.getColumn(3).getWidth() > 42);
            }

            // 6. Lock remaining columns 1..3 so all 5 columns are locked -> presentation stays enabled, action is strict no-op
            for (int col = 1; col <= 3; col++) {
                colModel.getColumn(col).setPreferredWidth(135 + col);
                colModel.getColumn(col).setWidth(135 + col);
                var lockItem = (JCheckBoxMenuItem) colMgr.buildHeaderContextMenu(col).getComponent(0);
                lockItem.doClick();
                assertTrue(colMgr.isViewColumnLocked(col));
            }
            assertFalse(colMgr.hasAnyUnlockedColumn());
            assertTrue("Alt+R presentation must remain enabled when all columns are locked so it never leaks to Run menu",
                isPopupActionPresentationEnabled(ui.rightSizeAllUnlockedAction()));
            firePopupAction(ui.rightSizeAllUnlockedAction());
            swingAltRAction.actionPerformed(new ActionEvent(ui.panel(), ActionEvent.ACTION_PERFORMED, "altR"));
            assertEquals(210, colModel.getColumn(0).getWidth());
            assertEquals(136, colModel.getColumn(1).getWidth());
            assertEquals(137, colModel.getColumn(2).getWidth());
            assertEquals(138, colModel.getColumn(3).getWidth());
            assertEquals(exactUseDepthWidth, colModel.getColumn(4).getWidth());
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static void shrinkUnlockedColumns(javax.swing.table.TableColumnModel colModel, int shrunkWidth) {
        for (int col = 1; col <= 3; col++) {
            colModel.getColumn(col).setPreferredWidth(shrunkWidth);
            colModel.getColumn(col).setWidth(shrunkWidth);
        }
    }

    private static void dispatchAltRFromFocusOrigin(
        StvnNamespaceBrowserPopup.BrowserContent ui,
        Action swingAltRAction,
        Component focusOrigin
    ) {
        dispatchAltRKeyListeners(focusOrigin);
        swingAltRAction.actionPerformed(new ActionEvent(focusOrigin, ActionEvent.ACTION_PERFORMED, "altR"));
    }

    private static void dispatchAltRKeyListeners(Component target) {
        long now = System.currentTimeMillis();
        KeyEvent pressed = new KeyEvent(
            target, KeyEvent.KEY_PRESSED, now, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_R, 'r'
        );
        for (KeyListener listener : target.getKeyListeners()) {
            listener.keyPressed(pressed);
        }
        KeyEvent typedR = new KeyEvent(
            target, KeyEvent.KEY_TYPED, now + 1, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_UNDEFINED, 'r'
        );
        for (KeyListener listener : target.getKeyListeners()) {
            listener.keyTyped(typedR);
        }
        KeyEvent typedMacRegistered = new KeyEvent(
            target, KeyEvent.KEY_TYPED, now + 2, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_UNDEFINED, '\u00AE'
        );
        for (KeyListener listener : target.getKeyListeners()) {
            listener.keyTyped(typedMacRegistered);
        }
    }

    private static boolean isPopupActionPresentationEnabled(AnAction action) {
        AnActionEvent event = TestActionEvent.createTestEvent(action);
        action.update(event);
        return event.getPresentation().isEnabled();
    }

    private static void firePopupAction(AnAction action) {
        AnActionEvent event = TestActionEvent.createTestEvent(action);
        action.update(event);
        if (event.getPresentation().isEnabled()) {
            action.actionPerformed(event);
        }
    }

    private static void doLayoutRecursively(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                doLayoutRecursively(child);
            }
        }
    }

    private static List<ContractRow> loadContractRows(Path contractPath) throws IOException {
        String content = Files.readString(contractPath, StandardCharsets.UTF_8);
        var compiled = StvnCompiler.compile(content, contractPath.toAbsolutePath().toString(), StvnParserConfig.DEFAULT);
        assertFalse("Contract must compile cleanly: " + compiled.diagnostics(), compiled.hasErrors());
        StvnValue payload = compiled.document().orElseThrow().requirePayload();
        StvnValue.StvnSeq seq = (StvnValue.StvnSeq) payload;
        List<ContractRow> rows = new ArrayList<>();
        for (StvnValue elem : seq.elements()) {
            StvnValue.StvnTuple tup = (StvnValue.StvnTuple) elem;
            String code = ((StvnValue.StvnEnum) tup.elements().get(0)).variantTag().substring(1);
            String sym = ((StvnValue.StvnEnum) tup.elements().get(1)).variantTag().substring(1);
            int start = ((StvnValue.StvnInt) tup.elements().get(5)).value().intValueExact();
            int end = ((StvnValue.StvnInt) tup.elements().get(6)).value().intValueExact();
            String msg = ((StvnValue.StvnString) tup.elements().get(7)).value();
            rows.add(new ContractRow(code, sym, start, end, msg));
        }
        return rows;
    }
}
