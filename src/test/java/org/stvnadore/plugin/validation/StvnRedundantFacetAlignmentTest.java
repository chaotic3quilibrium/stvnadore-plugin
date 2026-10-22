package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform test suite validating diagnostic recognition, external annotator pinning,
 * automated quick-fix remediation, inspection harmonization, and code completion filtering
 * for redundant metadata facets under STVN 2.0.0 Core Rule W003 (WARN_REDUNDANT_METADATA_FACET).
 */
@NullMarked
public final class StvnRedundantFacetAlignmentTest extends BasePlatformTestCase {

  /**
   * Asserts that declaring #minIncl 0 on an unsigned integer triggers a non-blocking warning (W003)
   * and offers the automated RemoveRedundantFacetQuickFix.
   */
  public void testUnsignedIntMinInclZeroTriggersWarningAndOffersFix() {
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
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertFalse("Expected warning for redundant #minIncl 0 on unsigned :Int", warnings.isEmpty());
    assertTrue("Warning description must mention '#minIncl 0'",
        warnings.get(0).getDescription().contains("#minIncl 0"));

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
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertFalse("Expected warning on child #minIncl 0", warnings.isEmpty());

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
  public void testPrimitiveEquatableTrueTriggersWarning() {
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
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertFalse("Expected warning for redundant #equatable #TRUE on scalar primitive :Int", warnings.isEmpty());

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
   * governed by Core Rule W003, yielding exclusively to StvnExternalAnnotator warning squiggles.
   */
  public void testHarmonizationSuppressesInspectionWarningOnW003Warning() {
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

    var annotatorWarnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertEquals("Must report exactly 1 warning annotation from StvnExternalAnnotator", 1, annotatorWarnings.size());

    var inspectionWarnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
        .toList();
    assertTrue("Inspection warning must be suppressed on Core Rule W003 warning site", inspectionWarnings.isEmpty());
  }

  /**
   * Asserts that declaring #size 32 on signed :Int triggers a non-blocking warning (W003)
   * pinned to '#size 32' and offers RemoveRedundantFacetQuickFix.
   */
  public void testSignedIntSizeThirtyTwoTriggersWarning() {
    var text = """
        {
          :defs {
            :MyInt { #size 32 } :Int
          }
          :type :MyInt
          :body 42
        }
        """;
    myFixture.configureByText("signed_int_size_32.stvn", text);
    var highlights = myFixture.doHighlighting();
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("facet '#size 32' is redundant"))
        .toList();
    assertFalse("Expected warning for redundant #size 32 on signed :Int", warnings.isEmpty());

    var h = warnings.get(0);
    int expectedStart = text.indexOf("#size 32");
    int expectedEnd = expectedStart + "#size 32".length();
    assertEquals("Start offset must pin strictly to '#size 32'", expectedStart, h.getStartOffset());
    assertEquals("End offset must pin strictly to '#size 32'", expectedEnd, h.getEndOffset());

    myFixture.getEditor().getCaretModel().moveToOffset(expectedStart);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected 'Remove redundant facet' quick-fix to be available", actions.isEmpty());
  }

  /**
   * Asserts that declaring #size 64 on continuous :Float triggers a non-blocking warning (W003)
   * pinned to '#size 64' and offers RemoveRedundantFacetQuickFix.
   */
  public void testContinuousFloatSizeSixtyFourTriggersWarning() {
    var text = """
        {
          :defs {
            :MyFloat { #size 64 } :Float
          }
          :type :MyFloat
          :body 3.14
        }
        """;
    myFixture.configureByText("continuous_float_size_64.stvn", text);
    var highlights = myFixture.doHighlighting();
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("facet '#size 64' is redundant"))
        .toList();
    assertFalse("Expected warning for redundant #size 64 on continuous :Float", warnings.isEmpty());

    var h = warnings.get(0);
    int expectedStart = text.indexOf("#size 64");
    int expectedEnd = expectedStart + "#size 64".length();
    assertEquals("Start offset must pin strictly to '#size 64'", expectedStart, h.getStartOffset());
    assertEquals("End offset must pin strictly to '#size 64'", expectedEnd, h.getEndOffset());

    myFixture.getEditor().getCaretModel().moveToOffset(expectedStart);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected 'Remove redundant facet' quick-fix to be available", actions.isEmpty());
  }

