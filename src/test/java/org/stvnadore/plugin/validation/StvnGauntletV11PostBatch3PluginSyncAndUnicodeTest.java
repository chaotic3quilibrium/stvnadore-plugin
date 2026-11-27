package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionActionDelegate;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.browser.StvnNamespaceScope;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolCollector;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolEntry;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.settings.StvnConfigurable;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveDuplicateUseAliasQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveDuplicateUseEntryQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveIllegalIncludeModifierQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnReplaceStripWithExplicitAliasesQuickFix;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.ValueKeyword;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

/**
 * Comprehensive IntelliJ Platform integration test suite verifying Gauntlet v11 Post-Batch 3
 * plugin synchronization across Targets 1 through 5:
 * <ul>
 *   <li>Target 1: Coordinate-pinned {@code E006-NAMESPACE_COLLISION} parity across {@code Pass A}
 *       and {@code Pass B} ({@code count == 1}, {@code 0} {@code [0, EOF)} spans) and surgical
 *       {@code Replace '#strip' with explicit symbol aliases} quick-fix with {@code IntentionPreviewInfo.DIFF}.</li>
 *   <li>Target 2: Same-scope duplicate {@code :use} ({@code E005-DUPLICATE_MODULE_IMPORT}) parity,
 *       surgical removal quick-fixes, {@code 0} cascading {@code E030} errors, and 1-to-many VOP
 *       nominal rebranding across PSI references and {@code Ctrl+Alt+N} ({@code :defs}, {@code :type}, {@code :body}).</li>
 *   <li>Target 3: Symmetric 4-slot multi-line {@code MapEntry} formatting across {@code Ctrl+Alt+L},
 *       PSI fallback formatting, container layout intentions, and escaped-to-fenced conversions.</li>
 *   <li>Target 4: Surgical {@code Remove illegal modifier from ':include'} quick-fix on {@code E002}
 *       across {@code Pass A} and {@code Pass B}.</li>
 *   <li>Target 5: Symmetric {@code \u005CuXXXX} escape/unescape intentions ({@code Priority.NORMAL},
 *       {@code IntentionPreviewInfo.DIFF}) and {@code StvnConfigurable} settings toggle round-trip.</li>
 * </ul>
 */
@NullMarked
public final class StvnGauntletV11PostBatch3PluginSyncAndUnicodeTest extends BasePlatformTestCase {

