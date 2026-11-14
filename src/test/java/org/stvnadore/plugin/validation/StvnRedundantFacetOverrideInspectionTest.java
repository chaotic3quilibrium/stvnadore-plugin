package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.printer.StvnSourceFormatter;

/**
 * Platform integration test suite validating {@link StvnRedundantFacetOverrideInspection}
 * and {@link StvnRedundantFacetOverrideInspection.RemoveRedundantFacetQuickFix} under
 * 1:1 {@code W003 / WARN_REDUNDANT_METADATA_FACET} deconfliction with {@code stvnadore-core}.
 */
@NullMarked
public final class StvnRedundantFacetOverrideInspectionTest extends BasePlatformTestCase {

    private static final String PLUGIN_W003_TAG = "[stvn-plugin:W003-WARN_REDUNDANT_METADATA_FACET]";
    private static final String CORE_W003_TAG = "[stvn-core:W003-WARN_REDUNDANT_METADATA_FACET]";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnRedundantFacetOverrideInspection()});
    }

    public void testRedundantDefaultFacetWarningAndFix() {
        var text = """
            {
              :defs {
                :Text { #preserveIndent #FALSE } :String
              }
              :type :Text
              :body "sample"
            }
            """;
        myFixture.configureByText("redundant_default.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(PLUGIN_W003_TAG))
            .toList();
        assertEquals("Expected strictly 1 W003 warning on '#preserveIndent #FALSE'", 1, warnings.size());
        assertTrue("Warning message must state canonical W003 tautology reason",
            warnings.get(0).getDescription().contains("facet '#preserveIndent #FALSE' is redundant; fenced strings trim common leading indentation by default"));
        assertTrue("Must emit zero duplicate [stvn-core:] W003 warnings",
            highlights.stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains(CORE_W003_TAG)));

        int offset = text.indexOf("#preserveIndent");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Applying quick-fix must remove single-facet map entirely:\n" + result,
            result.contains(":Text :String"));
        assertFalse("Result must not contain metadata map on :Text", result.contains(":Text {"));
        assertEquals("Quick-fix output must match StvnSourceFormatter byte-for-byte",
            StvnSourceFormatter.format(result) + "\n", result);
    }

    public void testRedundantInheritedFacetWarningAndFix() {
        var text = """
            {
              :defs {
                :Parent { #preserveIndent #TRUE } :String
                :Child { #preserveIndent #TRUE #minIncl "a" } :Parent
              }
              :type :Child
              :body "sample"
            }
            """;
        myFixture.configureByText("redundant_inherited.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(PLUGIN_W003_TAG))
            .toList();
        assertEquals("Expected exactly 1 warning on child definition", 1, warnings.size());
        assertTrue("Warning message must attribute ancestor ':Parent' and '#preserveIndent #TRUE'",
            warnings.get(0).getDescription().contains("ancestor ':Parent' already establishes '#preserveIndent #TRUE'"));

        int offset = text.indexOf("#preserveIndent #TRUE #minIncl");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected '#minIncl \"a\"' preserved in child metadata map:\n" + result,
            result.contains(":Child { #minIncl \"a\" } :Parent"));
        assertFalse("Child must not contain #preserveIndent", result.contains(":Child { #preserveIndent"));
        assertEquals("Quick-fix output must match StvnSourceFormatter byte-for-byte",
            StvnSourceFormatter.format(result) + "\n", result);
    }

    public void testActiveOverrideProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :Parent { #preserveIndent #TRUE } :String
                :Child { #preserveIndent #FALSE } :Parent
              }
              :type :Child
              :body "sample"
            }
            """;
        myFixture.configureByText("active_override.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Active intentional override must produce zero redundant warnings", warnings.isEmpty());
    }

    public void testRedundantNumericFacetWarning() {
        var text = """
            {
              :defs {
                :BoundedText { #maxSize 500 } :String
                :DerivedText { #maxSize 500 } :BoundedText
              }
              :type :DerivedText
              :body "sample"
            }
            """;
        myFixture.configureByText("redundant_numeric.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Derived #maxSize is a Tier-1 non-overridable facet governed by Core E040 (zero W003 warnings)", warnings.isEmpty());
        var e040Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E040-ERR_NON_OVERRIDABLE_FACET"))
            .toList();
        assertEquals("Expected strictly 1 E040 error on derived #maxSize override", 1, e040Errors.size());
    }

    public void testDerivedNominalExplicitDefaultMaxSizeOverrideStillFlagsRedundantOverride() {
        var text = """
            {
              :defs {
                :BoundedMax { #maxSize 16777216 } :String
                :DerivedMax { #maxSize 16777216 } :BoundedMax
              }
              :type :DerivedMax
              :body "sample"
            }
            """;
        myFixture.configureByText("derived_explicit_default_maxsize.stvn", text);
        var highlights = myFixture.doHighlighting();
        var derivedWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("(:DerivedMax)"))
            .toList();
        assertTrue(
            "Derived #maxSize is a Tier-1 non-overridable facet governed by Core E040 (zero W003 warnings on :DerivedMax): " + derivedWarnings,
            derivedWarnings.isEmpty()
        );
        var e040Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E040-ERR_NON_OVERRIDABLE_FACET"))
            .toList();
        assertEquals("Expected strictly 1 E040 error on :DerivedMax's #maxSize 16777216", 1, e040Errors.size());
    }

    public void testRootDefaultMaxSizeUnifiesWithPluginW003WithoutDuplicateWarnings() {
        var text = """
            {
              :defs {
                :RootStr { #minSize 1 #maxSize 16777216 } :String
                :RootExact { #exact #maxSize 16777216 } :Float
                :RootSeq { #minSize 1 #maxSize 16777216 } :Seq(:Int)
                :RootSet { #minSize 1 #maxSize 16777216 } :Set(:Int)
                :RootMap { #minSize 1 #maxSize 16777216 } :Map(:String :Int)
              }
              :type :RootStr
              :body "sample"
            }
            """;
        myFixture.configureByText("root_default_maxsize_w003_yield.stvn", text);
        var highlights = myFixture.doHighlighting();
        var pluginW003Warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(PLUGIN_W003_TAG))
            .toList();
        assertEquals("Expected strictly 5 [stvn-plugin:W003-WARN_REDUNDANT_METADATA_FACET] warnings across all 5 root declarations",
            5, pluginW003Warnings.size());
        assertTrue("Must emit zero duplicate [stvn-core:W003-WARN_REDUNDANT_METADATA_FACET] highlights",
            highlights.stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains(CORE_W003_TAG)));
    }

    public void testExactFloatWithEquatableTrueEmitsRedundantWarning() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact #equatable #TRUE } :Float
              }
              :type :FloatExactACorrect
              :body 1.0
            }
            """;
        myFixture.configureByText("exact_float_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(PLUGIN_W003_TAG))
            .toList();
        assertEquals("Expected exactly 1 warning on redundant facet '#equatable #TRUE'", 1, warnings.size());
        assertTrue("Warning message must mention '#equatable #TRUE'",
            warnings.get(0).getDescription().contains("'#equatable #TRUE'"));

        int offset = text.indexOf("#equatable #TRUE");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Applying quick-fix must preserve #exact in metadata map:\n" + result,
            result.contains(":FloatExactACorrect { #exact } :Float"));
        assertFalse("Result must not contain #equatable", result.contains("#equatable"));
    }

    public void testExactFloatWithEquatableFalseProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :FloatExactAOverride { #exact #equatable #FALSE } :Float
              }
              :type :FloatExactAOverride
              :body 1.0
            }
            """;
        myFixture.configureByText("exact_float_equatable_false.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Active intentional override {#exact #equatable #FALSE} must produce zero warnings", warnings.isEmpty());
    }

    public void testContinuousFloatWithEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :Float32A { #size 32 #equatable #TRUE } :Float
              }
              :type :Float32A
              :body 1.0
            }
            """;
        myFixture.configureByText("continuous_float_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Declaring {#size 32 #equatable #TRUE} on :Float is an active override and must produce zero warnings", warnings.isEmpty());
    }

    public void testContinuousFloatWithEquatableFalseEmitsRedundantWarning() {
        var text = """
            {
              :defs {
                :Float32Redundant { #size 32 #equatable #FALSE } :Float
              }
              :type :Float32Redundant
              :body 1.0
            }
            """;
        myFixture.configureByText("continuous_float_equatable_false.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(PLUGIN_W003_TAG))
            .toList();
        assertEquals("Expected exactly 1 warning on redundant continuous float equatable false", 1, warnings.size());
        assertTrue("Warning message must mention '#equatable #FALSE'",
            warnings.get(0).getDescription().contains("'#equatable #FALSE'"));
    }

    public void testTupleAllEquatableFieldsWithEquatableFalseProducesZeroWarningsOnTuple() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact } :Float
                :Float32A { #size 32 #equatable #TRUE } :Float
                :Float64A { #equatable #TRUE } :Float
                :Values { #equatable #FALSE } :Tuple(:FloatExactACorrect :Float32A :Float64A)
              }
              :type :Values
              :body (1.0 2.0 3.0)
            }
            """;
        myFixture.configureByText("tuple_all_equatable_false.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Active intentional override {#equatable #FALSE} on naturally equatable tuple must produce zero warnings", warnings.isEmpty());
    }

    public void testTupleAllEquatableFieldsWithEquatableTrueEmitsRedundantWarning() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact } :Float
                :Float32A { #size 32 #equatable #TRUE } :Float
                :Float64A { #equatable #TRUE } :Float
                :Values { #equatable #TRUE } :Tuple(:FloatExactACorrect :Float32A :Float64A)
              }
              :type :Values
              :body (1.0 2.0 3.0)
            }
            """;
        myFixture.configureByText("tuple_all_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(PLUGIN_W003_TAG))
            .toList();
        assertEquals("Expected exactly 1 warning on redundant tuple equatable true", 1, warnings.size());
        assertTrue("Warning message must mention '#equatable #TRUE'",
            warnings.get(0).getDescription().contains("'#equatable #TRUE'"));

        int offset = text.indexOf("#equatable #TRUE } :Tuple");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Applying quick-fix must remove single-facet map entirely:\n" + result,
            result.contains(":Values :Tuple(:FloatExactACorrect :Float32A :Float64A)"));
        assertFalse("Result must not contain metadata map on :Values", result.contains(":Values {"));
    }

    public void testTupleWithNonEquatableFieldAndEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact } :Float
                :FloatContinuous { #size 32 } :Float
                :Values { #equatable #TRUE } :Tuple(:FloatExactACorrect :FloatContinuous)
              }
              :type :Values
              :body (1.0 2.0)
            }
            """;
        myFixture.configureByText("tuple_non_equatable_field_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Active override restoring capability to tuple containing continuous float must produce zero warnings", warnings.isEmpty());
    }

    public void testFloatPlainEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :FloatPlain { #equatable #TRUE } :Float
              }
              :type :FloatPlain
              :body 1.0
            }
            """;
        myFixture.configureByText("float_plain_equatable.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Declaring {#equatable #TRUE} on unadorned :Float is an active override and must produce zero warnings", warnings.isEmpty());
    }

    public void testTupleEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :MyTuple { #equatable #TRUE } :Tuple(:Int :Float)
              }
              :type :MyTuple
              :body (42 1.0)
            }
            """;
        myFixture.configureByText("tuple_equatable.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Declaring {#equatable #TRUE} on :Tuple(:Int :Float) must produce zero redundant warnings", warnings.isEmpty());
    }

    public void testDerivedNominalTypesRepeatingIntrinsicDefaultsEmitOnlyE040AndZeroW003Warnings() {
        myFixture.configureByText("derived_nominal_no_w003.stvn_d", """
            {
              :meta {
                #name "derived_nominal_no_w003"
                #kind #DEFS
              }
              :defs {
                :Int32 :Int
                :Int32b { #size 32 } :Int32
                :Float64 :Float
                :Float64b { #size 64 } :Float64
                :Uint32a { #unsigned } :Int
                :ChildUInt { #size 32 } :Uint32a
              }
            }
            """);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertEquals("Derived nominal types repeating intrinsic defaults must emit 0 W003 warnings", 0, warnings.size());

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected exactly 3 E040 errors on ':Int32b', ':Float64b', and ':ChildUInt'", 3, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.getDescription().contains("E040-ERR_NON_OVERRIDABLE_FACET")));
    }

    public void testStage4Tier1ImplicitCardinalityAndFloatSizeLocksRenderE040AndQuickFixes() {
        var text = """
            {
              :defs {
                :ImplicitString :String
                :TrapOverrideImplicitStringMaxSize { #max<caret>Size 64 } :ImplicitString
                :ImplicitFloat64 :Float
                :TrapOverrideImplicitFloatSize { #size 32 } :ImplicitFloat64
              }
              :type :ImplicitString
              :body "ok"
            }
            """;
        myFixture.configureByText("stage4_tier1_implicit_locks.stvn", text);
        var highlights = myFixture.doHighlighting();

        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
            .toList();
        assertTrue("Expected zero warnings on derived Tier-1 locks: " + warnings, warnings.isEmpty());

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected strictly 2 E040 errors", 2, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.getDescription().contains("E040-ERR_NON_OVERRIDABLE_FACET")));

        var removeActions = myFixture.filterAvailableIntentions("Remove non-overridable facet '#maxSize 64'");
        assertFalse("Expected 'Remove non-overridable facet' quick-fix", removeActions.isEmpty());
        var rebaseActions = myFixture.filterAvailableIntentions("Change base type from ':ImplicitString' to primitive ':String'");
        assertFalse("Expected 'Change base type to primitive' quick-fix", rebaseActions.isEmpty());
    }

    public void testRedundantStringDefaultsZeroVsAll26InspectionsDeconflictionAndFormatterParity() {
        var text = """
            {
              :defs {
                :RedundantStringDefaults { #minSize 0 #maxSize 16777216 #preserveIndent #FALSE } :String
              }
              :type :RedundantStringDefaults
              :body "ok"
            }
            """;
        var allStvnInspections = LocalInspectionEP.LOCAL_INSPECTION.getExtensionList().stream()
            .filter(ep -> "STVN".equals(ep.language))
            .map(ep -> (LocalInspectionTool) ep.instantiateTool())
            .toArray(LocalInspectionTool[]::new);
        assertEquals(26, allStvnInspections.length);

        // Mode 1: All 26 inspections enabled -> strictly 1 [stvn-plugin:W003-...] per facet, 0 [stvn-core:...]
        myFixture.enableInspections(allStvnInspections);
        try {
            myFixture.configureByText("redundant_string_defaults.stvn", text);
            var highlights = myFixture.doHighlighting();
            var w003Highlights = highlights.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
                .toList();
            assertEquals("Expected strictly 3 W003 highlights (zero stacking): " + w003Highlights, 3, w003Highlights.size());
            assertTrue("All 3 must carry [stvn-plugin:W003-WARN_REDUNDANT_METADATA_FACET]",
                w003Highlights.stream().allMatch(h -> h.getDescription().contains(PLUGIN_W003_TAG)));
            assertTrue("Must contain zero legacy WARN_REDUNDANT_FACET_OVERRIDE tags",
                highlights.stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains("WARN_REDUNDANT_FACET_OVERRIDE")));
            assertTrue("Must contain zero [stvn-core:W003-...] duplicates",
                highlights.stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains(CORE_W003_TAG)));
        } finally {
            myFixture.disableInspections(allStvnInspections);
        }

        // Mode 2: 0 local inspections enabled -> StvnExternalAnnotator emits identical 3 spans as [stvn-core:W003-...]
        myFixture.disableInspections(new StvnRedundantFacetOverrideInspection());
        myFixture.configureByText("redundant_string_defaults_core.stvn", text);
        var coreHighlights = myFixture.doHighlighting();
        var coreW003 = coreHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(CORE_W003_TAG))
            .toList();
        assertEquals("With 0 local inspections, Core annotator must emit all 3 W003 spans", 3, coreW003.size());

        int preserveOffset = text.indexOf("#preserveIndent #FALSE");
        myFixture.getEditor().getCaretModel().moveToOffset(preserveOffset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet '#preserveIndent #FALSE'");
        assertFalse("RemoveRedundantFacetQuickFix must also be registered via StvnExternalAnnotator", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var afterSingleRemoval = myFixture.getFile().getText();
        assertTrue(afterSingleRemoval.contains(":RedundantStringDefaults { #minSize 0 #maxSize 16777216 } :String"));
        assertEquals("Multi-facet quick-fix output must match StvnSourceFormatter byte-for-byte",
            StvnSourceFormatter.format(afterSingleRemoval) + "\n", afterSingleRemoval);
    }

    public void testBrokenSyntaxPass1PurePsiFallbackAcrossNominalOverrides() {
        var brokenSyntaxText = """
            {
              :defs {
                :ParentInt { #minIncl 10 #maxExcl 100 } :Int
                :ChildInt { #minIncl 10 #maxExcl 100 } :ParentInt
                :ParentFloat { #exact #minIncl 0.0 #maxExcl 50.0 } :Float
                :ChildFloat { #minIncl 0.00 #maxExcl 50.0 } :ParentFloat
                :ParentStr { #minIncl "a" #maxExcl "z" #preserveIndent #TRUE #regex "^[a-z]+$" } :String
                :ChildStr { #minIncl "a" #maxExcl "z" #preserveIndent #TRUE #regex "^[a-z]+$" } :ParentStr
                :ParentEpoch { #s #minIncl 1700000000 } :TimeEpoch
                :ChildEpoch { #minIncl 1700000000 } :ParentEpoch
                :ParentDt { #offset #minIncl "2026-01-01T00:00:00Z" } :DateTime
                :ChildDt { #minIncl "2026-01-01T00:00:00+00:00" } :ParentDt
                :Role :Enum [ #READ #WRITE #ADMIN ]
                :SubRole { #filterIncl [ #READ #WRITE #ADMIN ] } :Role
              }
              :type :ChildInt
              :body (
            """;
        myFixture.configureByText("broken_syntax_psi_fallback.stvn", brokenSyntaxText);
        var highlights = myFixture.doHighlighting();
        var pluginWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains(PLUGIN_W003_TAG))
            .toList();
        assertEquals(
            "Pure-PSI fallback must emit all 11 nominal-override W003 warnings even when Pass 1 syntax is unclosed: " + pluginWarnings,
            11,
            pluginWarnings.size()
        );
        assertTrue("Enum #filterIncl fallback must emit zero-bracket-padded '[#READ #WRITE #ADMIN]'",
            pluginWarnings.stream().anyMatch(h -> h.getDescription().contains("facet '#filterIncl [#READ #WRITE #ADMIN]' is redundant")));
    }
}