  /**
   * Asserts that declaring valid non-default bit-width #unsigned #size 16 on :Int compiles cleanly with zero error annotations.
   */
  public void testUnsignedIntSizeSixteenPermittedWithoutError() {
    var text = """
        {
          :defs {
            :MyUInt { #unsigned #size 16 } :Int
          }
          :type :MyUInt
          :body 65535
        }
        """;
    myFixture.configureByText("unsigned_int_size_16.stvn", text);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
    assertTrue("Unsigned integer with non-default #size 16 is valid domain narrowing and must produce zero error annotations: " + errors, errors.isEmpty());
  }

  /**
   * Asserts that declaring single-precision #size 32 on continuous :Float compiles cleanly with zero error annotations.
   */
  public void testContinuousFloatSizeThirtyTwoPermittedWithoutError() {
    var text = """
        {
          :defs {
            :SingleFloat { #size 32 } :Float
          }
          :type :SingleFloat
          :body 3.14
        }
        """;
    myFixture.configureByText("continuous_float_size_32.stvn", text);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
    assertTrue("Continuous float with #size 32 narrows precision and must produce zero error annotations: " + errors, errors.isEmpty());
  }

  /**
   * Asserts that applying RemoveRedundantFacetQuickFix to { #size 64 } :Float deletes the entire
   * metadata block and leaves bare ':JsonNumberFloat :Float' without empty braces.
   */
  public void testExecuteQuickFixOnContinuousFloatSizeSixtyFourExcisesEmptyBlock() {
    var text = """
        {
          :defs {
            :JsonNumberFloat { #size 64 } :Float
          }
          :type :JsonNumberFloat
          :body 1.0
        }
        """;
    myFixture.configureByText("float_size_64_fix.stvn", text);
    myFixture.doHighlighting();
    int offset = text.indexOf("#size 64");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected quick-fix to be available", actions.isEmpty());
    myFixture.launchAction(actions.get(0));

    var result = myFixture.getFile().getText();
    assertTrue("Applying quick-fix must remove single-facet block leaving bare ':JsonNumberFloat :Float':\n" + result,
        result.contains(":JsonNumberFloat :Float"));
    assertFalse("Result must not contain metadata braces for :JsonNumberFloat", result.contains(":JsonNumberFloat {"));
    assertFalse("Result must not contain empty braces '{}'", result.contains("{}"));
  }

