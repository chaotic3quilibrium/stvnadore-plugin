package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver;
import com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
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
     * Enforces the anti-tautological dual-assertion pattern.
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

        // 1. Positive Assertion: Assert error exists and pins strictly to :StringFixed4
        int typeOffset = content.indexOf(":StringFixed4");
        var typeRange = new com.intellij.openapi.util.TextRange(typeOffset, typeOffset + ":StringFixed4".length());
        boolean foundOffendingHighlight = false;
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null && h.getDescription().contains(":StringFixed4")) {
                var hRange = new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset());
                assertEquals("Error squiggly must pin exactly to the offending type keyword", typeRange, hRange);
                foundOffendingHighlight = true;
            }
        }
        assertTrue("Annotator must positively highlight the undefined type ':StringFixed4'", foundOffendingHighlight);

        // 2. Negative Assertion: Assert zero highlights intersect preceding metadata facet '#minSize 4'
        var minSizeOffset = content.indexOf("#minSize 4");
        var minSizeRange = new com.intellij.openapi.util.TextRange(minSizeOffset, minSizeOffset + "#minSize 4".length());
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR) {
                var hRange = new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset());
                assertFalse("Preceding valid metadata facet '#minSize 4' must not receive squiggly annotations: " + h.getDescription(),
                    hRange.intersects(minSizeRange));
            }
        }
    }

    /**
     * Verifies that an adorned definition with an undeclared type inside a package block
     * pins the error squiggly strictly to the undeclared type token while leaving metadata facets clean.
     */
    public void testAdornedDefinitionWithUndeclaredTypeInPackageBlockPinsExactToken() {
        var content = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :MicCode { #minSize 4 #maxSize 4 #regex "^[A-Z]{4}$" } :StringFixed4
                }
              }
            }
            """;
        myFixture.configureByText("pkg_pinning_test.stvn_inclf", content);
        var highlights = myFixture.doHighlighting();

        // 1. Positive assertion on :StringFixed4
        int typeOffset = content.indexOf(":StringFixed4");
        var typeRange = new com.intellij.openapi.util.TextRange(typeOffset, typeOffset + ":StringFixed4".length());
        boolean foundError = false;
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null && h.getDescription().contains(":StringFixed4")) {
                var hRange = new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset());
                assertEquals("Package-enclosed error squiggly must pin exactly to :StringFixed4", typeRange, hRange);
                foundError = true;
            }
        }
        assertTrue("Package-enclosed undefined type must receive error annotation", foundError);

        // 2. Negative assertion on entire metadata block
        int metaStart = content.indexOf("{ #minSize 4");
        int metaEnd = content.indexOf("}", metaStart) + 1;
        var metaRange = new com.intellij.openapi.util.TextRange(metaStart, metaEnd);
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR) {
                var hRange = new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset());
                assertFalse("Metadata facet block must remain clean: " + h.getDescription(),
                    hRange.intersects(metaRange));
            }
        }
    }

    /**
     * Verifies that a local undefined type in a file containing an :include statement
     * highlights the local type keyword and does NOT falsely pin to the :include statement.
     */
    public void testLocalUndefinedTypeInFileWithIncludesPinsLocalTokenNotInclude() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :MicCode :String
                }
              }
            }
            """;
        myFixture.addFileToProject("local_prim.stvn_inclf", primitivesContent);

        var activeContent = """
            {
              :defs {
                :include [ "local_prim.stvn_inclf" ]
                :OrderId :Uint64
              }
            }
            """;
        myFixture.configureByText("local_active.stvn", activeContent);
        var highlights = myFixture.doHighlighting();

        // 1. Assert :Uint64 receives error highlight
        int uintOffset = activeContent.indexOf(":Uint64");
        var uintRange = new com.intellij.openapi.util.TextRange(uintOffset, uintOffset + ":Uint64".length());
        boolean foundUintError = false;
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null && h.getDescription().contains(":Uint64")) {
                var hRange = new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset());
                assertEquals("Local undefined type must pin to :Uint64", uintRange, hRange);
                foundUintError = true;
            }
        }
        assertTrue("Local undefined type ':Uint64' must be highlighted", foundUintError);

        // 2. Assert :include does NOT receive a pinned error for :Uint64
        int inclOffset = activeContent.indexOf(":include");
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null && h.getDescription().contains("local_prim.stvn_inclf")) {
                fail("Local error must not pin to clean include statement: " + h.getDescription());
            }
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

    /**
     * Verifies that when a document contains only an include error originating from an invalid
     * child module, WolfTheProblemSolver positively identifies the parent file as a problem file.
     */
    public void testPinnedIncludeErrorMarksParentFileInWolfTheProblemSolver() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :SequenceNumber :Uint49
                }
              }
            }
            """;
        myFixture.addFileToProject("wolf_primitives.stvn_inclf", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "wolf_primitives.stvn_inclf" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("wolf_events.stvn_incl", eventsContent);
        var virtualFile = eventsFile.getVirtualFile();
        assertNotNull("VirtualFile must not be null", virtualFile);

        var wolf = WolfTheProblemSolver.getInstance(getProject());
        if (wolf instanceof MockWolfTheProblemSolver mockWolf) {
            mockWolf.setDelegate(WolfTheProblemSolverImpl.createTestInstance(getProject()));
        }

        try {
            var highlights = myFixture.doHighlighting();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            boolean hasPinnedError = highlights.stream().anyMatch(h ->
                h.getSeverity() == HighlightSeverity.ERROR &&
                h.getDescription() != null &&
                h.getDescription().contains("wolf_primitives.stvn_inclf"));
            assertTrue("Parent file must display pinned error highlight on include statement", hasPinnedError);

            assertTrue("WolfTheProblemSolver must report wolf_events.stvn_incl as problem file for pinned include error",
                wolf.isProblemFile(virtualFile));
        } finally {
            if (wolf instanceof MockWolfTheProblemSolver mockWolf) {
                mockWolf.resetDelegate();
            }
        }
    }

    /**
     * Verifies that when an invalid included child module is fixed, re-annotating the parent document
     * cleanly removes all pinned include errors and clears the problem status in WolfTheProblemSolver.
     */
    public void testFixingChildModuleClearsParentProblemFileInWolfTheProblemSolver() throws Exception {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :SequenceNumber :Uint49
                }
              }
            }
            """;
        var primitivesFile = myFixture.addFileToProject("fixable_primitives.stvn_inclf", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "fixable_primitives.stvn_inclf" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("fixable_events.stvn_incl", eventsContent);
        var eventsVf = eventsFile.getVirtualFile();
        assertNotNull("VirtualFile must not be null", eventsVf);

        var wolf = WolfTheProblemSolver.getInstance(getProject());
        if (wolf instanceof MockWolfTheProblemSolver mockWolf) {
            mockWolf.setDelegate(WolfTheProblemSolverImpl.createTestInstance(getProject()));
        }

        try {
            // 1. Initial invalid state
            myFixture.doHighlighting();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue("Parent file must initially be marked as problem file", wolf.isProblemFile(eventsVf));

            // 2. Fix the child module
            var validPrimitivesContent = """
                {
                  :defs {
                    :package :com/nyse/primitives {
                      :SequenceNumber {#unsigned #size 49} :Int
                    }
                  }
                }
                """;
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                try {
                    primitivesFile.getVirtualFile().setBinaryContent(validPrimitivesContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });

            var physicalPath = org.stvnadore.plugin.reference.StvnTypeResolver.resolvePhysicalPath(eventsVf, getProject(), eventsFile.getText());
            var tempParent = java.nio.file.Path.of(physicalPath).getParent();
            if (tempParent != null) {
                var childDiskFile = tempParent.resolve("fixable_primitives.stvn_inclf");
                java.nio.file.Files.writeString(childDiskFile, validPrimitivesContent, java.nio.charset.StandardCharsets.UTF_8);
            }

            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                myFixture.getEditor().getDocument().setText(eventsContent);
            });
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

            // 3. Re-highlight parent file
            myFixture.openFileInEditor(eventsVf);
            var cleanHighlights = myFixture.doHighlighting();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            boolean hasErrors = cleanHighlights.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR);
            assertFalse("Parent file must contain zero ERROR highlights after child module is fixed", hasErrors);

            assertFalse("WolfTheProblemSolver must clear problem status for events.stvn_incl after child fix",
                wolf.isProblemFile(eventsVf));

            var fileStatus = FileStatusManager.getInstance(getProject()).getStatus(eventsVf);
            assertEquals("FileStatus must be NOT_CHANGED after fix",
                FileStatus.NOT_CHANGED, fileStatus);
        } finally {
            if (wolf instanceof MockWolfTheProblemSolver mockWolf) {
                mockWolf.resetDelegate();
            }
        }
    }
}
