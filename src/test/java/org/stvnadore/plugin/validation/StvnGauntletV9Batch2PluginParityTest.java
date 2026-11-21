package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.reference.StvnTypeResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * End-to-end IntelliJ Platform parity suite for Gauntlet v9 Batch 2 ({@code PB-01} through {@code PB-04}):
 * <ul>
 *   <li>{@code PB-01}: Surgical {@code E009} bound-pair coordinate pinning, {@code #minSize > #maxSize} swap fixes,
 *       {@code IntentionPreviewInfo.DIFF}, and {@code TC-BIJECT-07} {@code W003} co-location parity.</li>
 *   <li>{@code PB-02}: Stateless JFlex depth-aware {@code MALFORMED_FENCE} recovery on mismatched closing tags,
 *       multi-slot {@code :body} preservation, and {@code Pass A} ({@code 0} inspections) vs. {@code Pass B}
 *       ({@code 26} inspections) highlight and quick-fix parity.</li>
 *   <li>{@code PB-03}: Tripartite {@code :DateTime} schema bound mode validation parity, {@code :body} suspension
 *       across nominal and inline poisoned schemas, degraded hover rendering, and Safeguard 2 non-poisoning
 *       {@code ERR_FACET_ORDER_VIOLATION} preservation.</li>
 *   <li>{@code PB-04}: Mirrored Track A {@code shared-fixtures/} conformance verification.</li>
 * </ul>
 */
@NullMarked
public final class StvnGauntletV9Batch2PluginParityTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");

    private void enableAll26StvnInspections() {
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
            new StvnFloatPrecisionLossInspection()
        );
    }

    public void testPB01_MirroredInvertedBoundsFixturePassAAndPassBWithDiffAndBothSideQuickFix() throws IOException {
        Path fixturePath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/intervals/inverted_bounds_sibling_facet_and_non_adjacent_pinning.stvn_d"
        );
        String content = Files.readString(fixturePath, StandardCharsets.UTF_8);

        // Pass A: 0 local inspections enabled
        myFixture.configureByText("inverted_bounds_sibling_facet_and_non_adjacent_pinning.stvn_d", content);
        var passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit exact 5 ERROR highlights matching .contract.stvn_i: " + passAErrors,
            5, passAErrors.size());
        assertEquals("#minIncl \"Z\" #maxIncl \"A\"", passAErrors.get(0).getText());
        assertEquals("#minSize 12 #maxSize 4", passAErrors.get(1).getText());
        assertEquals("#minIncl 200 #maxExcl 100", passAErrors.get(2).getText());
        assertEquals("#size 16", passAErrors.get(3).getText());
        assertEquals("#maxExcl 5", passAErrors.get(4).getText());

        // Pass B: all 26 local inspections enabled
        enableAll26StvnInspections();
        myFixture.configureByText("inverted_bounds_sibling_facet_and_non_adjacent_pinning.stvn_d", content);
        var passBErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit exact 5 ERROR highlights with 0 duplicates: " + passBErrors,
            5, passBErrors.size());
        for (var err : passBErrors) {
            String span = err.getText().trim();
            assertFalse("Delimiters '{' and '}' must never carry error squiggles: " + span,
                span.startsWith("{") || span.endsWith("}"));
            assertFalse("Sibling #maxSize 8 must never be underlined by string value-bound E009: " + span,
                span.contains("#maxSize 8"));
        }

        // Verify SwapInvertedBoundsQuickFix + IntentionPreviewInfo.DIFF on #minSize 12 #maxSize 4
        myFixture.getEditor().getCaretModel().moveToOffset(content.indexOf("#minSize 12") + 2);
        var cardSwap = myFixture.findSingleIntention("Swap inverted bounds ('#minSize 4' and '#maxSize 12')");
        assertNotNull("Expected SwapInvertedBoundsQuickFix on #minSize > #maxSize", cardSwap);
        String cardPreview = myFixture.getIntentionPreviewText(cardSwap);
        assertTrue("Expected IntentionPreviewInfo.DIFF to swap #minSize/#maxSize: " + cardPreview,
            cardPreview.contains(":TrapInvertedSeqSize { #minSize 4 #maxSize 12 } :Seq( :Int )"));

        // Verify SwapInvertedBoundsQuickFix + IntentionPreviewInfo.DIFF on both #minIncl 10 and #maxExcl 5
        myFixture.getEditor().getCaretModel().moveToOffset(content.indexOf("#minIncl 10") + 2);
        var earlierSwap = myFixture.findSingleIntention("Swap inverted bounds ('#minIncl 5' and '#maxExcl 10')");
        assertNotNull("Expected SwapInvertedBoundsQuickFix on earlier non-adjacent #minIncl 10", earlierSwap);
        assertTrue(myFixture.getIntentionPreviewText(earlierSwap).contains("#minIncl 5 #size 16 #maxExcl 10"));

        myFixture.getEditor().getCaretModel().moveToOffset(content.indexOf("#maxExcl 5") + 2);
        var laterSwap = myFixture.findSingleIntention("Swap inverted bounds ('#minIncl 5' and '#maxExcl 10')");
        assertNotNull("Expected SwapInvertedBoundsQuickFix on later non-adjacent #maxExcl 5", laterSwap);
        myFixture.launchAction(laterSwap);
        assertTrue(myFixture.getFile().getText().contains(":TrapNonAdjacentBounds { #minIncl 5 #size 16 #maxExcl 10 } :Int"));
    }

    public void testPB01_NonAdjacentCardinalitySwapAndPurePsiFallbackTcBiject07() {
        enableAll26StvnInspections();
        var nonAdjCardAndFallback = """
            {
              :meta {
                #name "non_adj_card_and_biject"
                #kind #BODY
              }
              :defs {
                :NonAdjSeq { #minSize 12 #equatable #FALSE #maxSize 4 } :Seq( :Int )
                :Parent { #minIncl 20 #maxExcl 30 } :Int
                :BadInverted { #minIncl 20 #maxExcl 10 } :Parent
                :BadInvertedNonAdj { #minIncl 20 #comparable #FALSE #maxExcl 10 } :Parent
              }
              :type :Int
              :body (
            """;
        myFixture.configureByText("non_adj_card_and_biject.stvn", nonAdjCardAndFallback);
        var highlights = myFixture.doHighlighting();

        var w003Warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertEquals("Pure-PSI fallback must suppress W003 on :BadInverted and preserve W003 on :BadInvertedNonAdj #minIncl 20: " + w003Warnings,
            1, w003Warnings.size());
        assertEquals("#minIncl 20", w003Warnings.get(0).getText());

        var normalCardDoc = """
            {
              :meta {
                #name "non_adj_card"
                #kind #DEFS
              }
              :defs {
                :NonAdjSeq { #minSize 12 #equatable #FALSE #maxSize 4 } :Seq( :Int )
              }
            }
            """;
        myFixture.configureByText("non_adj_card.stvn_d", normalCardDoc);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(normalCardDoc.indexOf("#minSize 12") + 2);
        var earlierCardFix = myFixture.findSingleIntention("Swap inverted bounds ('#minSize 4' and '#maxSize 12')");
        assertNotNull("Expected SwapInvertedBoundsQuickFix on non-adjacent earlier #minSize 12", earlierCardFix);
        assertTrue(myFixture.getIntentionPreviewText(earlierCardFix).contains("#minSize 4 #equatable #FALSE #maxSize 12"));
    }

    public void testPB02_MirroredMismatchedFenceBodyRecoveryPassAAndPassBWithDiff() throws IOException {
        Path mismatchPath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/strings/fenced_string_delimiter_mismatch_e025.stvn"
        );
        Path bodyRecoveryPath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/strings/fenced_string_mismatched_close_body_recovery.stvn"
        );
        String mismatchContent = Files.readString(mismatchPath, StandardCharsets.UTF_8);
        String recoveryContent = Files.readString(bodyRecoveryPath, StandardCharsets.UTF_8);

        // Pass A (0 inspections): exact 1 ERROR on e025 + balance quick-fixes attached by StvnExternalAnnotator
        myFixture.configureByText("fenced_string_delimiter_mismatch_e025.stvn", mismatchContent);
        var passAMismatchErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit exact 1 ERROR on fenced_string_delimiter_mismatch_e025: " + passAMismatchErrors,
            1, passAMismatchErrors.size());
        myFixture.getEditor().getCaretModel().moveToOffset(mismatchContent.indexOf("\"\"\"[SQL]") + 4);
        assertNotNull("Pass A must still offer BalanceClosingTagQuickFix",
            myFixture.findSingleIntention("Replace '[JSON]\"\"\"' with '[SQL]\"\"\"'"));
        assertNotNull("Pass A must still offer BalanceOpeningTagQuickFix",
            myFixture.findSingleIntention("Replace '[SQL]' with '[JSON]'"));

        // Pass A (0 inspections) on fenced_string_mismatched_close_body_recovery.stvn: exact 5 ERRORs
        myFixture.configureByText("fenced_string_mismatched_close_body_recovery.stvn", recoveryContent);
        var passARecoveryErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit exact 5 ERRORs matching .contract.stvn_i: " + passARecoveryErrors,
            5, passARecoveryErrors.size());

        // Pass B (26 inspections) on fenced_string_mismatched_close_body_recovery.stvn
        enableAll26StvnInspections();
        myFixture.configureByText("fenced_string_mismatched_close_body_recovery.stvn", recoveryContent);
        var passBRecoveryErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        int suspendedSlotOffset = recoveryContent.indexOf("\"suspended-broken-regex-slot\"");
        assertTrue("Suspended Slot 6 governed by :BrokenRegex must carry 0 ERROR highlights: " + passBRecoveryErrors,
            passBRecoveryErrors.stream().noneMatch(h -> h.getStartOffset() >= suspendedSlotOffset));
        assertTrue("No core E025 duplicate may appear on [SQL]...[JSON] when StvnFencedString is enabled",
            passBRecoveryErrors.stream().noneMatch(h -> h.getDescription() != null
                && h.getDescription().contains("found mismatched closing tag '[JSON]'")));

        // Verify bidirectional IntentionPreviewInfo.DIFF on [JSON]""" in Pass B
        myFixture.getEditor().getCaretModel().moveToOffset(recoveryContent.indexOf("[JSON]\"\"\"") + 2);
        var balanceClose = myFixture.findSingleIntention("Replace '[JSON]\"\"\"' with '[SQL]\"\"\"'");
        assertNotNull(balanceClose);
        assertTrue(myFixture.getIntentionPreviewText(balanceClose).contains("[SQL]\"\"\""));
        myFixture.launchAction(balanceClose);
        assertTrue(myFixture.getFile().getText().contains("[SQL]\"\"\"\n    \"suspended-broken-regex-slot\""));
    }

    public void testPB03_DateTimeBoundsModeMismatchPoisoningSuspendsBodyAndHoverAndPreservesFacetOrderHealthy() throws IOException {
        Path dtPoisonPath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/temporal/datetime_schema_bounds_mode_mismatch_poisoning.stvn"
        );
        String dtPoisonContent = Files.readString(dtPoisonPath, StandardCharsets.UTF_8);

        // Pass A (0 inspections)
        myFixture.configureByText("datetime_schema_bounds_mode_mismatch_poisoning.stvn", dtPoisonContent);
        var passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        int bodyIdx = dtPoisonContent.indexOf(":body");
        assertEquals("Pass A must emit exact 4 :defs ERRORs and 0 :body ERRORs: " + passAErrors,
            4, passAErrors.size());
        assertTrue("Pass A must emit 0 ERRORs inside :body",
            passAErrors.stream().noneMatch(h -> h.getStartOffset() >= bodyIdx));

        // Pass B (26 inspections)
        enableAll26StvnInspections();
        PsiFile file = myFixture.configureByText("datetime_schema_bounds_mode_mismatch_poisoning.stvn", dtPoisonContent);
        var passBErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit exact 4 :defs ERRORs (0 secondary E009 and 0 :body ERRORs): " + passBErrors,
            4, passBErrors.size());
        assertTrue("Pass B must emit 0 ERRORs inside :body",
            passBErrors.stream().noneMatch(h -> h.getStartOffset() >= bodyIdx));

        var docProvider = new StvnDocumentationProvider();
        String defHover = generateHoverAt(docProvider, file, dtPoisonContent.indexOf(":ZonedDispatchTs") + 2);
        assertTrue("Expected degraded schema banner on :ZonedDispatchTs hover: " + defHover,
            defHover.contains("Degraded Schema") || defHover.contains("\u26A0"));
        String bodySlotHover = generateHoverAt(docProvider, file, dtPoisonContent.indexOf("\"2026-07-04T12:00:00-05:00[America/Chicago]\"") + 2);
        assertTrue("Expected Suspended (Degraded Schema) on :ZonedDispatchTs :body slot hover: " + bodySlotHover,
            bodySlotHover.contains("Suspended (Degraded Schema)"));

        // Inline poisoned :DateTime + Safeguard 2 non-poisoning ERR_FACET_ORDER_VIOLATION check
        String inlineAndOrderDoc = """
            {
              :meta {
                #name "inline_poison_and_facet_order"
                #kind #BODY
              }
              :defs {
                :DisorderedHealthyOffset {
                  #minIncl "2026-01-01T00:00:00Z"
                  #offset
                } :DateTime
              }
              :type :Tuple(
                { #zoned #minIncl "2026-01-01T00:00:00-06:00[America/Chicago]" } :DateTime
                :DisorderedHealthyOffset
              )
              :body (
                "2026-07-04T12:00:00-05:00[America/Chicago]"
                "2025-01-01T00:00:00[UTC]"
              )
            }
            """;
        PsiFile inlineFile = myFixture.configureByText("inline_poison_and_facet_order.stvn", inlineAndOrderDoc);
        assertFalse("Safeguard 2: pure ERR_FACET_ORDER_VIOLATION must NOT mark :DisorderedHealthyOffset degraded",
            StvnTypeResolver.isDegradedNominalAlias(inlineFile, ":DisorderedHealthyOffset"));

        var inlineErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        int inlineSlot1Offset = inlineAndOrderDoc.indexOf("\"2026-07-04T12:00:00-05:00[America/Chicago]\"");
        int slot2Offset = inlineAndOrderDoc.indexOf("\"2025-01-01T00:00:00[UTC]\"");
        assertTrue("Inline poisoned :DateTime slot 1 must suspend :body validation (0 errors on slot 1): " + inlineErrors,
            inlineErrors.stream().noneMatch(h -> h.getStartOffset() == inlineSlot1Offset));
        assertTrue("Safeguard 2: non-poisoned :DisorderedHealthyOffset must still validate :body slot 2 (E028): " + inlineErrors,
            inlineErrors.stream().anyMatch(h -> h.getStartOffset() == slot2Offset));
    }

    private String generateHoverAt(StvnDocumentationProvider provider, PsiFile file, int offset) {
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        PsiElement leaf = file.findElementAt(offset);
        assertNotNull("Expected PSI leaf at offset " + offset, leaf);
        PsiElement target = provider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, offset);
        if (target == null) {
            var ref = file.findReferenceAt(offset);
            target = ref != null ? ref.resolve() : leaf;
        }
        assertNotNull("Expected documentation target at offset " + offset, target);
        String doc = provider.generateDoc(target, leaf);
        assertNotNull("Expected generated hover documentation at offset " + offset, doc);
        return doc;
    }
}
