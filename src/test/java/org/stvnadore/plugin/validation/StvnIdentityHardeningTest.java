package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.validation.DiagnosticBag;

/**
 * Platform verification test suite for Document Identity Hardening and Specification Alignment.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Stem Cardinality Invariant (1 &lt;= |stems| &lt;= 2, E032)</li>
 *   <li>Reserved System Domain Prefix Alignment (prefix.startsWith("stvn_"), E038)</li>
 *   <li>Empty Root Metadata Block Disambiguation and Pinning (ERR_EMPTY_META_BLOCK, E039)</li>
 *   <li>In-place canonical :meta block replacement without duplicate header insertion</li>
 *   <li>Modular Body Payload (.stvn_i) mandatory :meta block governance (#BODY_INCLUDE)</li>
 * </ul>
 */
@NullMarked
public final class StvnIdentityHardeningTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnMissingMetaInspection());
    }

    /**
     * Verifies that multi-stem filenames containing 3 or more stem segments
     * (e.g. this.is.a.file.domain.stvn) emit ERR_INVALID_FILENAME_STEM (E032).
     */
    public void testMultiStemFilenameEmitsInvalidFilenameStem() {
        var content = """
            {
              :type :Int
              :body 10
            }
            """;
        var file = myFixture.configureByText("this.is.a.file.domain.stvn", content);
        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(file);
        assertNotNull("CollectedInfo must not be null", collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull("AnnotationResult must not be null", result);

        var stemError = result.diagnostics().stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR
                && d.errorCode().isPresent()
                && (DiagnosticBag.ERR_INVALID_FILENAME_STEM.equals(d.errorCode().get())
                    || StvnDiagnosticCodes.ERR_INVALID_FILENAME_STEM.equals(d.errorCode().get())))
            .findFirst();

        assertTrue("Multi-stem file (3+ stems) must emit ERR_INVALID_FILENAME_STEM (E032)", stemError.isPresent());
        assertTrue("Error message must describe stem cardinality invariant",
            stemError.get().message().contains("filename must contain exactly one or two dot-separated stem segments"));

        var highlights = myFixture.doHighlighting();
        var highlightError = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getDescription() != null
                && h.getDescription().contains("ERR_INVALID_FILENAME_STEM"))
            .findFirst();
        assertTrue("Editor highlighting must report ERR_INVALID_FILENAME_STEM", highlightError.isPresent());
    }

    /**
     * Verifies that user documents declaring custom domain with reserved prefix 'stvn_'
     * (e.g. test.stvn_custom.stvn) emit ERR_RESERVED_DOMAIN_PROHIBITED (E038).
     */
    public void testCustomReservedDomainPrefixEmitsReservedDomainProhibited() {
        var content = """
            {
              :meta {
                #name "test"
                #domain "stvn_custom"
                #kind #BODY
              }
              :type :Int
              :body 20
            }
            """;
        var file = myFixture.configureByText("test.stvn_custom.stvn", content);
        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(file);
        assertNotNull("CollectedInfo must not be null", collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull("AnnotationResult must not be null", result);

        var domainError = result.diagnostics().stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR
                && d.errorCode().isPresent()
                && (DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().get())
                    || StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED.equals(d.errorCode().get())))
            .findFirst();

        assertTrue("Custom 'stvn_*' domain must emit ERR_RESERVED_DOMAIN_PROHIBITED (E038)", domainError.isPresent());

        var highlights = myFixture.doHighlighting();
        var highlightError = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getDescription() != null
                && h.getDescription().contains("ERR_RESERVED_DOMAIN_PROHIBITED"))
            .findFirst();
        assertTrue("Editor highlighting must report ERR_RESERVED_DOMAIN_PROHIBITED", highlightError.isPresent());
    }

    /**
     * Verifies that empty root metadata block ':meta {}' emits ERR_EMPTY_META_BLOCK (E039)
     * pinned strictly to the '{}' token span.
     */
    public void testEmptyMetaBlockEmitsE039AndPinsToBraces() {
        var content = """
            {
              :meta {<caret>}
              :defs {
                :Order :Int
              }
            }
            """;
        myFixture.configureByText("empty_meta.stvn_d", content);
        var highlights = myFixture.doHighlighting();

        var e039 = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getDescription() != null
                && h.getDescription().contains("ERR_EMPTY_META_BLOCK"))
            .findFirst();

        assertTrue("Must emit ERR_EMPTY_META_BLOCK (E039)", e039.isPresent());
        assertEquals("Coordinates must pin strictly to '{}' braces", "{}", e039.get().getText());
    }

    /**
     * Verifies that triggering 'Insert missing root :meta block' on a document with ':meta {}'
     * replaces the empty block in-place without generating a duplicate ':meta' header.
     */
    public void testEmptyMetaQuickFixReplacesBlockInPlaceWithoutDuplication() {
        var content = """
            {
              :meta {<caret>}
              :defs {
                :Order :Int
              }
            }
            """;
        myFixture.configureByText("order.stvn_d", content);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertFalse("Expected 'Insert missing root :meta block' intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Must contain #name \"order\"", text.contains("#name \"order\""));
        assertTrue("Must contain #kind #DEFS", text.contains("#kind #DEFS"));
        assertFalse("Must NOT contain leftover empty :meta {}", text.contains(":meta {}"));

        // Verify there is strictly ONE ':meta' keyword in the resulting document
        int firstMeta = text.indexOf(":meta");
        assertTrue("Must contain :meta block", firstMeta >= 0);
        int secondMeta = text.indexOf(":meta", firstMeta + 5);
        assertEquals("Must NOT duplicate :meta block", -1, secondMeta);
    }

    /**
     * Verifies that omitting a root :meta block on a modular body payload (.stvn_i)
     * reports an error on line 1 '{' and quick-fix inserts '#kind #BODY_INCLUDE'.
     */
    public void testMissingMetaOnModularBodyPayloadEmitsErrorAndOffersQuickFix() {
        var content = """
            {
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("payload.stvn_i", content);
        var highlights = myFixture.doHighlighting();

        var missingMetaErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getDescription() != null
                && h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .toList();

        assertFalse("Expected missing :meta error on .stvn_i", missingMetaErrors.isEmpty());
        assertEquals("Error must pin to root opening brace '{'", "{", missingMetaErrors.get(0).getText());

        var actions = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertFalse("Expected 'Insert missing root :meta block' intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Must generate #name \"payload\"", text.contains("#name \"payload\""));
        assertTrue("Must generate #kind #BODY_INCLUDE", text.contains("#kind #BODY_INCLUDE"));
    }

    /**
     * Verifies that canonical hermetic payload documents (.stvn) without :meta
     * produce zero missing metadata errors under SPEC § 1.1 and § 1.2.
     */
    public void testCanonicalHermeticPayloadWithoutMetaProducesZeroErrors() {
        var content = """
            {
              :type :Int
              :body 100
            }
            """;
        myFixture.configureByText("canonical_payload.stvn", content);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
                && h.getDescription() != null
                && (h.getDescription().contains("ERR_DOCUMENT_KIND_MISMATCH")
                    || h.getDescription().contains("requires root :meta block")))
            .toList();

        assertTrue("Canonical .stvn payload document must not require :meta", errors.isEmpty());
    }

    /**
     * Verifies that bare dotfiles (.stvn_i) lacking a stem segment emit ERR_INVALID_FILENAME_STEM.
     */
    public void testBareDotfileEmitsInvalidFilenameStem() {
        var file = myFixture.configureByText(".stvn_i", "{\n  :body 1\n}\n");
        var annotator = new StvnExternalAnnotator();
        var collected = annotator.collectInformation(file);
        assertNotNull(collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull(result);

        var stemError = result.diagnostics().stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR
                && d.errorCode().isPresent()
                && (DiagnosticBag.ERR_INVALID_FILENAME_STEM.equals(d.errorCode().get())
                    || StvnDiagnosticCodes.ERR_INVALID_FILENAME_STEM.equals(d.errorCode().get())))
            .findFirst();

        assertTrue("Bare dotfile must emit ERR_INVALID_FILENAME_STEM", stemError.isPresent());
    }
}
