package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lexer.FlexAdapter;
import com.intellij.psi.StringEscapesTokenTypes;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.parser._StvnLexer;
import org.stvnadore.plugin.StvnSimpleStringHighlightingLexer;
import org.stvnadore.plugin.actions.StvnCreateFencedStringIntention;
import org.stvnadore.plugin.intentions.StvnConvertEscapedStringToFencedIntention;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * IntelliJ Platform parity suite verifying Target 3 fenced-string conversion gates and Target 4
 * standalone-{@code \r} JFlex lexing, UTF-16 surrogate escape highlighting, and Pass A vs. Pass B
 * {@code E001} ({@code ERR_FORBIDDEN_RAW_CHARACTER} and {@code ERR_UNPAIRED_SURROGATE}) {@code count == 1}
 * deconfliction across all 27 local inspections.
 */
@NullMarked
public final class StvnStringCharacterHygieneParityTest extends BasePlatformTestCase {

    public void testBothFencedIntentionsGateOnCanRepresentVerbatimInFencedString() {
        var createIntention = new StvnCreateFencedStringIntention();
        var convertIntention = new StvnConvertEscapedStringToFencedIntention();

        String[] rejectedBodies = {
            "\"line1\\r\\nline2\"",
            "\"line1\\u000D\\nline2\"",
            "\"bell_\\u0007\"",
            "\"del_\\u007F\"",
            "\"c1_\\u0085\"",
            "\"bidi_\\u202E\"",
            "\"zwsp_\\u200B\"",
            "\"bom_\\uFEFF\"",
            "\"ls_\\u2028\"",
            "\"ps_\\u2029\"",
            "\"unpaired_\\uD800\"",
            "\"  leading_spaces_without_preserve_indent\""
        };

        for (String rejectedLiteral : rejectedBodies) {
            String doc = """
                {
                  :meta {
                    #name "fence_gate"
                    #domain "hygiene"
                    #kind #BODY
                  }
                  :type :String
                  :body %s
                }
                """.formatted(rejectedLiteral);
            myFixture.configureByText("fence_gate.hygiene.stvn", doc);
            int offset = doc.indexOf(rejectedLiteral) + 2;
            myFixture.getEditor().getCaretModel().moveToOffset(offset);
            var elem = myFixture.getFile().findElementAt(offset);
            assertNotNull(elem);
            assertFalse("StvnCreateFencedStringIntention must reject " + rejectedLiteral,
                createIntention.isAvailable(getProject(), myFixture.getEditor(), elem));
            assertFalse("StvnConvertEscapedStringToFencedIntention must reject " + rejectedLiteral,
                convertIntention.isAvailable(getProject(), myFixture.getEditor(), elem));
        }

        // Valid printable emoji and ZWJ sequences with \n convert cleanly
        String validEmojiDoc = """
            {
              :meta {
                #name "emoji_fence"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "\\uD83D\\uDE80\\n\\uD83D\\uDC68\\u200D\\uD83D\\uDCBB"
            }
            """;
        myFixture.configureByText("emoji_fence.hygiene.stvn", validEmojiDoc);
        int validOffset = validEmojiDoc.indexOf("\\uD83D\\uDE80") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(validOffset);
        var validElem = myFixture.getFile().findElementAt(validOffset);
        assertNotNull(validElem);
        assertFalse("Escaped emoji literal must not offer plain simple-string intention",
            createIntention.isAvailable(getProject(), myFixture.getEditor(), validElem));
        assertTrue("Escaped emoji literal must offer escaped-to-fenced intention",
            convertIntention.isAvailable(getProject(), myFixture.getEditor(), validElem));

        var availableConvert = myFixture.filterAvailableIntentions(convertIntention.getText());
        assertEquals(1, availableConvert.size());
        myFixture.launchAction(availableConvert.get(0));
        String converted = myFixture.getEditor().getDocument().getText();
        assertTrue("Converted fenced string must contain verbatim emojis: " + converted,
            converted.contains("\"\"\"[RAW]\n    \uD83D\uDE80\n    \uD83D\uDC68\u200D\uD83D\uDCBB[RAW]\"\"\""));

        String plainEmojiDoc = """
            {
              :meta {
                #name "plain_emoji"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "\uD83D\uDE80\uD83D\uDC68\u200D\uD83D\uDCBB"
            }
            """;
        myFixture.configureByText("plain_emoji.hygiene.stvn", plainEmojiDoc);
        int plainOffset = plainEmojiDoc.indexOf("\uD83D\uDE80") + 1;
        myFixture.getEditor().getCaretModel().moveToOffset(plainOffset);
        var plainElem = myFixture.getFile().findElementAt(plainOffset);
        assertNotNull(plainElem);
        assertTrue("Escape-free emoji literal must offer plain simple-string intention",
            createIntention.isAvailable(getProject(), myFixture.getEditor(), plainElem));
        assertFalse("Escape-free emoji literal must not offer escaped-to-fenced intention",
            convertIntention.isAvailable(getProject(), myFixture.getEditor(), plainElem));
        assertEquals(
            "\"a\\r\\n\\u202e\\ufeff\"",
            StvnPsiLiteralEvaluator.formatCanonicalQuotedString("a\r\n\u202E\uFEFF")
        );
    }