    private static final String ESCAPE_INTENTION_TEXT = "Escape non-ASCII characters as \\uXXXX";
    private static final String UNESCAPE_INTENTION_TEXT = "Unescape \\uXXXX sequences to UTF-8";
    private static final String PLAIN_TO_FENCED_TEXT = "Convert string literal to fenced string block";
    private static final String ESCAPED_TO_FENCED_TEXT = "Convert escaped string to fenced string";
    private static final String FENCED_TO_SIMPLE_TEXT = "Convert fenced string to escaped string";
    private static final String EXPAND_CONTAINER_TEXT = "Expand container to multiple lines";
    private static final String COLLAPSE_CONTAINER_TEXT = "Collapse container to single line";

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
            new StvnMapCapacityInspection(),
            new StvnSeqCapacityInspection(),
            new StvnSetCapacityInspection(),
            new StvnStringCapacityInspection(),
            new StvnExcessiveExactFloatPrecisionInspection(),
            new StvnFloatPrecisionLossInspection(),
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
            new StvnStringEscapeScrutinyInspection()
        );
    }

    public void testTarget1_CoordinatePinnedE006ParityAndReplaceStripWithExplicitAliasesQuickFix() {
        String stage8StripCollision = """
            {
              :meta {
                #name   "stage8_strip_collision"
                #domain "subsea"
                #kind   #BODY
              }
              :defs {
                :package :subsea/pump/primary {
                  :LaserDriveMa { #unsigned #size 16 } :Int
                  :PumpMode     :Enum[#IDLE #BOOST]
                }
                :package :subsea/pump/raman {
                  :LaserDriveMa { #unsigned #size 16 } :Int
                  :PumpMode     :Enum[#IDLE #BOOST]
                  #BOOST_MA     :LaserDriveMa 450
                }
                :use [
                  :subsea/pump/primary { #strip }
                  :subsea/pump/raman { #strip }
                ]
              }
              :type :Int
              :body 300
            }
            """;

        // 1. Standalone LocalInspectionTool (without StvnExternalAnnotator)
        PsiFile standaloneFile = myFixture.configureByText("stage8_strip_collision.subsea.stvn", stage8StripCollision);
        var wrapper = new LocalInspectionToolWrapper(new StvnFlatDocumentIncludeInspection());
        var problemDescriptors = InspectionEngine.runInspectionOnFile(
            standaloneFile,
            wrapper,
            InspectionManager.getInstance(getProject()).createNewGlobalContext()
        );
        assertEquals("Standalone StvnFlatDocumentIncludeInspection must emit exactly 1 E006 problem",
            1, problemDescriptors.size());
        String expectedCollisionDesc =
            "Namespace collision(s) in ':use': [:LaserDriveMa, :PumpMode]; replace '#strip' with explicit symbol aliases or rename colliding ':use' aliases [stvn-core:E006-NAMESPACE_COLLISION]";
        assertEquals(expectedCollisionDesc, problemDescriptors.get(0).getDescriptionTemplate());

        // 2. Pass A (0-inspection StvnExternalAnnotator): exact span, count == 1, DIFF preview
        List<HighlightInfo> passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit strictly 1 ERROR: " + passAErrors, 1, passAErrors.size());
        int expectedRamanStart = stage8StripCollision.indexOf(":subsea/pump/raman { #strip }");
        int expectedRamanEnd = expectedRamanStart + ":subsea/pump/raman { #strip }".length();
        assertEquals(expectedRamanStart, passAErrors.get(0).getStartOffset());
        assertEquals(expectedRamanEnd, passAErrors.get(0).getEndOffset());
        assertEquals(expectedCollisionDesc, passAErrors.get(0).getDescription());

        myFixture.getEditor().getCaretModel().moveToOffset(expectedRamanStart + 5);
        IntentionAction passAFix = myFixture.findSingleIntention(StvnReplaceStripWithExplicitAliasesQuickFix.QUICK_FIX_NAME);
        String passAPreview = myFixture.getIntentionPreviewText(passAFix);
        assertNotNull("Expected IntentionPreviewInfo.DIFF in Pass A", passAPreview);
        assertTrue(passAPreview.contains(":LaserDriveMa :RamanLaserDriveMa"));
        assertTrue(passAPreview.contains(":PumpMode     :RamanPumpMode"));
        assertTrue("Non-colliding #BOOST_MA must retain identity alias #BOOST_MA #BOOST_MA:\n" + passAPreview,
            passAPreview.contains("#BOOST_MA     #BOOST_MA"));

        // 3. Pass B (all 27 inspections + StvnExternalAnnotator deduplication): count == 1 + live quick-fix execution
        enableAll27StvnInspections();
        myFixture.configureByText("stage8_strip_collision.subsea.stvn", stage8StripCollision);
        List<HighlightInfo> passBErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit strictly 1 deduplicated ERROR: " + passBErrors, 1, passBErrors.size());
        assertEquals(expectedRamanStart, passBErrors.get(0).getStartOffset());
        assertEquals(expectedRamanEnd, passBErrors.get(0).getEndOffset());
        assertEquals(expectedCollisionDesc, passBErrors.get(0).getDescription());

        myFixture.getEditor().getCaretModel().moveToOffset(expectedRamanStart + 5);
        List<IntentionAction> passBFixes = myFixture.filterAvailableIntentions(
            StvnReplaceStripWithExplicitAliasesQuickFix.QUICK_FIX_NAME
        );
        assertEquals("Pass B must attach strictly 1 Replace '#strip' quick-fix", 1, passBFixes.size());
        myFixture.launchAction(passBFixes.get(0));

        String healedText = myFixture.getEditor().getDocument().getText();
        assertFalse(
            "Healed document must compile with 0 errors in StvnCompiler:\n" + healedText,
            StvnCompiler.compileToResult(healedText, "stage8_strip_collision.subsea.stvn").hasErrors()
        );
        List<HighlightInfo> postFixErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Expected 0 remaining ERROR highlights after quick-fix: " + postFixErrors, postFixErrors.isEmpty());

        // 4. Explicit UseMapAlias RHS collision + unpackaged :include collision parity
        myFixture.addFileToProject(
            "raw_mod_one.stvn_d",
            "{\n  :meta { #name \"raw_mod_one\" #kind #DEFS }\n  :defs { :RawShared :Int }\n}\n"
        );
        myFixture.addFileToProject(
            "raw_mod_two.stvn_d",
            "{\n  :meta { #name \"raw_mod_two\" #kind #DEFS }\n  :defs { :RawShared :String }\n}\n"
        );
        String rhsAndIncCollision = """
            {
              :meta {
                #name "rhs_inc_col"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [
                  "./raw_mod_one.stvn_d"
                  "./raw_mod_two.stvn_d"
                ]
                :package :pkg/r { :Remote :Int }
                :LocalTaken :Int
                :use [ :pkg/r { :Remote :LocalTaken } ]
              }
              :type :Int
              :body 1
            }
            """;
        myFixture.configureByText("rhs_inc_col.stvn_i", rhsAndIncCollision);
        List<HighlightInfo> rhsIncErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected 2 coordinate-pinned E006 errors (1 on ./raw_mod_two.stvn_d, 1 on :LocalTaken RHS): "
            + rhsIncErrors, 2, rhsIncErrors.size());
        int incLitStart = rhsAndIncCollision.indexOf("\"./raw_mod_two.stvn_d\"");
        assertEquals(incLitStart, rhsIncErrors.get(0).getStartOffset());
        int rhsStart = rhsAndIncCollision.lastIndexOf(":LocalTaken");
        assertEquals(rhsStart, rhsIncErrors.get(1).getStartOffset());
    }

    public void testTarget2_DuplicateUseE005ParityRemovalQuickFixesAndOneToManyVopRebrandingPsiAndBrowser() {
        String dupUseDoc = """
            {
              :meta {
                #name   "dup_use_probe"
                #domain "subsea"
                #kind   #BODY
              }
              :defs {
                :package :subsea/pump/primary {
                  :LaserDriveMa { #unsigned #size 16 } :Int
                  :PumpMode     :Enum[#IDLE #BOOST]
                  #MAX_MA       :LaserDriveMa 500
                }
                :use [
                  :subsea/pump/primary { #strip }
                  :subsea/pump/primary { #strip }
                ]
                :package :subsea/consumer {
                  :use [
                    :subsea/pump/primary {
                      :LaserDriveMa :DriveAlias
                      :subsea/pump/primary/LaserDriveMa :DriveAlias
                    }
                  ]
                  :ConsumerSlot :DriveAlias
                }
              }
              :type :Tuple(:LaserDriveMa :subsea/consumer/ConsumerSlot)
              :body (#MAX_MA 200)
            }
            """;

        // Pass A (0-inspection) -> verify 2 E005 errors and 0 cascading E030 errors
        myFixture.configureByText("dup_use_probe.subsea.stvn", dupUseDoc);
        List<HighlightInfo> passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit strictly 2 E005 errors and 0 cascading E030: " + passAErrors, 2, passAErrors.size());

        // Pass B (27-inspection) -> verify 1:1 count == 1 parity and execute both removal quick-fixes
        enableAll27StvnInspections();
        myFixture.configureByText("dup_use_probe.subsea.stvn", dupUseDoc);
        List<HighlightInfo> passBErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit strictly 2 deduplicated E005 errors (0 cascading E030): " + passBErrors,
            2, passBErrors.size());

        int secondEntryStart = dupUseDoc.indexOf(
            ":subsea/pump/primary { #strip }",
            dupUseDoc.indexOf(":subsea/pump/primary { #strip }") + 1
        );
        myFixture.getEditor().getCaretModel().moveToOffset(secondEntryStart + 4);
        IntentionAction removeEntryFix = myFixture.findSingleIntention(StvnRemoveDuplicateUseEntryQuickFix.QUICK_FIX_NAME);
        String entryPreview = myFixture.getIntentionPreviewText(removeEntryFix);
        assertNotNull(entryPreview);
        myFixture.launchAction(removeEntryFix);

        int dupAliasStart = myFixture.getFile().getText().indexOf(":subsea/pump/primary/LaserDriveMa :DriveAlias");
        myFixture.getEditor().getCaretModel().moveToOffset(dupAliasStart + 4);
        IntentionAction removeAliasFix = myFixture.findSingleIntention(StvnRemoveDuplicateUseAliasQuickFix.QUICK_FIX_NAME);
        String aliasPreview = myFixture.getIntentionPreviewText(removeAliasFix);
        assertNotNull(aliasPreview);
        myFixture.launchAction(removeAliasFix);

        String cleanedDupDoc = myFixture.getEditor().getDocument().getText();
        assertFalse("Cleaned document must leave zero orphan blank lines inside :use:\n" + cleanedDupDoc,
            cleanedDupDoc.contains("\n\n                ]"));
        assertFalse(StvnCompiler.compileToResult(cleanedDupDoc, "dup_use_probe.subsea.stvn").hasErrors());

        // Case 2B-2: 1-to-Many VOP Nominal Rebranding across PSI References & Ctrl+Alt+N (:defs, :type, :body)
        String vopOneToManyDoc = """
            {
              :meta {
                #name   "vop_rebrand"
                #domain "subsea"
                #kind   #BODY
              }
              :defs {
                :package :subsea/pump/primary {
                  :LaserDriveMa { #unsigned #size 16 } :Int
                  :PumpMode     :Enum[#IDLE #BOOST]
                  #DEFAULT_MA   :LaserDriveMa 300
                }
                :use [
                  :subsea/pump/primary { #strip } {
                    :LaserDriveMa :PrimaryMa
                    :LaserDriveMa :BackupMa
                    #DEFAULT_MA   #PRIMARY_DEF
                    #DEFAULT_MA   #BACKUP_DEF
                  }
                ]
                :Envelope :Tuple(:PrimaryMa :BackupMa :PumpMode)
              }
              :type :Envelope
              :body (#PRIMARY_DEF #BACKUP_DEF #BOOST)
            }
            """;
        PsiFile vopFile = myFixture.configureByText("vop_rebrand.subsea.stvn", vopOneToManyDoc);
        List<HighlightInfo> vopErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("1-to-Many VOP rebranding must emit 0 errors: " + vopErrors, vopErrors.isEmpty());

        // Verify PSI resolution of :PrimaryMa, :BackupMa, #PRIMARY_DEF, #BACKUP_DEF and eviction of bare :LaserDriveMa
        int tuplePrimaryOff = vopOneToManyDoc.indexOf(":Tuple(:PrimaryMa") + ":Tuple(".length() + 1;
        int tupleBackupOff = vopOneToManyDoc.indexOf(":BackupMa :PumpMode") + 1;
        PsiElement primaryKw = vopFile.findElementAt(tuplePrimaryOff).getParent();
        PsiElement backupKw = vopFile.findElementAt(tupleBackupOff).getParent();
        assertTrue(primaryKw instanceof TypeKeyword);
        assertTrue(backupKw instanceof TypeKeyword);
        assertNotNull(new StvnTypeReference((TypeKeyword) primaryKw).resolve());
        assertNotNull(new StvnTypeReference((TypeKeyword) backupKw).resolve());
        assertNull("Bare :LaserDriveMa must be evicted from #strip",
            StvnTypeReference.resolveTypeAtContext(primaryKw, ":LaserDriveMa"));

        int bodyPrimaryConstOff = vopOneToManyDoc.indexOf("(#PRIMARY_DEF") + 2;
        int bodyBackupConstOff = vopOneToManyDoc.indexOf("#BACKUP_DEF #BOOST") + 2;
        PsiElement pConstKw = vopFile.findElementAt(bodyPrimaryConstOff).getParent();
        PsiElement bConstKw = vopFile.findElementAt(bodyBackupConstOff).getParent();
        assertTrue(pConstKw instanceof ValueKeyword);
        assertTrue(bConstKw instanceof ValueKeyword);
        assertNotNull(new StvnConstantReference((ValueKeyword) pConstKw).resolve());
        assertNotNull(new StvnConstantReference((ValueKeyword) bConstKw).resolve());
        assertNull("Bare #DEFAULT_MA must be evicted from #strip",
            StvnConstantReference.resolveConstantAtContext(pConstKw, "#DEFAULT_MA"));

        // Verify Ctrl+Alt+N across :defs, :type, and :body scopes lists both rebranded symbols and excludes bare evicted names
        List<String> defsNames = StvnNamespaceSymbolCollector.collectSymbols(vopFile, StvnNamespaceScope.DEFS)
            .stream().map(StvnNamespaceSymbolEntry::name).toList();
        assertTrue("DEFS scope must contain :PrimaryMa and :BackupMa: " + defsNames,
            defsNames.contains(":PrimaryMa") && defsNames.contains(":BackupMa"));
        assertTrue("DEFS scope must contain #PRIMARY_DEF and #BACKUP_DEF: " + defsNames,
            defsNames.contains("#PRIMARY_DEF") && defsNames.contains("#BACKUP_DEF"));
        assertFalse("DEFS scope must evict bare :LaserDriveMa and #DEFAULT_MA: " + defsNames,
            defsNames.contains(":LaserDriveMa") || defsNames.contains("#DEFAULT_MA"));

        List<String> typeNames = StvnNamespaceSymbolCollector.collectSymbols(vopFile, StvnNamespaceScope.TYPE)
            .stream().map(StvnNamespaceSymbolEntry::name).toList();
        assertTrue("TYPE scope closure must retain both :PrimaryMa and :BackupMa: " + typeNames,
            typeNames.contains(":PrimaryMa") && typeNames.contains(":BackupMa"));

        List<String> bodyNames = StvnNamespaceSymbolCollector.collectSymbols(vopFile, StvnNamespaceScope.BODY)
            .stream().map(StvnNamespaceSymbolEntry::name).toList();
        assertTrue("BODY scope closure must retain :PrimaryMa, :BackupMa, #PRIMARY_DEF, and #BACKUP_DEF: " + bodyNames,
            bodyNames.contains(":PrimaryMa")
                && bodyNames.contains(":BackupMa")
                && bodyNames.contains("#PRIMARY_DEF")
                && bodyNames.contains("#BACKUP_DEF"));
    }

    public void testTarget3_SymmetricMultiLineMapEntryAcrossFormatterLayoutIntentionsAndEscapedToFenced() {
        String crampedMapDoc = """
            {
              :meta {
                #name   "map_sym"
                #domain "subsea"
                #kind   #BODY
              }
              :type :Map(:String :String)
              :body {
                ["regex" \"\"\"[REGEX]
                    ^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$[REGEX]\"\"\"]
                ["mode" "strict"]
              }
            }
            """;
        String expectedFormatted = """
            {
              :meta {
                #name   "map_sym"
                #domain "subsea"
                #kind   #BODY
              }
              :type :Map(:String :String)
              :body {
                [
                  "regex"
                  \"\"\"[REGEX]
                    ^((25[0-5]|(2[0-4]|1[0-9]|[1-9]|)[0-9])\\.?\\b){4}$[REGEX]\"\"\"
                ]
                ["mode" "strict"]
              }
            }
            """;

        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        try {
            // 0. CORE_SOURCE_FORMATTER Ctrl+Alt+L
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            PsiFile coreFmtFile = myFixture.configureByText("map_sym.subsea.stvn", crampedMapDoc);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFmtFile);
            });
            assertEquals(expectedFormatted, coreFmtFile.getText());

            // 1. PSI_STRUCTURE_PRESERVING Ctrl+Alt+L
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            PsiFile psiFmtFile = myFixture.configureByText("map_sym.subsea.stvn", crampedMapDoc);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(psiFmtFile);
            });
            assertEquals(expectedFormatted, psiFmtFile.getText());

            // 2. Expand single-line ["mode" "strict"] slice inside multi-line MapLiteral -> 4-slot symmetry, then Collapse back
            int modeSliceOff = psiFmtFile.getText().indexOf("[\"mode\" \"strict\"]");
            myFixture.getEditor().getCaretModel().moveToOffset(modeSliceOff);
            IntentionAction expandSlice = myFixture.findSingleIntention("Expand map entry to multiple lines");
            myFixture.launchAction(expandSlice);
            assertTrue("Expanded slice must use 4-slot symmetry:\n" + myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("    [\n      \"mode\"\n      \"strict\"\n    ]"));

            int expandedBrackOff = myFixture.getFile().getText().lastIndexOf("[\n      \"mode\"");
            myFixture.getEditor().getCaretModel().moveToOffset(expandedBrackOff);
            IntentionAction collapseSlice = myFixture.findSingleIntention("Collapse map entry to single line");
            myFixture.launchAction(collapseSlice);
            assertEquals(expectedFormatted, myFixture.getFile().getText());

            // 3. Convert escaped string to fenced string inside a single-line MapEntry of a multi-line MapLiteral
            String escapedInMultiMap = """
                {
                  :type :Map(:String :String)
                  :body {
                    ["regex" "^a\\\\.b$"]
                    ["mode" "strict"]
                  }
                }
                """;
            PsiFile escMapFile = myFixture.configureByText("esc_map.stvn", escapedInMultiMap);
            myFixture.getEditor().getCaretModel().moveToOffset(escMapFile.getText().indexOf("^a") + 1);
            myFixture.launchAction(myFixture.findSingleIntention(ESCAPED_TO_FENCED_TEXT));
            assertTrue(
                "Escaped-to-fenced inside MapLiteral must produce symmetric 4-slot MapEntry:\n" + escMapFile.getText(),
                escMapFile.getText().contains("    [\n      \"regex\"\n      \"\"\"[RAW]\n        ^a\\.b$[RAW]\"\"\"\n    ]")
            );
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
        }
    }

    public void testTarget4_RemoveIllegalModifierFromIncludeQuickFixAcrossPassAAndPassB() {
        myFixture.addFileToProject(
            "v11_stage09_optics_base.subsea_telemetry.stvn_d",
            """
            {
              :meta {
                #name   "v11_stage09_optics_base"
                #domain "subsea_telemetry"
                #kind   #DEFS
              }
              :defs {
                :package :subsea/optics {
                  :OpticalPowerDbm :Int
                }
              }
            }
            """
        );
        String stage9IncludeDoc = """
            {
              :meta {
                #name   "v11_stage09_consumer"
                #domain "subsea_telemetry"
                #kind   #BODY_INCLUDE
              }
              :defs {
                :include ["v11_stage09_optics_base.subsea_telemetry.stvn_d" { #strip }]
                :use [ :subsea/optics { #strip } ]
              }
              :type :OpticalPowerDbm
              :body -12
            }
            """;

        // Pass A (0-inspection)
        myFixture.configureByText("v11_stage09_consumer.subsea_telemetry.stvn_i", stage9IncludeDoc);
        List<HighlightInfo> passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals(1, passAErrors.size());
        int stripModStart = stage9IncludeDoc.indexOf("{ #strip }");
        assertEquals(stripModStart, passAErrors.get(0).getStartOffset());
        assertEquals(stripModStart + "{ #strip }".length(), passAErrors.get(0).getEndOffset());

        myFixture.getEditor().getCaretModel().moveToOffset(stripModStart + 2);
        IntentionAction passAFix = myFixture.findSingleIntention(StvnRemoveIllegalIncludeModifierQuickFix.QUICK_FIX_NAME);
        String preview = myFixture.getIntentionPreviewText(passAFix);
        assertNotNull(preview);
        assertTrue(preview.contains(":include [\"v11_stage09_optics_base.subsea_telemetry.stvn_d\"]"));

        // Pass B (27-inspection) + live quick-fix execution
        enableAll27StvnInspections();
        myFixture.configureByText("v11_stage09_consumer.subsea_telemetry.stvn_i", stage9IncludeDoc);
        List<HighlightInfo> passBErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit strictly 1 deduplicated E002 error: " + passBErrors, 1, passBErrors.size());
        assertEquals(passAErrors.get(0).getDescription(), passBErrors.get(0).getDescription());

        myFixture.getEditor().getCaretModel().moveToOffset(stripModStart + 2);
        List<IntentionAction> passBFixes = myFixture.filterAvailableIntentions(
            StvnRemoveIllegalIncludeModifierQuickFix.QUICK_FIX_NAME
        );
        assertEquals(1, passBFixes.size());
        myFixture.launchAction(passBFixes.get(0));

        String healed = myFixture.getEditor().getDocument().getText();
        assertTrue(healed.contains(":include [\"v11_stage09_optics_base.subsea_telemetry.stvn_d\"]"));
        List<HighlightInfo> postHealErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Healed :include module must produce 0 errors: " + postHealErrors, postHealErrors.isEmpty());
    }

    public void testTarget5_UnicodeEscapeIntentionsPriorityPreviewAndConfigurableRoundTrip() {
        assertEquals(ESCAPE_INTENTION_TEXT, StvnBundle.message("stvn.intention.escape.non.ascii.text"));
        assertEquals(UNESCAPE_INTENTION_TEXT, StvnBundle.message("stvn.intention.unescape.unicode.text"));

        String emojisA = """
            {
              :meta {
                #name   "emoji_roundtrip"
                #domain "subsea"
                #kind   #BODY
              }
              :type :Tuple({ #minSize 1 #maxSize 128 } :String :Int)
              :body (
                "\\uD83D\\uDE80 by \\uD83D\\uDC68\\u200D\\uD83D\\uDCBB\\nline2"
                1
              )
            }
            """;
        String emojisB = """
            {
              :meta {
                #name   "emoji_roundtrip"
                #domain "subsea"
                #kind   #BODY
              }
              :type :Tuple({ #minSize 1 #maxSize 128 } :String :Int)
              :body (
                \"\"\"[RAW]
                  🚀 by 👨‍💻
                  line2[RAW]\"\"\"
                1
              )
            }
            """;
        String emojisC = """
            {
              :meta {
                #name   "emoji_roundtrip"
                #domain "subsea"
                #kind   #BODY
              }
              :type :Tuple({ #minSize 1 #maxSize 128 } :String :Int)
              :body (
                "🚀 by 👨‍💻\\nline2"
                1
              )
            }
            """;

        enableAll27StvnInspections();
        var settings = StvnSettings.getInstance(getProject());
        boolean origToggle = settings.isEscapeNonAsciiInSimpleStrings();
        StvnConfigurable configurable = new StvnConfigurable(getProject());
        try {
            // 1. Verify StvnConfigurable checkbox UI lifecycle (createComponent, isModified, apply, reset)
            JComponent ui = configurable.createComponent();
            assertNotNull(ui);
            String expectedCbLabel = StvnBundle.message("stvn.settings.escape.non.ascii.simple.strings");
            JCheckBox escapeCheckBox = findCheckBoxByText(ui, expectedCbLabel);
            assertNotNull("Expected STVN Settings checkbox '" + expectedCbLabel + "'", escapeCheckBox);
            assertFalse(escapeCheckBox.isSelected());
            assertFalse(configurable.isModified());

            escapeCheckBox.setSelected(true);
            assertTrue(configurable.isModified());
            configurable.apply();
            assertTrue(settings.isEscapeNonAsciiInSimpleStrings());
            assertFalse(configurable.isModified());

            escapeCheckBox.setSelected(false);
            assertTrue(configurable.isModified());
            configurable.reset();
            assertTrue(escapeCheckBox.isSelected());

            escapeCheckBox.setSelected(false);
            configurable.apply();
            assertFalse(settings.isEscapeNonAsciiInSimpleStrings());

            // 2. Verify Unescape (emojis_a -> emojis_c) & Escape (emojis_c -> emojis_a) + Priority.NORMAL ordering
            myFixture.configureByText("emoji_roundtrip.subsea.stvn", emojisA);
            int strOffA = myFixture.getFile().getText().indexOf("\\uD83D\\uDE80") + 2;
            myFixture.getEditor().getCaretModel().moveToOffset(strOffA);

            List<IntentionAction> availableAtA = myFixture.getAvailableIntentions();
            List<String> textsAtA = availableAtA.stream().map(IntentionAction::getText).toList();
            int topIdx = textsAtA.indexOf(ESCAPED_TO_FENCED_TEXT);
            int unescIdx = textsAtA.indexOf(UNESCAPE_INTENTION_TEXT);
            int collapseIdx = textsAtA.indexOf(COLLAPSE_CONTAINER_TEXT);
            assertEquals("Priority.TOP fencing intention must rank #1", 0, topIdx);
            assertTrue("Unescape intention must appear at Priority.NORMAL below TOP and above LOW: " + textsAtA,
                unescIdx > topIdx && (collapseIdx < 0 || unescIdx < collapseIdx));
            assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(availableAtA.get(unescIdx)));
            assertTrue(myFixture.filterAvailableIntentions(ESCAPE_INTENTION_TEXT).isEmpty());

            IntentionAction unescAction = myFixture.findSingleIntention(UNESCAPE_INTENTION_TEXT);
            assertNotNull(myFixture.getIntentionPreviewText(unescAction));
            myFixture.launchAction(unescAction);
            assertEquals(emojisC, myFixture.getEditor().getDocument().getText());
            List<HighlightInfo> emojisCProblems = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                    || (h.getSeverity() == HighlightSeverity.WARNING
                        && h.getDescription() != null
                        && !h.getDescription().contains("W002-WARN_UNFENCED_STRING_NEWLINE")))
                .toList();
            assertTrue("Unescaped emojis_c must produce 0 errors and 0 character-scrutiny warnings: " + emojisCProblems,
                emojisCProblems.isEmpty());

            int strOffC = myFixture.getFile().getText().indexOf("🚀") + 1;
            myFixture.getEditor().getCaretModel().moveToOffset(strOffC);
            IntentionAction escAction = myFixture.findSingleIntention(ESCAPE_INTENTION_TEXT);
            assertEquals(PriorityAction.Priority.NORMAL, unwrapPriority(escAction));
            assertNotNull(myFixture.getIntentionPreviewText(escAction));
            myFixture.launchAction(escAction);
            assertEquals(emojisA, myFixture.getEditor().getDocument().getText());

            // 3. Verify emojis_a -> emojis_b -> emojis_c (default toggle false) vs emojis_a -> emojis_b -> emojis_a (toggle true)
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("\\uD83D\\uDE80") + 2);
            myFixture.launchAction(myFixture.findSingleIntention(ESCAPED_TO_FENCED_TEXT));
            assertEquals(emojisB, myFixture.getEditor().getDocument().getText());

            // Inside fenced string, simple-string Unicode-escape intentions must be suppressed
            int fencedOff = myFixture.getFile().getText().indexOf("🚀") + 1;
            myFixture.getEditor().getCaretModel().moveToOffset(fencedOff);
            assertTrue(myFixture.filterAvailableIntentions(ESCAPE_INTENTION_TEXT).isEmpty());
            assertTrue(myFixture.filterAvailableIntentions(UNESCAPE_INTENTION_TEXT).isEmpty());

            // Default false: emojis_b -> emojis_c
            myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));
            assertEquals(emojisC, myFixture.getEditor().getDocument().getText());

            // Enable toggle via StvnConfigurable: emojis_b -> emojis_a (strict 1:1 round-trip!)
            escapeCheckBox.setSelected(true);
            configurable.apply();
            myFixture.configureByText("emoji_roundtrip.subsea.stvn", emojisB);
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("🚀") + 1);
            myFixture.launchAction(myFixture.findSingleIntention(FENCED_TO_SIMPLE_TEXT));
            assertEquals(emojisA, myFixture.getEditor().getDocument().getText());

            // Escape-free raw non-ASCII string ("Café") coexists cleanly with PLAIN_TO_FENCED_TEXT
            myFixture.configureByText("cafe_plain.stvn", "{\n  :type :String\n  :body \"Café\"\n}\n");
            myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("Café") + 2);
            assertEquals(1, myFixture.filterAvailableIntentions(PLAIN_TO_FENCED_TEXT).size());
            assertEquals(1, myFixture.filterAvailableIntentions(ESCAPE_INTENTION_TEXT).size());
        } finally {
            configurable.disposeUIResources();
            settings.getState().escapeNonAsciiInSimpleStrings = origToggle;
        }
    }

    private byte[] readVfsBytes(String relPath) {
        try {
            return myFixture.findFileInTempDir(relPath).contentsToByteArray();
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static PriorityAction.Priority unwrapPriority(IntentionAction action) {
        IntentionAction cur = action;
        while (cur instanceof IntentionActionDelegate delegate) {
            IntentionAction next = delegate.getDelegate();
            if (next == cur) {
                break;
            }
            cur = next;
        }
        return cur instanceof PriorityAction pa ? pa.getPriority() : PriorityAction.Priority.NORMAL;
    }

    private static JCheckBox findCheckBoxByText(Container root, String text) {
        for (Component comp : root.getComponents()) {
            if (comp instanceof JCheckBox cb && text.equals(cb.getText())) {
                return cb;
            }
            if (comp instanceof Container child) {
                JCheckBox found = findCheckBoxByText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
