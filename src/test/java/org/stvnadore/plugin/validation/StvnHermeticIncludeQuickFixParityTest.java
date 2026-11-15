package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.plugin.validation.quickfix.StvnConvertToModularQuickFix;

import java.util.List;

/**
 * End-to-end platform test suite verifying hermetic {@code :include} ({@code E005}) quick-fix parity,
 * 1:1 Core vs. local-inspection span and STE prose alignment, {@code PriorityAction} ordering,
 * sibling/comment preservation, sole-element {@code :defs} handling across {@code .stvn} and {@code .stvn_d},
 * {@code Rule STR-STEM} {@code :meta} synthesis during modular conversion, {@code IntentionPreviewInfo} safety,
 * target filename collision suppression, and binary extension suppression.
 */
@NullMarked
public final class StvnHermeticIncludeQuickFixParityTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnFlatDocumentIncludeInspection());
    }

    public void testZeroVsAll26InspectionsE005SpanAndProseParity() {
        String stvnSource = """
            {
              :meta {
                #name "parity_payload"
                #kind #BODY
              }
              :defs {
                :include [ "avionics_bus_shared.avionics.stvn_d" ]
                :PortId { #unsigned #size 16 #minIncl 1 } :Int
              }
              :type :PortId
              :body 443
            }
            """;

        myFixture.disableInspections(new StvnFlatDocumentIncludeInspection());
        myFixture.configureByText("parity_payload.stvn", stvnSource);
        List<HighlightInfo> coreE005 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E005"))
            .toList();
        assertEquals("Expected 1 Core E005 highlight with 0 inspections enabled", 1, coreE005.size());

        InspectionProfileEntry[] all26 = instantiateAll26PluginInspections();
        myFixture.enableInspections(all26);
        myFixture.configureByText("parity_payload.stvn", stvnSource);
        List<HighlightInfo> pluginE005 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E005"))
            .toList();
        assertEquals("Expected 1 Plugin E005 highlight with 26 inspections enabled (zero stacked duplicates)", 1, pluginE005.size());

        HighlightInfo c = coreE005.get(0);
        HighlightInfo p = pluginE005.get(0);
        assertEquals("1:1 startOffset parity required", c.getStartOffset(), p.getStartOffset());
        assertEquals("1:1 endOffset parity required", c.getEndOffset(), p.getEndOffset());
        assertTrue(c.getDescription().contains(
            "Hermetic document or schema (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot contain include statements: parity_payload.stvn"
                + " [stvn-core:E005-ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT]"));
        assertTrue(p.getDescription().contains(
            "Hermetic document or schema (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot contain include statements: parity_payload.stvn"
                + " [stvn-plugin:E005-ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT]"));
    }

    public void testPriorityOrderingAndGauntletV8Stage1BothBeforeAndAfterSectionReorder() {
        var directRemoveFix = new StvnFlatDocumentIncludeInspection.RemoveIncludeStatementQuickFix(
            "avionics_bus_shared.avionics.stvn_d"
        );
        assertEquals(
            "RemoveIncludeStatementQuickFix must carry Priority.HIGH",
            PriorityAction.Priority.HIGH,
            directRemoveFix.getPriority()
        );

        String outOfOrderSource = """
            {
              :defs {
                // Header comment before include
                :include [ "avionics_bus_shared.avionics.stvn_d" ]
                // Comment between include and sibling declarations
                :BusChannel { #unsigned #size 8 } :Int
                :PortId { #unsigned #size 16 #minIncl 1 } :Int
                #ROUTING_BANNER :String "AVIONICS_BUS_V8"
              }
              :meta {
                #name "gauntlet_v8_stage1_envelope_meta_and_section_order"
                #kind #BODY
              }
              :type :Tuple(:BusChannel :PortId :String)
              :body (4 8080 #ROUTING_BANNER)
            }
            """;

        var psiFile = myFixture.addFileToProject(
            "gauntlet_v8_stage1_envelope_meta_and_section_order.stvn",
            outOfOrderSource
        );
        myFixture.configureFromExistingVirtualFile(psiFile.getVirtualFile());

        var directConvertFix = new StvnConvertToModularQuickFix(myFixture.getFile(), false);
        assertEquals(
            "StvnConvertToModularQuickFix must carry Priority.LOW",
            PriorityAction.Priority.LOW,
            directConvertFix.getPriority()
        );

        myFixture.getEditor().getCaretModel().moveToOffset(
            outOfOrderSource.indexOf(":include [ \"avionics_bus_shared.avionics.stvn_d\" ]") + 4
        );
        myFixture.doHighlighting();

        String removeLabel = "Remove :include directive for 'avionics_bus_shared.avionics.stvn_d'";
        String convertLabel = "Convert document to modular format (.stvn_i)";
        List<String> availableLabels = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();
        IntentionAction removeFix = myFixture.findSingleIntention(removeLabel);
        IntentionAction convertFix = myFixture.findSingleIntention(convertLabel);
        assertNotNull(removeFix);
        assertNotNull(convertFix);
        int removeIndex = availableLabels.indexOf(removeLabel);
        int convertIndex = availableLabels.indexOf(convertLabel);
        assertTrue(
            "Primary RemoveIncludeStatementQuickFix (Priority.HIGH) must precede secondary StvnConvertToModularQuickFix (Priority.LOW): "
                + availableLabels,
            removeIndex >= 0 && convertIndex > removeIndex
        );

        String previewRemove = myFixture.getIntentionPreviewText(removeFix);
        assertNotNull("RemoveIncludeStatementQuickFix must produce live diff preview", previewRemove);
        assertFalse(previewRemove.contains(":include"));
        assertTrue(previewRemove.contains("// Header comment before include"));
        assertTrue(previewRemove.contains("// Comment between include and sibling declarations"));

        String previewConvert = myFixture.getIntentionPreviewText(convertFix);
        assertNotNull("StvnConvertToModularQuickFix must produce CustomDiff preview without VFS mutation", previewConvert);
        assertTrue(previewConvert.contains("#kind #BODY_INCLUDE"));
        assertEquals(
            "Hover preview must never rename physical VirtualFile",
            "gauntlet_v8_stage1_envelope_meta_and_section_order.stvn",
            myFixture.getFile().getVirtualFile().getName()
        );

        myFixture.launchAction(removeFix);
        String afterRemove = myFixture.getFile().getText();
        assertFalse(afterRemove.contains(":include"));
        assertTrue(afterRemove.contains("// Header comment before include"));
        assertTrue(afterRemove.contains("// Comment between include and sibling declarations"));
        assertTrue(afterRemove.contains(":BusChannel { #unsigned #size 8 } :Int"));
        assertTrue(afterRemove.contains(":PortId { #unsigned #size 16 #minIncl 1 } :Int"));
        assertTrue(afterRemove.contains("#ROUTING_BANNER :String \"AVIONICS_BUS_V8\""));

        // Verify canonical :meta -> :defs -> :type -> :body order offers identical quick-fixes and preserves same-line // comment
        String canonicalOrderSource = """
            {
              :meta {
                #name "canonical_stage1"
                #kind #BODY
              }
              :defs {
                :include [ "avionics_bus_shared.avionics.stvn_d" ]  // keep trailing note
                :BusChannel { #unsigned #size 8 } :Int
              }
              :type :BusChannel
              :body 4
            }
            """;
        var canonicalFile = myFixture.addFileToProject("canonical_stage1.stvn", canonicalOrderSource);
        myFixture.configureFromExistingVirtualFile(canonicalFile.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(canonicalOrderSource.indexOf(":include") + 3);
        myFixture.doHighlighting();

        IntentionAction removeWithSameLineComment = myFixture.findSingleIntention(
            "Remove :include directive for 'avionics_bus_shared.avionics.stvn_d'"
        );
        myFixture.launchAction(removeWithSameLineComment);
        String afterSameLineRemove = myFixture.getFile().getText();
        assertFalse(afterSameLineRemove.contains(":include"));
        assertTrue("Same-line trailing // comment must remain intact at 4-space indent:\n" + afterSameLineRemove,
            afterSameLineRemove.contains("    // keep trailing note\n"));
    }

    public void testMultiTargetIncludeUsesGenericRemovalLabel() {
        String multiIncludeSource = """
            {
              :meta {
                #name "multi_include"
                #kind #BODY
              }
              :defs {
                :include [ "alpha.stvn_d" "beta.stvn_d" ]
                :LocalId :Int
              }
              :type :LocalId
              :body 5
            }
            """;
        myFixture.configureByText("multi_include.stvn", multiIncludeSource);
        myFixture.getEditor().getCaretModel().moveToOffset(multiIncludeSource.indexOf(":include") + 3);
        myFixture.doHighlighting();

        IntentionAction genericRemove = myFixture.findSingleIntention("Remove :include directive");
        myFixture.launchAction(genericRemove);
        assertFalse(myFixture.getFile().getText().contains(":include"));
        assertTrue(myFixture.getFile().getText().contains(":LocalId :Int"));
    }

    public void testSoleElementIncludeAndUseRemovalInBodyPayloadVsDefsSchema() {
        // 1. Sole :include in .stvn (#BODY) omits :defs cleanly so zero E002 ERR_EMPTY_DEFS_BLOCK occurs
        String solePayloadInclude = """
            {
              :meta {
                #name "sole_payload"
                #kind #BODY
              }
              :defs {
                :include [ "shared.stvn_d" ]
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("sole_payload.stvn", solePayloadInclude);
        myFixture.getEditor().getCaretModel().moveToOffset(solePayloadInclude.indexOf(":include") + 2);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Remove :include directive for 'shared.stvn_d'"));

        String cleanedPayload = myFixture.getFile().getText();
        assertFalse("Sole :include in #BODY document must remove empty :defs section:\n" + cleanedPayload,
            cleanedPayload.contains(":defs"));
        var compileRes = StvnCompiler.compileToResult(cleanedPayload, "sole_payload.stvn");
        assertTrue("Payload document after sole :include removal must compile with zero errors: " + compileRes.diagnostics(),
            compileRes.isSuccess());
        assertEquals("Formatted output must match StvnSourceFormatter fixed point",
            StvnSourceFormatter.format(cleanedPayload).trim(), cleanedPayload.trim());

        // 2. Sole :use in .stvn (#BODY) likewise omits empty :defs cleanly
        String solePayloadUse = """
            {
              :meta {
                #name "sole_use_payload"
                #kind #BODY
              }
              :defs {
                :use [ :external/pkg { #strip } ]
              }
              :type :Int
              :body 9
            }
            """;
        myFixture.configureByText("sole_use_payload.stvn", solePayloadUse);
        myFixture.getEditor().getCaretModel().moveToOffset(solePayloadUse.indexOf(":external/pkg") + 2);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Remove :use directive for ':external/pkg'"));
        assertTrue("Sole :use removal in #BODY document must compile cleanly",
            StvnCompiler.compileToResult(myFixture.getFile().getText(), "sole_use_payload.stvn").isSuccess());

        // 3. Sole :include in .stvn_d (#DEFS) retains mandatory :defs enclosure
        String soleSchemaInclude = """
            {
              :meta {
                #name "sole_schema"
                #kind #DEFS
              }
              :defs {
                :include [ "shared.stvn_d" ]
              }
            }
            """;
        myFixture.configureByText("sole_schema.stvn_d", soleSchemaInclude);
        myFixture.getEditor().getCaretModel().moveToOffset(soleSchemaInclude.indexOf(":include") + 2);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Remove :include directive for 'shared.stvn_d'"));
        String cleanedSchema = myFixture.getFile().getText();
        assertTrue("Mandatory :defs enclosure must remain in .stvn_d:\n" + cleanedSchema,
            cleanedSchema.contains("  :defs {\n  }\n"));
        assertFalse(cleanedSchema.contains(":include"));
    }

    public void testConvertToModularSynthesizesAndUpdatesMetaAcrossAllHermeticShapes() {
        myFixture.addFileToProject("shared_types.avionics.stvn_d", """
            {
              :meta {
                #name "shared_types"
                #domain "avionics"
                #kind #DEFS
              }
              :defs {
                :AltitudeFt :Int
              }
            }
            """);

        // Shape 1: 1-stem .stvn WITHOUT :meta -> synthesizes :meta { #name "<name>" #kind #BODY_INCLUDE }
        var metaLessOneStem = myFixture.addFileToProject("metaless_one_stem.stvn", """
            {
              :defs {
                :include [ "shared_types.avionics.stvn_d" ]
              }
              :type :AltitudeFt
              :body 35000
            }
            """);
        myFixture.configureFromExistingVirtualFile(metaLessOneStem.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":include") + 3);
        myFixture.doHighlighting();
        IntentionAction convertOneStem = myFixture.findSingleIntention("Convert document to modular format (.stvn_i)");
        String previewOneStem = myFixture.getIntentionPreviewText(convertOneStem);
        assertTrue("Preview must synthesize 1-stem :meta without #domain:\n" + previewOneStem,
            previewOneStem.contains("#name \"metaless_one_stem\"")
                && previewOneStem.contains("#kind #BODY_INCLUDE")
                && !previewOneStem.contains("#domain"));
        myFixture.launchAction(convertOneStem);
        assertEquals("metaless_one_stem.stvn_i", myFixture.getFile().getVirtualFile().getName());
        String afterOneStem = myFixture.getFile().getText();
        assertEquals("Synthesized 1-stem .stvn_i must match StvnSourceFormatter fixed point",
            StvnSourceFormatter.format(afterOneStem).trim(), afterOneStem.trim());

        // Shape 2: 2-stem .stvn WITHOUT :meta -> synthesizes #name, #domain, and #kind #BODY_INCLUDE
        var metaLessTwoStem = myFixture.addFileToProject("flight_deck.avionics.stvn", """
            {
              :defs {
                :include [ "shared_types.avionics.stvn_d" ]
              }
              :type :AltitudeFt
              :body 12000
            }
            """);
        myFixture.configureFromExistingVirtualFile(metaLessTwoStem.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":include") + 3);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Convert document to modular format (.stvn_i)"));
        assertEquals("flight_deck.avionics.stvn_i", myFixture.getFile().getVirtualFile().getName());
        String afterTwoStem = myFixture.getFile().getText();
        assertTrue(afterTwoStem.contains("#name \"flight_deck\""));
        assertTrue(afterTwoStem.contains("#domain \"avionics\""));
        assertTrue(afterTwoStem.contains("#kind #BODY_INCLUDE"));
        assertEquals(StvnSourceFormatter.format(afterTwoStem).trim(), afterTwoStem.trim());

        // Shape 3: .stvn WITH out-of-order :meta omitting #kind and reversed facets -> inserts #kind #BODY_INCLUDE
        var missingKindReversed = myFixture.addFileToProject("reversed_meta.avionics.stvn", """
            {
              :defs {
                :include [ "shared_types.avionics.stvn_d" ]
              }
              :meta {
                #domain "avionics"
                #name "reversed_meta"
              }
              :type :AltitudeFt
              :body 100
            }
            """);
        myFixture.configureFromExistingVirtualFile(missingKindReversed.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":include") + 3);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Convert document to modular format (.stvn_i)"));
        assertEquals("reversed_meta.avionics.stvn_i", myFixture.getFile().getVirtualFile().getName());
        assertTrue("Omitted #kind must be inserted into out-of-order :meta:\n" + myFixture.getFile().getText(),
            myFixture.getFile().getText().contains("    #kind #BODY_INCLUDE\n  }"));

        // Shape 4: 2-stem .stvn_d -> .stvn_di with #kind #DEFS_INCLUDE
        var hermeticSchema = myFixture.addFileToProject("bus_frame.avionics.stvn_d", """
            {
              :meta {
                #name "bus_frame"
                #domain "avionics"
                #kind #DEFS
              }
              :defs {
                :include [ "shared_types.avionics.stvn_d" ]
                :FrameAltitude :AltitudeFt
              }
            }
            """);
        myFixture.configureFromExistingVirtualFile(hermeticSchema.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":include") + 3);
        myFixture.doHighlighting();
        IntentionAction convertSchema = myFixture.findSingleIntention("Convert schema to modular format (.stvn_di)");
        String schemaPreviewText = myFixture.getIntentionPreviewText(convertSchema);
        assertTrue(schemaPreviewText.contains("#kind #DEFS_INCLUDE"));
        IntentionPreviewInfo schemaPreview = convertSchema.generatePreview(
            getProject(), myFixture.getEditor(), myFixture.getFile()
        );
        assertNotSame(IntentionPreviewInfo.EMPTY, schemaPreview);
        assertEquals("Preview must never rename physical VirtualFile",
            "bus_frame.avionics.stvn_d", myFixture.getFile().getVirtualFile().getName());
        myFixture.launchAction(convertSchema);
        assertEquals("bus_frame.avionics.stvn_di", myFixture.getFile().getVirtualFile().getName());
        assertTrue(myFixture.getFile().getText().contains("#kind #DEFS_INCLUDE"));
    }

    public void testCollisionGuardAndBinaryExtensionSuppression() {
        // 1. Existing target .stvn_i in same folder suppresses StvnConvertToModularQuickFix while keeping Remove fix
        myFixture.addFileToProject("colliding_unit.stvn_i", """
            {
              :meta {
                #name "colliding_unit"
                #kind #BODY_INCLUDE
              }
              :type :Int
              :body 1
            }
            """);
        var collidingHermetic = myFixture.addFileToProject("colliding_unit.stvn", """
            {
              :meta {
                #name "colliding_unit"
                #kind #BODY
              }
              :defs {
                :include [ "other.stvn_d" ]
              }
              :type :Int
              :body 2
            }
            """);
        myFixture.configureFromExistingVirtualFile(collidingHermetic.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":include") + 3);
        myFixture.doHighlighting();
        assertEquals("Removal quick-fix must still be offered when target .stvn_i collides",
            1, myFixture.filterAvailableIntentions("Remove :include directive for 'other.stvn_d'").size());
        assertTrue("Modular conversion must be suppressed when colliding_unit.stvn_i already exists",
            myFixture.filterAvailableIntentions("Convert document to modular format (.stvn_i)").isEmpty());

        // 2. Binary extensions (.stvn_b / .stvn_bd) are rejected by computeTargetFileName
        assertNull(StvnConvertToModularQuickFix.computeTargetFileName("payload.stvn_b"));
        assertNull(StvnConvertToModularQuickFix.computeTargetFileName("schema.stvn_bd"));
        assertNull(StvnConvertToModularQuickFix.computeTargetFileName("already_mod.stvn_i"));
        assertNull(StvnConvertToModularQuickFix.computeTargetFileName("already_mod.stvn_di"));
    }

    private static InspectionProfileEntry[] instantiateAll26PluginInspections() {
        return new InspectionProfileEntry[] {
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
}
