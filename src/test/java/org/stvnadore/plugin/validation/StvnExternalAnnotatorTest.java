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
import org.stvnadore.core.validation.DiagnosticBag;
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
              // primitives.stvn_di
              :defs {
                :BadType { #regex "^[0-9]+$" } :Int
              }
            }
            """;
        myFixture.addFileToProject("primitives.stvn_di", includedContent);

        var parentContent = """
            {
              // Header comment in events.stvn_i that must never receive error squiggles
              :defs {
                :include [ "primitives.stvn_di" ]
                :EventId :String
              }
            }
            """;
        var parentFile = myFixture.configureByText("events.stvn_i", parentContent);

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
     * Verifies that compiler prohibition and keyword diagnostics emitted for an included module
     * (such as obsolete compound string and integer keywords) do not leak into the parent document,
     * do not register on the parent buffer, and never project annotations onto indented comments or whitespace.
     */
    public void testProhibitedCompoundKeywordsInIncludedModuleDoNotLeakToParentComments() {
        var primitivesContent = """
            {
              // primitives.stvn_di
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
        myFixture.addFileToProject("primitives.stvn_di", primitivesContent);

        var eventsContent = """
            {
              // events.stvn_i
              :defs {
                // 1. Ingest primitives module directly
                :include [ "primitives.stvn_di" ]

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
        var eventsFile = myFixture.configureByText("events.stvn_i", eventsContent);

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
                assertFalse("Parent buffer must not receive :StringFixed4 undefined type error",
                    desc.contains(":StringFixed4"));
                assertFalse("Parent buffer must not receive :Uint64 undefined type error",
                    desc.contains(":Uint64"));
            }
        }
    }

    /**
     * Verifies that when an undefined or prohibited child type is referenced inside a type definition,
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
        var file = myFixture.configureByText("pinning_test.stvn_di", content);
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
        myFixture.configureByText("pkg_pinning_test.stvn_di", content);
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
              :meta {
                #name "local_prim"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :com/nyse/primitives {
                  :MicCode :String
                }
              }
            }
            """;
        myFixture.addFileToProject("local_prim.stvn_di", primitivesContent);

        var activeContent = """
            {
              :defs {
                :include [ "local_prim.stvn_di" ]
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
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null && h.getDescription().contains("local_prim.stvn_di")) {
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
              :meta {
                #name "undef_primitives"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :com/nyse/primitives {
                  :MicCode { #minSize 4 #maxSize 4 } :String
                  :SequenceNumber :Uint49
                  #DEFAULT_MIC :MicCode "XNYS"
                }
              }
            }
            """;
        myFixture.addFileToProject("undef_primitives.stvn_di", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "undef_primitives.stvn_di" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                  :use [ :com/nyse/primitives { #DEFAULT_MIC #PRIMARY_EXCHANGE } ]
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("undef_events.stvn_i", eventsContent);
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
        myFixture.addFileToProject("trans_primitives.stvn_di", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "trans_primitives.stvn_di" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                }
              }
            }
            """;
        myFixture.addFileToProject("trans_events.stvn_di", eventsContent);

        var orderBatchContent = """
            {
              :defs {
                :include [ "trans_events.stvn_di" ]
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
              :meta {
                #name "clean_primitives"
                #domain "events"
                #kind #DEFS_INCLUDE
              }
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
        myFixture.addFileToProject("clean_primitives.stvn_di", primitivesContent);

        var eventsContent = """
            {
              :meta {
                #name "clean_events"
                #domain "events"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "clean_primitives.stvn_di" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                  :use [ :com/nyse/primitives { #DEFAULT_MIC #PRIMARY_EXCHANGE } ]
                }
              }
            }
            """;
        myFixture.configureByText("clean_events.stvn_di", eventsContent);
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
        myFixture.addFileToProject("wolf_primitives.stvn_di", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "wolf_primitives.stvn_di" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("wolf_events.stvn_di", eventsContent);
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
                h.getDescription().contains("wolf_primitives.stvn_di"));
            assertTrue("Parent file must display pinned error highlight on include statement", hasPinnedError);

            assertTrue("WolfTheProblemSolver must report wolf_events.stvn_di as problem file for pinned include error",
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
              :meta {
                #name "fixable_primitives"
                #domain "events"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :package :com/nyse/primitives {
                  :SequenceNumber :Uint49
                }
              }
            }
            """;
        var primitivesFile = myFixture.addFileToProject("fixable_primitives.stvn_di", primitivesContent);

        var eventsContent = """
            {
              :meta {
                #name "fixable_events"
                #domain "events"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "fixable_primitives.stvn_di" ]
                :package :com/nyse/events {
                  :use [ :com/nyse/primitives { #strip } ]
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("fixable_events.stvn_di", eventsContent);
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
                  :meta {
                    #name "fixable_primitives"
                    #domain "events"
                    #kind #DEFS_INCLUDE
                  }
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
                var childDiskFile = tempParent.resolve("fixable_primitives.stvn_di");
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

            assertFalse("WolfTheProblemSolver must clear problem status for events.stvn_di after child fix",
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

    /**
     * Verifies that undeclared legacy temporal keywords (:DateTimeOffset, :DateTimeZoned, :DateTimeAudited)
     * produce ERR_UNDEFINED_TYPE error annotations pinned directly to the offending token coordinates.
     */
    public void testUndeclaredLegacyTemporalKeywordsProduceUndefinedTypeErrorAnnotations() {
        for (String legacyType : List.of(":DateTimeOffset", ":DateTimeZoned", ":DateTimeAudited")) {
            var content = """
                {
                  :type %s
                  :body "2026-03-15T08:00:00Z"
                }
                """.formatted(legacyType);
            myFixture.configureByText("legacy_temporal_error_" + legacyType.substring(1) + ".stvn", content);

            var highlights = myFixture.doHighlighting();
            var errorHighlights = highlights.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .toList();

            assertFalse("Document containing legacy temporal keyword " + legacyType + " must produce error highlights", errorHighlights.isEmpty());
            assertTrue("Must contain undefined type error for " + legacyType,
                errorHighlights.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("Undefined type: " + legacyType)));

            // Verify precise token range pinning
            var offsetError = errorHighlights.stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains("Undefined type: " + legacyType))
                .findFirst()
                .orElseThrow();
            var targetText = content.substring(offsetError.getStartOffset(), offsetError.getEndOffset());
            assertEquals(legacyType, targetText);
        }
    }

    /**
     * Verifies that canonical STVN 2.0.0 :DateTime with mode facet produces zero errors.
     */
    public void testCanonicalDateTimeWithFacetProducesZeroErrors() {
        var content = """
            {
              :type { #offset } :DateTime
              :body "2026-03-15T08:00:00Z"
            }
            """;
        myFixture.configureByText("canonical_datetime_valid.stvn", content);
        var highlights = myFixture.doHighlighting();
        boolean hasErrors = highlights.stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR);
        assertFalse("Valid canonical :DateTime declaration must produce zero errors", hasErrors);
    }

    /**
     * Verifies that pseudo-prelude path :org/stvnadore/prelude/DateTimeOffset fails closed as an
     * unresolved nominal reference without causing exceptions in the external annotator or doc provider.
     */
    public void testPseudoPreludeTemporalPathProducesUnresolvedTypeAnnotationWithoutCrashing() {
        var content = """
            {
              :type :org/stvnadore/prelude/DateTimeOffset
              :body "2026-03-15T08:00:00Z"
            }
            """;
        myFixture.configureByText("pseudo_prelude_temporal.stvn", content);
        var highlights = myFixture.doHighlighting();
        var error = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).findFirst().orElseThrow();
        assertTrue(error.getDescription() != null && error.getDescription().contains("Undefined type: :org/stvnadore/prelude/DateTimeOffset"));
    }

    /**
     * Verifies that a composite :Tuple containing 16 invalid types receives all 16 error squigglies
     * simultaneously with distinct non-clobbered coordinate ranges.
     */
    public void testCompositeTupleWithSixteenInvalidTypesReceivesSixteenSimultaneousErrorSquigglies() {
        var content = """
            {
              :defs {
                :Values :Tuple (
                  :TimeEpochNs
                  :TimeEpochUs
                  :TimeEpochMs
                  :TimeEpochS

                  :DateTimeOffset
                  :DateTimeZoned
                  :DateTimeAudited

                  :org/stvnadore/prelude/TimeEpochNs
                  :org/stvnadore/prelude/TimeEpochUs
                  :org/stvnadore/prelude/TimeEpochMs
                  :org/stvnadore/prelude/TimeEpochS

                  :org/stvnadore/prelude/DateTimeOffset
                  :org/stvnadore/prelude/DateTimeZoned
                  :org/stvnadore/prelude/DateTimeAudited

                  {#offset} :org/stvnadore/prelude/DateTime
                  {#ns} :org/stvnadore/prelude/TimeEpoch
                )
              }
              :type :Values
              :body (
                1 2 3 4
                "2026-03-15T08:00:00Z" "2026-03-15T08:00:00Z" "2026-03-15T08:00:00Z"
                5 6 7 8
                "2026-03-15T08:00:00Z" "2026-03-15T08:00:00Z" "2026-03-15T08:00:00Z"
                "2026-03-15T08:00:00Z" 1789241400
              )
            }
            """;
        myFixture.configureByText("sixteen_errors.stvn", content);
        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Undefined type: "))
            .toList();

        assertEquals("Must produce exactly 16 undefined type error highlights simultaneously", 16, errorHighlights.size());

        var distinctRanges = errorHighlights.stream()
            .map(h -> new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset()))
            .distinct()
            .toList();
        assertEquals("All 16 error squigglies must possess distinct coordinate ranges", 16, distinctRanges.size());
    }

    /**
     * Verifies that placing a scalar literal in a map entry collection value slot
     * produces an ERR_TYPE_MISMATCH highlight pinned strictly to the offending scalar literal.
     */
    public void testMapEntryValueScalarForCollectionHighlightPinnedStrictly() {
        var content = """
            {
              :defs {
                :String0 { #maxSize 0 } :String
              }
              :type :Map( :String0 :Set( :String0 ) )
              :body {
                [ "" "" ]
              }
            }
            """;
        myFixture.configureByText("map_scalar_mismatch.stvn", content);
        var highlights = myFixture.doHighlighting();
        var mismatchErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Type mismatch: expected collection constructor :Set, found scalar :String"))
            .toList();

        assertEquals("Must produce exactly 1 type mismatch error highlight", 1, mismatchErrors.size());
        var error = mismatchErrors.getFirst();
        int expectedStart = content.lastIndexOf("\"\"");
        int expectedEnd = expectedStart + 2;
        assertEquals("Highlight start offset must pin strictly to second string literal", expectedStart, error.getStartOffset());
        assertEquals("Highlight end offset must pin strictly to second string literal", expectedEnd, error.getEndOffset());
    }

    public void testOrderViolationDeduplicatedWhenInspectionActive() {
        var disorderedContent = """
            {
              :defs {
                :BadOrder { #size 16 #unsigned } :Int
              }
              :type :BadOrder
              :body 42
            }
            """;
        var file = myFixture.configureByText("disordered.stvn", disorderedContent);
        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(file);
        assertNotNull(collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull(result);

        // Annotator should suppress ERR_FACET_ORDER_VIOLATION when inspection is registered
        boolean hasSuppressedOrderDiag = result.diagnostics().stream()
            .anyMatch(d -> d.errorCode().isPresent() && "ERR_FACET_ORDER_VIOLATION".equals(d.errorCode().get()));
        assertTrue("Compiler must have produced ERR_FACET_ORDER_VIOLATION", hasSuppressedOrderDiag);

        // Verifies isDiagnosticSuppressed returns true
        var orderDiag = result.diagnostics().stream().filter(d -> d.errorCode().isPresent() && "ERR_FACET_ORDER_VIOLATION".equals(d.errorCode().get())).findFirst().orElseThrow();
        assertTrue("External annotator must suppress order diagnostic when inspection active", annotator.isDiagnosticSuppressed(file, orderDiag));
    }

    public void testExternalAnnotatorSuppressesStemMismatchWhenInspectionActive() {
        var content = """
            {
              :meta {
                #name "json_example"
                #domain "place_holder"
                #kind #BODY_INCLUDE
              }
              :type :Seq(:Int)
              :body [ 1 2 3 ]
            }
            """;
        var file = myFixture.configureByText("json_example.stvn_i", content);
        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(file);
        assertNotNull(collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull(result);

        // Find domain omitted diagnostic from compiler result
        var domainDiag = result.diagnostics().stream()
            .filter(d -> (d.errorCode().isPresent() && DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME.equals(d.errorCode().get()))
                || d.message().contains("WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME")
                || d.message().contains("is omitted from filename stem slot 1"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Compiler must produce WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME"));

        assertTrue("External annotator must suppress domain omitted diagnostic when StvnStemMismatch inspection is active",
            annotator.isDiagnosticSuppressed(file, domainDiag));

        myFixture.enableInspections(new StvnStemMismatchInspection());
        var highlights = myFixture.doHighlighting();
        var domainWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("is omitted from filename stem slot 1"))
            .toList();
        assertEquals("Editor must contain exactly 1 domain warning without duplication", 1, domainWarnings.size());
    }

    public void testUhohNineCasesNominalOverrideAndMutualExclusivityDiagnostics() {
        var content = """
            {
              :defs {
                :FloatExactA { #exact } :Float
                :Float32A { #size 32 } :Float
                :FloatExactAToExact { #exact } :Float32A
                :FloatExactATo32 { #size 32 } :FloatExactA
                :FloatExactATo64 { #size 64 } :FloatExactA
                :Float64A { #size 64 } :Float
                :Float32AToExact { #exact } :Float32A
                :Float32ATo32 { #size 32 } :Float32A
                :Float32ATo64 { #size 64 } :Float32A
                :Float64B { #size 64 } :Float
                :Float64AToExact { #exact } :Float64B
                :Float64ATo32 { #size 32 } :Float64B
                :Float64ATo64 { #size 64 } :Float64B
              }
              :type :Int
              :body 0
            }
            """;
        myFixture.configureByText("uhoh_nine.stvn", content);
        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        var uhohHighlights = errorHighlights.stream()
            .filter(h -> h.getDescription() != null && !h.getDescription().contains("is redundant"))
            .toList();
        assertEquals("Must produce exactly 9 error highlights for the 9 uhoh cases", 9, uhohHighlights.size());

        // Verify lines 4, 8, 12 report mutual exclusivity for #exact
        long exactMutexCount = errorHighlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("mutually exclusive") && h.getText().contains("#exact"))
            .count();
        assertEquals("Lines 4, 8, 12 must report ERR_MUTUALLY_EXCLUSIVE on #exact", 3, exactMutexCount);

        // Verify lines 5, 6, 10, 13 report mutual exclusivity on conflicting #size
        long sizeMutexCount = errorHighlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("mutually exclusive") && h.getText().contains("#size"))
            .count();
        assertEquals("Lines 5, 6, 10, 13 must report ERR_MUTUALLY_EXCLUSIVE on conflicting #size", 4, sizeMutexCount);

        // Verify lines 9, 14 report non-overridable facet on re-declared #size
        long nonOverridableCount = errorHighlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("non-overridable") && h.getText().contains("#size"))
            .count();
        assertEquals("Lines 9, 14 must report ERR_NON_OVERRIDABLE_FACET on re-declared #size", 2, nonOverridableCount);
    }

    /**
     * Verifies Single Diagnostic Highlight Invariant (Rule STR-04):
     * When StvnFencedStringInspection is active, the external annotator suppresses
     * the raw compiler syntax diagnostic for prohibited fence arrow delimiter,
     * ensuring exactly one error highlight with the in-place quick-fix appears.
     */
    public void testProhibitedFencedStringArrowSuppressedWhenInspectionActive() {
        myFixture.enableInspections(new StvnFencedStringInspection());
        var content = """
            {
              :defs {
                :NestedDocumentStore :String
              }
              :type :Tuple( :NestedDocumentStore )
              :body (
                \"\"\"->[EMBEDDED_STVN]
                {
                  :type :Seq( :Boolean )
                  :body [ #TRUE #FALSE ]
                }
                [EMBEDDED_STVN]\"\"\"
              )
            }
            """;
        myFixture.configureByText("syntax_torture_snippet.stvn", content);
        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Exactly one error highlight must exist on opening delimiter line", 1, errorHighlights.size());
        var error = errorHighlights.get(0);
        assertEquals("Rule STR-04 violation: Fenced string delimiter arrow '->' is prohibited [stvn-plugin:ERR_PROHIBITED_FENCE_ARROW]", error.getDescription());
        assertFalse("Raw compiler syntax error duplicate must be suppressed", error.getDescription().contains("STVN Syntax Error:"));

        var quickFixes = myFixture.getAllQuickFixes();
        boolean hasRemoveArrowFix = quickFixes.stream()
            .anyMatch(f -> f.getText().contains("Remove prohibited '->' arrow"));
        assertTrue("Remove prohibited '->' arrow quick-fix must be available", hasRemoveArrowFix);
    }

    /**
     * Verifies that transitive include failure correctly attributes the grandchild module
     * and groups diagnostics on the parent include statement without stacked duplicate errors,
     * while offering quick-fixes for both files in the failure chain.
     */
    public void testTransitiveIncludeDiagnosticAttributionAndDeduplication() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :OrderId {#unsigned #size 64} :Int
                }
              }
            }
            """;
        myFixture.addFileToProject("primitives.stvn_d", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "primitives.stvn_d" ]
              }
            }
            """;
        myFixture.addFileToProject("events.stvn_di", eventsContent);

        var orderBatchContent = """
            {
              :defs {
                :include [ "events.stvn_di" ]
              }
            }
            """;
        myFixture.configureByText("order_batch.stvn_i", orderBatchContent);
        var highlights = myFixture.doHighlighting();

        var includeErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("events.stvn_di"))
            .toList();

        assertEquals("Expected exactly one grouped annotation on include statement", 1, includeErrors.size());
        var errorDesc = includeErrors.get(0).getDescription();
        assertTrue("Expected attribution to transitive primitives.stvn_d", errorDesc.contains("primitives.stvn_d"));

        var quickFixes = myFixture.getAllQuickFixes();
        boolean hasEventsFix = quickFixes.stream()
            .anyMatch(f -> f.getText().contains("events.stvn_di"));
        boolean hasPrimitivesFix = quickFixes.stream()
            .anyMatch(f -> f.getText().contains("primitives.stvn_d"));
        assertTrue("Expected quick-fix to open direct include events.stvn_di", hasEventsFix);
        assertTrue("Expected quick-fix to open transitive include primitives.stvn_d", hasPrimitivesFix);
    }

    /**
     * Verifies that collectInformation flushes unsaved editor documents via saveAllDocuments()
     * and produces valid collected information.
     */
    public void testExternalAnnotatorCollectInformationFlushesDocuments() {
        var file = myFixture.configureByText("dummy.stvn", "{\n  :body 42\n}\n");
        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(file);
        assertNotNull("CollectedInfo must not be null", collected);
        assertEquals(file.getText(), collected.text());
    }

    /**
     * Verifies that when an included child module contains an adornment/empty metadata map error
     * (such as {#sizex 64}), the coordinates do not bleed onto parent tokens (such as :package),
     * and the diagnostic pins strictly to the parent :include statement.
     */
    public void testIncludedModuleEmptyMetadataMapDoesNotBleedCoordinatesToPackageEnclosure() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :OrderId {#unsigned #size 64} :Int
                  :Int64 {#sizex 64} :Int
                }
              }
            }
            """;
        myFixture.addFileToProject("prim_meta_err.stvn_d", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "prim_meta_err.stvn_d" ]
                :package :com/nyse/events {
                  :AgencySide :Int
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("events_meta_err.stvn_di", eventsContent);
        var highlights = myFixture.doHighlighting();

        // 1. Assert :package :com/nyse/events receives ZERO error squiggles
        int pkgOffset = eventsContent.indexOf(":package :com/nyse/events");
        var pkgRange = new com.intellij.openapi.util.TextRange(pkgOffset, pkgOffset + ":package :com/nyse/events".length());

        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR) {
                var hRange = new com.intellij.openapi.util.TextRange(highlight.getStartOffset(), highlight.getEndOffset());
                assertFalse("Parent package enclosure must not receive child error squiggly: " + highlight.getDescription(),
                    hRange.intersects(pkgRange));
            }
        }

        // 2. Assert error pins strictly to the :include statement
        int includeOffset = eventsContent.indexOf(":include");
        boolean foundPinnedError = false;
        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR) {
                if (highlight.getStartOffset() <= includeOffset && highlight.getEndOffset() >= includeOffset) {
                    foundPinnedError = true;
                    assertTrue("Pinned annotation must identify child include: " + highlight.getDescription(),
                        highlight.getDescription().contains("prim_meta_err.stvn_d"));
                }
            }
        }
        assertTrue("Parent :include statement must receive pinned child error annotation", foundPinnedError);
    }

    /**
     * Verifies that hover tooltips on :include statements render clean HTML lists with <br/>
     * and &bull; bullet points instead of collapsed run-on paragraphs.
     */
    public void testIncludeTooltipRendersCleanHtmlWithoutRunOnParagraph() {
        var primitivesContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :Bad1 :UndefType1
                  :Bad2 :UndefType2
                }
              }
            }
            """;
        myFixture.addFileToProject("html_tooltip_prim.stvn_d", primitivesContent);

        var eventsContent = """
            {
              :defs {
                :include [ "html_tooltip_prim.stvn_d" ]
              }
            }
            """;
        myFixture.configureByText("html_tooltip_events.stvn_di", eventsContent);
        var highlights = myFixture.doHighlighting();

        int includeOffset = eventsContent.indexOf(":include");
        com.intellij.codeInsight.daemon.impl.HighlightInfo pinnedHighlight = null;
        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR &&
                highlight.getStartOffset() <= includeOffset && highlight.getEndOffset() >= includeOffset) {
                pinnedHighlight = highlight;
                break;
            }
        }
        assertNotNull("Include statement must receive error highlight", pinnedHighlight);
        var tooltip = pinnedHighlight.getToolTip();
        assertNotNull("Highlight must provide rich HTML tooltip", tooltip);
        assertTrue("Tooltip must start with <html> tag: " + tooltip, tooltip.startsWith("<html>"));
        assertTrue("Tooltip must contain HTML line break <br/>: " + tooltip, tooltip.contains("<br/>"));
        assertTrue("Tooltip must contain bullet entity &bull;: " + tooltip, tooltip.contains("&bull;"));
        assertFalse("Tooltip must not contain repeated file prefix per bullet: " + tooltip,
            tooltip.contains("contains compilation errors: &bull; Included file"));
    }

    /**
     * Verifies that when a leaf dependency fails, the root cause structural error is prioritized
     * ahead of secondary cascading undefined type noise in the grouped diagnostics list.
     */
    public void testDeepestFailedDependencyPrioritizedOverCascadingUndefinedTypes() {
        var leafContent = """
            {
              :defs {
                :package :com/nyse/primitives {
                  :OrderId {#unsigned #size 64} :Int
                  :Int64 {#sizex 64} :Int
                }
              }
            }
            """;
        myFixture.addFileToProject("prio_leaf.stvn_d", leafContent);

        var intermediateContent = """
            {
              :defs {
                :include [ "prio_leaf.stvn_d" ]
                :package :com/nyse/events {
                  :ExecReport :Tuple( :OrderId )
                }
              }
            }
            """;
        myFixture.addFileToProject("prio_intermediate.stvn_di", intermediateContent);

        var rootContent = """
            {
              :defs {
                :include [ "prio_intermediate.stvn_di" ]
              }
            }
            """;
        myFixture.configureByText("prio_root.stvn_i", rootContent);
        var highlights = myFixture.doHighlighting();

        int includeOffset = rootContent.indexOf(":include");
        com.intellij.codeInsight.daemon.impl.HighlightInfo pinnedHighlight = null;
        for (var highlight : highlights) {
            if (highlight.getSeverity() == HighlightSeverity.ERROR &&
                highlight.getStartOffset() <= includeOffset && highlight.getEndOffset() >= includeOffset) {
                pinnedHighlight = highlight;
                break;
            }
        }
        assertNotNull("Root include statement must receive error highlight", pinnedHighlight);
        var tooltip = pinnedHighlight.getToolTip();
        assertNotNull("Highlight must provide HTML tooltip", tooltip);

        // Root cause error in leaf (Unrecognized constraint facet #sizex) must appear prominently in tooltip
        int rootCauseIdx = tooltip.indexOf("Unrecognized constraint facet");
        assertTrue("Leaf root cause error must be present in tooltip: " + tooltip, rootCauseIdx >= 0);
        assertTrue("Tooltip must contain unrecognized facet tag: " + tooltip, tooltip.contains("#sizex"));
        assertTrue("Cascading undefined type errors must be collapsed in tooltip: " + tooltip,
            tooltip.contains("secondary cascading undefined type errors suppressed"));
        assertFalse("Cascading undefined type error must not appear as bullet in tooltip: " + tooltip,
            tooltip.contains("&bull; Undefined type: :OrderId"));
    }

    /**
     * Verifies Vector 1: Universal Coordinate Bleed Elimination.
     * When bleed_primitives.stvn_d contains a syntax error on line 11, line 11 of bleed_events.stvn_di
     * must receive zero error squiggles, and the error must pin strictly to :include.
     */
    public void testChildSyntaxErrorDoesNotBleedCoordinatesToParentPackageEnclosure() {
        var primitivesContent = """
            {
              :meta {
                #kind #DEFS
              }
              :defs {
                :package :com/nyse/primitives {
                  :OrderId1 :Int
                  :OrderId2 :Int
                  :OrderId3 :Int
                  :OrderId4 :Int
                  :BadLine {#sizex 64} :Int
                }
              }
            }
            """;
        myFixture.addFileToProject("bleed_primitives.stvn_d", primitivesContent);

        var eventsContent = """
            {
              :meta {
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "bleed_primitives.stvn_d" ]
                :Dummy1 :Int
                :Dummy2 :Int
                :Dummy3 :Int
                :Dummy4 :Int
                :package :com/nyse/events {
                  :AgencySide :Int
                }
              }
            }
            """;
        var eventsFile = myFixture.configureByText("bleed_events.stvn_di", eventsContent);
        var highlights = myFixture.doHighlighting();

        // 1. Negative Assertion: Assert line 11 / :package receives zero error squiggles
        int pkgOffset = eventsContent.indexOf(":package :com/nyse/events");
        var pkgRange = new com.intellij.openapi.util.TextRange(pkgOffset, pkgOffset + ":package :com/nyse/events".length());
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR) {
                var hRange = new com.intellij.openapi.util.TextRange(h.getStartOffset(), h.getEndOffset());
                assertFalse("Parent package enclosure must not receive child syntax error: " + h.getDescription(),
                    hRange.intersects(pkgRange));
            }
        }

        // 2. Positive Assertion: Assert error pins strictly to the :include statement
        int includeOffset = eventsContent.indexOf(":include");
        boolean foundPinnedError = false;
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() <= includeOffset && h.getEndOffset() >= includeOffset) {
                foundPinnedError = true;
                assertTrue("Pinned annotation must identify child include: " + h.getDescription(),
                    h.getDescription().contains("bleed_primitives.stvn_d"));
            }
        }
        assertTrue("Parent :include statement must receive pinned child syntax error annotation", foundPinnedError);
    }

    /**
     * Verifies Vector 2: Attribution Inversion Elimination.
     * Undefined type :OrderSide in undef_events.stvn_di must attribute to undef_events.stvn_di,
     * not inverted to undef_primitives.stvn_d which declared :OrderSide.
     */
    public void testUndefinedTypeAttributesToConsumerNotDeclarationSite() {
        var primitivesContent = """
            {
              :meta {
                #kind #DEFS
              }
              :defs {
                :package :com/nyse/primitives {
                  :OrderSide :Enum [ #BUY #SELL #CROSS ]
                }
              }
            }
            """;
        myFixture.addFileToProject("undef_primitives.stvn_d", primitivesContent);

        var eventsContent = """
            {
              :meta {
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "undef_primitives.stvn_d" ]
                :package :com/nyse/events {
                  :AgencySide :OrderSide
                }
              }
            }
            """;
        myFixture.addFileToProject("undef_events.stvn_di", eventsContent);

        var rootContent = """
            {
              :meta {
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "undef_events.stvn_di" ]
              }
            }
            """;
        myFixture.configureByText("undef_order_batch.stvn_i", rootContent);
        var highlights = myFixture.doHighlighting();

        int includeOffset = rootContent.indexOf(":include");
        com.intellij.codeInsight.daemon.impl.HighlightInfo pinnedHighlight = null;
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() <= includeOffset && h.getEndOffset() >= includeOffset) {
                pinnedHighlight = h;
                break;
            }
        }
        assertNotNull("Root include statement must receive error highlight", pinnedHighlight);
        var tooltip = pinnedHighlight.getToolTip();
        assertNotNull("Highlight must provide HTML tooltip", tooltip);

        // Must NOT attribute :OrderSide to undef_primitives.stvn_d
        assertFalse("Undefined type :OrderSide must not be attributed to undef_primitives.stvn_d: " + tooltip,
            tooltip.contains("[undef_events.stvn_di -> undef_primitives.stvn_d] Undefined type: :OrderSide"));
        assertTrue("Tooltip must contain Undefined type: :OrderSide: " + tooltip,
            tooltip.contains("Undefined type: :OrderSide"));
        assertTrue("Tooltip must attribute error to consumer undef_events.stvn_di: " + tooltip,
            tooltip.contains("undef_events.stvn_di"));
    }

    /**
     * Verifies that unrecognized constraint facets in definitions do not emit spurious
     * ERR_EMPTY_METADATA_BLOCK inspection errors, emitting strictly the single compiler diagnostic
     * ERR_UNRECOGNIZED_METADATA_FACET.
     */
    public void testUnrecognizedFacetEliminatesSpuriousEmptyBlockDiagnostic() {
        var content = """
            {
              :meta {
                #name "primitives"
                #domain "nyse"
                #kind #DEFS
              }
              :defs {
                :Int64 {#sizex 64} :Int
              }
            }
            """;
        myFixture.enableInspections(new StvnMetadataFacetInspection());
        myFixture.configureByText("primitives.stvn_d", content);
        var highlights = myFixture.doHighlighting();
        var errorDescriptions = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .map(h -> h.getDescription() != null ? h.getDescription() : "")
            .toList();

        assertEquals("Expected strictly single compiler diagnostic for unrecognized facet: " + errorDescriptions,
            1, errorDescriptions.size());
        assertTrue("Expected compiler diagnostic ERR_UNRECOGNIZED_METADATA_FACET: " + errorDescriptions.get(0),
            errorDescriptions.get(0).contains("Unrecognized constraint facet '#sizex'")
            && errorDescriptions.get(0).contains("[stvn-core:ERR_UNRECOGNIZED_METADATA_FACET]"));
        assertFalse("Spurious ERR_EMPTY_METADATA_BLOCK must be eliminated: " + errorDescriptions,
            errorDescriptions.stream().anyMatch(d -> d.contains("ERR_EMPTY_METADATA_BLOCK")));
    }

    /**
     * Verifies that genuinely empty constraint blocks emit ERR_EMPTY_METADATA_BLOCK with clarified terminology.
     */
    public void testGenuinelyEmptyConstraintBlockReportsDiagnostic() {
        var content = """
            {
              :meta {
                #name "primitives"
                #domain "nyse"
                #kind #DEFS
              }
              :defs {
                :Int64 {} :Int
              }
            }
            """;
        myFixture.enableInspections(new StvnMetadataFacetInspection());
        myFixture.configureByText("primitives.stvn_d", content);
        var highlights = myFixture.doHighlighting();
        var errorDescriptions = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .map(h -> h.getDescription() != null ? h.getDescription() : "")
            .toList();

        assertTrue("Expected Empty type constraint block diagnostic: " + errorDescriptions,
            errorDescriptions.stream().anyMatch(d -> d.contains("Empty type constraint block is invalid; remove '{}' or specify valid constraint facets [stvn-plugin:ERR_EMPTY_METADATA_BLOCK]")));
    }

    /**
     * Vector 1: Verifies that omitting a root :meta block in a definitions schema (.stvn_d)
     * produces exactly ONE error diagnostic on line 1 '{' and exactly ONE quick-fix,
     * suppressing the external annotator duplicate when the inspection is active.
     */
    public void testMissingMetaProducesSingleDeduplicatedDiagnosticOnLine1() {
        myFixture.enableInspections(new StvnMissingMetaInspection());
        var content = """
            {
              :defs {
                :package :com/example {
                  :MyId :Int
                }
              }
            }
            """;
        myFixture.configureByText("missing_meta_root.stvn_d", content);
        var highlights = myFixture.doHighlighting();

        var lineOneErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() == 0 || "{".equals(h.getText()))
            .toList();

        assertEquals("Expected exactly one error on line 1 '{'", 1, lineOneErrors.size());
        var desc = lineOneErrors.get(0).getDescription();
        assertTrue("Diagnostic must originate from inspection",
            desc.contains("[stvn-plugin:ERR_DOCUMENT_KIND_MISMATCH]"));

        var actions = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertEquals("Expected exactly one quick-fix intention", 1, actions.size());
    }

    /**
     * Vector 2: Verifies that when an included file (.stvn_d) misses :meta,
     * the error is pinned strictly to the :include statement in the active file (.stvn_di)
     * and NEVER leaks onto line 1 '{' of the active file buffer.
     */
    public void testChildMissingMetaDoesNotLeakToParentLineOne() {
        myFixture.enableInspections(new StvnMissingMetaInspection());
        var primitivesContent = """
            {
              :defs {
                :package :com/example {
                  :OrderId :Int
                }
              }
            }
            """;
        myFixture.addFileToProject("child_missing_meta.stvn_d", primitivesContent);

        var eventsContent = """
            {
              :meta {
                #name "events"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "child_missing_meta.stvn_d" ]
              }
            }
            """;
        myFixture.configureByText("parent_events_v2.stvn_di", eventsContent);
        var highlights = myFixture.doHighlighting();

        // 1. Verify line 1 has zero errors because events.stvn_di has valid :meta
        var lineOneErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() == 0 || "{".equals(h.getText()))
            .toList();
        assertTrue("Line 1 '{' must receive zero errors", lineOneErrors.isEmpty());

        // 2. Verify :include statement receives the pinned error for child_missing_meta.stvn_d
        int includeOffset = eventsContent.indexOf(":include");
        var includeErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() <= includeOffset && h.getEndOffset() >= includeOffset)
            .toList();
        assertEquals("Expected error pinned to :include statement", 1, includeErrors.size());
        var incDesc = includeErrors.get(0).getDescription();
        assertTrue("Error must cite child_missing_meta.stvn_d", incDesc.contains("child_missing_meta.stvn_d"));
        assertTrue("Error must cite missing :meta", incDesc.contains("Mandatory :meta block missing"));
    }

    /**
     * Vector 3: Verifies that when an included module contains multiple errors (missing :meta
     * and invalid facet), both are collected and rendered cleanly under the direct include tooltip.
     */
    public void testChildMultipleErrorsRenderCleanGroupedIncludeTooltip() {
        myFixture.enableInspections(new StvnMissingMetaInspection());
        var primitivesContent = """
            {
              :meta {
                #name "primitives"
                #kind #BODY
                #sizex 64
              }
              :defs {
                :package :com/example {
                  :OrderId :Int
                }
              }
            }
            """;
        myFixture.addFileToProject("child_multi_err.stvn_d", primitivesContent);

        var eventsContent = """
            {
              :meta {
                #name "events"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "child_multi_err.stvn_d" ]
              }
            }
            """;
        myFixture.configureByText("parent_events_v3.stvn_di", eventsContent);
        var highlights = myFixture.doHighlighting();

        int includeOffset = eventsContent.indexOf(":include");
        var pinnedHighlight = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() <= includeOffset && h.getEndOffset() >= includeOffset)
            .findFirst()
            .orElse(null);

        assertNotNull("Include statement must receive error highlight", pinnedHighlight);
        var tooltip = pinnedHighlight.getToolTip();
        assertNotNull("Highlight must provide rich HTML tooltip", tooltip);
        assertTrue("Tooltip must have direct file header",
            tooltip.contains("Included file 'child_multi_err.stvn_d' contains compilation errors:"));
        assertTrue("Tooltip must include invalid facet error",
            tooltip.contains("sizex"));
        assertTrue("Tooltip must include document kind mismatch error",
            tooltip.contains("Document kind") && tooltip.contains("does not match file extension"));
    }

    /**
     * Asserts that an unfenced block string receives exactly 1 syntax error annotation pinned
     * strictly to the opening delimiter span, leaving lines 10-11 clean and attaching
     * the ConvertToFencedStringIntentionAction quick-fix.
     */
    public void testUnfencedBlockStringSingleHighlightAndCoordinatePinning() {
        String code = "{\n  :defs {}\n  :type :String\n  :body \"\"\"<caret>\n    Line 1\n    Line 2\n  \"\"\"\n}\n";
        myFixture.configureByText("uhoh_test.stvn", code);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(com.intellij.lang.annotation.HighlightSeverity.ERROR))
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Rule STR-04 violation"))
            .toList();
        assertEquals("Must report exactly 1 syntax error squiggle across entire document", 1, errors.size());
        var err = errors.get(0);
        int tripleOffset = code.indexOf("\"\"\"");
        assertEquals("Highlight range must pin strictly to opening triple quotes (Rule STR-04 § 7.3)",
            new com.intellij.openapi.util.TextRange(tripleOffset, tripleOffset + 3),
            new com.intellij.openapi.util.TextRange(err.getStartOffset(), err.getEndOffset()));

        var fix = myFixture.findSingleIntention("Convert to fenced string '\"\"\"[FENCE]...[FENCE]\"\"\"'");
        assertNotNull("Expected Convert to fenced string quick-fix", fix);
        myFixture.launchAction(fix);
        String expected = "{\n  :defs {}\n  :type :String\n  :body \"\"\"[FENCE]\n    Line 1\n    Line 2\n  [FENCE]\"\"\"\n}\n";
        myFixture.checkResult(expected);
    }

    /**
     * Asserts that example_a.stvn produces exactly 3 error highlights on :defs, :type, and :body
     * with zero cascade on closing brace, zero duplicate tooltips, and correct intention availability.
     */
    public void testExampleABootstrapCoordinatePinningAndIntentions() {
        String code = "{\n  :defs\n  :type\n  :body\n}\n";
        myFixture.configureByText("example_a.stvn", code);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(com.intellij.lang.annotation.HighlightSeverity.ERROR))
            .toList();

        assertEquals("Expected exactly 3 syntax errors on incomplete clauses", 3, errors.size());

        int defsStart = code.indexOf(":defs");
        assertEquals(new com.intellij.openapi.util.TextRange(defsStart, defsStart + 5), new com.intellij.openapi.util.TextRange(errors.get(0).getStartOffset(), errors.get(0).getEndOffset()));
        assertTrue(errors.get(0).getDescription().contains("clause ':defs' requires definition block"));

        int typeStart = code.indexOf(":type");
        assertEquals(new com.intellij.openapi.util.TextRange(typeStart, typeStart + 5), new com.intellij.openapi.util.TextRange(errors.get(1).getStartOffset(), errors.get(1).getEndOffset()));
        assertTrue(errors.get(1).getDescription().contains("clause ':type' requires a schema type"));

        int bodyStart = code.indexOf(":body");
        assertEquals(new com.intellij.openapi.util.TextRange(bodyStart, bodyStart + 5), new com.intellij.openapi.util.TextRange(errors.get(2).getStartOffset(), errors.get(2).getEndOffset()));
        assertTrue(errors.get(2).getDescription().contains("clause ':body' requires a value payload"));

        myFixture.getEditor().getCaretModel().moveToOffset(defsStart);
        assertNotNull(myFixture.findSingleIntention("Insert definition block '{ :Type :String }'"));

        myFixture.getEditor().getCaretModel().moveToOffset(typeStart);
        assertNotNull(myFixture.findSingleIntention("Insert schema type ':String'"));
    }

    /**
     * Asserts that example_b.stvn offers 'Remove redundant :defs {} block'.
     */
    public void testExampleBEmptyDefsInPayloadRemediation() {
        String code = "{\n  :defs {}\n  :type :String\n  :body \"hello\"\n}\n";
        myFixture.configureByText("example_b.stvn", code);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(com.intellij.lang.annotation.HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected exactly 1 error on empty :defs {}", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Empty definition block ':defs {}' is redundant"));

        myFixture.getEditor().getCaretModel().moveToOffset(code.indexOf("{}"));
        var fix = myFixture.findSingleIntention("Remove redundant ':defs {}' block");
        assertNotNull(fix);
        myFixture.launchAction(fix);
        myFixture.checkResult("{\n  :type :String\n  :body \"hello\"\n}\n");
    }

    /**
     * Asserts that example_d.stvn infers 0 for :Int body payload.
     */
    public void testExampleDInferredBodyValue() {
        String code = "{\n  :type :Int\n  :body\n}\n";
        myFixture.configureByText("example_d.stvn", code);
        myFixture.doHighlighting();

        myFixture.getEditor().getCaretModel().moveToOffset(code.indexOf(":body"));
        var fix = myFixture.findSingleIntention("Insert default body value");
        assertNotNull(fix);
        myFixture.launchAction(fix);
        myFixture.checkResult("{\n  :type :Int\n  :body 0\n}\n");
    }

    /**
     * Asserts that example_f.stvn_d renders exactly 2 canvas squiggles (root brace '{' and defs braces '{}')
     * with zero ghost errors, zero tooltips stacked, and appropriate intentions attached.
     */
    public void testExampleFZeroWidthGhostErrorRemediation() {
        myFixture.enableInspections(new StvnMissingMetaInspection());
        String code = "{\n  :defs {}\n}\n";
        myFixture.configureByText("example_f.stvn_d", code);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(com.intellij.lang.annotation.HighlightSeverity.ERROR))
            .toList();

        assertEquals("Expected exactly 2 canvas error squiggles on example_f.stvn_d", 2, errors.size());

        // First error on root opening brace '{'
        var metaError = errors.get(0);
        assertEquals("Missing meta error must pin to line 1 '{' [0, 1)", new com.intellij.openapi.util.TextRange(0, 1), new com.intellij.openapi.util.TextRange(metaError.getStartOffset(), metaError.getEndOffset()));
        assertTrue(metaError.getDescription().contains("Definitions schema requires root :meta block"));

        // Second error on empty defs braces '{}'
        var defsError = errors.get(1);
        int bracesStart = code.indexOf("{}");
        assertEquals("Empty defs error must pin to '{}' [10, 12)", new com.intellij.openapi.util.TextRange(bracesStart, bracesStart + 2), new com.intellij.openapi.util.TextRange(defsError.getStartOffset(), defsError.getEndOffset()));
        assertTrue(defsError.getDescription().contains("requires at least one type definition inside ':defs { ... }'"));

        myFixture.getEditor().getCaretModel().moveToOffset(0);
        var metaFix = myFixture.findSingleIntention("Insert missing root :meta block");
        assertNotNull(metaFix);

        myFixture.getEditor().getCaretModel().moveToOffset(bracesStart);
        var defsFix = myFixture.findSingleIntention("Insert type definition ':Type :String'");
        assertNotNull(defsFix);

        // Execute type definition fix
        myFixture.launchAction(defsFix);
        String expected = "{\n  :defs {\n    :Type :String\n  }\n}\n";
        myFixture.checkResult(expected);
    }

    /**
     * Asserts that { :meta :defs :type :body } produces exactly 4 distinct error squiggles
     * pinned strictly to the four keywords, with zero secondary cascade errors on '}' or EOF (§ 7.3.1).
     */
    public void testAcidTestFourClausesProducesExactlyFourErrorsPinned() {
        String code = "{\n  :meta\n  :defs\n  :type\n  :body\n}\n";
        myFixture.configureByText("acid_test.stvn", code);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Must produce exactly 4 distinct clause errors", 4, errors.size());

        // Verify pinning to :meta
        int metaIdx = code.indexOf(":meta");
        assertEquals(new com.intellij.openapi.util.TextRange(metaIdx, metaIdx + 5),
            new com.intellij.openapi.util.TextRange(errors.get(0).getStartOffset(), errors.get(0).getEndOffset()));
        assertTrue(errors.get(0).getDescription().contains("clause ':meta' requires a metadata block"));

        // Verify pinning to :defs
        int defsIdx = code.indexOf(":defs");
        assertEquals(new com.intellij.openapi.util.TextRange(defsIdx, defsIdx + 5),
            new com.intellij.openapi.util.TextRange(errors.get(1).getStartOffset(), errors.get(1).getEndOffset()));
        assertTrue(errors.get(1).getDescription().contains("clause ':defs' requires definition block"));

        // Verify pinning to :type
        int typeIdx = code.indexOf(":type");
        assertEquals(new com.intellij.openapi.util.TextRange(typeIdx, typeIdx + 5),
            new com.intellij.openapi.util.TextRange(errors.get(2).getStartOffset(), errors.get(2).getEndOffset()));
        assertTrue(errors.get(2).getDescription().contains("clause ':type' requires a schema type"));

        // Verify pinning to :body
        int bodyIdx = code.indexOf(":body");
        assertEquals(new com.intellij.openapi.util.TextRange(bodyIdx, bodyIdx + 5),
            new com.intellij.openapi.util.TextRange(errors.get(3).getStartOffset(), errors.get(3).getEndOffset()));
        assertTrue(errors.get(3).getDescription().contains("clause ':body' requires a value payload"));
    }

    /**
     * Asserts that example_a.stvn does NOT report ERR_RESERVED_KEYWORD_ON_LHS or 'cannot be aliased' on :type.
     */
    public void testExampleANoSpuriousLhsReservedErrorOnType() {
        myFixture.enableInspections(new StvnLhsReservedTypeInspection());
        String code = "{\n  :defs\n  :type\n  :body\n}\n";
        myFixture.configureByText("example_a.stvn", code);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        for (var err : errors) {
            assertFalse("Must never report ERR_RESERVED_KEYWORD_ON_LHS on :type",
                err.getDescription().contains("ERR_RESERVED_KEYWORD_ON_LHS") ||
                err.getDescription().contains("cannot be aliased"));
        }
    }

    /**
     * Asserts that { :meta {} :defs {} :type :body } highlights both sets of empty '{}'
     * alongside syntax errors on :type and :body.
     */
    public void testEmptyBlocksWithSyntaxErrorsHighlightsBothEmptyBlocksAndSyntaxErrors() {
        String code = "{\n  :meta {}\n  :defs {}\n  :type\n  :body\n}\n";
        myFixture.configureByText("empty_blocks.stvn", code);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Must report exactly 4 errors simultaneously", 4, errors.size());

        // Verify squiggles pin strictly to '{}'
        int metaBraces = code.indexOf("{}");
        int defsBraces = code.indexOf("{}", metaBraces + 2);
        assertEquals(new com.intellij.openapi.util.TextRange(metaBraces, metaBraces + 2),
            new com.intellij.openapi.util.TextRange(errors.get(0).getStartOffset(), errors.get(0).getEndOffset()));
        assertEquals(new com.intellij.openapi.util.TextRange(defsBraces, defsBraces + 2),
            new com.intellij.openapi.util.TextRange(errors.get(1).getStartOffset(), errors.get(1).getEndOffset()));
    }

    /**
     * Asserts that StvnBootstrapDocumentIntentionAction correctly bootstraps 0-byte documents
     * across all 4 canonical extensions and transforms them into 100% clean non-erred states.
     */
    public void testBootstrapDocumentIntentionOnZeroByteFiles() {
        String[] extensions = { "order.stvn", "order.stvn_i", "order.stvn_d", "order.stvn_di" };
        for (var ext : extensions) {
            myFixture.configureByText(ext, "");
            var intention = myFixture.findSingleIntention("Bootstrap STVN Document");
            assertNotNull("Intention must be available on 0-byte " + ext, intention);
            myFixture.launchAction(intention);

            // Re-highlight: Result must have ZERO errors
            var highlights = myFixture.doHighlighting();
            var errors = highlights.stream()
                .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
                .toList();
            assertEquals("Bootstrapped document for " + ext + " must compile cleanly with 0 errors", 0, errors.size());
        }
    }

    /**
     * Asserts that invoking the in-place :meta quick-fix on an incomplete clause
     * generates a valid :meta block without duplicating the :meta keyword.
     */
    public void testInPlaceMetaScaffoldingDoesNotDuplicateKeyword() {
        var initial = """
            {
              :meta
              :defs
            }
            """;
        myFixture.configureByText("example_i.stvn_di", initial);
        myFixture.doHighlighting();

        int metaOffset = initial.indexOf(":meta");
        myFixture.getEditor().getCaretModel().moveToOffset(metaOffset + 2);

        var actions = myFixture.filterAvailableIntentions("Insert metadata block");
        assertFalse("Expected in-place metadata block intention action to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :meta {
                #name "example_i"
                #kind #DEFS_INCLUDE
              }
              :defs
            }
            """);
    }

    /**
     * Asserts that bootstrapping a compound file 'order.finance.stvn_d' from 0 bytes
     * inserts #name "order" and #domain "finance", compiling cleanly with 0 errors.
     */
    public void testBootstrapDocumentIntentionOnCompoundStem() {
        myFixture.configureByText("order.finance.stvn_d", "");
        var intention = myFixture.findSingleIntention("Bootstrap STVN Document");
        assertNotNull("Intention must be available on 0-byte compound file", intention);
        myFixture.launchAction(intention);

        var text = myFixture.getEditor().getDocument().getText();
        assertTrue("Bootstrapped document must contain #name \"order\":\n" + text, text.contains("#name \"order\""));
        assertTrue("Bootstrapped document must contain #domain \"finance\":\n" + text, text.contains("#domain \"finance\""));

        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Bootstrapped compound document must compile cleanly with 0 errors: " + errors, 0, errors.size());
    }

    /**
     * Asserts that an empty (0-byte) project file named 'prelude.stvn_core.stvn_d'
     * emits ERR_RESERVED_DOMAIN_PROHIBITED (E038) in Layer 1 identity ingress
     * without requiring any file content.
     */
    public void testEmptyReservedDomainProjectFileEmitsLayer1Error() {
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", "");
        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(file);
        assertNotNull("CollectedInfo must not be null", collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull("AnnotationResult must not be null", result);
        var e038 = result.diagnostics().stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR
                && d.errorCode().isPresent()
                && DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().get()))
            .findFirst();
        assertTrue("Empty prelude.stvn_core.stvn_d must emit ERR_RESERVED_DOMAIN_PROHIBITED in Layer 1", e038.isPresent());
    }

    /**
     * Asserts that 'Bootstrap STVN Document' intention action is suppressed
     * on dead-on-arrival compilation units violating Layer 1 identity invariants.
     */
    public void testBootstrapIntentionSuppressedOnLayer1InvalidFiles() {
        myFixture.configureByText("prelude.stvn_core.stvn_d", "");
        var reservedIntention = myFixture.filterAvailableIntentions("Bootstrap STVN Document");
        assertTrue("Bootstrap intention must be unavailable on prelude.stvn_core.stvn_d", reservedIntention.isEmpty());

        myFixture.configureByText("test.stvn_incl", "");
        var legacyIntention = myFixture.filterAvailableIntentions("Bootstrap STVN Document");
        assertTrue("Bootstrap intention must be unavailable on test.stvn_incl", legacyIntention.isEmpty());
    }
}
