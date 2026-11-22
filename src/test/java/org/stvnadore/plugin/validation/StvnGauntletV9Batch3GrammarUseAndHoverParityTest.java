package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.browser.StvnNamespaceScope;
import org.stvnadore.plugin.browser.StvnNamespaceSymbolCollector;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.TypeDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * End-to-end IntelliJ Platform parity suite for Gauntlet v9 Batch 3 ({@code PB3-01} through {@code PB3-04}):
 * <ul>
 *   <li>{@code PB3-01}: Grammar-Kit {@code stvn.bnf} zero-cascade constructor homology across all 8 schema
 *       constructors, bounds-safe PSI accessors, Safeguard 2 interior lexical error non-expansion, and
 *       {@code Pass A} vs. {@code Pass B} diagnostic parity.</li>
 *   <li>{@code PB3-02}: Multi-element {@code :use [ ... ]} navigation, left-to-right § 4.2 eviction,
 *       2nd/3rd {@code UseElement} code completion, element-scoped quick-fixes, and {@code #strip <arg>}
 *       {@code Pass A} vs. {@code Pass B} parity.</li>
 *   <li>{@code PB3-03}: Stage 7 degraded hover exemplar suppression, Safeguard 3 guarded recursive
 *       {@code :TreeNode} exemplar preservation, {@code minSize} collection synthesis, coordinate-overlap
 *       {@code Status: Invalid} facet badges, and inductive constituent trait derivation.</li>
 * </ul>
 */
@NullMarked
public final class StvnGauntletV9Batch3GrammarUseAndHoverParityTest extends BasePlatformTestCase {

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

    public void testPb301_EmptyAndWrongArityConstructorsPassAAndPassBParity() throws IOException {
        Path zeroCascadePath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/composites/empty_and_wrong_arity_constructors_zero_cascade.stvn"
        );
        String zeroCascadeContent = Files.readString(zeroCascadePath, StandardCharsets.UTF_8);

        // Pass A (0 inspections): exact 19 ERROR highlights matching .contract.stvn_i
        myFixture.configureByText("empty_and_wrong_arity_constructors_zero_cascade.stvn", zeroCascadeContent);
        var passAErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit exact 19 ERROR highlights matching .contract.stvn_i: " + passAErrors,
            19, passAErrors.size());
        assertEquals(":Enum[]", passAErrors.get(0).getText());
        assertEquals(":Enum [   ]", passAErrors.get(1).getText());
        assertEquals(":Tuple()", passAErrors.get(2).getText());
        assertTrue(passAErrors.get(2).getDescription().contains(":Tuple() requires at least one schema type"));
        assertEquals(":Tuple (   )", passAErrors.get(3).getText());
        assertTrue(passAErrors.get(3).getDescription().contains(":Tuple() requires at least one schema type"));
        assertEquals(":Union()", passAErrors.get(4).getText());
        assertTrue(passAErrors.get(4).getDescription().contains(":Union() requires at least 2 schema types"));
        assertEquals(":Union (   )", passAErrors.get(5).getText());
        assertTrue(passAErrors.get(5).getDescription().contains(":Union() requires at least 2 schema types"));
        assertEquals(":Option()", passAErrors.get(6).getText());
        assertEquals(":Option(:Int :String)", passAErrors.get(7).getText());
        assertEquals(":Seq()", passAErrors.get(8).getText());
        assertEquals(":Seq(:Int :String)", passAErrors.get(9).getText());
        assertEquals(":Set()", passAErrors.get(10).getText());
        assertEquals(":Set(:Int :String)", passAErrors.get(11).getText());
        assertEquals(":Either()", passAErrors.get(12).getText());
        assertEquals(":Either(:Int)", passAErrors.get(13).getText());
        assertEquals(":Either(:Int :String :Boolean)", passAErrors.get(14).getText());
        assertEquals(":Map()", passAErrors.get(15).getText());
        assertEquals(":Map(:String)", passAErrors.get(16).getText());
        assertEquals(":Map(:String :Int :Boolean)", passAErrors.get(17).getText());
        assertEquals("0", passAErrors.get(18).getText());

        // Pass B (all 26 inspections): exact 19 ERROR highlights with 0 duplicates and 0 false W003
        enableAll26StvnInspections();
        PsiFile zeroCascadeFile = myFixture.configureByText(
            "empty_and_wrong_arity_constructors_zero_cascade.stvn", zeroCascadeContent
        );
        var passBHighlights = myFixture.doHighlighting();
        var passBErrors = passBHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit exact 19 ERROR highlights with 0 duplicates: " + passBErrors,
            19, passBErrors.size());
        assertTrue("0-arity/wrong-arity constructors must never emit vacuous W003 redundant facet warnings",
            passBHighlights.stream().noneMatch(h -> h.getDescription() != null
                && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET")));

        // Verify bounds-safe exemplar generation and hover on 0-arity and under-arity definitions
        var docProvider = new StvnDocumentationProvider();
        for (TypeDefinition td : PsiTreeUtil.findChildrenOfType(zeroCascadeFile, TypeDefinition.class)) {
            String name = td.getTypeDefTarget().getText();
            if (!":ValidPort".equals(name)) {
                assertNull("Malformed constructor " + name + " must never fabricate an exemplar",
                    StvnExemplarValueGenerator.generateExemplar(td.getSchemaType(), null));
                String hover = generateHoverAt(docProvider, zeroCascadeFile, td.getTextRange().getStartOffset() + 1);
                assertFalse("Malformed constructor " + name + " hover must omit Exemplar Value:\n" + hover,
                    hover.contains("Exemplar Value:"));
            }
        }

        // Safeguard 2: interior lexical error inside :Tuple( @ ) must NOT expand across outer :Tuple( @ )
        String interiorLexicalDoc = """
            {
              :meta {
                #name "interior_lexical_error_pin"
                #kind #DEFS
              }
              :defs {
                :BadLexicalTuple :Tuple( @ )
              }
            }
            """;
        myFixture.configureByText("interior_lexical_error_pin.stvn_d", interiorLexicalDoc);
        var lexicalErrors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertFalse("Expected lexical error on '@'", lexicalErrors.isEmpty());
        assertTrue("Safeguard 2: interior '@' lexical error must NOT expand across ':Tuple( @ )': " + lexicalErrors,
            lexicalErrors.stream().noneMatch(h -> ":Tuple( @ )".equals(h.getText())));
    }

    public void testPb302_MultiElementUseNavigationEvictionQuickFixesAndPassBParity() throws IOException {
        Path validUsePath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/valid/modules/multi_element_use_scoping_and_eviction.core_test.stvn"
        );
        String validUseContent = Files.readString(validUsePath, StandardCharsets.UTF_8);

        // Pass A & Pass B: 0 ERRORs on multi-element :use scoping and eviction fixture
        myFixture.configureByText("multi_element_use_scoping_and_eviction.core_test.stvn", validUseContent);
        assertTrue("Pass A must report 0 ERRORs on valid multi-element :use fixture",
            myFixture.doHighlighting().stream().noneMatch(h -> h.getSeverity() == HighlightSeverity.ERROR));

        enableAll26StvnInspections();
        PsiFile validFile = myFixture.configureByText(
            "multi_element_use_scoping_and_eviction.core_test.stvn", validUseContent
        );
        assertTrue("Pass B must report 0 ERRORs on valid multi-element :use fixture",
            myFixture.doHighlighting().stream().noneMatch(h -> h.getSeverity() == HighlightSeverity.ERROR));

        // 1-hop #strip resolution & 2-hop UseMapAlias resolution across 1st, 2nd, 3rd, and 4th UseElement
        int dispatchIdx = validUseContent.indexOf(":DispatchEnvelope :Tuple(");
        PsiElement thrustResolved = resolveReferenceAt(
            validFile, validUseContent.indexOf(":ThrustMillinewtons", dispatchIdx) + 2
        );
        TypeDefinition thrustDef = PsiTreeUtil.getParentOfType(thrustResolved, TypeDefinition.class, false);
        assertNotNull("1st UseElement #strip :ThrustMillinewtons must resolve inside TypeDefinition", thrustDef);
        PackageEnclosure thrustPkg = PsiTreeUtil.getParentOfType(thrustDef, PackageEnclosure.class);
        assertNotNull(thrustPkg);
        assertEquals(":propulsion/thruster", thrustPkg.getPackagePath().getText());

        PsiElement attitudeThrustAlias = resolveReferenceAt(
            validFile, validUseContent.indexOf(":AttitudeThrust", dispatchIdx) + 2
        );
        assertNotNull("2nd UseElement alias :AttitudeThrust must resolve to UseMapAlias RHS", attitudeThrustAlias);
        int secondElemAliasOffset = validUseContent.indexOf(":ThrustMillinewtons :AttitudeThrust") + 2;
        PsiElement attitudeThrustResolved = resolveReferenceAt(validFile, secondElemAliasOffset);
        TypeDefinition attitudeThrustDef = PsiTreeUtil.getParentOfType(attitudeThrustResolved, TypeDefinition.class, false);
        assertNotNull("2nd UseElement LHS :ThrustMillinewtons must resolve inside TypeDefinition", attitudeThrustDef);
        PackageEnclosure attitudePkg = PsiTreeUtil.getParentOfType(attitudeThrustDef, PackageEnclosure.class);
        assertNotNull(attitudePkg);
        assertEquals(":propulsion/attitude", attitudePkg.getPackagePath().getText());

        PsiElement telemetryResolved = resolveReferenceAt(
            validFile, validUseContent.indexOf(":TelemetrySample", dispatchIdx) + 2
        );
        assertNotNull("3rd UseElement #strip :TelemetrySample must resolve inside TypeDefinition",
            PsiTreeUtil.getParentOfType(telemetryResolved, TypeDefinition.class, false));

        PsiElement portAliasResolved = resolveReferenceAt(
            validFile, validUseContent.indexOf(":Port\n    )", dispatchIdx) + 2
        );
        assertNotNull("4th UseElement prelude alias :Port must resolve", portAliasResolved);

        int bodyIdx = validUseContent.indexOf(":body (");
        PsiElement rcsConstResolved = resolveReferenceAt(
            validFile, validUseContent.indexOf("#RCS_PULSE_THRUST", bodyIdx) + 2
        );
        assertNotNull("1st UseElement #strip constant #RCS_PULSE_THRUST must resolve inside ConstantDefinition",
            PsiTreeUtil.getParentOfType(rcsConstResolved, ConstantDefinition.class, false));

        // Hover on 2nd UseElement constant alias #ATTITUDE_PULSE
        var docProvider = new StvnDocumentationProvider();
        String aliasHover = generateHoverAt(
            docProvider, validFile, validUseContent.indexOf("#ATTITUDE_PULSE", bodyIdx) + 2
        );
        assertTrue("Hover on 2nd UseElement constant alias must display Renamed Reference:\n" + aliasHover,
            aliasHover.contains("#ATTITUDE_PULSE") && aliasHover.contains("RCS_PULSE_THRUST"));

        // StvnNamespaceSymbolCollector symbol collection across multi-element :use
        var typeScopeSymbols = StvnNamespaceSymbolCollector.collectSymbols(validFile, StvnNamespaceScope.TYPE);
        assertTrue("Collector must expose 2nd UseElement alias :AttitudeThrust",
            typeScopeSymbols.stream().anyMatch(e -> ":AttitudeThrust".equals(e.name())));
        assertTrue("Collector must expose 3rd UseElement #strip symbol :TelemetrySample",
            typeScopeSymbols.stream().anyMatch(e -> ":TelemetrySample".equals(e.name())));
        assertTrue("Collector must expose 4th UseElement alias :Port",
            typeScopeSymbols.stream().anyMatch(e -> ":Port".equals(e.name())));

        // Invalid multi-element :use isolated target errors: Pass A vs. Pass B parity (exact 4 ERRORs)
        Path invalidUsePath = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/modules/multi_element_use_isolated_target_errors.core_test.stvn_i"
        );
        String invalidUseContent = Files.readString(invalidUsePath, StandardCharsets.UTF_8);

        var testProject = getProject();
        assertNotNull(testProject);
        // Disable inspections temporarily to verify Pass A, then re-enable for Pass B
        myFixture.disableInspections(
            new StvnTrailingSlashInspection(),
            new StvnMetadataFacetInspection(),
            new StvnUnresolvedTypeInspection(),
            new StvnFlatDocumentIncludeInspection()
        );
        myFixture.configureByText("multi_element_use_isolated_target_errors.core_test.stvn_i", invalidUseContent);
        var invalidPassA = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass A must emit exact 4 ERRORs on isolated multi-element :use fixture: " + invalidPassA,
            4, invalidPassA.size());
        assertEquals(":local/alpha/", invalidPassA.get(0).getText());
        assertEquals("{}", invalidPassA.get(1).getText());
        assertEquals("#TRUE", invalidPassA.get(2).getText());
        assertEquals(":external/missing/pkg", invalidPassA.get(3).getText());

        enableAll26StvnInspections();
        myFixture.configureByText("multi_element_use_isolated_target_errors.core_test.stvn_i", invalidUseContent);
        var invalidPassB = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Pass B must emit exact 4 ERRORs (0 false E004 on '{ #strip #TRUE }' and 0 errors on 5th sibling :ServicePort): "
            + invalidPassB, 4, invalidPassB.size());
        assertEquals(":local/alpha/", invalidPassB.get(0).getText());
        assertEquals("{}", invalidPassB.get(1).getText());
        assertEquals("#TRUE", invalidPassB.get(2).getText());
        assertEquals(":external/missing/pkg", invalidPassB.get(3).getText());

        // Element-scoped RemoveUseStatementQuickFix (E035) preserves healthy sibling UseElement
        String hermeticMultiUse = """
            {
              :meta {
                #name "hermetic_multi_use_fix"
                #kind #BODY
              }
              :defs {
                :package :local/good {
                  :GoodId :Int
                }
                :use [
                  :external/bad { #strip }
                  :local/good { #strip }
                ]
              }
              :type :GoodId
              :body 42
            }
            """;
        myFixture.configureByText("hermetic_multi_use_fix.stvn", hermeticMultiUse);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(hermeticMultiUse.indexOf(":external/bad") + 2);
        var removeElemFix = myFixture.findSingleIntention("Remove :use directive for ':external/bad'");
        assertNotNull("Expected element-scoped RemoveUseStatementQuickFix", removeElemFix);
        myFixture.launchAction(removeElemFix);
        String afterRemove = myFixture.getFile().getText();
        assertFalse("Removed UseElement ':external/bad' must be deleted: " + afterRemove,
            afterRemove.contains(":external/bad"));
        assertTrue("Healthy sibling UseElement ':local/good { #strip }' must remain intact: " + afterRemove,
            afterRemove.contains(":local/good { #strip }"));

        // StvnImportUseQuickFix appends alias block to matching UseElement without corrupting sibling UseElements
        String appendMultiUse = """
            {
              :meta {
                #name "append_multi_use_fix"
                #kind #BODY
              }
              :defs {
                :package :local/first {
                  :FirstId :Int
                }
                :package :local/second {
                  :SecondId :Int
                  :ExtraId :String
                }
                :use [
                  :local/first { :FirstId :FirstId }
                  :local/second { :SecondId :SecondId }
                ]
              }
              :type :ExtraId
              :body "ok"
            }
            """;
        myFixture.configureByText("append_multi_use_fix.stvn", appendMultiUse);
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(appendMultiUse.indexOf(":type :ExtraId") + ":type ".length() + 1);
        var importFix = myFixture.findSingleIntention(
            "Import ':ExtraId' from :local/second via :use"
        );
        assertNotNull("Expected StvnImportUseQuickFix on :ExtraId", importFix);
        myFixture.launchAction(importFix);
        String afterImport = myFixture.getFile().getText();
        assertTrue("1st sibling UseElement must remain intact: " + afterImport,
            afterImport.contains(":local/first { :FirstId :FirstId }"));
        assertTrue("2nd UseElement must receive ':ExtraId :ExtraId': " + afterImport,
            afterImport.contains(":ExtraId :ExtraId"));
    }

    public void testPb302_SecondAndThirdUseElementCompletion() {
        String secondTargetDoc = """
            {
              :meta {
                #name "multi_use_completion"
                #kind #DEFS
              }
              :defs {
                :package :local/alpha {
                  :AlphaCode :String
                }
                :package :local/beta {
                  :BetaCount :Int
                  #BETA_CONST :Int 7
                }
                :use [ :local/alpha { #strip } :<caret> ]
              }
            }
            """;
        myFixture.configureByText("multi_use_completion.stvn_d", secondTargetDoc);
        LookupElement[] targetItems = myFixture.completeBasic();
        assertNotNull("Expected completion items on 2nd UseElement target", targetItems);
        List<String> targetStrings = Arrays.stream(targetItems).map(LookupElement::getLookupString).toList();
        assertTrue("2nd UseElement target must offer ':local/beta': " + targetStrings,
            targetStrings.contains(":local/beta"));
        assertTrue("2nd UseElement target must offer ':org/stvnadore/prelude': " + targetStrings,
            targetStrings.contains(":org/stvnadore/prelude"));

        // 3rd UseElement alias block completion offers #strip AND exported :Type / #CONST from 3rd UseTarget
        String thirdBlockDoc = """
            {
              :meta {
                #name "multi_use_block_completion"
                #kind #DEFS
              }
              :defs {
                :package :local/alpha {
                  :AlphaCode :String
                }
                :package :local/beta {
                  :BetaCount :Int
                  #BETA_CONST :Int 7
                }
                :use [
                  :local/alpha { #strip }
                  :org/stvnadore/prelude { :Port :Port }
                  :local/beta { <caret> }
                ]
              }
            }
            """;
        myFixture.configureByText("multi_use_block_completion.stvn_d", thirdBlockDoc);
        LookupElement[] blockItems = myFixture.completeBasic();
        assertNotNull("Expected completion items inside 3rd UseElement block", blockItems);
        List<String> blockStrings = Arrays.stream(blockItems).map(LookupElement::getLookupString).toList();
        assertTrue("3rd UseElement block must offer '#strip': " + blockStrings,
            blockStrings.contains("#strip"));
        assertTrue("3rd UseElement block must offer exported ':BetaCount': " + blockStrings,
            blockStrings.contains(":BetaCount"));
        assertTrue("3rd UseElement block must offer exported '#BETA_CONST': " + blockStrings,
            blockStrings.contains("#BETA_CONST"));
        assertFalse("3rd UseElement block must NOT leak 1st UseElement ':AlphaCode': " + blockStrings,
            blockStrings.contains(":AlphaCode"));
    }

    public void testPb303_Stage7DegradedHoverInvalidFacetsInductiveTraitsAndMinSizeExemplars() throws IOException {
        Path stage7Path = SHARED_FIXTURES_ROOT.resolve(
            "syntax/invalid/composites/stage7_and_stage8_empty_composite_defs_isolation.stvn"
        );
        String stage7Content = Files.readString(stage7Path, StandardCharsets.UTF_8);

        enableAll26StvnInspections();
        PsiFile stage7File = myFixture.configureByText(
            "stage7_and_stage8_empty_composite_defs_isolation.stvn", stage7Content
        );
        var stage7Errors = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Stage 7/8 isolation fixture must emit exact 5 ERRORs in Pass B: " + stage7Errors,
            5, stage7Errors.size());

        var docProvider = new StvnDocumentationProvider();

        // 1. :TrapInvertedSeqCapacity: Degraded banner, BOTH #minSize and #maxSize marked Invalid, NO Exemplar Value
        String invertedHover = generateHoverAt(
            docProvider, stage7File, stage7Content.indexOf(":TrapInvertedSeqCapacity") + 2
        );
        assertTrue("Expected Degraded Schema Warning on :TrapInvertedSeqCapacity:\n" + invertedHover,
            invertedHover.contains("Degraded Schema Warning"));
        assertFalse("Degraded :TrapInvertedSeqCapacity must omit Exemplar Value:\n" + invertedHover,
            invertedHover.contains("Exemplar Value:"));
        assertTrue("Both inverted #minSize and #maxSize must render Status: Invalid:\n" + invertedHover,
            countOccurrences(invertedHover, ">Invalid<") >= 2);
        assertFalse("Inverted #minSize/#maxSize must NOT claim Active Override:\n" + invertedHover,
            invertedHover.contains("Active Override"));

        // 2. :TrapFloatInSet: Degraded banner, #equatable #FALSE with Derived (non-equatable: :SampleReading),
        //    Non-Conforming Constituents link, and NO Exemplar Value
        String floatSetHover = generateHoverAt(
            docProvider, stage7File, stage7Content.indexOf(":TrapFloatInSet") + 2
        );
        assertTrue("Expected Degraded Schema Warning on :TrapFloatInSet:\n" + floatSetHover,
            floatSetHover.contains("Degraded Schema Warning"));
        assertFalse("Degraded :TrapFloatInSet must omit Exemplar Value:\n" + floatSetHover,
            floatSetHover.contains("Exemplar Value:"));
        assertTrue("Expected #equatable #FALSE with Derived (non-equatable: :SampleReading):\n" + floatSetHover,
            floatSetHover.contains("Derived (non-equatable: :SampleReading)"));
        assertTrue("Expected Non-Conforming Constituents block with Element: :SampleReading on :TrapFloatInSet:\n" + floatSetHover,
            floatSetHover.contains("Non-Conforming Constituents:")
                && floatSetHover.contains("Element:")
                && floatSetHover.contains("<code>:SampleReading</code>"));

        // 3. Packaged FQNI + pure-PSI unlowered TypeDefinition fallback + :Map invertible vs non-invertible + nested inline
        String brokenAndTraitsDoc = """
            {
              :meta {
                #name "broken_buffer_and_inductive_traits"
                #kind #DEFS
              }
              :defs {
                :package :telemetry {
                  :SampleFloat :Float
                  :PkgInverted { #minSize 12 #equatable #FALSE #maxSize 4 } :Seq(:String)
                  :NonInvMap :Map(:String :SampleFloat)
                  :InvMap { #invertible } :Map(:String :SampleFloat)
                  :NestedInlineSet :Set(:Tuple({ #exact } :Float :Float))
                }
              }
              :defs {
                :UnloweredInverted { #minSize 12 #equatable #FALSE #maxSize 4 } :Seq(:String)
              }
            }
            """;
        PsiFile brokenFile = myFixture.configureByText("broken_buffer_and_inductive_traits.stvn_d", brokenAndTraitsDoc);
        TypeDefinition unloweredDef = PsiTreeUtil.findChildrenOfType(brokenFile, TypeDefinition.class).stream()
            .filter(td -> ":UnloweredInverted".equals(td.getTypeDefTarget().getText()))
            .findFirst()
            .orElseThrow();
        assertNull("Unlowered secondary :defs TypeDefinition must leave nominal IR schema null",
            StvnTypeResolver.resolveNominalIrSchema(unloweredDef));

        String pkgInvHover = generateHoverAt(
            docProvider, brokenFile, brokenAndTraitsDoc.indexOf(":PkgInverted") + 2
        );
        assertTrue("Packaged :telemetry/PkgInverted must mark non-adjacent #minSize and #maxSize Invalid:\n" + pkgInvHover,
            countOccurrences(pkgInvHover, ">Invalid<") >= 2);
        assertFalse("Packaged :telemetry/PkgInverted must omit Exemplar Value:\n" + pkgInvHover,
            pkgInvHover.contains("Exemplar Value:"));

        String unloweredInvHover = generateHoverAt(
            docProvider, brokenFile, brokenAndTraitsDoc.indexOf(":UnloweredInverted") + 2
        );
        assertTrue("Pure-PSI fallback (nominal IR null) must still mark #minSize and #maxSize Invalid:\n" + unloweredInvHover,
            countOccurrences(unloweredInvHover, ">Invalid<") >= 2);
        assertFalse("Pure-PSI fallback on inverted bounds must omit Exemplar Value:\n" + unloweredInvHover,
            unloweredInvHover.contains("Exemplar Value:"));

        String nonInvMapHover = generateHoverAt(
            docProvider, brokenFile, brokenAndTraitsDoc.indexOf(":NonInvMap") + 2
        );
        assertTrue("Non-invertible :Map(:String :SampleFloat) must derive #equatable #TRUE:\n" + nonInvMapHover,
            nonInvMapHover.contains("<code>#equatable</code></td><td style=\"padding: 2px 4px;\"><code>#TRUE</code>"));

        String invMapHover = generateHoverAt(
            docProvider, brokenFile, brokenAndTraitsDoc.indexOf(":InvMap") + 2
        );
        assertTrue("Invertible :Map(:String :SampleFloat) must derive #equatable #FALSE from Value: :SampleFloat:\n" + invMapHover,
            invMapHover.contains("Derived (non-equatable: :SampleFloat)")
                && invMapHover.contains("Non-Conforming Constituents:")
                && invMapHover.contains("Value:"));

        String nestedInlineSetHover = generateHoverAt(
            docProvider, brokenFile, brokenAndTraitsDoc.indexOf(":NestedInlineSet") + 2
        );
        assertTrue("Nested inline :Set(:Tuple({ #exact } :Float :Float)) must derive #equatable #FALSE with Element: :Float:\n"
            + nestedInlineSetHover,
            nestedInlineSetHover.contains("Derived (non-equatable: :Float)")
                && nestedInlineSetHover.contains("Element:")
                && nestedInlineSetHover.contains("<code>:Float</code>"));

        // 4. Safeguard 3 (:TreeNode recursion) + minSize collection synthesis + bounded > 16 preview + uninhabited :Set(:Boolean)
        String exemplarsDoc = """
            {
              :meta {
                #name "exemplar_synthesis_parity"
                #kind #DEFS
              }
              :defs {
                :TreeNode :Tuple(:String :Seq(:TreeNode))
                :ThreeSeq { #minSize 3 #maxSize 5 } :Seq(:Int)
                :ThreeSet { #minSize 3 #maxSize 5 } :Set(:Int)
                :TwoMap { #invertible #minSize 2 #maxSize 4 } :Map(:String :Int)
                :HugeSeq { #minSize 25 #maxSize 30 } :Seq(:Int)
                :ImpossibleBoolSet { #minSize 3 #maxSize 4 } :Set(:Boolean)
              }
            }
            """;
        PsiFile exemplarsFile = myFixture.configureByText("exemplar_synthesis_parity.stvn_d", exemplarsDoc);

        String treeHover = generateHoverAt(docProvider, exemplarsFile, exemplarsDoc.indexOf(":TreeNode") + 2);
        assertTrue("Safeguard 3: guarded recursive :TreeNode must still synthesize (\"example\" []):\n" + treeHover,
            treeHover.contains("(\"example\" [])"));

        String threeSeqHover = generateHoverAt(docProvider, exemplarsFile, exemplarsDoc.indexOf(":ThreeSeq") + 2);
        assertTrue("#minSize 3 :Seq(:Int) must synthesize 3 elements:\n" + threeSeqHover,
            threeSeqHover.contains("[0 0 0]"));

        String threeSetHover = generateHoverAt(docProvider, exemplarsFile, exemplarsDoc.indexOf(":ThreeSet") + 2);
        assertTrue("#minSize 3 :Set(:Int) must synthesize 3 distinct elements:\n" + threeSetHover,
            threeSetHover.contains("[0 1 2]"));

        String twoMapHover = generateHoverAt(docProvider, exemplarsFile, exemplarsDoc.indexOf(":TwoMap") + 2);
        assertTrue("#minSize 2 #invertible :Map(:String :Int) must synthesize 2 distinct key/value pairs:\n" + twoMapHover,
            twoMapHover.contains("{ [\"example\" 0] [\"example_1\" 1] }"));

        TypeDefinition hugeSeqDef = PsiTreeUtil.findChildrenOfType(exemplarsFile, TypeDefinition.class).stream()
            .filter(td -> ":HugeSeq".equals(td.getTypeDefTarget().getText()))
            .findFirst()
            .orElseThrow();
        var hugeSeqIr = StvnTypeResolver.resolveNominalIrSchema(hugeSeqDef);
        assertNotNull(hugeSeqIr);
        assertNull("generateExemplar must return null when minSize > 16 cannot be materialized verbatim",
            StvnExemplarValueGenerator.generateExemplar(
                hugeSeqDef.getSchemaType(), hugeSeqIr
            ));
        String hugeSeqHover = generateHoverAt(docProvider, exemplarsFile, exemplarsDoc.indexOf(":HugeSeq") + 2);
        assertTrue("Hover on valid #minSize 25 :Seq(:Int) must render bounded '... (#minSize 25)' preview:\n" + hugeSeqHover,
            hugeSeqHover.contains("... (#minSize 25)"));

        String impossibleSetHover = generateHoverAt(
            docProvider, exemplarsFile, exemplarsDoc.indexOf(":ImpossibleBoolSet") + 2
        );
        assertFalse("Uninhabited { #minSize 3 } :Set(:Boolean) must omit Exemplar Value:\n" + impossibleSetHover,
            impossibleSetHover.contains("Exemplar Value:"));
    }

    private PsiElement resolveReferenceAt(PsiFile file, int offset) {
        var ref = file.findReferenceAt(offset);
        assertNotNull("Expected PSI reference at offset " + offset, ref);
        PsiElement resolved = ref.resolve();
        assertNotNull("Expected PSI reference to resolve at offset " + offset, resolved);
        return resolved;
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
