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
}