    public void testFlexLexerStandaloneCrFencedStringAndHighlightingSurrogateTokens() {
        // 1. Direct _StvnLexer test on standalone \r fenced string
        String standaloneCrDoc = "{\r  :type :String\r  :body \"\"\"[RAW]\r  hello\r  \tworld\r[RAW]\"\"\"\r}";
        var lexer = new FlexAdapter(new _StvnLexer());
        lexer.start(standaloneCrDoc);
        List<String> fencedTokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == StvnTypes.LITERAL_STRING_FENCED) {
                fencedTokens.add(standaloneCrDoc.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
            }
            assertFalse("Standalone \\r fenced string must not fracture into MALFORMED_FENCE",
                lexer.getTokenType() == StvnTypes.MALFORMED_FENCE);
            lexer.advance();
        }
        assertEquals("Expected strictly 1 LITERAL_STRING_FENCED token on standalone \\r", 1, fencedTokens.size());

        // 2. StvnSimpleStringHighlightingLexer paired vs unpaired surrogate classification
        String str = "\"\\uD83D\\uDE80_\\uD800_\\uDC00_\\uD83D\\u0041\"";
        var hiLexer = new StvnSimpleStringHighlightingLexer();
        hiLexer.start(str, 0, str.length(), 0);

        List<String> validEscapes = new ArrayList<>();
        List<String> invalidEscapes = new ArrayList<>();
        while (hiLexer.getTokenType() != null) {
            String slice = str.substring(hiLexer.getTokenStart(), hiLexer.getTokenEnd());
            if (hiLexer.getTokenType() == StringEscapesTokenTypes.VALID_STRING_ESCAPE_TOKEN) {
                validEscapes.add(slice);
            } else if (hiLexer.getTokenType() == StringEscapesTokenTypes.INVALID_UNICODE_ESCAPE_TOKEN) {
                invalidEscapes.add(slice);
            }
            hiLexer.advance();
        }
        assertEquals(List.of("\\uD83D\\uDE80", "\\u0041"), validEscapes);
        assertEquals(List.of("\\uD800", "\\uDC00", "\\uD83D"), invalidEscapes);
    }

    public void testPassAAndPassBExactCountOneParityOnForbiddenRawCharactersAndUnpairedSurrogates() {
        var all27 = LocalInspectionEP.LOCAL_INSPECTION.getExtensionList().stream()
            .filter(ep -> "STVN".equals(ep.language))
            .map(ep -> (LocalInspectionTool) ep.instantiateTool())
            .toArray(LocalInspectionTool[]::new);
        assertEquals("Expected all 30 registered STVN inspections", 30, all27.length);

        String[] e001Cases = {
            // 1. Raw Trojan Source RLO U+202E
            """
            {
              :meta {
                #name "raw_bidi"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "trojan_\u202E_bidi"
            }
            """,
            // 2. Raw ZWSP U+200B
            """
            {
              :meta {
                #name "raw_zwsp"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "zwsp_\u200B_here"
            }
            """,
            // 3. Raw BEL U+0007
            """
            {
              :meta {
                #name "raw_bel"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "bel_\u0007_here"
            }
            """,
            // 4. Raw DEL U+007F
            """
            {
              :meta {
                #name "raw_del"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "del_\u007F_here"
            }
            """,
            // 5. Raw interior BOM U+FEFF
            """
            {
              :meta {
                #name "raw_bom"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "bom_\uFEFF_here"
            }
            """,
            // 6. Unpaired surrogate escape "\uD800"
            """
            {
              :meta {
                #name "unpaired_surr"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "lone_\\uD800_surrogate"
            }
            """
        };

        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> passAResults = new ArrayList<>();
        List<String> normalizedSources = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();

        // Pass A: 0 inspections enabled (default BasePlatformTestCase profile)
        for (int idx = 0; idx < e001Cases.length; idx++) {
            String src = e001Cases[idx];
            String fileName = (idx == 5 ? "unpaired_surr" : "raw_case_" + idx) + ".hygiene.stvn";
            if (idx < 5) {
                src = src.replaceFirst("#name \"[^\"]+\"", "#name \"raw_case_" + idx + "\"");
            }
            normalizedSources.add(src);
            fileNames.add(fileName);

            myFixture.configureByText(fileName, src);
            var passAErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
            assertEquals("Pass A must emit strictly 1 E001 error for case #" + idx + ": " + passAErrors,
                1, passAErrors.size());
            assertTrue("Pass A error must carry E001 tag: " + passAErrors.get(0).getDescription(),
                passAErrors.get(0).getDescription().contains("[stvn-core:E001-"));
            passAResults.add(passAErrors.get(0));
        }

        // Pass B: All 27 inspections enabled (including StvnTabCharacterInspection and StvnStringEscapeScrutinyInspection)
        myFixture.enableInspections(all27);
        try {
            for (int idx = 0; idx < normalizedSources.size(); idx++) {
                myFixture.configureByText(fileNames.get(idx), normalizedSources.get(idx));
                var passBErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
                assertEquals("Pass B (all 27 inspections) must emit strictly 1 E001 error without dropping Core E001 for case #"
                    + idx + ": " + passBErrors, 1, passBErrors.size());
                var passAError = passAResults.get(idx);
                assertEquals(passAError.getStartOffset(), passBErrors.get(0).getStartOffset());
                assertEquals(passAError.getEndOffset(), passBErrors.get(0).getEndOffset());
                assertEquals(passAError.getDescription(), passBErrors.get(0).getDescription());
            }
        } finally {
            myFixture.disableInspections(all27);
        }
    }
}
