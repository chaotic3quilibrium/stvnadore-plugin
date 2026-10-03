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
        var actions = myFixture.filterAvailableIntentions("Remove empty metadata block");
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
        myFixture.addFileToProject("module.stvn_incl", "{\n  :defs {}\n}\n");
        var text = """
            {
              :defs {
                :include [ "module.stvn_incl" {} ]
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
                :include [ "module.stvn_incl" ]
              }
              :type :String
              :body "test"
            }
            """);
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
                :IllegalType { } :Int32
              }
              :type :IllegalType
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
                :SeqCapRange  { #minSize 0 #maxSize 4096 } :Seq( :Int )
                :SetCapMax    { #maxSize 4096 } :Set( :Int )
                :SetCapRange  { #minSize 1 #maxSize 4096 } :Set( :Int )
                :MapCapMax    { #maxSize 4096 } :Map( :String :Int )
                :MapCapRange  { #minSize 0 #maxSize 4096 } :Map( :String :Int )
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
                :AliasSeq { #maxSize 1024 } :RawSeq
                :AliasSet { #minSize 1 #maxSize 512 } :RawSet
                :AliasMap { #invertible #maxSize 256 } :RawMap
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
                :Foo { #minSize 4 } :BaseStr
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
}
