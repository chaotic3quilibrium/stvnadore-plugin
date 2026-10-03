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
}
