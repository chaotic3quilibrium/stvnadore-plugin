package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;

import java.util.List;

/**
 * Platform test suite verifying cross-file diagnostic isolation and comment locus protection
 * in StvnExternalAnnotator.
 */
@NullMarked
public final class StvnExternalAnnotatorTest extends BasePlatformTestCase {

    /**
     * Verifies that diagnostics originating from an included file do not project
     * false-positive error squiggles onto parent document lines or comments.
     */
    public void testIncludedFileDiagnosticsDoNotLeakToParentComments() {
        var includedContent = """
            {
              // primitives.stvn_inclf
              :defs {
                :BadType { #regex "^[0-9]+$" } :Int
              }
            }
            """;
        myFixture.addFileToProject("primitives.stvn_inclf", includedContent);

        var parentContent = """
            {
              // Header comment in events.stvn_incl that must never receive error squiggles
              :defs {
                :include [ "primitives.stvn_inclf" ]
                :EventId :String
              }
            }
            """;
        var parentFile = myFixture.configureByText("events.stvn_incl", parentContent);

        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(parentFile);
        assertNotNull("Collected information must not be null", collected);

        var result = annotator.doAnnotate(collected);
        assertNotNull("Annotation result must not be null", result);

        int commentOffset = parentContent.indexOf("// Header comment");

        // When external annotator processes diagnostics, no annotations should land on the comment
        var highlights = myFixture.doHighlighting();
        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR) {
                var range = com.intellij.openapi.util.TextRange.create(highlight.getStartOffset(), highlight.getEndOffset());
                assertFalse("Error annotation must never intersect comment locus",
                    range.contains(commentOffset));
            }
        }
    }

    /**
     * Verifies that compiler deprecation and keyword diagnostics emitted for an included module
     * (such as obsolete compound string and integer keywords) do not leak into the parent document,
     * do not register on the parent buffer, and never project annotations onto indented comments or whitespace.
     */
    public void testDeprecatedCompoundKeywordsInIncludedModuleDoNotLeakToParentComments() {
        var primitivesContent = """
            {
              // primitives.stvn_inclf
              :defs {
                :package :com/nyse/primitives {
                  // Legacy compound string keyword
                  :MicCode { #regex "^[A-Z]{4}$" } :StringFixed4

                  // Legacy compound integer keyword
                  :OrderId :Uint64
                }
              }
            }
            """;
        myFixture.addFileToProject("primitives.stvn_inclf", primitivesContent);

        var eventsContent = """
            {
              // events.stvn_incl
              :defs {
                // 1. Ingest primitives module directly
                :include [ "primitives.stvn_inclf" ]

                // 2. Scoped Package Enclosure: entries expand to :com/nyse/events/*
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]

                  // Regulated financial execution tuple
                  :ExecutionReport :Tuple(
                    :MicCode
                    :OrderId
                  )
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("events.stvn_incl", eventsContent);

        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(eventsFile);
        assertNotNull("Collected information must not be null", collected);

        var result = annotator.doAnnotate(collected);
        assertNotNull("Annotation result must not be null", result);

        var highlights = myFixture.doHighlighting();

        // 1. Assert zero squiggles appear over any comment lines or whitespace across all severities
        for (var highlight : highlights) {
            for (int offset = highlight.getStartOffset(); offset < highlight.getEndOffset(); offset++) {
                var leaf = eventsFile.findElementAt(offset);
                assertFalse("No annotation must ever intersect a comment leaf element: " + highlight.getDescription(),
                    leaf instanceof com.intellij.psi.PsiComment);
            }
            var textSlice = eventsContent.substring(
                Math.min(highlight.getStartOffset(), eventsContent.length()),
                Math.min(highlight.getEndOffset(), eventsContent.length())
            );
            assertFalse("No annotation must ever cover only whitespace: " + highlight.getDescription(),
                textSlice.isBlank());
        }

        // 2. Assert no annotations containing :StringFixed4 or :Uint64 are registered on the parent buffer
        for (var highlight : highlights) {
            var desc = highlight.getDescription();
            if (desc != null) {
                assertFalse("Parent buffer must not receive :StringFixed4 deprecation warning",
                    desc.contains(":StringFixed4"));
                assertFalse("Parent buffer must not receive :Uint64 deprecation warning",
                    desc.contains(":Uint64"));
            }
        }
    }

    /**
     * Verifies that when an undefined or deprecated child type is referenced inside a type definition,
     * error coordinates pin strictly to the child type keyword without annotating preceding valid metadata facets.
     */
    public void testOffendingChildPinningLeavesPrecedingMetadataFacetUnannotated() {
        var content = """
            {
              :defs {
                :MicCode { #minSize 4 } :StringFixed4
              }
            }
            """;
        var file = myFixture.configureByText("pinning_test.stvn_inclf", content);
        var highlights = myFixture.doHighlighting();
        var minSizeOffset = content.indexOf("#minSize 4");
        var minSizeRange = new com.intellij.openapi.util.TextRange(minSizeOffset, minSizeOffset + "#minSize 4".length());
        for (var h : highlights) {
            var hRange = new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset());
            assertFalse("Preceding valid metadata facet '#minSize 4' must not receive squiggly annotations: " + h.getDescription(),
                hRange.intersects(minSizeRange));
        }
    }

    /**
     * Verifies that when an included child module contains an undefined nominal type error,
     * the child coordinates do not project onto parent constant alias tokens (#DEFAULT_MIC #PRIMARY_EXCHANGE),
     * and the diagnostic pins strictly to the parent :include statement.
     */
    public void testChildUndefinedTypeDoesNotProjectOffsetsOntoParentTokens() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :MicCode { #minSize 4 #maxSize 4 } :String
                  :SequenceNumber :Uint49
                  #DEFAULT_MIC :MicCode "XNYS"
                }
              }
            }
            """;
        myFixture.addFileToProject("undef_primitives.stvn_inclf", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "undef_primitives.stvn_inclf" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                  :use [ :com/nyse/primitives { #DEFAULT_MIC #PRIMARY_EXCHANGE } ]
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("undef_events.stvn_incl", eventsContent);
        var highlights = myFixture.doHighlighting();

        // 1. Assert #DEFAULT_MIC and #PRIMARY_EXCHANGE receive ZERO error squiggles
        int useLineOffset = eventsContent.indexOf("#DEFAULT_MIC #PRIMARY_EXCHANGE");
        var useLineRange = new com.intellij.openapi.util.TextRange(useLineOffset, useLineOffset + "#DEFAULT_MIC #PRIMARY_EXCHANGE".length());

        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR) {
                var hRange = new com.intellij.openapi.util.TextRange(highlight.getStartOffset(), highlight.getEndOffset());
                assertFalse("Parent constant alias line must not receive child error squiggly: " + highlight.getDescription(),
                    hRange.intersects(useLineRange));
            }
        }

        // 2. Assert error pins strictly to the :include statement
        int includeOffset = eventsContent.indexOf(":include");
        boolean foundPinnedError = false;
        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR) {
                if (highlight.getStartOffset() <= includeOffset && highlight.getEndOffset() >= includeOffset) {
                    foundPinnedError = true;
                    assertTrue("Pinned annotation must identify child error: " + highlight.getDescription(),
                        highlight.getDescription().contains("Undefined type: :Uint49"));
                }
            }
        }
        assertTrue("Parent :include statement must receive pinned child error annotation", foundPinnedError);
    }

    /**
     * Verifies that when a child module at depth 2 contains a compilation error,
     * the error does not project onto root envelope definitions (:TradeBatch) at depth 0.
     */
    public void testTransitiveChildErrorDoesNotProjectOntoRootBatchEnvelope() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :SequenceNumber :Uint49
                }
              }
            }
            """;
        myFixture.addFileToProject("trans_primitives.stvn_inclf", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "trans_primitives.stvn_inclf" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                }
              }
            }
            """;
        myFixture.addFileToProject("trans_events.stvn_incl", eventsContent);

        var orderBatchContent = """
            {
              :defs {
                :include [ "trans_events.stvn_incl" ]
                :BatchId {#unsigned #size 32} :Int
                :ExecutionReport {#unsigned #size 64} :Int
                :TradeBatch :Tuple( :BatchId :ExecutionReport )
              }
            }
            """;
        var orderBatchFile = myFixture.configureByText("trans_order_batch.stvn", orderBatchContent);
        var highlights = myFixture.doHighlighting();

        // Assert :TradeBatch receives zero error annotations
        int tradeBatchOffset = orderBatchContent.indexOf(":TradeBatch");
        var tradeBatchRange = new com.intellij.openapi.util.TextRange(tradeBatchOffset, tradeBatchOffset + ":TradeBatch".length());

        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR) {
                var hRange = new com.intellij.openapi.util.TextRange(highlight.getStartOffset(), highlight.getEndOffset());
                assertFalse("Root :TradeBatch token must not receive transitive child error: " + highlight.getDescription(),
                    hRange.intersects(tradeBatchRange));
            }
        }
    }

    /**
     * Verifies that clean multi-file inclusions produce zero false-positive error annotations.
     */
    public void testCleanMultiFileInclusionProducesZeroAnnotations() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :MicCode { #minSize 4 #maxSize 4 #regex "^[A-Z]{4}$" } :String
                  :OrderId {#unsigned #size 64} :Int
                  :SequenceNumber {#unsigned #size 49} :Int
                  #DEFAULT_MIC :MicCode "XNYS"
                }
              }
            }
            """;
        myFixture.addFileToProject("clean_primitives.stvn_inclf", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "clean_primitives.stvn_inclf" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                  :use [ :com/nyse/primitives { #DEFAULT_MIC #PRIMARY_EXCHANGE } ]
                }
              }
            }
            """;
        myFixture.configureByText("clean_events.stvn_incl", eventsContent);
        var highlights = myFixture.doHighlighting();

        for (var highlight : highlights) {
            assertFalse("Clean multi-file inclusion must produce zero ERROR highlights: " + highlight.getDescription(),
                highlight.getSeverity() == HighlightSeverity.ERROR);
        }
    }
}
