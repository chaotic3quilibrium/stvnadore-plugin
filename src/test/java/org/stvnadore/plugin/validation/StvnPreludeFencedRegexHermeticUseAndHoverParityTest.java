package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.navigation.StvnGotoDeclarationHandler;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.TypeDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comprehensive IntelliJ Platform integration and anti-tautological parity test suite verifying:
 * <ul>
 *   <li><b>P1</b>: Prelude and user-authored fenced {@code [REGEX]} dedent, {@code #preserveIndent}
 *       independence, string capacity inspection parity, and canonical quoted hover presentation.</li>
 *   <li><b>P2</b>: Hermetic {@code :use} {@code E035} 1:1 Core vs. local-inspection parity, quick-fixes,
 *       completion filtering, trailing-slash deconfliction, and modular {@code :use} {@code E030}.</li>
 *   <li><b>P3</b>: Four-locus {@code :DateTime} human-readable {@code UTC Time:} hover rows and
 *       {@code <nobr>} schema header anti-wrap protection.</li>
 *   <li><b>P4</b>: Enclosing degraded ancestor container child hover suspension and {@code Ctrl+Click}
 *       declaration navigation preservation.</li>
 * </ul>
 */
@NullMarked
public final class StvnPreludeFencedRegexHermeticUseAndHoverParityTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider docProvider = new StvnDocumentationProvider();
    private final StvnGotoDeclarationHandler gotoHandler = new StvnGotoDeclarationHandler();

    private void enableAll26LocalInspections() {
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

    private String getDocAtOffset(int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = docProvider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Documentation element at offset " + offset + " must not be null", docElem);
        var doc = docProvider.generateDoc(docElem, elem);
        assertNotNull("Generated documentation at offset " + offset + " must not be null", doc);
        return doc;
    }

    // =========================================================================
    // P1: Fenced [REGEX] Dedent, Capacity Inspection & Canonical Hover Parity
    // =========================================================================

    public void testP1FencedRegexDedentStringCapacityAndCanonicalQuotedHoverParity() throws IOException {
        var fixturePath = Paths.get(
            "src", "test", "resources", "shared-fixtures", "syntax", "valid", "scalars",
            "fenced_regex_dedent_and_preserve_indent.stvn"
        );
        var fixtureSource = Files.readString(fixturePath, StandardCharsets.UTF_8).replace("\r\n", "\n");

        // 0-inspection mode (Core external annotator only)
        myFixture.configureByText("fenced_regex_dedent_and_preserve_indent.stvn", fixtureSource);
        var coreHighlights = myFixture.doHighlighting();
        var coreErrors = coreHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        assertTrue("Expected 0 Core errors on fenced_regex_dedent_and_preserve_indent.stvn, got: " + coreErrors,
            coreErrors.isEmpty());

        // 26-inspection mode (all local inspections enabled concurrently)
        enableAll26LocalInspections();
        myFixture.configureByText("fenced_regex_dedent_and_preserve_indent.stvn", fixtureSource);
        var pluginHighlights = myFixture.doHighlighting();
        var pluginErrors = pluginHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        assertTrue("Expected 0 errors with all 26 inspections enabled on fenced_regex_dedent_and_preserve_indent.stvn, got: " + pluginErrors,
            pluginErrors.isEmpty());

        // Hover over user-authored fenced #regex and fenced #minIncl/#maxIncl types and constants
        String slugConstDoc = getDocAtOffset(fixtureSource.indexOf("#SLUG_REGEX :String") + 1);
        assertFalse("Constant hover must not leak raw triple-quote [REGEX] fence:\n" + slugConstDoc,
            slugConstDoc.contains("\"\"\"[REGEX]"));
        assertTrue("Constant hover must display canonically quoted regex pattern:\n" + slugConstDoc,
            slugConstDoc.contains("\"^[a-z0-9]+(?:\\\\.[a-z0-9]+)*$\""));

        String inlineVersionDoc = getDocAtOffset(fixtureSource.indexOf(":InlineVersion") + 1);
        assertFalse("Type hover must not leak raw triple-quote [REGEX] fence:\n" + inlineVersionDoc,
            inlineVersionDoc.contains("\"\"\"[REGEX]"));
        assertTrue("Type hover must display canonically quoted inline regex:\n" + inlineVersionDoc,
            inlineVersionDoc.contains("\"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+$\""));

        String lexBoundedDoc = getDocAtOffset(fixtureSource.indexOf(":FencedLexBounded") + 1);
        assertFalse("Type hover must not leak raw [BOUND] fence:\n" + lexBoundedDoc,
            lexBoundedDoc.contains("\"\"\"[BOUND]"));
        assertTrue("Type hover must display canonically quoted string bounds:\n" + lexBoundedDoc,
            lexBoundedDoc.contains("\"aaa\"") && lexBoundedDoc.contains("\"zzz\""));

        // Verify StvnStringCapacityInspection exact dedented vs #preserveIndent length & Prelude fenced regex hovers
        var preludeAndCapacitySource = """
            {
              :meta {
                #name "prelude_and_capacity_check"
                #kind #BODY
              }
              :defs {
                :use [ :org/stvnadore/prelude {
                  :SemVer :SemVer
                  :IPv4 :IPv4
                  #EMAIL_LOWER_CASE_REGEX #EMAIL_LOWER_CASE_REGEX
                  #EMAIL_MIXED_CASE_REGEX #EMAIL_MIXED_CASE_REGEX
                } ]
                :ExactFourDedented { #minSize 4 #maxSize 4 } :String
                :ExactTwelvePreserved { #minSize 12 #maxSize 12 #preserveIndent #TRUE } :String
                :DegradedFencedRegex {
                  #minSize 10
                  #maxSize 2
                  #regex \"\"\"[REGEX]
                    ^[a-z]+$[REGEX]\"\"\"
                } :String
                :EmailLower { #minSize 3 #maxSize 128 #regex #EMAIL_LOWER_CASE_REGEX } :String
                :EmailMixed { #minSize 3 #maxSize 128 #regex #EMAIL_MIXED_CASE_REGEX } :String
                :Envelope :Tuple(:SemVer :IPv4 :ExactFourDedented :ExactTwelvePreserved :EmailLower :EmailMixed)
              }
              :type :Envelope
              :body (
                "2.0.0-rc.1+build.7"
                "192.168.1.10"
                \"\"\"[RAW]
                    abcd[RAW]\"\"\"
                \"\"\"[RAW]
                    abcd[RAW]\"\"\"
                "alice@example.com"
                "Alice.Smith@Example.COM"
              )
            }
            """;
        myFixture.configureByText("prelude_and_capacity_check.stvn", preludeAndCapacitySource);
        var capHighlights = myFixture.doHighlighting();
        var capErrors = capHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        assertEquals(
            "Expected strictly 1 error (the intentional inverted bounds on :DegradedFencedRegex) and 0 false capacity errors, got: " + capErrors,
            1,
            capErrors.size()
        );
        assertTrue("Expected E009 inverted range error on :DegradedFencedRegex, got: " + capErrors.get(0).getDescription(),
            capErrors.get(0).getDescription().contains("E009"));
        var pw004Warnings = capHighlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("PW004"))
            .toList();
        assertTrue("Expected 0 PW004 string capacity overflow warnings on dedented vs preserved fenced strings, got: " + pw004Warnings,
            pw004Warnings.isEmpty());

        // Hover over :SemVer, :IPv4, #EMAIL_LOWER_CASE_REGEX, #EMAIL_MIXED_CASE_REGEX, and :DegradedFencedRegex
        int bodyTypeIdx = preludeAndCapacitySource.indexOf(":Envelope :Tuple(");
        String semVerDoc = getDocAtOffset(preludeAndCapacitySource.indexOf(":SemVer", bodyTypeIdx) + 1);
        assertFalse(":SemVer hover must never leak raw [REGEX] fence:\n" + semVerDoc, semVerDoc.contains("\"\"\"[REGEX]"));
        assertTrue(":SemVer hover must preserve Underlying Type :String and corrected [0-9a-zA-Z-]* range:\n" + semVerDoc,
            semVerDoc.contains("<b>Underlying Type:</b> <code>:String</code>")
                && semVerDoc.contains("[0-9a-zA-Z-]*")
                && !semVerDoc.contains("[0-zA-Z0-9-]*"));

        String ipv4Doc = getDocAtOffset(preludeAndCapacitySource.indexOf(":IPv4", bodyTypeIdx) + 1);
        assertFalse(":IPv4 hover must never leak raw [REGEX] fence:\n" + ipv4Doc, ipv4Doc.contains("\"\"\"[REGEX]"));
        assertTrue(":IPv4 hover must preserve Underlying Type :String:\n" + ipv4Doc,
            ipv4Doc.contains("<b>Underlying Type:</b> <code>:String</code>"));

        String emailLowerDoc = getDocAtOffset(preludeAndCapacitySource.indexOf(":EmailLower") + 1);
        assertFalse(":EmailLower hover must never leak raw [REGEX] fence:\n" + emailLowerDoc,
            emailLowerDoc.contains("\"\"\"[REGEX]"));

        String degradedFencedDoc = getDocAtOffset(preludeAndCapacitySource.indexOf(":DegradedFencedRegex") + 1);
        assertFalse("Degraded PSI-fallback type hover must never leak raw [REGEX] fence:\n" + degradedFencedDoc,
            degradedFencedDoc.contains("\"\"\"[REGEX]"));
        assertTrue("Degraded PSI-fallback type hover must display canonically quoted \"^[a-z]+$\":\n" + degradedFencedDoc,
            degradedFencedDoc.contains("\"^[a-z]+$\""));
    }

    // =========================================================================
    // P2: Hermetic :use E035 Parity, Quick-Fixes, Completion & Modular E030
    // =========================================================================

    public void testP2Stage9AndModularUseZeroVsTwentySixInspectionParity() throws IOException {
        var stage9Path = Paths.get(
            "src", "test", "resources", "shared-fixtures", "syntax", "invalid", "packages",
            "gauntlet_v7_stage9_packages_scoped_use_and_prelude_types.core_test.stvn"
        );
        var stage9Source = Files.readString(stage9Path, StandardCharsets.UTF_8).replace("\r\n", "\n");

        // 1. 0-inspection mode (Core external annotator)
        myFixture.configureByText("gauntlet_v7_stage9_packages_scoped_use_and_prelude_types.core_test.stvn", stage9Source);
        var coreStage9 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected 6 Core errors in Stage 9 fixture", 6, coreStage9.size());
        var coreE035 = coreStage9.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("[stvn-core:E035-ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT]"))
            .toList();
        assertEquals("Expected 3 Core E035 highlights", 3, coreE035.size());
        assertHighlightSpanAndTarget(coreE035.get(0), 393, 419, ":external/unloaded/billing");
        assertHighlightSpanAndTarget(coreE035.get(1), 444, 469, ":external/unloaded/ledger");
        assertHighlightSpanAndTarget(coreE035.get(2), 510, 544, ":org/stvnadore/prelude/nonexistent");

        // Modular E030 in 0-inspection mode
        var netPrimitivesPath = Paths.get(
            "src", "test", "resources", "shared-fixtures", "syntax", "valid", "modules",
            "network_primitives.stvn_d"
        );
        myFixture.addFileToProject(
            "syntax/valid/modules/network_primitives.stvn_d",
            Files.readString(netPrimitivesPath, StandardCharsets.UTF_8).replace("\r\n", "\n")
        );
        var modularPath = Paths.get(
            "src", "test", "resources", "shared-fixtures", "syntax", "invalid", "modules",
            "modular_unresolved_package_use.stvn_i"
        );
        var modularSource = Files.readString(modularPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        var modularPsi = myFixture.addFileToProject("syntax/invalid/modules/modular_unresolved_package_use.stvn_i", modularSource);
        myFixture.configureFromExistingVirtualFile(modularPsi.getVirtualFile());
        var coreModularErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected 3 Core E030 errors in modular_unresolved_package_use.stvn_i", 3, coreModularErrors.size());
        assertModularE030Span(coreModularErrors.get(0), 169, 195, ":external/unloaded/billing");
        assertModularE030Span(coreModularErrors.get(1), 220, 245, ":external/unloaded/ledger");
        assertModularE030Span(coreModularErrors.get(2), 286, 320, ":org/stvnadore/prelude/nonexistent");

        // 2. 26-inspection mode (all local inspections enabled)
        enableAll26LocalInspections();
        myFixture.configureByText("gauntlet_v7_stage9_packages_scoped_use_and_prelude_types.core_test.stvn", stage9Source);
        var pluginStage9 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected strictly 6 deduplicated errors in Stage 9 with all 26 inspections enabled", 6, pluginStage9.size());
        var pluginE035 = pluginStage9.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("[stvn-plugin:E035-ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT]"))
            .toList();
        assertEquals("Expected 3 plugin E035 highlights", 3, pluginE035.size());
        assertHighlightSpanAndTarget(pluginE035.get(0), 393, 419, ":external/unloaded/billing");
        assertHighlightSpanAndTarget(pluginE035.get(1), 444, 469, ":external/unloaded/ledger");
        assertHighlightSpanAndTarget(pluginE035.get(2), 510, 544, ":org/stvnadore/prelude/nonexistent");

        // Assert 1:1 prose equality after stripping [stvn-core:...] vs [stvn-plugin:...]
        for (int i = 0; i < 3; i++) {
            String coreProse = stripOriginPrefix(coreE035.get(i).getDescription());
            String pluginProse = stripOriginPrefix(pluginE035.get(i).getDescription());
            assertEquals("Core and Plugin E035 prose must match 1:1", coreProse, pluginProse);
        }

        // Modular E030 in 26-inspection mode
        myFixture.configureFromExistingVirtualFile(modularPsi.getVirtualFile());
        var pluginModularErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected strictly 3 E030 errors in modular_unresolved_package_use.stvn_i under 26 inspections",
            3, pluginModularErrors.size());
        assertModularE030Span(pluginModularErrors.get(0), 169, 195, ":external/unloaded/billing");
        assertModularE030Span(pluginModularErrors.get(1), 220, 245, ":external/unloaded/ledger");
        assertModularE030Span(pluginModularErrors.get(2), 286, 320, ":org/stvnadore/prelude/nonexistent");
    }

    public void testP2HermeticUseEdgeCasesQuickFixesAndCompletionFiltering() {
        enableAll26LocalInspections();

        // Trailing-slash deconfliction (E002 wins, E035 does not stack) + nested :use inside rejected reserved package
        var edgeSource = """
            {
              :meta {
                #name "hermetic_edge_cases"
                #kind #BODY
              }
              :defs {
                :package :org/stvnadore/custom_hack {
                  :use [ :org/stvnadore/custom_hack { #strip } ]
                  :Hijacked :Int
                }
                :use [ :external/trailing/ { #strip } ]
                :use [ : ]
              }
              :type :Int
              :body 1
            }
            """;
        myFixture.configureByText("hermetic_edge_cases.stvn", edgeSource);
        var highlights = myFixture.doHighlighting();
        var trailingE035 = highlights.stream()
            .filter(h -> h.getDescription() != null
                && h.getDescription().contains("E035")
                && h.getDescription().contains(":external/trailing/"))
            .toList();
        assertTrue("Trailing-slash :use must emit E002 only and never stack E035: " + trailingE035, trailingE035.isEmpty());

        var reservedNestedE035 = highlights.stream()
            .filter(h -> h.getDescription() != null
                && h.getDescription().contains("[stvn-plugin:E035-ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT]")
                && h.getDescription().contains(":org/stvnadore/custom_hack"))
            .toList();
        assertEquals("Nested :use targeting rejected reserved package must emit E035", 1, reservedNestedE035.size());

        var bareColonE035 = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("cannot target external package: : ["))
            .toList();
        assertTrue("Incomplete ':use [ : ]' must never emit premature E035", bareColonE035.isEmpty());

        // Quick-Fix 1: RemoveUseStatementQuickFix
        var fixSource = """
            {
              :meta {
                #name "quickfix_remove_use"
                #kind #BODY
              }
              :defs {
                :use [ :external/unloaded/billing { #strip } ]
                :LocalId :Int
              }
              :type :LocalId
              :body 7
            }
            """;
        myFixture.configureByText("quickfix_remove_use.stvn", fixSource);
        myFixture.getEditor().getCaretModel().moveToOffset(fixSource.indexOf(":external/unloaded/billing") + 2);
        myFixture.doHighlighting();
        IntentionAction removeFix = myFixture.findSingleIntention("Remove :use directive for ':external/unloaded/billing'");
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            removeFix.invoke(getProject(), myFixture.getEditor(), myFixture.getFile())
        );
        assertFalse("RemoveUseStatementQuickFix must delete offending :use line:\n" + myFixture.getFile().getText(),
            myFixture.getFile().getText().contains(":external/unloaded/billing"));

        // Quick-Fix 2: StvnConvertToModularQuickFix on hermetic .stvn
        var convertPsi = myFixture.addFileToProject("quickfix_convert_modular.stvn", """
            {
              :meta {
                #name "quickfix_convert_modular"
                #kind #BODY
              }
              :defs {
                :use [ :external/unloaded/billing { #strip } ]
                :LocalId :Int
              }
              :type :LocalId
              :body 7
            }
            """);
        myFixture.configureFromExistingVirtualFile(convertPsi.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":external/unloaded/billing") + 2);
        myFixture.doHighlighting();
        IntentionAction convertFix = myFixture.findSingleIntention("Convert document to modular format (.stvn_i)");
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            convertFix.invoke(getProject(), myFixture.getEditor(), myFixture.getFile())
        );
        assertEquals("quickfix_convert_modular.stvn_i", myFixture.getFile().getVirtualFile().getName());
        assertTrue("Modular conversion must rewrite #kind #BODY to #kind #BODY_INCLUDE:\n" + myFixture.getFile().getText(),
            myFixture.getFile().getText().contains("#kind #BODY_INCLUDE"));

        // Completion Filtering: External workspace package excluded in hermetic .stvn, included in modular .stvn_i
        myFixture.addFileToProject("external_workspace_pkg.stvn_di", """
            {
              :meta {
                #name "external_workspace_pkg"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :com/external/shared {
                  :SharedId :Int
                }
              }
            }
            """);

        myFixture.configureByText("hermetic_completion.stvn", """
            {
              :meta {
                #name "hermetic_completion"
                #kind #BODY
              }
              :defs {
                :package :com/local/app {
                  :LocalPort :Int
                }
                :use [ :<caret> ]
              }
              :type :Int
              :body 1
            }
            """);
        LookupElement[] hermeticItems = myFixture.completeBasic();
        assertNotNull("Completion items in hermetic :use must not be null", hermeticItems);
        Set<String> hermeticStrings = Arrays.stream(hermeticItems).map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertTrue("Hermetic :use completion must include Prelude package: " + hermeticStrings,
            hermeticStrings.contains(":org/stvnadore/prelude"));
        assertTrue("Hermetic :use completion must include local :defs package: " + hermeticStrings,
            hermeticStrings.contains(":com/local/app"));
        assertFalse("Hermetic :use completion must exclude external workspace package: " + hermeticStrings,
            hermeticStrings.contains(":com/external/shared"));

        myFixture.configureByText("modular_completion.stvn_i", """
            {
              :meta {
                #name "modular_completion"
                #kind #BODY_INCLUDE
              }
              :defs {
                :use [ :<caret> ]
              }
              :type :Int
              :body 1
            }
            """);
        LookupElement[] modularItems = myFixture.completeBasic();
        assertNotNull("Completion items in modular :use must not be null", modularItems);
        Set<String> modularStrings = Arrays.stream(modularItems).map(LookupElement::getLookupString).collect(Collectors.toSet());
        assertTrue("Modular :use completion must include external workspace package: " + modularStrings,
            modularStrings.contains(":com/external/shared"));
    }

    // =========================================================================
    // P3: Four-Locus :DateTime UTC Time: & <nobr> Schema Header Anti-Wrap
    // =========================================================================

    public void testP3FourLocusDateTimeUtcTimeAndNobrAntiWrapParity() {
        var text = """
            {
              :meta {
                #name "four_locus_datetime_utc_time"
                #kind #BODY
              }
              :defs {
                :OffsetTs { #offset } :DateTime
                :ZonedTs { #zoned } :DateTime
                :AuditedTs { #audited } :DateTime
                :EpochSec { #s } :TimeEpoch
                #OFFSET_EVENT :OffsetTs "2026-11-12T12:30:00-05:00"
                #CHAINED_OFFSET :OffsetTs #OFFSET_EVENT
                #ZONED_EVENT :ZonedTs "2026-11-12T12:30:00[America/New_York]"
                #AUDITED_EVENT :AuditedTs "2026-11-12T12:30:00.500-05:00[America/New_York]"
                #DST_GAP_INVALID :ZonedTs "2026-03-08T02:30:00[America/New_York]"
                :EventPayload :Tuple(:OffsetTs :ZonedTs :AuditedTs :OffsetTs :EpochSec)
              }
              :type :EventPayload
              :body (
                #CHAINED_OFFSET
                #ZONED_EVENT
                #AUDITED_EVENT
                "2026-11-12T12:30:00-05:00"
                1794504600
              )
            }
            """;
        myFixture.configureByText("four_locus_datetime_utc_time.stvn", text);
        var settings = StvnSettings.getInstance(getProject());
        var prevFormat = settings.getState().timeEpochHourFormat;
        try {
            // Test HOUR_12 across all 4 loci
            settings.getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_12;

            // Locus 1: :defs LHS #OFFSET_EVENT
            String lhsDoc12 = getDocAtOffset(text.indexOf("#OFFSET_EVENT :OffsetTs") + 1);
            assertTrue("Locus 1 must render <nobr> Type row:\n" + lhsDoc12,
                lhsDoc12.contains("<nobr><b>Type:</b> <code>:OffsetTs</code></nobr>"));
            assertTrue("Locus 1 must render <nobr> Temporal Mode row:\n" + lhsDoc12,
                lhsDoc12.contains("<nobr><b>Temporal Mode:</b> <code>{ #offset } :DateTime</code></nobr>"));
            assertTrue("Locus 1 must render ISO UTC Preview and 12h UTC Time:\n" + lhsDoc12,
                lhsDoc12.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>")
                    && lhsDoc12.contains("<b>UTC Time:</b> Thursday, November 12, 2026, at 5:30:00 PM UTC"));

            // Locus 2: :defs RHS literal & chained #CONST
            String rhsDoc12 = getDocAtOffset(text.indexOf("\"2026-11-12T12:30:00-05:00\"") + 2);
            assertTrue("Locus 2 must render <nobr> Value Type and Governing Schema rows plus 12h UTC Time:\n" + rhsDoc12,
                rhsDoc12.contains("<nobr><b>Value Type:</b>")
                    && rhsDoc12.contains("<nobr><b>Governing Schema:</b>")
                    && rhsDoc12.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>")
                    && rhsDoc12.contains("<b>UTC Time:</b> Thursday, November 12, 2026, at 5:30:00 PM UTC"));

            int chainRhsIdx = text.indexOf("#CHAINED_OFFSET :OffsetTs #OFFSET_EVENT") + "#CHAINED_OFFSET :OffsetTs ".length() + 1;
            String chainRhsDoc12 = getDocAtOffset(chainRhsIdx);
            assertTrue("Locus 2 chained #CONST must render 12h UTC Time:\n" + chainRhsDoc12,
                chainRhsDoc12.contains("<b>UTC Time:</b> Thursday, November 12, 2026, at 5:30:00 PM UTC"));

            // Locus 3: :body #CONST references (#offset, #zoned, #audited)
            int bodyIdx = text.indexOf(":body (");
            String bodyRefDoc12 = getDocAtOffset(text.indexOf("#CHAINED_OFFSET", bodyIdx) + 1);
            assertTrue("Locus 3 must render <nobr> Slot Schema and 12h UTC Time:\n" + bodyRefDoc12,
                bodyRefDoc12.contains("<nobr><b>Slot Schema:</b>")
                    && bodyRefDoc12.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>")
                    && bodyRefDoc12.contains("<b>UTC Time:</b> Thursday, November 12, 2026, at 5:30:00 PM UTC"));

            String bodyZonedDoc12 = getDocAtOffset(text.indexOf("#ZONED_EVENT", bodyIdx) + 1);
            assertTrue("Locus 3 (#zoned) must render 12h UTC Time:\n" + bodyZonedDoc12,
                bodyZonedDoc12.contains("<b>UTC Time:</b> Thursday, November 12, 2026, at 5:30:00 PM UTC"));

            // Locus 4: :body direct string literal & :TimeEpoch <nobr>
            String bodyLitDoc12 = getDocAtOffset(text.indexOf("\"2026-11-12T12:30:00-05:00\"", bodyIdx) + 2);
            assertTrue("Locus 4 must render 12h UTC Time:\n" + bodyLitDoc12,
                bodyLitDoc12.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>")
                    && bodyLitDoc12.contains("<b>UTC Time:</b> Thursday, November 12, 2026, at 5:30:00 PM UTC"));

            String epochDoc = getDocAtOffset(text.indexOf("1794504600", bodyIdx) + 2);
            assertTrue(":TimeEpoch Temporal Scale must also be wrapped in <nobr>:\n" + epochDoc,
                epochDoc.contains("<nobr><b>Temporal Scale:</b> <code>{ #s } :TimeEpoch</code></nobr>"));

            // Test HOUR_24 mode + sub-second .500 precision on #AUDITED_EVENT
            settings.getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_24;
            String bodyAuditedDoc24 = getDocAtOffset(text.indexOf("#AUDITED_EVENT", bodyIdx) + 1);
            assertTrue("Locus 3 (#audited, HOUR_24) must render 24h UTC Time with .500 millis:\n" + bodyAuditedDoc24,
                bodyAuditedDoc24.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00.5Z</code>")
                    && bodyAuditedDoc24.contains("<b>UTC Time:</b> Thursday, November 12, 2026, at 17:30:00.500 UTC"));

            // Spring-forward DST gap timestamp must suppress both UTC Preview and UTC Time
            String dstGapDoc = getDocAtOffset(text.indexOf("#DST_GAP_INVALID") + 1);
            assertFalse("Invalid DST-gap timestamp must suppress UTC Preview and UTC Time:\n" + dstGapDoc,
                dstGapDoc.contains("<b>UTC Preview:</b>") || dstGapDoc.contains("<b>UTC Time:</b>"));
        } finally {
            settings.getState().timeEpochHourFormat = prevFormat;
        }
    }

    // =========================================================================
    // P4: Enclosing Degraded Container Child Hover & Ctrl+Click Navigation
    // =========================================================================

    public void testP4DegradedAncestorContainerChildHoverAndCtrlClickParity() {
        var content = """
            {
              :meta {
                #name "stage7_degraded_container_children"
                #kind #BODY
              }
              :defs {
                :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 65536 } :Int
                #DEFAULT_PORT :BoundedPort 8080
                :SettlementState :Enum [ #CREATED #SETTLED #FAILED ]
                :TrapInvertedSeqBounds { #minSize 5 #maxSize 2 } :Seq(:BoundedPort)
                :TrapInvertedEnumSeq { #minSize 5 #maxSize 2 } :Seq(:SettlementState)
                :Stage7Envelope :Tuple(
                  :TrapInvertedSeqBounds
                  :TrapInvertedEnumSeq
                  :TrapInvertedSeqBounds
                  :BoundedPort
                )
              }
              :type :Stage7Envelope
              :body (
                [ 8080 ]
                [ #CREATED ]
                [ #DEFAULT_PORT ]
                5000
              )
            }
            """;
        var psiFile = myFixture.configureByText("stage7_degraded_container_children.stvn", content);
        int bodyIdx = content.indexOf(":body (");

        // 1. Scalar child 8080 inside degraded [ 8080 ] (:TrapInvertedSeqBounds)
        int scalarChildOffset = content.indexOf("[ 8080 ]", bodyIdx) + 2;
        String scalarChildDoc = getDocAtOffset(scalarChildOffset);
        assertTrue("Scalar child inside degraded :Seq must display yellow Degraded Schema Warning banner:\n" + scalarChildDoc,
            scalarChildDoc.contains("Degraded Schema Warning")
                && scalarChildDoc.contains("Nominal type <code>:TrapInvertedSeqBounds</code> contains constraint errors in <code>:defs</code> (underlying base type: <code>:Seq( :BoundedPort )</code>)."));
        assertTrue("Scalar child inside degraded :Seq must report Suspended (Degraded Schema):\n" + scalarChildDoc,
            scalarChildDoc.contains("Suspended (Degraded Schema)"));
        assertFalse("Scalar child inside degraded :Seq must never report Satisfied or Exemplar Value:\n" + scalarChildDoc,
            scalarChildDoc.contains(">Satisfied<") || scalarChildDoc.contains("<b>Exemplar Value:</b>"));

        // 2. Enum child #CREATED inside degraded [ #CREATED ] (:TrapInvertedEnumSeq)
        int enumChildOffset = content.indexOf("#CREATED", bodyIdx) + 1;
        String enumChildDoc = getDocAtOffset(enumChildOffset);
        assertTrue("Enum child inside degraded :Seq must route to suspended Payload Value card with warning banner:\n" + enumChildDoc,
            enumChildDoc.contains("Degraded Schema Warning")
                && enumChildDoc.contains(":TrapInvertedEnumSeq")
                && enumChildDoc.contains("Suspended (Degraded Schema)"));
        assertFalse("Enum child inside degraded :Seq must never report Satisfied:\n" + enumChildDoc,
            enumChildDoc.contains(">Satisfied<"));

        // 3. #CONST child #DEFAULT_PORT inside degraded [ #DEFAULT_PORT ] (:TrapInvertedSeqBounds)
        int constChildOffset = content.indexOf("#DEFAULT_PORT", bodyIdx) + 1;
        String constChildDoc = getDocAtOffset(constChildOffset);
        assertTrue("#CONST child inside degraded :Seq must route to suspended Payload Value card with warning banner:\n" + constChildDoc,
            constChildDoc.contains("Degraded Schema Warning")
                && constChildDoc.contains(":TrapInvertedSeqBounds")
                && constChildDoc.contains("Suspended (Degraded Schema)"));

        // 4. Healthy sibling slot 5000 in outer :Stage7Envelope tuple must remain Satisfied
        int healthySiblingOffset = content.indexOf("5000", bodyIdx) + 1;
        String healthySiblingDoc = getDocAtOffset(healthySiblingOffset);
        assertFalse("Healthy sibling tuple slot must NOT display Degraded Schema Warning:\n" + healthySiblingDoc,
            healthySiblingDoc.contains("Degraded Schema Warning"));
        assertTrue("Healthy sibling tuple slot must report Satisfied:\n" + healthySiblingDoc,
            healthySiblingDoc.contains("Satisfied"));

        // 5. Ctrl+Click (StvnGotoDeclarationHandler) must remain intact on all three children
        var scalarTargets = gotoHandler.getGotoDeclarationTargets(psiFile.findElementAt(scalarChildOffset), scalarChildOffset, myFixture.getEditor());
        assertNotNull("Ctrl+Click on 8080 must resolve", scalarTargets);
        assertEquals(1, scalarTargets.length);
        assertTrue(scalarTargets[0] instanceof TypeDefinition);
        assertEquals(":BoundedPort", ((TypeDefinition) scalarTargets[0]).getTypeDefTarget().getText());

        var enumTargets = gotoHandler.getGotoDeclarationTargets(psiFile.findElementAt(enumChildOffset), enumChildOffset, myFixture.getEditor());
        assertNotNull("Ctrl+Click on #CREATED must resolve", enumTargets);
        assertEquals(1, enumTargets.length);
        assertTrue(enumTargets[0] instanceof org.stvnadore.psi.ValueKeyword
            && enumTargets[0].getParent() instanceof org.stvnadore.psi.EnumDef);
        assertEquals("#CREATED", enumTargets[0].getText());

        var constTargets = gotoHandler.getGotoDeclarationTargets(psiFile.findElementAt(constChildOffset), constChildOffset, myFixture.getEditor());
        assertNotNull("Ctrl+Click on #DEFAULT_PORT must resolve", constTargets);
        assertEquals(1, constTargets.length);
        assertTrue(constTargets[0] instanceof ConstantDefinition);
        assertEquals("#DEFAULT_PORT", ((ConstantDefinition) constTargets[0]).getValueKeyword().getText());
    }

    private static void assertHighlightSpanAndTarget(HighlightInfo info, int expectedStart, int expectedEnd, String expectedTarget) {
        assertEquals("Mismatch startOffset for " + info.getDescription(), expectedStart, info.getStartOffset());
        assertEquals("Mismatch endOffset for " + info.getDescription(), expectedEnd, info.getEndOffset());
        assertTrue("Expected target '" + expectedTarget + "' in: " + info.getDescription(),
            info.getDescription() != null && info.getDescription().contains(
                "Scoped :use in hermetic document (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot target external package: " + expectedTarget
            ));
    }

    private static void assertModularE030Span(HighlightInfo info, int expectedStart, int expectedEnd, String expectedTarget) {
        assertEquals("Mismatch modular E030 startOffset for " + info.getDescription(), expectedStart, info.getStartOffset());
        assertEquals("Mismatch modular E030 endOffset for " + info.getDescription(), expectedEnd, info.getEndOffset());
        assertTrue("Modular unresolved :use must emit [stvn-core:E030-UNDEFINED_TYPE] for " + expectedTarget + ", got: " + info.getDescription(),
            info.getDescription() != null
                && info.getDescription().contains("[stvn-core:E030-UNDEFINED_TYPE]")
                && info.getDescription().contains(expectedTarget));
    }

    private static String stripOriginPrefix(String description) {
        return description.replace("[stvn-core:", "[ORIGIN:").replace("[stvn-plugin:", "[ORIGIN:");
    }
}
