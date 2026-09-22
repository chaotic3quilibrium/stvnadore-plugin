package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Automated platform test suite validating {@link StvnStringCardinalityInspection}.
 * Verifies prohibition of '#size' on ':String' per MCT § 3.1.3 and quick-fix conversions.
 */
@NullMarked
public final class StvnStringCardinalityInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnStringCardinalityInspection()});
    }

    public void testProhibitedSizeOnStringTriggersError() {
        myFixture.configureByText("test_prohibited_size.stvn",
            """
            {
              :defs {
                :ProhibitedName { #size 64 } :String
              }
              :type :ProhibitedName
              :body "hello"
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Facet '#size' is prohibited on ':String'"))
            .toList();
        assertFalse("Expected string cardinality inspection error for #size on :String", errors.isEmpty());
        assertTrue(errors.get(0).getDescription().contains("MCT § 3.1.3"));
    }

    public void testConvertSizeToStringBoundsQuickFix() {
        var text = """
            {
              :defs {
                :ProhibitedName { #size 64 } :String
              }
              :type :ProhibitedName
              :body "hello"
            }
            """;
        myFixture.configureByText("quickfix_size.stvn", text);
        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Convert '#size' to '#minSize 1 #maxSize <N>'");
        assertFalse("Expected conversion quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #minSize 1 in converted text", result.contains("#minSize 1"));
        assertTrue("Expected #maxSize 64 in converted text", result.contains("#maxSize 64"));
    }

    public void testValidStringBoundsProduceZeroErrors() {
        myFixture.configureByText("test_valid_bounds.stvn",
            """
            {
              :defs {
                :ValidString { #minSize 1 #maxSize 128 } :String
              }
              :type :ValidString
              :body "compliant"
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var cardinalityErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Facet '#size' is prohibited on ':String'"))
            .toList();
        assertTrue("Compliant string bounds must produce zero cardinality errors", cardinalityErrors.isEmpty());
    }
}