  /**
   * Asserts that completion in ':defs { :MyInt { <caret> } :Int }' contains '#size 64' and '#size 16',
   * but does NOT contain '#size 32'.
   */
  public void testCompletionSuppressesSizeThirtyTwoOnSignedInt() {
    var text = """
        {
          :defs {
            :MyInt { <caret> } :Int
          }
          :type :MyInt
          :body 0
        }
        """;
    myFixture.configureByText("completion_signed_int_size.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    assertFalse("Must suppress redundant '#size 32' on signed :Int", lookupStrings.contains("#size 32"));
    assertTrue("Must propose valid '#size 64' on signed :Int", lookupStrings.contains("#size 64"));
    assertTrue("Must propose valid '#size 16' on signed :Int", lookupStrings.contains("#size 16"));
  }

  /**
   * Asserts that completion in ':defs { :MyFloat { <caret> } :Float }' contains '#size 32',
   * but does NOT contain '#size 64'.
   */
  public void testCompletionSuppressesSizeSixtyFourOnContinuousFloat() {
    var text = """
        {
          :defs {
            :MyFloat { <caret> } :Float
          }
          :type :MyFloat
          :body 0.0
        }
        """;
    myFixture.configureByText("completion_continuous_float_size.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    assertFalse("Must suppress redundant '#size 64' on continuous :Float", lookupStrings.contains("#size 64"));
    assertTrue("Must propose single-precision '#size 32' on continuous :Float", lookupStrings.contains("#size 32"));
  }

  /**
   * Asserts that completion in ':defs { :MyUInt { #unsigned <caret> } :Int }' suppresses redundant #size 32 under Rule W003.
   */
  public void testCompletionUnsignedIntSuppressesSize32AndProposesNonDefaultSizes() {
    var text = """
        {
          :defs {
            :MyUInt { #unsigned <caret> } :Int
          }
          :type :MyUInt
          :body 0
        }
        """;
    myFixture.configureByText("completion_unsigned_int_size.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    assertFalse("Must suppress redundant '#size 32' on unsigned :Int under Rule W003", lookupStrings.contains("#size 32"));
    assertTrue("Must propose non-default width '#size 64' on unsigned :Int", lookupStrings.contains("#size 64"));
    assertTrue("Must propose non-default width '#size 16' on unsigned :Int", lookupStrings.contains("#size 16"));
    assertTrue("Must propose non-default width '#size 8' on unsigned :Int", lookupStrings.contains("#size 8"));
    assertTrue("Must propose non-default width '#size 128' on unsigned :Int", lookupStrings.contains("#size 128"));
  }

  /**
   * Asserts that declaring #size 32 on { #unsigned } :Int triggers warning W003
   * and suppresses duplicate local inspection warnings.
   */
  public void testUnsignedIntSize32TriggersWarningW003WithoutDuplicateWarning() {
    var text = """
        {
          :defs {
            :BadUInt { #unsigned #size 32 } :Int
          }
          :type :BadUInt
          :body 42
        }
        """;
    myFixture.enableInspections(new StvnRedundantFacetOverrideInspection());
    myFixture.configureByText("unsigned_size_32_tautology.stvn", text);
    var highlights = myFixture.doHighlighting();
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("facet '#size 32' is redundant"))
        .toList();
    assertFalse("Expected warning W003 for redundant #size 32 on unsigned integer", warnings.isEmpty());

    var allWarnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .toList();
    assertEquals("Redundant facet inspection warning must be suppressed by W003 gate, leaving exactly 1 warning squiggle: " + allWarnings, 1, allWarnings.size());
  }

  /**
   * Asserts that declaring #maxSize 16777216 on { #exact } :Float triggers a non-blocking warning (W003)
   * pinned strictly to '#maxSize 16777216' and offers RemoveRedundantFacetQuickFix.
   */
  public void testExactFloatMaxSizeSixteenMillionTriggersWarning() {
    var text = """
        {
          :defs {
            :BadExact { #exact #maxSize 16777216 } :Float
          }
          :type :BadExact
          :body 123.45
        }
        """;
    myFixture.configureByText("exact_float_max_size_16m.stvn", text);
    var highlights = myFixture.doHighlighting();
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("facet '#maxSize 16777216' is redundant"))
        .toList();
    assertFalse("Expected warning for redundant #maxSize 16777216 on { #exact } :Float", warnings.isEmpty());

    var h = warnings.get(0);
    int expectedStart = text.indexOf("#maxSize 16777216");
    int expectedEnd = expectedStart + "#maxSize 16777216".length();
    assertEquals("Start offset must pin strictly to '#maxSize 16777216'", expectedStart, h.getStartOffset());
    assertEquals("End offset must pin strictly to '#maxSize 16777216'", expectedEnd, h.getEndOffset());

    myFixture.getEditor().getCaretModel().moveToOffset(expectedStart);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected 'Remove redundant facet' quick-fix to be available", actions.isEmpty());
  }

  /**
   * Asserts that executing RemoveRedundantFacetQuickFix on { #exact #maxSize 16777216 } :Float
   * deletes only '#maxSize 16777216' and leaves clean '{ #exact } :Float' without empty braces.
   */
  public void testExecuteQuickFixOnExactFloatMaxSizeSixteenMillionLeavesCleanFormatting() {
    var text = """
        {
          :defs {
            :BadExact { #exact #maxSize 16777216 } :Float
          }
          :type :BadExact
          :body 123.45
        }
        """;
    myFixture.configureByText("exact_float_max_size_16m_fix.stvn", text);
    myFixture.doHighlighting();
    int offset = text.indexOf("#maxSize 16777216");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected quick-fix to be available", actions.isEmpty());
    myFixture.launchAction(actions.get(0));

    var result = myFixture.getFile().getText();
    assertTrue("Applying quick-fix must leave clean '{ #exact } :Float':\n" + result,
        result.contains(":BadExact { #exact } :Float"));
    assertFalse("Result must not contain #maxSize 16777216", result.contains("#maxSize 16777216"));
  }

  /**
   * Asserts that declaring continuous :Float with #maxSize 18 triggers a fatal compilation error (E008)
   * pinned strictly to '#maxSize 18'.
   */
  public void testContinuousFloatMaxSizeEighteenTriggersE008() {
    var text = """
        {
          :defs {
            :BadFloat { #maxSize 18 } :Float
          }
          :type :BadFloat
          :body 1.5
        }
        """;
    myFixture.configureByText("continuous_float_max_size_18.stvn", text);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("facet '#maxSize' is prohibited on continuous ':Float'"))
        .toList();
    assertFalse("Expected fatal error for #maxSize on continuous :Float", errors.isEmpty());

    var h = errors.get(0);
    int expectedStart = text.indexOf("#maxSize 18");
    int expectedEnd = expectedStart + "#maxSize 18".length();
    assertEquals("Start offset must pin strictly to '#maxSize 18'", expectedStart, h.getStartOffset());
    assertEquals("End offset must pin strictly to '#maxSize 18'", expectedEnd, h.getEndOffset());
  }

