package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.StringEscapesTokenTypes;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnSyntaxHighlighter;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.psi.StringLiteral;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * End-to-end platform test suite verifying {@code TB-01} 1:1 {@code LayeredLexer} and
 * {@link StvnExternalAnnotator} invalid-escape span parity, {@code TB-02} fail-closed hover
 * compliance and call-site guards on corrupt strings, and {@code TB-03} 26-local-inspection
 * cascade suppression ({@code STVN_LANGUAGE_SPEC.md} section 5.3.1).
 */
@NullMarked
public final class StvnSimpleStringEscapeHighlightAndHoverParityTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider provider = new StvnDocumentationProvider();

    private String getDocAtOffset(int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Documentation element at offset " + offset + " must not be null", docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Generated documentation at offset " + offset + " must not be null", doc);
        return doc;
    }

    public void testTb01LayeredLexerInvalidEscapeSpansMatchExternalAnnotatorE002SpansOneToOne() {
        String source = """
            {
              :meta {
                #name "tb01_span_parity"
                #kind #BODY
              }
              :defs {
                :SlotStr { #maxSize 128 } :String
              }
              :type :Tuple(:SlotStr :SlotStr :SlotStr :SlotStr :SlotStr)
              :body (
                "CORRUPT_ESCAPE_\\z_AND_HEX_\\u12G4"
                "\\uG123"
                "END_\\u12"
                "\\u12\\z"
                "QUOTE_\\u12\\"_NEXT_\\q"
              )
            }
            """;
        myFixture.configureByText("tb01_span_parity.stvn", source);

        var lexer = new StvnSyntaxHighlighter().getHighlightingLexer();
        lexer.start(source);
        List<TextRange> lexerInvalidEscapeRanges = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            var type = lexer.getTokenType();
            if (type == StringEscapesTokenTypes.INVALID_CHARACTER_ESCAPE_TOKEN
                    || type == StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN) {
                lexerInvalidEscapeRanges.add(new TextRange(lexer.getTokenStart(), lexer.getTokenEnd()));
            }
            lexer.advance();
        }

        var errorHighlights = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .sorted(Comparator.comparingInt(h -> h.getStartOffset()))
            .toList();

        List<TextRange> annotatorErrorRanges = errorHighlights.stream()
            .map(h -> new TextRange(h.getStartOffset(), h.getEndOffset()))
            .toList();

        assertEquals("Expected 8 disjoint invalid escape spans across all 5 strings", 8, lexerInvalidEscapeRanges.size());
        assertEquals(
            "LayeredLexer INVALID_STRING_ESCAPE token ranges must match StvnExternalAnnotator E002 error ranges 1:1",
            lexerInvalidEscapeRanges,
            annotatorErrorRanges
        );

        for (var h : errorHighlights) {
            assertNotNull(h.getDescription());
            assertTrue(
                "Every error highlight must carry [stvn-core:E002-STVN_SYNTAX_ERROR], got: " + h.getDescription(),
                h.getDescription().contains("[stvn-core:E002-STVN_SYNTAX_ERROR]")
            );
        }
    }

    public void testTb02CorruptStringHoverReportsViolatedAcrossBodyStringDateTimeDefsRhsAndBodyConstRef() {
        String source = """
            {
              :meta {
                #name "tb02_hover_compliance"
                #kind #BODY
              }
              :defs {
                :SlotStr { #maxSize 128 } :String
                :StrictCode { #minSize 2 #maxSize 8 #regex "^[A-Z]+$" } :String
                :AuditedTs { #audited #minIncl "2020-01-01T00:00:00Z[UTC]" } :DateTime
                :BoundedOffsetTs { #offset #minIncl "2026-01-01T00:00:00Z" } :DateTime
                :BadRegex { #regex "[unclosed_\\z_\\u12" } :String
                #BAD_STR_CONST :StrictCode "a_\\z_\\u00GG"
              }
              :type :Tuple(:SlotStr :AuditedTs :BoundedOffsetTs :StrictCode :BadRegex :BadRegex)
              :body (
                "CORRUPT_ESCAPE_\\z_AND_HEX_\\u12G4"
                "2025-03-15T08:00:00Z\\z\\u12G4"
                "2024-03-15T08:00:00\\u005A"
                #BAD_STR_CONST
                "any"
                "degraded_slot_corrupt_\\q"
              )
            }
            """;
        var psiFile = myFixture.configureByText("tb02_hover_compliance.stvn", source);

        // 1. Verify evaluateStringContentLength returns 0 (never raw inner length) on corrupt PSI StringLiteral
        int slot0Start = source.indexOf("\"CORRUPT_ESCAPE_\\z_AND_HEX_\\u12G4\"");
        var slot0Elem = psiFile.findElementAt(slot0Start + 1);
        assertNotNull(slot0Elem);
        assertTrue(slot0Elem.getParent() instanceof StringLiteral);
        assertEquals(0, StvnPsiLiteralEvaluator.evaluateStringContentLength((StringLiteral) slot0Elem.getParent(), false));

        // 2. Hover across prefix, \\z, and \\u12G4 offsets on Slot 0 must report Violated with BOTH E002 messages
        int prefixOff = slot0Start + 3;
        int zOff = source.indexOf("\\z", slot0Start) + 1;
        int hexOff = source.indexOf("\\u12G4", slot0Start) + 2;
        for (int off : new int[]{prefixOff, zOff, hexOff}) {
            String doc = getDocAtOffset(off);
            assertTrue("Hover at offset " + off + " must render Violated:\n" + doc, doc.contains(">Violated<"));
            assertFalse("Hover at offset " + off + " must NEVER render Satisfied:\n" + doc, doc.contains(">Satisfied<"));
            assertFalse("Hover must NEVER claim length 32 satisfies bounds:\n" + doc,
                doc.contains("satisfies capacity bounds"));
            assertTrue("Hover must include \\z E002 diagnostic:\n" + doc,
                doc.contains("Illegal escape sequence '\\z' in simple string literal"));
            assertTrue("Hover must include \\u12G4 E002 diagnostic:\n" + doc,
                doc.contains("Invalid hexadecimal digits in Unicode escape '\\u12G4'"));
        }

        // 3. Hover on corrupt :DateTime literal must report Violated with BOTH E002 escape errors
        int dtCorruptOff = source.indexOf("\"2025-03-15T08:00:00Z\\z\\u12G4\"") + 5;
        String dtCorruptDoc = getDocAtOffset(dtCorruptOff);
        assertTrue("Corrupt :DateTime hover must render Violated:\n" + dtCorruptDoc, dtCorruptDoc.contains(">Violated<"));
        assertTrue("Corrupt :DateTime hover must include \\z E002 error:\n" + dtCorruptDoc,
            dtCorruptDoc.contains("Illegal escape sequence '\\z' in simple string literal"));
        assertTrue("Corrupt :DateTime hover must include \\u12G4 E002 error:\n" + dtCorruptDoc,
            dtCorruptDoc.contains("Invalid hexadecimal digits in Unicode escape '\\u12G4'"));

        // 4. Hover on valid escaped out-of-bounds :DateTime ("2024-03-15T08:00:00\u005A" < 2026-01-01) must report Violated
        int dtEscapedOff = source.indexOf("\"2024-03-15T08:00:00\\u005A\"") + 5;
        String dtEscapedDoc = getDocAtOffset(dtEscapedOff);
        assertTrue("Out-of-bounds escaped :DateTime hover must render Violated:\n" + dtEscapedDoc,
            dtEscapedDoc.contains(">Violated<"));
        assertTrue("Out-of-bounds escaped :DateTime hover must unescape \\u005A before bound check:\n" + dtEscapedDoc,
            dtEscapedDoc.contains("below minimum inclusive bound"));

        // 5. :defs LHS #BAD_STR_CONST preserves non-injection isolation; :defs RHS reports Violated
        int defsLhsOff = source.indexOf("#BAD_STR_CONST :StrictCode") + 2;
        String defsLhsDoc = getDocAtOffset(defsLhsOff);
        assertFalse(":defs LHS constant hover must NEVER inject Compliance Status:\n" + defsLhsDoc,
            defsLhsDoc.contains("Compliance Status:"));

        int defsRhsOff = source.indexOf("\"a_\\z_\\u00GG\"") + 2;
        String defsRhsDoc = getDocAtOffset(defsRhsOff);
        assertTrue(":defs RHS corrupt constant value hover must render Violated:\n" + defsRhsDoc,
            defsRhsDoc.contains(">Violated<"));
        assertTrue(":defs RHS hover must include both E002 escape errors:\n" + defsRhsDoc,
            defsRhsDoc.contains("Illegal escape sequence '\\z'")
                && defsRhsDoc.contains("Invalid hexadecimal digits in Unicode escape '\\u00GG'"));

        // 6. :body #BAD_STR_CONST reference reports In Violation with upstream constant E002 diagnostics
        int bodyConstOff = source.lastIndexOf("#BAD_STR_CONST") + 2;
        String bodyConstDoc = getDocAtOffset(bodyConstOff);
        assertTrue(":body #BAD_STR_CONST hover must render In Violation:\n" + bodyConstDoc,
            bodyConstDoc.contains(">In Violation<"));
        assertFalse(":body #BAD_STR_CONST hover must NEVER render Satisfied:\n" + bodyConstDoc,
            bodyConstDoc.contains(">Satisfied<"));
        assertTrue(":body #BAD_STR_CONST hover must surface upstream E002 errors:\n" + bodyConstDoc,
            bodyConstDoc.contains("Illegal escape sequence '\\z'")
                && bodyConstDoc.contains("Invalid hexadecimal digits in Unicode escape '\\u00GG'"));

        // 7. Safeguard 2: lexically valid "any" in degraded :BadRegex slot -> Suspended (Degraded Schema),
        //    whereas lexically corrupt "degraded_slot_corrupt_\q" -> Violated without Exemplar Value leak
        int validInDegradedOff = source.indexOf("\"any\"") + 2;
        String validInDegradedDoc = getDocAtOffset(validInDegradedOff);
        assertTrue("Lexically valid literal in degraded slot must report Suspended (Degraded Schema):\n" + validInDegradedDoc,
            validInDegradedDoc.contains("Suspended (Degraded Schema)"));
        assertFalse(validInDegradedDoc.contains(">Violated<"));

        int corruptInDegradedOff = source.indexOf("\"degraded_slot_corrupt_\\q\"") + 3;
        String corruptInDegradedDoc = getDocAtOffset(corruptInDegradedOff);
        assertTrue("Lexically corrupt literal in degraded slot must report Violated:\n" + corruptInDegradedDoc,
            corruptInDegradedDoc.contains(">Violated<"));
        assertTrue(corruptInDegradedDoc.contains("Illegal escape sequence '\\q' in simple string literal"));
        assertFalse("Degraded schema must not leak Exemplar Value on corrupt literal:\n" + corruptInDegradedDoc,
            corruptInDegradedDoc.contains("Exemplar Value:"));
    }

    public void testTb02BodyConstHoverAndFencedTabSubSpanErrorWhenPass3Suspended() {
        // Case A: Pass 2 lowers :defs (recording E002 on #BAD_STR_CONST), while root-level E001 tab before :body suspends Pass 3
        String pass3SuspendedSource = "{\n  :meta {\n    #name \"suspended_pass3\"\n    #kind #BODY\n  }\n  :defs {\n    :StrictCode { #minSize 2 #maxSize 8 } :String\n    #BAD_STR_CONST :StrictCode \"a_\\z_\\u00GG\"\n  }\n  :type :Tuple(:StrictCode :String)\n\t:body (#BAD_STR_CONST \"\"\"[RAW]\t\nhello\n[RAW]\"\"\")\n}";
        myFixture.configureByText("suspended_pass3.stvn", pass3SuspendedSource);

        int bodyConstOff = pass3SuspendedSource.indexOf("(#BAD_STR_CONST") + 3;
        String bodyConstDoc = getDocAtOffset(bodyConstOff);
        assertTrue("Pass-3-suspended :body #BAD_STR_CONST hover must report In Violation:\n" + bodyConstDoc,
            bodyConstDoc.contains(">In Violation<"));
        assertTrue("Pass-3-suspended :body #BAD_STR_CONST hover must surface upstream E002 errors:\n" + bodyConstDoc,
            bodyConstDoc.contains("Illegal escape sequence '\\z'")
                && bodyConstDoc.contains("Invalid hexadecimal digits in Unicode escape '\\u00GG'"));

        int fencedOff = pass3SuspendedSource.indexOf("\"\"\"[RAW]") + 10;
        String fencedDoc = getDocAtOffset(fencedOff);
        assertTrue("Fenced string with E001 tab on opening fence line must report Violated:\n" + fencedDoc,
            fencedDoc.contains(">Violated<"));

        // Case B: Pass 2 itself suspended by #kind #DEFS mismatch — PSI decoder fallback still prevents false Satisfied
        String pass2SuspendedSource = "{ :meta { #name \"suspended_pass2\" #kind #DEFS } :defs { :StrictCode { #minSize 2 #maxSize 8 } :String #BAD_STR_CONST :StrictCode \"a_\\z\" } :type :StrictCode :body #BAD_STR_CONST }";
        myFixture.configureByText("suspended_pass2.stvn", pass2SuspendedSource);

        int pass2BodyConstOff = pass2SuspendedSource.lastIndexOf("#BAD_STR_CONST") + 3;
        String pass2BodyConstDoc = getDocAtOffset(pass2BodyConstOff);
        assertTrue("Pass-2-suspended :body #BAD_STR_CONST hover must still report In Violation:\n" + pass2BodyConstDoc,
            pass2BodyConstDoc.contains(">In Violation<"));
        assertFalse(pass2BodyConstDoc.contains(">Satisfied<"));
        assertTrue(pass2BodyConstDoc.contains("Malformed string literal in constant definition"));
    }

    public void testTb02AndTb03CallSiteGuardsSuppressOverflowFencedConversionQuickFixesAndMetaCascades() {
        var capacityInspection = new StvnStringCapacityInspection();
        capacityInspection.inspectPayload = true;
        var stemInspection = new StvnStemMismatchInspection();
        var metaFacetInspection = new StvnMetadataFacetInspection();

        myFixture.enableInspections(capacityInspection, stemInspection, metaFacetInspection);
        try {
            String source = """
                {
                  :meta {
                    #name "bad_\\z_stem_\\u12G4"
                    #domain "stvn_bad_\\q"
                    #kind #BODY
                  }
                  :defs {
                    :ShortCode { #minSize 1 #maxSize 4 #regex "^[A-Z]+$" } :String
                  }
                  :type :ShortCode
                  :body "overflow_and_corrupt_\\z_\\u12G4"
                }
                """;
            myFixture.configureByText("good_stem.stvn", source);

            var highlights = myFixture.doHighlighting();
            for (var h : highlights) {
                String desc = h.getDescription();
                if (desc == null) continue;
                assertFalse("Corrupt string must NEVER trigger PW004 capacity overflow warning: " + desc,
                    desc.contains("WARN_STRING_LITERAL_CAPACITY_OVERFLOW"));
                assertFalse("Corrupt :meta #name must NEVER trigger W001 stem mismatch warning: " + desc,
                    desc.contains("WARN_DOCUMENT_NAME_MISMATCH"));
                assertFalse("Corrupt :meta #domain must NEVER trigger W002 domain warning: " + desc,
                    desc.contains("WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME"));
                assertFalse("Corrupt :meta #domain must NEVER trigger E038 reserved domain error: " + desc,
                    desc.contains("ERR_RESERVED_DOMAIN_PROHIBITED"));
                assertFalse("Corrupt :meta #name/#domain must NEVER trigger E008 POSIX facet error: " + desc,
                    desc.contains("ERR_INVALID_METADATA_FACET"));
            }

            int bodyStrOff = source.indexOf("\"overflow_and_corrupt_") + 4;
            myFixture.getEditor().getCaretModel().moveToOffset(bodyStrOff);
            for (var action : myFixture.getAvailableIntentions()) {
                String text = action.getText();
                assertFalse("Corrupt string must NEVER offer Convert to fenced string intention: " + text,
                    text.contains("Convert to fenced string"));
                assertFalse("Corrupt string must NEVER offer uppercase/lowercase conversion quick-fix: " + text,
                    text.contains("Convert string to uppercase") || text.contains("Convert string to lowercase"));
                assertFalse("Corrupt string must NEVER offer Truncate string literal quick-fix: " + text,
                    text.contains("Truncate string literal"));
            }
        } finally {
            myFixture.disableInspections(capacityInspection, stemInspection, metaFacetInspection);
        }
    }
}
