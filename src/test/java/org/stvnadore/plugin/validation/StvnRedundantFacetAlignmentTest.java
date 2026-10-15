package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform test suite validating diagnostic recognition, external annotator pinning,
 * automated quick-fix remediation, inspection harmonization, and code completion filtering
 * for redundant metadata facets under STVN 2.0.0 Core Rule E036 (ERR_REDUNDANT_METADATA_FACET).
 */
@NullMarked
public final class StvnRedundantFacetAlignmentTest extends BasePlatformTestCase {

  /**
   * Asserts that declaring #minIncl 0 on an unsigned integer triggers a fatal compilation error (E036)
   * and offers the automated RemoveRedundantFacetQuickFix.
   */
  public void testUnsignedIntMinInclZeroTriggersFatalErrorAndOffersFix() {
    var text = """
        {
          :defs {
            :MyType { #unsigned #minIncl 0 } :Int
          }
          :type :MyType
          :body 42
        }
        """;
    myFixture.configureByText("unsigned_min_incl_zero.stvn", text);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertFalse("Expected fatal error for redundant #minIncl 0 on unsigned :Int", errors.isEmpty());
    assertTrue("Error description must mention '#minIncl 0'",
        errors.get(0).getDescription().contains("#minIncl 0"));

    int offset = text.indexOf("#minIncl 0");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected 'Remove redundant facet' quick-fix to be available", actions.isEmpty());
  }

  /**
   * Asserts that applying RemoveRedundantFacetQuickFix to an unsigned integer with multiple facets
   * cleanly deletes the offending facet while preserving single spacing and formatting.
   */
  public void testExecuteQuickFixOnUnsignedIntLeavesCleanFormatting() {
    var text = """
        {
          :defs {
            :MyType { #unsigned #minIncl 0 } :Int
          }
          :type :MyType
          :body 42
        }
        """;
    myFixture.configureByText("unsigned_min_incl_zero_fix.stvn", text);
    myFixture.doHighlighting();
    int offset = text.indexOf("#minIncl 0");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected quick-fix to be available", actions.isEmpty());
    myFixture.launchAction(actions.get(0));

    var result = myFixture.getFile().getText();
    assertTrue("Applying quick-fix must leave clean '{ #unsigned } :Int':\n" + result,
        result.contains(":MyType { #unsigned } :Int"));
    assertFalse("Result must not contain #minIncl 0", result.contains("#minIncl 0"));
  }

  /**
   * Asserts that applying RemoveRedundantFacetQuickFix to a single-facet metadata block
   * excises both the facet and the enclosing braces to prevent ERR_EMPTY_METADATA_BLOCK (E003).
   */
  public void testExecuteQuickFixOnSingleFacetExcisesEmptyBlock() {
    var text = """
        {
          :defs {
            :BadString { #minSize 0 } :String
          }
          :type :BadString
          :body "test"
        }
        """;
    myFixture.configureByText("string_min_size_zero_fix.stvn", text);
    myFixture.doHighlighting();
    int offset = text.indexOf("#minSize 0");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected quick-fix to be available", actions.isEmpty());
    myFixture.launchAction(actions.get(0));

    var result = myFixture.getFile().getText();
    assertTrue("Applying quick-fix must remove single-facet block leaving bare ':BadString :String':\n" + result,
        result.contains(":BadString :String"));
    assertFalse("Result must not contain metadata braces for :BadString", result.contains(":BadString {"));
    assertFalse("Result must not contain empty braces '{}'", result.contains("{}"));
  }

  /**
   * Asserts that declaring #minIncl 0 on a subtype whose ancestor establishes #unsigned
   * triggers a fatal error, and applying the quick-fix excises the child metadata block cleanly.
   */
  public void testTransitiveUnsignedFloorTautologyQuickFix() {
    var text = """
        {
          :defs {
            :ParentUnsigned { #unsigned } :Int
            :Child { #minIncl 0 } :ParentUnsigned
          }
          :type :Child
          :body 42
        }
        """;
    myFixture.configureByText("transitive_unsigned_fix.stvn", text);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertFalse("Expected fatal error on child #minIncl 0", errors.isEmpty());

    int offset = text.indexOf("#minIncl 0");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected quick-fix on child facet", actions.isEmpty());
    myFixture.launchAction(actions.get(0));

    var result = myFixture.getFile().getText();
    assertTrue("Applying quick-fix must excise child metadata map leaving bare ':Child :ParentUnsigned':\n" + result,
        result.contains(":Child :ParentUnsigned"));
    assertFalse("Child must not contain metadata map", result.contains(":Child {"));
  }

  /**
   * Asserts that declaring redundant default trait #equatable #TRUE on scalar primitive :Int
   * triggers a fatal error, and applying the quick-fix excises the facet cleanly.
   */
  public void testPrimitiveEquatableTrueTriggersFatalError() {
    var text = """
        {
          :defs {
            :MyInt { #equatable #TRUE } :Int
          }
          :type :MyInt
          :body 42
        }
        """;
    myFixture.configureByText("primitive_equatable_true.stvn", text);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertFalse("Expected fatal error for redundant #equatable #TRUE on scalar primitive :Int", errors.isEmpty());

    int offset = text.indexOf("#equatable #TRUE");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected quick-fix to be available", actions.isEmpty());
    myFixture.launchAction(actions.get(0));

    var result = myFixture.getFile().getText();
    assertTrue("Applying quick-fix must leave bare ':MyInt :Int':\n" + result,
        result.contains(":MyInt :Int"));
    assertFalse("Result must not contain metadata map", result.contains(":MyInt {"));
  }