  /**
   * Asserts that declaring valid narrowing #maxSize 18 or widening #maxSize 100000000 on { #exact } :Float
   * compiles cleanly with zero error annotations.
   */
  public void testExactFloatValidNarrowingAndWideningPermittedWithoutError() {
    var text = """
        {
          :defs {
            :CurrencyAmount { #exact #maxSize 18 } :Float
            :SuperPrecise { #exact #maxSize 100000000 } :Float
          }
          :type :Tuple( :CurrencyAmount :SuperPrecise )
          :body ( 123.45 3.141592653589793238462643383279 )
        }
        """;
    myFixture.configureByText("exact_float_valid_sizing.stvn", text);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
    assertTrue("Valid exact float narrowings and widenings must produce zero error annotations: " + errors, errors.isEmpty());
  }

  /**
   * Asserts that completion in ':defs { :Price { #exact <caret> } :Float }' proposes #maxSize 18
   * and #maxSize 38, but suppresses #maxSize 16777216 and #size.
   */
  public void testCompletionInExactFloatProposesDomainNarrowingsAndSuppressesTautology() {
    var text = """
        {
          :defs {
            :Price { #exact <caret> } :Float
          }
          :type :Price
          :body 0.0
        }
        """;
    myFixture.configureByText("completion_exact_float.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    assertTrue("Must propose currency narrowing '#maxSize 18'", lookupStrings.contains("#maxSize 18"));
    assertTrue("Must propose financial narrowing '#maxSize 38'", lookupStrings.contains("#maxSize 38"));
    assertFalse("Must suppress tautological default ceiling '#maxSize 16777216'", lookupStrings.contains("#maxSize 16777216"));
    assertFalse("Must suppress '#size 32' on exact float", lookupStrings.contains("#size 32"));
    assertFalse("Must suppress '#size 64' on exact float", lookupStrings.contains("#size 64"));
  }

  /**
   * Asserts that completion in ':defs { :Continuous { <caret> } :Float }' suppresses #maxSize entirely.
   */
  public void testCompletionInContinuousFloatSuppressesMaxSize() {
    var text = """
        {
          :defs {
            :Continuous { <caret> } :Float
          }
          :type :Continuous
          :body 0.0
        }
        """;
    myFixture.configureByText("completion_continuous_float.stvn", text);
    myFixture.completeBasic();
    var lookupStrings = myFixture.getLookupElementStrings();
    assertNotNull("Lookup strings must not be null", lookupStrings);
    var maxSizeProposals = lookupStrings.stream().filter(s -> s.startsWith("#maxSize")).toList();
    assertTrue("Must suppress all '#maxSize' proposals on continuous :Float: " + maxSizeProposals, maxSizeProposals.isEmpty());
    assertTrue("Must propose '#exact' on continuous :Float", lookupStrings.contains("#exact"));
    assertTrue("Must propose '#size 32' on continuous :Float", lookupStrings.contains("#size 32"));
    assertFalse("Must suppress redundant '#size 64' on continuous :Float", lookupStrings.contains("#size 64"));
  }

  /**
   * Asserts that declaring natural exclusive bound #maxExcl 64 on #size 6 unsigned integer
   * triggers non-blocking warning W003, and executing quick-fix removes it cleanly.
   */
  public void testUnsignedIntNaturalCeilingTriggersWarningW003() {
    var text = """
        {
          :defs {
            :SquareIndex { #unsigned #size 6 #maxExcl 64 } :Int
          }
          :type :SquareIndex
          :body 42
        }
        """;
    myFixture.configureByText("unsigned_natural_ceiling.stvn", text);
    var highlights = myFixture.doHighlighting();
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("#maxExcl 64"))
        .toList();
    assertFalse("Expected W003 warning for redundant #maxExcl 64 on unsigned :Int with #size 6", warnings.isEmpty());

    int offset = text.indexOf("#maxExcl 64");
    myFixture.getEditor().getCaretModel().moveToOffset(offset);
    var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
    assertFalse("Expected quick-fix for #maxExcl 64", actions.isEmpty());
    myFixture.launchAction(actions.get(0));

    var result = myFixture.getFile().getText();
    assertTrue("Applying quick-fix must leave clean '{ #unsigned #size 6 } :Int':\n" + result,
        result.contains(":SquareIndex { #unsigned #size 6 } :Int"));
    assertFalse("Result must not contain #maxExcl 64", result.contains("#maxExcl 64"));
  }

