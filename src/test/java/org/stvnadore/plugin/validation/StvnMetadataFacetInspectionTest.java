package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Tests for StvnMetadataFacetInspection and associated quick-fixes.
 */
@NullMarked
public final class StvnMetadataFacetInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnMetadataFacetInspection());
    }

    public void testEmptyMetadataBlockOnTypeQuickFix() {
        var text = """
            {
              :defs {
                :InvalidPort {} :Uint16
              }
              :type :InvalidPort
              :body 8080
            }
            """;
        myFixture.configureByText("test.stvn", text);
        int offset = text.indexOf("{}");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();
        var actions = myFixture.filterAvailableIntentions("Remove empty type constraint block");
        assertFalse("Expected quick fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
            {
              :defs {
                :InvalidPort :Uint16
              }
              :type :InvalidPort
              :body 8080
            }
            """);
    }

    public void testEmptyDirectiveBlockInIncludeQuickFix() {
        myFixture.addFileToProject("module.stvn_di", "{\n  :defs {}\n}\n");
        var text = """
            {
              :defs {
                :include [ "module.stvn_di" {} ]
              }
              :type :String
              :body "test"
            }
            """;
        myFixture.configureByText("test_incl.stvn", text);
        int offset = text.indexOf("{}");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();
        var actions = myFixture.filterAvailableIntentions("Remove empty directive block");
        assertFalse("Expected quick fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
            {
              :defs {
                :include [ "module.stvn_di" ]
              }
              :type :String
              :body "test"
            }
            """);
    }

    public void testExactAndSizeMutuallyExclusiveOnFloat() {
        var text = """
            {
              :defs {
                :InvalidFloat { #exact #size 64 } :Float
              }
              :type :InvalidFloat
              :body 1.23
            }
            """;
        myFixture.configureByText("exact_size_mutex.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_MUTUALLY_EXCLUSIVE"))
            .toList();
        assertFalse("Expected ERR_MUTUALLY_EXCLUSIVE for #exact and #size on :Float", errors.isEmpty());
        assertTrue(errors.get(0).getDescription().contains("facet '#size' is mutually exclusive with '#exact'"));

        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove mutually exclusive facet");
        assertFalse("Expected 'Remove mutually exclusive facet' quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertFalse("Converted text must not contain #size", result.contains("#size"));
        assertTrue("Converted text must retain #exact", result.contains("#exact"));
    }

    /**
     * Asserts that declaring :ConflictedRate { #exact #size 64 } :Float emits strictly
     * ERR_MUTUALLY_EXCLUSIVE and zero redundant metadata facet warnings (Rule W003).
     */
    public void testConflictedExactAndSize64SuppressesRedundancyWarning() {
        var text = """
            {
              :defs {
                :ConflictedRate { #exact #size 64 } :Float
              }
              :type :ConflictedRate
              :body 1.23
            }
            """;
        myFixture.configureByText("conflicted_rate.stvn", text);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_MUTUALLY_EXCLUSIVE"))
            .toList();
        assertFalse("Expected ERR_MUTUALLY_EXCLUSIVE on #size 64", errors.isEmpty());
        assertTrue(errors.get(0).getDescription().contains("facet '#size' is mutually exclusive with '#exact'"));

        var redundancyWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
            .toList();
        assertTrue("Redundancy warning W003 must be suppressed on exact float: " + redundancyWarnings, redundancyWarnings.isEmpty());
    }

    public void testInvalidStripFacetOnTypeQuickFix() {
        var text = """
            {
              :defs {
                :IllegalType { #strip } :Int32
              }
              :type :IllegalType
              :body 42
            }
            """;
        myFixture.configureByText("test_invalid_strip.stvn", text);
        int offset = text.indexOf("#strip");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();
        var actions = myFixture.filterAvailableIntentions("Remove invalid facet");
        assertFalse("Expected quick fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
            {
              :defs {
                :IllegalType :Int32
              }
              :type :IllegalType
              :body 42
            }
            """);
    }

    public void testConstantDefinitionPermitsDefinitionBoundsAndConstraintFacets() {
        var text = """
            {
              :defs {
                #EPOCH     { #ns } :TimeEpoch 18446744073709551615
                #DATE      { #offset } :DateTime "2026-10-27T07:00:00Z"
                #MAX_RETRY { #unsigned #size 8 } :Int 3
                #API_HOST  { #regex "^[a-z.]+$" } :String "api.internal.net"
                #BOUNDED   { #minIncl 0 #maxExcl 100 } :Int 42
              }
              :type :Int
              :body 1
            }
            """;
        myFixture.configureByText("constant_valid_facets.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Constant definitions must permit Tier 1, 3, and 4 facets without error: " + errors, errors.isEmpty());
    }

    public void testConstantDefinitionProhibitsTraitOverridesAndDirectives() {
        var text = """
            {
              :defs {
                #INVALID_TRAIT { #equatable #TRUE } :Int 42
                #INVALID_STRIP { #strip } :String "data"
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("constant_invalid_facets.stvn", text);
        var highlights = myFixture.doHighlighting();
        var traitErr = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Trait overrides are not permitted on constants"))
            .findFirst();
        var stripErr = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Directive facet '#strip' is not permitted"))
            .findFirst();
        assertTrue("Expected trait override error on constant", traitErr.isPresent());
        assertTrue("Expected strip directive error on constant", stripErr.isPresent());
    }

    public void testConstantTraitQuickFixRemovesEnclosingBlockCleanly() {
        var text = """
            {
              :defs {
                #INVALID_TRAIT { #equatable #TRUE } :Int 42
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("constant_fix.stvn", text);
        int offset = text.indexOf("#equatable");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();
        var actions = myFixture.filterAvailableIntentions("Remove invalid facet");
        assertFalse("Expected quick fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
            {
              :defs {
                #INVALID_TRAIT :Int 42
              }
              :type :Int
              :body 42
            }
            """);
    }

    public void testTemporalIntervalFacetsPermitted() {
        var text = """
            {
              :defs {
                :Epoch { #s #minIncl 0 #maxExcl 100 } :TimeEpoch
                :Date  { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
              }
              :type :Epoch
              :body 50
            }
            """;
        myFixture.configureByText("temporal_intervals.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Interval facets on temporal types must not produce facet errors: " + errors, errors.isEmpty());
    }

    public void testAdornedCollectionFacetsPermitted() {
        var text = """
            {
              :defs {
                :SeqCapMax    { #maxSize 4096 } :Seq( :Int )
                :SeqCapRange  { #minSize 1 #maxSize 4096 } :Seq( :Int )
                :SetCapMax    { #maxSize 4096 } :Set( :Int )
                :SetCapRange  { #minSize 1 #maxSize 4096 } :Set( :Int )
                :MapCapMax    { #maxSize 4096 } :Map( :String :Int )
                :MapCapRange  { #minSize 1 #maxSize 4096 } :Map( :String :Int )
                :MapInvert    { #invertible } :Map( :String :Int )
                :MapAllFacets { #invertible #minSize 1 #maxSize 4096 } :Map( :String :Int )
              }
              :type :SeqCapMax
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("adorned_collections.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Adorned collection types must produce zero facet errors: " + errors, errors.isEmpty());
    }

    public void testAdornedNominalCollectionAliasFacetsPermitted() {
        var text = """
            {
              :defs {
                :RawSeq :Seq( :Int )
                :RawSet :Set( :String )
                :RawMap :Map( :String :Int )
                :AliasSeq { #equatable #FALSE } :RawSeq
                :AliasSet { #equatable #FALSE } :RawSet
                :AliasMap { #equatable #FALSE } :RawMap
              }
              :type :AliasSeq
              :body [ 42 ]
            }
            """;
        myFixture.configureByText("adorned_collection_aliases.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertTrue("Nominal collection aliases with valid facets must produce zero errors: " + errors, errors.isEmpty());
    }

    public void testIncompatibleFacetsOnCollectionsRejected() {
        var text = """
            {
              :defs {
                :BadSeq { #minIncl 0 } :Seq( :Int )
                :BadSet { #unsigned } :Set( :Int )
                :BadMap { #regex "[a-z]+" } :Map( :String :Int )
                :BadInv { #invertible } :Seq( :Int )
              }
              :type :BadSeq
              :body [ 1 ]
            }
            """;
        myFixture.configureByText("incompatible_collection_facets.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId())
                || (h.getDescription() != null && h.getDescription().startsWith("Facet is not permitted on")))
            .toList();
        assertEquals("Expected exactly 4 facet errors on incompatible collections", 4, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Facet is not permitted on :Seq( :Int )"));
        assertTrue(errors.get(1).getDescription().contains("Facet is not permitted on :Set( :Int )"));
        assertTrue(errors.get(2).getDescription().contains("Facet is not permitted on :Map( :String :Int )"));
        assertTrue(errors.get(3).getDescription().contains("Facet is not permitted on :Seq( :Int )"));
    }

    public void testDuplicateFacetsInMetadataBlockRejected() {
        var text = """
            {
              :defs {
                :BadInt    { #size 53 #size 1 } :Int
                :BadString { #maxSize 4096 #maxSize 1024 } :String
              }
              :type :BadInt
              :body 0
            }
            """;
        myFixture.configureByText("duplicate_facets.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();

        assertEquals("Expected exactly 2 duplicate facet errors", 2, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Duplicate facet '#size' is prohibited"));
        assertTrue(errors.get(1).getDescription().contains("Duplicate facet '#maxSize' is prohibited"));

        // Verify Quick-Fix on first duplicate (#size 1)
        int offset = text.indexOf("#size 1");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove duplicate facet");
        assertFalse("Expected 'Remove duplicate facet' quick-fix", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :BadInt    { #size 53 } :Int
                :BadString { #maxSize 4096 #maxSize 1024 } :String
              }
              :type :BadInt
              :body 0
            }
            """);
    }

    public void testRegexPermittedOnNominalStringDefinitionWithoutContradictoryError() {
        var text = """
            {
              :defs {
                :StringFixed4 { #minSize 4 #maxSize 4 } :String
                :MicCode { #regex "^[A-Z]{4}$" } :StringFixed4
              }
              :type :MicCode
              :body "XNYS"
            }
            """;
        myFixture.configureByText("nominal_facet.stvn", text);
        var highlights = myFixture.doHighlighting();
        var facetErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId())
                || (h.getDescription() != null && h.getDescription().contains("Facet is not permitted on :StringFixed4")))
            .toList();

        assertTrue("Expected zero facet incompatibility errors on nominal string", facetErrors.isEmpty());

        int offset = text.indexOf("#regex");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove invalid facet");
        assertTrue("Destructive 'Remove invalid facet' quick-fix must not be offered", actions.isEmpty());
    }

    public void testUndeclaredNominalTypeBailsOutOfFacetValidationWithoutErrors() {
        var text = """
            {
              :defs {
                :MicCode { #minSize 4 #maxSize 4 #regex "^[A-Z]{4}$" } :StringFixed4
              }
              :type :MicCode
              :body "XNYS"
            }
            """;
        myFixture.configureByText("undeclared_nominal_facet.stvn", text);
        var highlights = myFixture.doHighlighting();
        var facetErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId())
                || (h.getDescription() != null && h.getDescription().contains("Facet is not permitted on :StringFixed4")))
            .toList();

        assertTrue("Undeclared nominal type must bail out of facet validation with zero errors: " + facetErrors, facetErrors.isEmpty());

        int offset = text.indexOf("#regex");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove invalid facet");
        assertTrue("Destructive 'Remove invalid facet' quick-fix must not be offered on undeclared type", actions.isEmpty());
    }

    public void testDeclaredNominalTypeEvaluatesFacetCompatibilityAgainstUnderlyingBaseType() {
        var text = """
            {
              :defs {
                :BaseStr :String
                :Foo { #regex "^[A-Z]{4}$" } :BaseStr
              }
              :type :Foo
              :body "TEST"
            }
            """;
        myFixture.configureByText("declared_nominal_facet.stvn", text);
        var highlights = myFixture.doHighlighting();
        var facetErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();

        assertTrue("Valid facet on declared nominal string alias must produce zero errors: " + facetErrors, facetErrors.isEmpty());
    }

    public void testIncompatibleFacetOnPrimitiveOrDeclaredNominalTypeRejected() {
        var text = """
            {
              :defs {
                :Bar { #regex "^[0-9]+$" } :Int
              }
              :type :Bar
              :body 42
            }
            """;
        myFixture.configureByText("incompatible_int_regex.stvn", text);
        var highlights = myFixture.doHighlighting();
        var facetErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId())
                || (h.getDescription() != null && h.getDescription().contains("Facet is not permitted on :Int")))
            .toList();

        assertEquals("Expected exactly 1 facet error on incompatible primitive type", 1, facetErrors.size());
        assertTrue(facetErrors.get(0).getDescription().contains("permitted facets for string types: [#equatable, #comparable, #regex"));
    }

    public void testEmptyConstraintBlockDiagnosticOnType() {
        var text = """
            {
              :defs {
                :Int64 {} :Int
              }
              :type :Int64
              :body 42
            }
            """;
        myFixture.configureByText("empty_type_block.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();

        assertEquals("Expected exactly 1 inspection error on empty type constraint block", 1, errors.size());
        assertEquals("Empty type constraint block is invalid; remove '{}' or specify valid constraint facets [stvn-plugin:E003-ERR_EMPTY_METADATA_BLOCK]",
            errors.get(0).getDescription());
    }

    public void testEmptyConstraintBlockWithCommentOnlyOnType() {
        var text = """
            {
              :defs {
                :Int64 {
                  // Only a comment inside constraint block
                } :Int
              }
              :type :Int64
              :body 42
            }
            """;
        myFixture.configureByText("empty_comment_block.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();

        assertEquals("Expected exactly 1 inspection error on constraint block with comment only", 1, errors.size());
        assertEquals("Empty type constraint block is invalid; remove '{}' or specify valid constraint facets [stvn-plugin:E003-ERR_EMPTY_METADATA_BLOCK]",
            errors.get(0).getDescription());
    }

    public void testUnrecognizedFacetSuppressesEmptyConstraintBlockDiagnostic() {
        var text = """
            {
              :defs {
                :Int64 {#sizex 64} :Int
              }
              :type :Int64
              :body 42
            }
            """;
        myFixture.configureByText("unrecognized_facet.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();

        assertTrue("Empty constraint block diagnostic must be suppressed",
            errors.stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains("ERR_EMPTY_METADATA_BLOCK")));
        assertTrue("Unrecognized facet diagnostic must be reported: " + errors,
            errors.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("ERR_UNRECOGNIZED_METADATA_FACET")));
    }

    public void testUnrecognizedBareFacetSuppressesEmptyConstraintBlockDiagnostic() {
        var text = """
            {
              :defs {
                :Int64 {#customFlag} :Int
              }
              :type :Int64
              :body 42
            }
            """;
        myFixture.configureByText("unrecognized_bare_facet.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();

        assertTrue("Empty constraint block diagnostic must be suppressed",
            errors.stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains("ERR_EMPTY_METADATA_BLOCK")));
        assertTrue("Unrecognized bare facet diagnostic must be reported: " + errors,
            errors.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("ERR_UNRECOGNIZED_METADATA_FACET")));
    }

    public void testEmptyConstraintBlockDiagnosticOnConstant() {
        var text = """
            {
              :defs {
                #Port {} :Int 8080
              }
              :type :Int
              :body #Port
            }
            """;
        myFixture.configureByText("empty_const_block.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();

        assertEquals("Expected exactly 1 inspection error on empty constant constraint block", 1, errors.size());
        assertEquals("Empty type constraint block is invalid; remove '{}' or specify valid constraint facets [stvn-plugin:E003-ERR_EMPTY_METADATA_BLOCK]",
            errors.get(0).getDescription());
    }

    public void testExactFloatPermitsMaxSizeNarrowingWithoutInspectionError() {
        var text = """
            {
              :defs {
                :FloatExactGoodB { #exact #maxSize 16777215 } :Float
              }
              :type :FloatExactGoodB
              :body 123.45
            }
            """;
        myFixture.configureByText("exact_float_good.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();
        assertTrue("Exact float narrowing must not produce StvnMetadataFacet error: " + errors, errors.isEmpty());
    }

    public void testTransitiveExactFloatPermitsMaxSizeNarrowingWithoutInspectionError() {
        var text = """
            {
              :defs {
                :BaseExact { #exact } :Float
                :SubExact  { #maxSize 18 } :BaseExact
              }
              :type :SubExact
              :body 12.34
            }
            """;
        myFixture.configureByText("transitive_exact_float.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();
        assertTrue("Transitive exact float narrowing must not produce StvnMetadataFacet error: " + errors, errors.isEmpty());
    }

    public void testMinSizeProhibitedOnExactFloat() {
        var text = """
            {
              :defs {
                :BadExactMin { #exact #minSize 4 } :Float
              }
              :type :BadExactMin
              :body 123.45
            }
            """;
        myFixture.configureByText("exact_float_bad_minsize.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnMetadataFacet".equals(h.getInspectionToolId()))
            .toList();
        assertEquals("Expected exactly 1 inspection error on #minSize on :Float", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Facet '#minSize' is not permitted on :Float; '#minSize' applies only to :String, :Seq, :Set, and :Map (permitted facets on :Float: [#exact, #size, #equatable, #comparable, #minIncl, #minExcl, #maxIncl, #maxExcl])"));
    }

    public void testDerivedNominalMapInvertibleFacetRejectedWithE040AndQuickFixes() {
        var text = """
            {
              :meta {
                #name "derived_nominal_map_invertible"
                #kind #BODY
              }
              :defs {
                :RawMap :Map( :String :Int )
                :AliasMap { <caret>#invertible } :RawMap
              }
              :type :AliasMap
              :body {
                [ "a" 1 ]
              }
            }
            """;
        myFixture.configureByText("derived_nominal_map_invertible.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected 1 E040 error on '#invertible' over ':RawMap'", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("E040-ERR_NON_OVERRIDABLE_FACET"));

        var changeBaseFix = myFixture.findSingleIntention("Change base type from ':RawMap' to primitive ':Map'");
        assertNotNull("Expected primary quick-fix to change ':RawMap' to ':Map'", changeBaseFix);
        var removeFacetFix = myFixture.findSingleIntention("Remove non-overridable facet '#invertible'");
        assertNotNull("Expected secondary quick-fix to remove '#invertible'", removeFacetFix);
    }

    public void testEmptyMetaBlockEmitsE039AndOffersPopulateQuickFixes() {
        var text = """
            {
              :meta <caret>{}
              :defs {
                :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 4096 } :Int
              }
              :type :BoundedPort
              :body 2048
            }
            """;
        myFixture.configureByText("empty_meta_block_with_body_error.core_test.stvn_i", text);
        var highlights = myFixture.doHighlighting();
        var e039Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E039-ERR_EMPTY_META_BLOCK"))
            .toList();
        assertEquals("Expected 1 E039 error on empty :meta {}", 1, e039Errors.size());
        assertTrue(e039Errors.get(0).getDescription().contains("Metadata block ':meta {}' must not be empty"));
        assertEquals("{}", e039Errors.get(0).getText());

        var populateActions = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertFalse("Expected InsertMissingMetaBlockQuickFix intention on :meta {}", populateActions.isEmpty());

        var kindActions = myFixture.filterAvailableIntentions("Insert missing #kind facet");
        assertFalse("Expected InsertMissingMetaKindFacetQuickFix intention on :meta {}", kindActions.isEmpty());

        myFixture.launchAction(kindActions.get(0));
        assertTrue("Expected #kind #BODY_INCLUDE to be inserted into :meta {}",
            myFixture.getFile().getText().contains("#kind #BODY_INCLUDE"));
    }

    public void testEmptyDefsBlockInPayloadEmitsE002AndRemoveQuickFix() {
        var text = """
            {
              :meta {
                #name "empty_defs_block_in_payload"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs <caret>{}
              :type { #unsigned #size 16 #minIncl 1024 #maxExcl 4096 } :Int
              :body 2048
            }
            """;
        myFixture.configureByText("empty_defs_block_in_payload.core_test.stvn_i", text);
        var highlights = myFixture.doHighlighting();
        var e002Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E002-ERR_EMPTY_DEFS_BLOCK"))
            .toList();
        assertEquals("Expected 1 E002 error on empty :defs {} in payload document", 1, e002Errors.size());
        assertTrue(e002Errors.get(0).getDescription().contains(
            "Empty definition block ':defs {}' is redundant; omit ':defs' or add definitions"
        ));
        assertEquals("{}", e002Errors.get(0).getText());

        var removeActions = myFixture.filterAvailableIntentions("Remove redundant ':defs {}' block");
        assertFalse("Expected 'Remove redundant :defs {} block' quick-fix", removeActions.isEmpty());
        myFixture.launchAction(removeActions.get(0));
        assertFalse("Expected :defs {} to be removed", myFixture.getFile().getText().contains(":defs"));
    }

    public void testEmptyDefsBlockInSchemaDocumentEmitsE002AndInsertSampleTypeQuickFix() {
        var text = """
            {
              :meta {
                #name "empty_schema"
                #kind #DEFS
              }
              :defs <caret>{}
            }
            """;
        myFixture.configureByText("empty_schema.stvn_d", text);
        var highlights = myFixture.doHighlighting();
        var e002Errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E002-ERR_EMPTY_DEFS_BLOCK"))
            .toList();
        assertEquals("Expected 1 E002 error on empty :defs {} in schema document", 1, e002Errors.size());
        assertTrue(e002Errors.get(0).getDescription().contains(
            "Definition schema ':defs {}' must declare at least one definition or include"
        ));
        assertEquals("{}", e002Errors.get(0).getText());

        var insertActions = myFixture.filterAvailableIntentions("Insert sample type definition in :defs");
        assertFalse("Expected 'Insert sample type definition in :defs' quick-fix", insertActions.isEmpty());
        myFixture.launchAction(insertActions.get(0));
        assertTrue("Expected :SampleType :Int inserted in :defs",
            myFixture.getFile().getText().contains(":SampleType :Int"));
    }

    public void testUnknownAndDuplicateMetaFacetsEmitE008AndRemoveQuickFix() {
        var text = """
            {
              :meta {
                #name "meta_facets_test"
                #name "duplicate_name"
                <caret>#author "rogue_architect"
                #kind #BODY_INCLUDE
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("meta_facets_test.stvn_i", text);
        var highlights = myFixture.doHighlighting();
        var unknownErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Invalid metadata facet tag '#author' in ':meta' block"))
            .toList();
        assertEquals("Expected 1 E008 error on unknown #author facet in :meta", 1, unknownErrors.size());

        var duplicateErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Duplicate metadata facet tag '#name' declared in ':meta' block"))
            .toList();
        assertEquals("Expected 1 E008 error on duplicate #name facet in :meta", 1, duplicateErrors.size());

        var removeAuthor = myFixture.filterAvailableIntentions("Remove invalid metadata facet '#author'");
        assertFalse("Expected 'Remove invalid metadata facet '#author'' quick-fix", removeAuthor.isEmpty());
        myFixture.launchAction(removeAuthor.get(0));
        assertFalse("Expected #author facet to be deleted", myFixture.getFile().getText().contains("#author"));
    }

    public void testTrapUnsignedFloatHighlightsUnsignedWithE008AndOffersQuickFixes() {
        var text = """
            {
              :defs {
                :TrapUnsignedFloat { #unsigned } :Float
              }
              :type :Float
              :body 1.0
            }
            """;
        myFixture.configureByText("trap_unsigned_float.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertEquals("Expected 1 E008 ERR_INVALID_METADATA_FACET error on :TrapUnsignedFloat { #unsigned } :Float", 1, errors.size());
        assertEquals("#unsigned", errors.get(0).getText());
        assertTrue(errors.get(0).getDescription().contains("facet '#unsigned' is only applicable to :Int, not :Float"));

        int offset = text.indexOf("#unsigned") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var removeActions = myFixture.filterAvailableIntentions("Remove inapplicable facet '#unsigned'");
        assertFalse("Expected 'Remove inapplicable facet '#unsigned'' quick-fix", removeActions.isEmpty());
        var changeBaseActions = myFixture.filterAvailableIntentions("Change base type to ':Int'");
        assertFalse("Expected 'Change base type to ':Int'' quick-fix", changeBaseActions.isEmpty());

        myFixture.launchAction(changeBaseActions.get(0));
        assertTrue("Expected base type to change to :Int",
            myFixture.getFile().getText().contains(":TrapUnsignedFloat { #unsigned } :Int"));
    }

    public void testExactOnIntAndMaxSizeOnContinuousFloatEmitE008WithQuickFixes() {
        var text = """
            {
              :defs {
                :TrapExactInt { #exact } :Int
                :TrapContinuousMaxSize { #maxSize 6 } :Float
              }
              :type :Int
              :body 1
            }
            """;
        myFixture.configureByText("trap_exact_int_and_float_maxsize.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertEquals("Expected 2 E008 ERR_INVALID_METADATA_FACET errors", 2, errors.size());
        assertEquals("#exact", errors.get(0).getText());
        assertTrue(errors.get(0).getDescription().contains("facet '#exact' is only applicable to :Float, not :Int"));
        assertEquals("#maxSize 6", errors.get(1).getText());

        int offset = text.indexOf("#exact") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var changeToFloatActions = myFixture.filterAvailableIntentions("Change base type to ':Float'");
        assertFalse("Expected 'Change base type to ':Float'' quick-fix", changeToFloatActions.isEmpty());
        myFixture.launchAction(changeToFloatActions.get(0));
        assertTrue("Expected base type to change to :Float",
            myFixture.getFile().getText().contains(":TrapExactInt { #exact } :Float"));
    }

    public void testStage6MinSizeOnTupleDisplaysClearTargetTypesAndPermittedTupleFacets() {
        var text = """
            {
              :defs {
                :TrapTupleWithSizeFacet { #min<caret>Size 2 } :Tuple( :Int :String )
              }
              :type :Tuple( :Int :String )
              :body ( 1 "a" )
            }
            """;
        myFixture.configureByText("stage6_tuple_min_size.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Expected strictly 1 E008 error on :TrapTupleWithSizeFacet", 1, errors.size());
        assertEquals(
            "Facet '#minSize' is not permitted on :Tuple( :Int :String ); '#minSize' applies only to :String, :Seq, :Set, and :Map (permitted facets on :Tuple: [#equatable, #comparable]) [stvn-plugin:E008-ERR_INVALID_METADATA_FACET]",
            errors.get(0).getDescription()
        );

        var removeActions = myFixture.filterAvailableIntentions("Remove inapplicable facet '#minSize'");
        assertFalse("Expected 'Remove inapplicable facet' quick-fix on #minSize", removeActions.isEmpty());
    }
}