  /**
   * Asserts that declaring #minIncl 0 on a signed integer domain is permitted without errors or warnings
   * because it narrows the domain from (-infinity, infinity) to [0, infinity).
   */
  public void testSignedIntMinInclZeroPermittedWithoutError() {
    var text = """
        {
          :defs {
            :NonNegativeInt { #minIncl 0 } :Int
          }
          :type :NonNegativeInt
          :body 0
        }
        """;
    myFixture.configureByText("signed_min_incl_zero.stvn", text);
    var highlights = myFixture.doHighlighting();
    var relevantProblems = highlights.stream()
        .filter(h -> (h.getSeverity() == HighlightSeverity.ERROR || h.getSeverity() == HighlightSeverity.WARNING))
        .filter(h -> h.getDescription() != null && h.getDescription().contains("redundant"))
        .toList();
    assertTrue("Signed :Int with #minIncl 0 is valid domain narrowing and must produce zero redundancy problems",
        relevantProblems.isEmpty());
  }

  /**
   * Asserts that declaring #minIncl 1 on an unsigned integer domain is permitted without errors or warnings
   * because it narrows the domain from [0, infinity) to [1, infinity).
   */
  public void testUnsignedIntMinInclOnePermittedWithoutError() {
    var text = """
        {
          :defs {
            :PositiveUInt { #unsigned #minIncl 1 } :Int
          }
          :type :PositiveUInt
          :body 1
        }
        """;
    myFixture.configureByText("unsigned_min_incl_one.stvn", text);
    var highlights = myFixture.doHighlighting();
    var relevantProblems = highlights.stream()
        .filter(h -> (h.getSeverity() == HighlightSeverity.ERROR || h.getSeverity() == HighlightSeverity.WARNING))
        .filter(h -> h.getDescription() != null && h.getDescription().contains("redundant"))
        .toList();
    assertTrue("Unsigned :Int with #minIncl 1 is valid domain narrowing and must produce zero redundancy problems",
        relevantProblems.isEmpty());
  }

  /**
   * Asserts that code completion inside an unsigned :Int metadata block suppresses #minIncl 0
   * and proposes the non-zero lower bound #minIncl 1.
   */
  public void testCompletionSuppressesMinInclZeroOnUnsignedInt() {
    var text = """
        {
          :defs {
            :MyType { #unsigned <caret> } :Int
          }
          :type :MyType
          :body 0
        }
        """;
    myFixture.configureByText("completion_unsigned.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    assertFalse("Must suppress '#minIncl 0' on unsigned :Int", lookupStrings.contains("#minIncl 0"));
    assertTrue("Must propose non-zero lower bound '#minIncl 1' on unsigned :Int", lookupStrings.contains("#minIncl 1"));
  }

  /**
   * Asserts that code completion inside a :String metadata block suppresses #minSize 0
   * and proposes the non-empty bound #minSize 1.
   */
  public void testCompletionSuppressesMinSizeZeroOnString() {
    var text = """
        {
          :defs {
            :MyString { <caret> } :String
          }
          :type :MyString
          :body ""
        }
        """;
    myFixture.configureByText("completion_string.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    assertFalse("Must suppress '#minSize 0' on :String", lookupStrings.contains("#minSize 0"));
    assertTrue("Must propose non-empty '#minSize 1' on :String", lookupStrings.contains("#minSize 1"));
  }

  /**
   * Asserts that code completion inside a scalar primitive :Int metadata block suppresses #equatable #TRUE
   * and offers valid trait override opt-out #equatable #FALSE.
   */
  public void testCompletionSuppressesEquatableTrueOnInt() {
    var text = """
        {
          :defs {
            :MyInt { <caret> } :Int
          }
          :type :MyInt
          :body 0
        }
        """;
    myFixture.configureByText("completion_int_traits.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    assertFalse("Must suppress redundant default trait '#equatable #TRUE' on :Int", lookupStrings.contains("#equatable #TRUE"));
    assertTrue("Must offer valid trait override '#equatable #FALSE' on :Int", lookupStrings.contains("#equatable #FALSE"));
  }

  /**
   * Asserts that StvnRedundantFacetOverrideInspection suppresses warning annotations on tokens
   * governed by Core Rule E036, yielding exclusively to StvnExternalAnnotator fatal error squiggles.
   */
  public void testHarmonizationSuppressesInspectionWarningOnE036FatalError() {
    var text = """
        {
          :defs {
            :BadString { #minSize 0 } :String
          }
          :type :BadString
          :body "test"
        }
        """;
    myFixture.enableInspections(new StvnRedundantFacetOverrideInspection());
    myFixture.configureByText("harmonization_string_min_size.stvn", text);
    var highlights = myFixture.doHighlighting();

    var errors = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertEquals("Must report exactly 1 fatal error annotation from StvnExternalAnnotator", 1, errors.size());

    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
        .toList();
    assertTrue("Inspection warning must be suppressed on Core Rule E036 fatal error site", warnings.isEmpty());
  }
}