  /**
   * Asserts that declaring signed integer natural bounds triggers warning W003.
   */
  public void testSignedIntNaturalBoundsTriggersWarningW003() {
    var text = """
        {
          :defs {
            :NaturalInt { #size 32 #minIncl -2147483648 #maxExcl 2147483648 } :Int
          }
          :type :NaturalInt
          :body 0
        }
        """;
    myFixture.configureByText("signed_natural_bounds.stvn", text);
    var highlights = myFixture.doHighlighting();
    var warnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getDescription() != null && h.getDescription().contains("is redundant"))
        .toList();
    assertEquals("Expected 3 warnings for redundant size and signed natural bounds", 3, warnings.size());
    assertTrue("Warning must cover #minIncl natural bound", warnings.stream().anyMatch(w -> w.getText().contains("-2147483648")));
    assertTrue("Warning must cover #maxExcl natural bound", warnings.stream().anyMatch(w -> w.getText().contains("2147483648")));
  }

  /**
   * Asserts that StvnRedundantFacetOverrideInspection suppresses duplicate warning on natural bound.
   */
  public void testHarmonizationSuppressesDuplicateOnNaturalBound() {
    var text = """
        {
          :defs {
            :SquareIndex { #unsigned #size 6 #maxExcl 64 } :Int
          }
          :type :SquareIndex
          :body 42
        }
        """;
    myFixture.enableInspections(new StvnRedundantFacetOverrideInspection());
    myFixture.configureByText("harmonization_natural_bound.stvn", text);
    var highlights = myFixture.doHighlighting();
    var maxExclWarnings = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
        .filter(h -> h.getText().contains("#maxExcl 64") || (h.getDescription() != null && h.getDescription().contains("#maxExcl 64")))
        .toList();
    assertEquals("Expected exactly 1 warning highlight over #maxExcl 64 (suppressing duplicate local inspection)", 1, maxExclWarnings.size());
  }

  /**
   * Asserts that hover documentation on redundant facet renders the W003 warning card.
   */
  public void testHoverDocOnRedundantFacetRendersWarningCard() {
    var text = """
        {
          :defs {
            :SquareIndex { #unsigned #size 6 #maxExcl 64 } :Int
          }
          :type :SquareIndex
          :body 42
        }
        """;
    var file = myFixture.configureByText("hover_redundant_facet.stvn", text);
    int offset = text.indexOf("#maxExcl 64");
    var element = file.findElementAt(offset);
    assertNotNull(element);

    var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
    var customDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), file, element, offset);
    assertNotNull("Expected custom documentation element for metadata entry", customDocElem);

    var doc = provider.generateDoc(customDocElem, element);
    assertNotNull("Expected hover doc for redundant facet", doc);
    assertTrue("Doc must mention 'Warning W003'", doc.contains("Warning W003"));
    assertTrue("Doc must state why facet is redundant", doc.contains("already bounds the maximum exclusive bound"));
    assertTrue("Doc must present canonical form", doc.contains(":SquareIndex { #unsigned #size 6 } :Int"));
  }

  /**
   * Asserts that bare sigil tokens (#, :, /) in metadata blocks do not trigger hover cards.
   */
  public void testIncompleteSigilsDoNotTriggerHoverCard() {
    var text = """
        {
          :defs {
            :MyType { # } :Int
          }
          :type :MyType
          :body 0
        }
        """;
    var file = myFixture.configureByText("bare_sigil_hover.stvn", text);
    int offset = text.indexOf("#");
    var element = file.findElementAt(offset);
    assertNotNull(element);

    var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
    var customDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), file, element, offset);
    var doc = customDocElem != null ? provider.generateDoc(customDocElem, element) : null;
    assertNull("Hover doc on bare sigil must be null", doc);
  }
}
