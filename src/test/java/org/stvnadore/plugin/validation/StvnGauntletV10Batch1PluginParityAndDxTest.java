package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.psi.TypeDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end IntelliJ Platform verification suite for Gauntlet v10 Batch 1
 * ({@code G10B1-01} through {@code G10B1-05}) across {@code Pass A} ({@code 0} inspections)
 * and {@code Pass B} ({@code 26} inspections).
 */
@NullMarked
public final class StvnGauntletV10Batch1PluginParityAndDxTest extends BasePlatformTestCase {

    private static final Path SHARED_FIXTURES_ROOT = Paths.get("src", "test", "resources", "shared-fixtures");

    private static final Pattern COMPLETE_SUFFIX_PATTERN =
        Pattern.compile("\\[stvn-(?:core|plugin):([A-Z0-9]+-[A-Z0-9_]+)\\]");

    private LocalInspectionTool[] createAll26Inspections() {
        return new LocalInspectionTool[]{
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

    private List<HighlightInfo> collectDiagnosticHighlights(List<HighlightInfo> all) {
        return all.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                || h.getSeverity() == HighlightSeverity.WARNING
                || h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .filter(h -> h.getDescription() != null && !h.getDescription().isBlank())
            .toList();
    }

    private void assertNoDuplicateDiagnosticRanges(String label, List<HighlightInfo> highlights) {
        Set<String> seen = new HashSet<>();
        for (HighlightInfo h : highlights) {
            String desc = h.getDescription() != null ? h.getDescription() : "";
            Matcher m = COMPLETE_SUFFIX_PATTERN.matcher(desc);
            assertTrue(label + " highlight missing complete suffix: " + desc, m.find());
            String key = h.getStartOffset() + ":" + h.getEndOffset() + ":" + m.group(1);
            assertTrue(label + " stacked duplicate highlight [" + key + "] -> " + desc, seen.add(key));
        }
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
        assertNotNull("Expected hover HTML at offset " + offset, doc);
        return doc;
    }

    private void assertSingleIntentionWithPreview(int offset, String label) {
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        List<IntentionAction> matches = myFixture.getAvailableIntentions().stream()
            .filter(a -> label.equals(a.getText()))
            .toList();
        assertEquals(
            "Expected strictly 1 '" + label + "' intention at offset " + offset + ", found: "
                + myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList(),
            1,
            matches.size()
        );
        String preview = myFixture.getIntentionPreviewText(matches.get(0));
        assertNotNull("Expected non-null IntentionPreviewInfo.DIFF for '" + label + "' at offset " + offset, preview);
        assertFalse("Expected non-empty DIFF preview for '" + label + "'", preview.isBlank());
    }

    public void testTarget1_ReservedDomainInvalidPosixAndDuplicateSuppressStemWarningsWithDisjointQuickFixParity() {
        String twoStemReserved = """
            {
              :meta {
                #name "vial_custody_manifest"
                #domain "stvn_cryo_vault"
                #kind #BODY
              }
              :type :Int
              :body 42
            }
            """;

        // Pass A (0 inspections)
        myFixture.configureByText("vial_custody_manifest.cryo_biobank.stvn", twoStemReserved);
        List<HighlightInfo> passA = collectDiagnosticHighlights(myFixture.doHighlighting());
        assertNoDuplicateDiagnosticRanges("T1 Pass A 2-stem E038", passA);
        assertEquals("Pass A must emit zero yellow warnings on E038: " + passA, 0L,
            passA.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());
        List<HighlightInfo> passAE038 = passA.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E038-ERR_RESERVED_DOMAIN_PROHIBITED"))
            .toList();
        assertEquals(1, passAE038.size());
        assertEquals("\"stvn_cryo_vault\"", passAE038.get(0).getText());

        int kwOffset = twoStemReserved.indexOf("#domain") + 2;
        int litOffset = twoStemReserved.indexOf("stvn_cryo_vault") + 3;
        assertSingleIntentionWithPreview(kwOffset, "Align '#domain' with filename stem slot 1 ('cryo_biobank')");
        assertSingleIntentionWithPreview(kwOffset, "Remove reserved 'stvn_' prefix from '#domain'");
        assertSingleIntentionWithPreview(litOffset, "Align '#domain' with filename stem slot 1 ('cryo_biobank')");
        assertSingleIntentionWithPreview(litOffset, "Remove reserved 'stvn_' prefix from '#domain'");
        assertTrue(myFixture.getAvailableIntentions().stream()
            .noneMatch(a -> a.getText().contains("Rename file to")));

        // Pass B (26 inspections)
        LocalInspectionTool[] all26 = createAll26Inspections();
        myFixture.enableInspections(all26);
        try {
            myFixture.configureByText("vial_custody_manifest.cryo_biobank.stvn", twoStemReserved);
            List<HighlightInfo> passB = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertNoDuplicateDiagnosticRanges("T1 Pass B 2-stem E038", passB);
            assertEquals("Pass B must emit zero yellow warnings on E038: " + passB, 0L,
                passB.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());
            List<HighlightInfo> passBE038 = passB.stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains("E038-ERR_RESERVED_DOMAIN_PROHIBITED"))
                .toList();
            assertEquals(1, passBE038.size());
            assertEquals("\"stvn_cryo_vault\"", passBE038.get(0).getText());

            assertSingleIntentionWithPreview(kwOffset, "Align '#domain' with filename stem slot 1 ('cryo_biobank')");
            assertSingleIntentionWithPreview(kwOffset, "Remove reserved 'stvn_' prefix from '#domain'");
            assertSingleIntentionWithPreview(litOffset, "Align '#domain' with filename stem slot 1 ('cryo_biobank')");
            assertSingleIntentionWithPreview(litOffset, "Remove reserved 'stvn_' prefix from '#domain'");

            // 1-stem E038: zero W002, offers Remove '#domain' facet + Remove reserved 'stvn_' prefix across #domain and literal
            myFixture.configureByText("vial_custody_manifest.stvn", twoStemReserved);
            List<HighlightInfo> oneStemHighlights = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertEquals("1-stem E038 must emit zero W002 warnings: " + oneStemHighlights, 0L,
                oneStemHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());
            assertSingleIntentionWithPreview(kwOffset, "Remove '#domain' facet");
            assertSingleIntentionWithPreview(litOffset, "Remove '#domain' facet");

            // Invalid POSIX #name / #domain + duplicate #name / #domain: zero W001/W002
            String invalidPosixAndDuplicates = """
                {
                  :meta {
                    #name "bad/name"
                    #name "also_wrong_stem"
                    #domain "bad.domain"
                    #domain "other_wrong_domain"
                    #kind #BODY
                  }
                  :type :Int
                  :body 1
                }
                """;
            myFixture.configureByText("clean_stem.clean_dom.stvn", invalidPosixAndDuplicates);
            List<HighlightInfo> posixAndDup = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertEquals("Invalid POSIX and 2nd+ duplicate facets must emit 0 stem warnings: " + posixAndDup, 0L,
                posixAndDup.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());
        } finally {
            myFixture.disableInspections(all26);
        }

        // Containment-escape check when StvnStemMismatch is enabled while StvnMetadataOrder is disabled
        myFixture.enableInspections(new StvnStemMismatchInspection());
        try {
            String outOfOrderValidName = """
                {
                  :meta {
                    #kind #BODY
                    #name "mismatched_valid_name"
                  }
                  :type :Int
                  :body 7
                }
                """;
            myFixture.configureByText("actual_file_stem.stvn", outOfOrderValidName);
            List<HighlightInfo> mixedMode = collectDiagnosticHighlights(myFixture.doHighlighting());
            List<HighlightInfo> mixedW001 = mixedMode.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                    && h.getDescription() != null
                    && h.getDescription().contains("W001-WARN_DOCUMENT_NAME_MISMATCH"))
                .toList();
            assertEquals("W001 must remain disjoint from Core keyword-pinned E008 when StvnMetadataOrder is disabled: " + mixedMode,
                1, mixedW001.size());
            assertEquals("\"mismatched_valid_name\"", mixedW001.get(0).getText());
            List<HighlightInfo> mixedE008 = mixedMode.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                    && h.getDescription() != null
                    && h.getDescription().contains("E008-ERR_FACET_ORDER_VIOLATION"))
                .toList();
            assertEquals(1, mixedE008.size());
            assertEquals("#name", mixedE008.get(0).getText());
        } finally {
            myFixture.disableInspections(new StvnStemMismatchInspection());
        }
    }

    public void testTarget2_DisorderedMetaAndMetadataMapExposeSingleReorderQuickFixUnderCoLocatedErrors() {
        String disorderedMetaWithKindE031 = """
            {
              :meta {
                #domain "cryo_biobank"
                #name "vial_custody_manifest"
                #kind #BODY_INCLUDE
              }
              :defs {
                :FloatOrderAndE010 { #minIncl 0.0 #size 16 } :Float
                :FloatOrderAndMutex { #minIncl 0.0 #exact #size 64 } :Float
                :IntOrderAndDiscrete { #maxIncl 10 #minIncl 1 #unsigned } :Int
                :ParentInt { #size 32 } :Int
                :ChildOrderAndE040 { #minIncl 0 #size 16 } :ParentInt
              }
              :type :Int
              :body 10
            }
            """;

        String metaReorderLabel = "Reorder ':meta' entries to canonical order (#name, #domain, #kind)";
        String facetReorderLabel = "Reorder metadata facets to canonical 5-tier order";

        for (boolean enable26 : new boolean[]{false, true}) {
            LocalInspectionTool[] all26 = createAll26Inspections();
            if (enable26) {
                myFixture.enableInspections(all26);
            }
            try {
                myFixture.configureByText("vial_custody_manifest.cryo_biobank.stvn", disorderedMetaWithKindE031);
                List<HighlightInfo> highlights = collectDiagnosticHighlights(myFixture.doHighlighting());
                assertNoDuplicateDiagnosticRanges(enable26 ? "T2 Pass B" : "T2 Pass A", highlights);

                int metaKw = disorderedMetaWithKindE031.indexOf(":meta") + 2;
                int metaLBrace = disorderedMetaWithKindE031.indexOf("{", metaKw);
                int domainPos = disorderedMetaWithKindE031.indexOf("#domain \"cryo_biobank\"") + 3;
                int domainValPos = disorderedMetaWithKindE031.indexOf("\"cryo_biobank\"") + 3;
                int namePos = disorderedMetaWithKindE031.indexOf("#name \"vial_custody_manifest\"") + 3;
                int nameValPos = disorderedMetaWithKindE031.indexOf("\"vial_custody_manifest\"") + 3;
                int kindPos = disorderedMetaWithKindE031.indexOf("#kind #BODY_INCLUDE") + 3;
                int kindValPos = disorderedMetaWithKindE031.indexOf("#BODY_INCLUDE") + 4;
                int metaRBrace = disorderedMetaWithKindE031.indexOf("}", kindValPos);

                for (int offset : new int[]{
                    metaKw, metaLBrace, domainPos, domainValPos, namePos, nameValPos, kindPos, kindValPos, metaRBrace
                }) {
                    assertSingleIntentionWithPreview(offset, metaReorderLabel);
                }

                // Co-errored MetadataMap anchors (#size 16 E010, #exact/#size 64 E008, #maxIncl 10 E011, #size 16 E040)
                int e010Offset = disorderedMetaWithKindE031.indexOf("#size 16 } :Float") + 2;
                int e010MinInclOffset = disorderedMetaWithKindE031.indexOf("#minIncl 0.0 #size 16") + 2;
                int mutexSize64Offset = disorderedMetaWithKindE031.indexOf("#size 64 } :Float") + 2;
                int mutexExactOffset = disorderedMetaWithKindE031.indexOf("#exact #size 64") + 2;
                int e011MaxInclOffset = disorderedMetaWithKindE031.indexOf("#maxIncl 10") + 2;
                int e040SizeOffset = disorderedMetaWithKindE031.indexOf("#size 16 } :ParentInt") + 2;

                for (int offset : new int[]{
                    e010Offset, e010MinInclOffset, mutexSize64Offset, mutexExactOffset, e011MaxInclOffset, e040SizeOffset
                }) {
                    assertSingleIntentionWithPreview(offset, facetReorderLabel);
                }

                // Launch reorder directly from #kind #BODY_INCLUDE (the exact reported bug locus)
                myFixture.getEditor().getCaretModel().moveToOffset(kindValPos);
                IntentionAction reorderFromKind = myFixture.findSingleIntention(metaReorderLabel);
                myFixture.launchAction(reorderFromKind);
                String afterMetaReorder = myFixture.getFile().getText();
                int nIdx = afterMetaReorder.indexOf("#name \"vial_custody_manifest\"");
                int dIdx = afterMetaReorder.indexOf("#domain \"cryo_biobank\"");
                int kIdx = afterMetaReorder.indexOf("#kind #BODY_INCLUDE");
                assertTrue("Expected #name -> #domain -> #kind after launching reorder on #kind:\n" + afterMetaReorder,
                    nIdx >= 0 && dIdx > nIdx && kIdx > dIdx);
            } finally {
                if (enable26) {
                    myFixture.disableInspections(all26);
                }
            }
        }
    }

    public void testTarget2_MetaKindSingleQuoteLabelsAndPosixIdExemplarSynthesis() {
        LocalInspectionTool[] all26 = createAll26Inspections();
        myFixture.enableInspections(all26);
        try {
            String wrongKindDoc = """
                {
                  :meta {
                    #name "quote_check"
                    #kind #BODY_INCLUDE
                  }
                  :type :Int
                  :body 1
                }
                """;
            myFixture.configureByText("quote_check.stvn", wrongKindDoc);
            myFixture.doHighlighting();
            assertSingleIntentionWithPreview(
                wrongKindDoc.indexOf("#kind #BODY_INCLUDE") + 3,
                "Update '#kind' to match file extension"
            );

            String missingKindDoc = """
                {
                  :meta {
                    #name "missing_kind"
                  }
                  :type :Int
                  :body 1
                }
                """;
            myFixture.configureByText("missing_kind.stvn_i", missingKindDoc);
            myFixture.doHighlighting();
            assertSingleIntentionWithPreview(
                missingKindDoc.indexOf(":meta") + 2,
                "Insert missing '#kind' facet"
            );

            String exemplarSchemas = """
                {
                  :defs {
                    :PosixId { #minSize 1 #maxSize 64 #regex "^[a-zA-Z0-9_-]+$" } :String
                    :UuidLower { #minSize 36 #maxSize 36 #regex "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" } :String
                    :Sha256Lower { #minSize 64 #maxSize 64 #regex "^[0-9a-f]{64}$" } :String
                  }
                  :type :PosixId
                  :body "ok_id"
                }
                """;
            PsiFile psiFile = myFixture.configureByText("exemplar_check.stvn", exemplarSchemas);
            myFixture.doHighlighting();
            var docProvider = new StvnDocumentationProvider();

            String posixHover = generateHoverAt(docProvider, psiFile, exemplarSchemas.indexOf(":PosixId {") + 2);
            assertTrue("Expected '\"example_id\"' exemplar on :PosixId hover, got:\n" + posixHover,
                posixHover.contains("&quot;example_id&quot;") || posixHover.contains("\"example_id\""));
            assertFalse(":PosixId hover must not display UUID exemplar:\n" + posixHover,
                posixHover.contains("550e8400-e29b-41d4-a716-446655440000"));

            TypeDefinition uuidDef = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, TypeDefinition.class)
                .stream().filter(td -> td.getTypeKeyword() != null && ":UuidLower".equals(td.getTypeKeyword().getText()))
                .findFirst().orElseThrow();
            assertEquals("\"550e8400-e29b-41d4-a716-446655440000\"",
                StvnExemplarValueGenerator.generateHoverExemplar(uuidDef.getSchemaType(), null));

            TypeDefinition shaDef = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, TypeDefinition.class)
                .stream().filter(td -> td.getTypeKeyword() != null && ":Sha256Lower".equals(td.getTypeKeyword().getText()))
                .findFirst().orElseThrow();
            assertEquals("\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"",
                StvnExemplarValueGenerator.generateHoverExemplar(shaDef.getSchemaType(), null));
        } finally {
            myFixture.disableInspections(all26);
        }
    }

    public void testTarget3A_IntTypedConstantHoverRadixResolutionMultiHopAndSafeguard422() {
        String source = """
            {
              :meta {
                #name "stage2_const_hover"
                #kind #BODY
              }
              :defs {
                :package :optics/collimator {
                  #PKG_HEX_MIN { #unsigned #size 16 } :Int 0x0064
                }
                :use [ :optics/collimator { #PKG_HEX_MIN #IMPORTED_HEX_MIN } ]
                #HEX_COLLIMATOR_MIN { #unsigned #size 16 } :Int 0x0064
                #DERIVED_MIN { #unsigned #size 16 } :Int #HEX_COLLIMATOR_MIN
                #NEG_BIN_OFFSET :Int -0b1000000
                #OCT_LIMIT :Int 0o0764
                #DEC_PLAIN :Int 100
                #BAD_OVERFLOW { #unsigned #size 8 } :Int 0x01FF
                :CollimatorAperture { #unsigned #size 16 #minIncl #HEX_COLLIMATOR_MIN #maxExcl 1000 } :Int
              }
              :type :Tuple( :CollimatorAperture :Int )
              :body ( #HEX_COLLIMATOR_MIN #IMPORTED_HEX_MIN )
            }
            """;

        PsiFile file = myFixture.configureByText("stage2_const_hover.stvn", source);
        myFixture.doHighlighting();
        var provider = new StvnDocumentationProvider();

        // 1. :defs LHS #HEX_COLLIMATOR_MIN
        String hexLhsHover = generateHoverAt(provider, file, source.indexOf("#HEX_COLLIMATOR_MIN {") + 3);
        assertTrue("Expected decoded (= 100) on 0x0064 constant hover:\n" + hexLhsHover,
            hexLhsHover.contains("<code>0x0064</code> (= <code>100</code>)"));
        assertTrue("Expected Valid Lexical & Syntax Forms on valid :Int constant hover:\n" + hexLhsHover,
            hexLhsHover.contains("Valid Lexical &amp; Syntax Forms:"));
        assertTrue(hexLhsHover.contains("Decimal: <code>100</code>"));
        assertTrue(hexLhsHover.contains("Hex: <code>0x0064</code>"));
        assertTrue(hexLhsHover.contains("Binary: <code>0b0000000001100100</code>"));
        assertTrue(hexLhsHover.contains("Octal: <code>0o144</code>"));
        assertFalse("Safeguard 4.22: Healthy :defs LHS card MUST NOT contain 'Compliance Status:':\n" + hexLhsHover,
            hexLhsHover.contains("Compliance Status:"));

        // 2. Metadata facet reference #minIncl #HEX_COLLIMATOR_MIN
        int minInclRefOffset = source.indexOf("#minIncl #HEX_COLLIMATOR_MIN") + "#minIncl ".length() + 3;
        String facetRefHover = generateHoverAt(provider, file, minInclRefOffset);
        assertTrue("Expected decoded (= 100) and 4-radix forms on #minIncl #HEX_COLLIMATOR_MIN hover:\n" + facetRefHover,
            facetRefHover.contains("<code>0x0064</code> (= <code>100</code>)")
                && facetRefHover.contains("Hex: <code>0x0064</code>"));
        assertFalse("Facet #CONST reference must omit :body Compliance Status:\n" + facetRefHover,
            facetRefHover.contains("Compliance Status:"));

        // 3. Multi-hop #DERIVED_MIN -> #HEX_COLLIMATOR_MIN -> 0x0064 (= 100)
        String derivedHover = generateHoverAt(provider, file, source.indexOf("#DERIVED_MIN") + 3);
        assertTrue("Expected chained (&rarr; 100) preview on #DERIVED_MIN:\n" + derivedHover,
            derivedHover.contains("<code>#HEX_COLLIMATOR_MIN</code> (&rarr; <code>100</code>)"));
        assertTrue("Expected terminal (= 100) on Resolved Value row:\n" + derivedHover,
            derivedHover.contains("<b>Resolved Value:</b> <code>0x0064</code> (= <code>100</code>)"));
        assertFalse(derivedHover.contains("Compliance Status:"));

        // 4. Negative binary literal -0b1000000 (= -64) with proper leading '-' before radix prefix
        String negBinHover = generateHoverAt(provider, file, source.indexOf("#NEG_BIN_OFFSET") + 3);
        assertTrue("Expected (= -64) on #NEG_BIN_OFFSET:\n" + negBinHover,
            negBinHover.contains("<code>-0b1000000</code> (= <code>-64</code>)"));
        assertTrue("Negative hex must format '-0x40' (never '0x-40'):\n" + negBinHover,
            negBinHover.contains("Hex: <code>-0x40</code>"));
        assertTrue("Negative binary must format '-0b1000000':\n" + negBinHover,
            negBinHover.contains("Binary: <code>-0b1000000</code>"));
        assertTrue("Negative octal must format '-0o100':\n" + negBinHover,
            negBinHover.contains("Octal: <code>-0o100</code>"));

        // 5. Octal 0o0764 (= 500) and plain decimal 100 (clean Value: 100)
        String octHover = generateHoverAt(provider, file, source.indexOf("#OCT_LIMIT") + 3);
        assertTrue(octHover.contains("<code>0o0764</code> (= <code>500</code>)"));
        String decHover = generateHoverAt(provider, file, source.indexOf("#DEC_PLAIN") + 3);
        assertTrue("Direct decimal literal must keep clean 'Value: 100':\n" + decHover,
            decHover.contains("<b>Value:</b> 100"));
        assertFalse(decHover.contains("(= <code>100</code>)"));

        // 6. Violating :defs constant #BAD_OVERFLOW omits Valid Lexical & Syntax Forms
        String badOverflowHover = generateHoverAt(provider, file, source.indexOf("#BAD_OVERFLOW") + 3);
        assertFalse("Violating constant must omit Valid Lexical & Syntax Forms:\n" + badOverflowHover,
            badOverflowHover.contains("Valid Lexical &amp; Syntax Forms:"));

        // 7. :use-renamed constant #IMPORTED_HEX_MIN in :body retains Renamed Reference +radix preview + :body Compliance Status
        int bodyImportedOffset = source.indexOf("#IMPORTED_HEX_MIN )") + 3;
        String bodyImportedHover = generateHoverAt(provider, file, bodyImportedOffset);
        assertTrue(bodyImportedHover.contains("<b>Renamed Reference:</b>"));
        assertTrue(bodyImportedHover.contains("<code>0x0064</code> (= <code>100</code>)"));
        assertTrue(bodyImportedHover.contains("<b>Compliance Status:</b>"));
    }

    public void testTarget3B_DefsRhsConstantValueHoverPreservesInlineMetadataInGoverningSchema() {
        String source = """
            {
              :defs {
                #HEX_COLLIMATOR_MIN { #unsigned #size 16 } :Int 0x0064
                #TAG_PREFIX { #minSize 1 #maxSize 16 } :String "alpha"
              }
              :type :Int
              :body 1
            }
            """;

        PsiFile file = myFixture.configureByText("stage2_rhs_governing_schema.stvn", source);
        myFixture.doHighlighting();
        var provider = new StvnDocumentationProvider();

        String rhsIntHover = generateHoverAt(provider, file, source.indexOf("0x0064") + 2);
        assertTrue(
            "Expected inline MetadataMap preserved in Governing Schema on :defs RHS Int literal:\n" + rhsIntHover,
            rhsIntHover.contains("<nobr><b>Governing Schema:</b> <code>{ #unsigned #size 16 } :Int</code></nobr>")
        );

        String rhsStrHover = generateHoverAt(provider, file, source.indexOf("\"alpha\"") + 2);
        assertTrue(
            "Expected inline MetadataMap preserved in Governing Schema on :defs RHS String literal:\n" + rhsStrHover,
            rhsStrHover.contains("<nobr><b>Governing Schema:</b> <code>{ #minSize 1 #maxSize 16 } :String</code></nobr>")
        );
    }

    public void testTarget4_InvalidFloatBitWidthPassAPassBParityQuickFixesAndContextAwareSizeHover() {
        String floatInvalidDoc = """
            {
              :defs {
                :InvalidFloatBitWidth { #size 16 } :Float
                :BoundedInvalidFloat { #size 16 #minIncl 0.0 #maxIncl 1.0 } :Float
                :ExactSafeInvalidFloat { #size 16 #minIncl 0.0 #maxExcl 1.0 } :Float
                :ParentFloat { #size 32 } :Float
                :DerivedFloat { #size 16 } :ParentFloat
                :IntBitWidth16 { #size 16 } :Int
              }
              :type :Int
              :body 0
            }
            """;

        // Pass A vs Pass B parity check
        myFixture.configureByText("stage3_float_size.stvn", floatInvalidDoc);
        List<HighlightInfo> passA = collectDiagnosticHighlights(myFixture.doHighlighting());
        assertNoDuplicateDiagnosticRanges("T4 Pass A", passA);

        LocalInspectionTool[] all26 = createAll26Inspections();
        myFixture.enableInspections(all26);
        try {
            PsiFile file = myFixture.configureByText("stage3_float_size.stvn", floatInvalidDoc);
            List<HighlightInfo> passB = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertNoDuplicateDiagnosticRanges("T4 Pass B", passB);
            assertEquals("Pass A and Pass B ERROR counts must match 1:1",
                passA.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count(),
                passB.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count());

            // Derived nominal alias :DerivedFloat must emit E040, NEVER E010
            int derivedSizeOffset = floatInvalidDoc.indexOf("#size 16 } :ParentFloat");
            assertTrue("Derived nominal :Float alias must remain governed by E040, not E010: " + passB,
                passB.stream().noneMatch(h -> h.getStartOffset() == derivedSizeOffset
                    && h.getDescription() != null && h.getDescription().contains("E010-FLOAT_INVALID_SIZE")));
            assertTrue("Derived nominal :Float alias must emit E040: " + passB,
                passB.stream().anyMatch(h -> h.getStartOffset() == derivedSizeOffset
                    && h.getDescription() != null && h.getDescription().contains("E040-ERR_NON_OVERRIDABLE_FACET")));

            // Verify 3-option remediation on standalone { #size 16 } :Float
            int standaloneOffset = floatInvalidDoc.indexOf("#size 16 } :Float") + 2;
            assertSingleIntentionWithPreview(standaloneOffset, "Change '#size 16' to '#size 32'");
            assertSingleIntentionWithPreview(standaloneOffset, "Use default 64-bit ':Float' (remove '#size 16')");
            assertSingleIntentionWithPreview(standaloneOffset, "Replace '#size 16' with '#exact'");

            // Verify #maxIncl suppresses 'Replace #size 16 with #exact'
            int maxInclGuardedOffset = floatInvalidDoc.indexOf("#size 16 #minIncl 0.0 #maxIncl 1.0") + 2;
            myFixture.getEditor().getCaretModel().moveToOffset(maxInclGuardedOffset);
            assertTrue("#maxIncl must suppress Replace '#size 16' with '#exact'",
                myFixture.filterAvailableIntentions("Replace '#size 16' with '#exact'").isEmpty());

            // Context-aware #size hover on :Float vs :Int
            var docProvider = new StvnDocumentationProvider();
            String floatSizeHover = generateHoverAt(docProvider, file, standaloneOffset);
            assertTrue("Expected IEEE-754 :Float #size 32 / default 64-bit guidance:\n" + floatSizeHover,
                floatSizeHover.contains("<code>#size 32</code>")
                    && floatSizeHover.contains("64-bit IEEE-754 double precision")
                    && floatSizeHover.contains("<code>#exact</code>"));
            assertFalse(":Float #size hover must not recommend illegal #size 16:\n" + floatSizeHover,
                floatSizeHover.contains("e.g. <code>#size 16</code>"));

            int intSizeOffset = floatInvalidDoc.indexOf("#size 16 } :Int") + 2;
            String intSizeHover = generateHoverAt(docProvider, file, intSizeOffset);
            assertTrue(":Int #size hover must preserve #size 16 / #size 64 examples:\n" + intSizeHover,
                intSizeHover.contains("<code>#size 16</code>") && intSizeHover.contains("<code>#size 64</code>"));

            // Apply 'Use default 64-bit :Float' -> excises empty {} with 0 E003 and 0 W003
            myFixture.configureByText("apply_default64.stvn", "{\n  :defs {\n    :F { #size 16 } :Float\n  }\n  :type :F\n  :body 1.5\n}\n");
            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("#size 16") + 2);
            myFixture.launchAction(myFixture.findSingleIntention("Use default 64-bit ':Float' (remove '#size 16')"));
            assertTrue(myFixture.getFile().getText().contains(":F :Float"));
            assertTrue("Post-fix buffer must have zero errors and zero W003 warnings",
                collectDiagnosticHighlights(myFixture.doHighlighting()).isEmpty());

            // Apply 'Replace #size 16 with #exact' -> places #exact at Tier-1 rank 1120 before #minIncl 0.0
            myFixture.configureByText(
                "apply_exact.stvn",
                "{\n  :defs {\n    :F { #size 16 #minIncl 0.0 #maxExcl 1.0 } :Float\n  }\n  :type :F\n  :body 0.5\n}\n"
            );
            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("#size 16") + 2);
            myFixture.launchAction(myFixture.findSingleIntention("Replace '#size 16' with '#exact'"));
            assertTrue(myFixture.getFile().getText().contains(":F { #exact #minIncl 0.0 #maxExcl 1.0 } :Float"));
            assertTrue("Post-fix buffer must have zero errors and zero warnings",
                collectDiagnosticHighlights(myFixture.doHighlighting()).isEmpty());
        } finally {
            myFixture.disableInspections(all26);
        }
    }

    public void testTarget5_FloatExactAndSizeMutexBidirectionalParityAndZeroWarningRemediation() {
        String mutexDoc = """
            {
              :defs {
                :ExactThenSize64 { #exact #size 64 } :Float
                :Size64ThenExact { #size 64 #exact } :Float
                :ExactThenSize32 { #exact #size 32 } :Float
                :Size32ThenExact { #size 32 #exact } :Float
                :ExactThenSize16 { #exact #size 16 } :Float
                :Size16ThenExact { #size 16 #exact } :Float
                :GuardedByMaxIncl { #exact #size 32 #maxIncl 10.0 } :Float
                :GuardedByMaxSize { #exact #size 32 #maxSize 12 } :Float
              }
              :type :Int
              :body 0
            }
            """;

        for (boolean enable26 : new boolean[]{false, true}) {
            LocalInspectionTool[] all26 = createAll26Inspections();
            if (enable26) {
                myFixture.enableInspections(all26);
            }
            try {
                myFixture.configureByText("stage3_float_mutex.stvn", mutexDoc);
                List<HighlightInfo> highlights = collectDiagnosticHighlights(myFixture.doHighlighting());
                assertNoDuplicateDiagnosticRanges(enable26 ? "T5 Pass B" : "T5 Pass A", highlights);
                assertEquals("Zero W003 warnings may fire on #exact/#size mutex traps: " + highlights, 0L,
                    highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());

                // Both #exact (1st) and #size 64 (2nd) in :ExactThenSize64 must expose identical 3-option suite
                int e64Exact = mutexDoc.indexOf("#exact #size 64") + 2;
                int e64Size = mutexDoc.indexOf("#size 64 } :Float") + 2;
                for (int off : new int[]{e64Exact, e64Size}) {
                    assertSingleIntentionWithPreview(off, "Remove conflicting facet '#size'");
                    assertSingleIntentionWithPreview(off, "Use default 64-bit ':Float' (remove '#exact' and '#size 64')");
                    assertSingleIntentionWithPreview(off, "Replace with '#size 32' (remove '#exact')");
                }

                // Both #size 32 (1st) and #exact (2nd) in :Size32ThenExact must expose bidirectional removals
                int s32Size = mutexDoc.indexOf("#size 32 #exact") + 2;
                int s32Exact = mutexDoc.indexOf("#exact } :Float\n    :ExactThenSize16") + 2;
                for (int off : new int[]{s32Size, s32Exact}) {
                    assertSingleIntentionWithPreview(off, "Remove conflicting facet '#size'");
                    assertSingleIntentionWithPreview(off, "Remove conflicting facet '#exact'");
                }

                // Both #size 16 (1st, carrying E010) and #exact (2nd, carrying E008) in :Size16ThenExact must deduplicate to count == 1
                int s16Size = mutexDoc.indexOf("#size 16 #exact") + 2;
                int s16Exact = mutexDoc.indexOf("#exact } :Float\n    :GuardedByMaxIncl") + 2;
                for (int off : new int[]{s16Size, s16Exact}) {
                    assertSingleIntentionWithPreview(off, "Remove conflicting facet '#size'");
                    assertSingleIntentionWithPreview(off, "Replace with '#size 32' (remove '#exact')");
                    assertSingleIntentionWithPreview(off, "Use default 64-bit ':Float' (remove '#exact' and '#size 16')");
                }

                // Sibling guards: #maxIncl suppresses 'Remove conflicting facet #size'; #maxSize suppresses continuous options
                myFixture.getEditor().getCaretModel().moveToOffset(mutexDoc.indexOf("#size 32 #maxIncl 10.0") + 2);
                assertTrue(myFixture.filterAvailableIntentions("Remove conflicting facet '#size'").isEmpty());
                assertEquals(1, myFixture.filterAvailableIntentions("Remove conflicting facet '#exact'").size());

                myFixture.getEditor().getCaretModel().moveToOffset(mutexDoc.indexOf("#size 32 #maxSize 12") + 2);
                assertEquals(1, myFixture.filterAvailableIntentions("Remove conflicting facet '#size'").size());
                assertTrue(myFixture.filterAvailableIntentions("Remove conflicting facet '#exact'").isEmpty());
            } finally {
                if (enable26) {
                    myFixture.disableInspections(all26);
                }
            }
        }

        // Verify applying each mutex quick-fix on { #exact #size 64 } :Float leaves 0 errors and 0 W003 warnings
        LocalInspectionTool[] all26 = createAll26Inspections();
        myFixture.enableInspections(all26);
        try {
            String singleTrap = "{\n  :defs {\n    :M { #exact #size 64 } :Float\n  }\n  :type :M\n  :body 1.25\n}\n";

            myFixture.configureByText("mutex_to_default64.stvn", singleTrap);
            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(singleTrap.indexOf("#exact") + 2);
            myFixture.launchAction(myFixture.findSingleIntention(
                "Use default 64-bit ':Float' (remove '#exact' and '#size 64')"
            ));
            assertTrue(myFixture.getFile().getText().contains(":M :Float"));
            assertTrue("Default 64-bit resolution must leave 0 errors and 0 W003 warnings",
                collectDiagnosticHighlights(myFixture.doHighlighting()).isEmpty());

            myFixture.configureByText("mutex_to_size32.stvn", singleTrap);
            myFixture.doHighlighting();
            myFixture.getEditor().getCaretModel().moveToOffset(singleTrap.indexOf("#size 64") + 2);
            myFixture.launchAction(myFixture.findSingleIntention("Replace with '#size 32' (remove '#exact')"));
            assertTrue(myFixture.getFile().getText().contains(":M { #size 32 } :Float"));
            assertTrue("32-bit resolution must leave 0 errors and 0 warnings",
                collectDiagnosticHighlights(myFixture.doHighlighting()).isEmpty());
        } finally {
            myFixture.disableInspections(all26);
        }
    }

    public void testMirroredTrackASharedFixturesPassAPassBParity() throws IOException {
        String[] fixtureRelPaths = {
            "syntax/invalid/metadata/vial_custody_manifest.cryo_biobank.stvn",
            "syntax/invalid/metadata/reserved_domain_with_mismatched_name_isolation.cryo_biobank.stvn",
            "syntax/invalid/metadata/meta_invalid_posix_no_stem_warning_1stem.stvn",
            "syntax/invalid/metadata/meta_invalid_posix_no_stem_warning_2stem.cryo_biobank.stvn",
            "metadata/invalid/domain_mismatch/inv_mismatch_float_invalid_size.stvn",
            "syntax/invalid/bounds/float_invalid_size_and_exact_size_mutex_both_orders.stvn"
        };

        for (String rel : fixtureRelPaths) {
            Path path = SHARED_FIXTURES_ROOT.resolve(rel);
            assertTrue("Mirrored Track A fixture must exist: " + path, Files.isRegularFile(path));
            String src = Files.readString(path, StandardCharsets.UTF_8);
            String fileName = path.getFileName().toString();

            myFixture.configureByText(fileName, src);
            List<HighlightInfo> passA = collectDiagnosticHighlights(myFixture.doHighlighting());
            assertNoDuplicateDiagnosticRanges("Mirrored Pass A (" + fileName + ")", passA);

            LocalInspectionTool[] all26 = createAll26Inspections();
            myFixture.enableInspections(all26);
            try {
                myFixture.configureByText(fileName, src);
                List<HighlightInfo> passB = collectDiagnosticHighlights(myFixture.doHighlighting());
                assertNoDuplicateDiagnosticRanges("Mirrored Pass B (" + fileName + ")", passB);
                assertEquals("Pass A vs Pass B ERROR count mismatch on " + fileName,
                    passA.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count(),
                    passB.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count());
                assertEquals("Pass A vs Pass B WARNING count mismatch on " + fileName,
                    passA.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count(),
                    passB.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());
            } finally {
                myFixture.disableInspections(all26);
            }
        }
    }
}
