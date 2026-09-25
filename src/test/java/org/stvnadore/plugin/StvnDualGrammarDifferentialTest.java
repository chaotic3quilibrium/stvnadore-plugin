package org.stvnadore.plugin;

import com.intellij.lexer.FlexAdapter;
import com.intellij.lexer.Lexer;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.parser._StvnLexer;
import org.stvnadore.psi.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Dual-Grammar Differential Test Suite verifying the 40 differential vectors (V01–V40)
 * cataloged in Audit 5 (AUDIT-STVN-2026-005) across all 4 parity dimensions:
 * <ul>
 *   <li>Dimension 1: Lexical Tokens & Terminals (V01–V12)</li>
 *   <li>Dimension 2: Literal & Numeric Radices (V13–V24)</li>
 *   <li>Dimension 3: Coordinates & Spans (V25–V28)</li>
 *   <li>Dimension 4: Syntactic Production Parity (V29–V40)</li>
 * </ul>
 */
@NullMarked
public final class StvnDualGrammarDifferentialTest extends BasePlatformTestCase {

    private static record TokenData(IElementType type, String text, int start, int end) {}

    private List<TokenData> tokenize(String input) {
        Lexer lexer = new FlexAdapter(new _StvnLexer(null));
        lexer.start(input);
        List<TokenData> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            tokens.add(new TokenData(
                lexer.getTokenType(),
                lexer.getBufferSequence().subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString(),
                lexer.getTokenStart(),
                lexer.getTokenEnd()
            ));
            lexer.advance();
        }
        return tokens;
    }

    private void assertNoSyntaxErrors(PsiFile file) {
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var err : errors) {
                sb.append("Error at offset ").append(err.getTextOffset()).append(": ")
                  .append(err.getErrorDescription()).append(" ('").append(err.getText()).append("')\n");
            }
            fail("Expected 0 syntax errors in AST, but found " + errors.size() + ":\n" + sb);
        }
    }

    // =========================================================================
    // Dimension 1: Lexical Tokens & Terminals (V01 - V12)
    // =========================================================================

    /**
     * V01: Zero-Tab Indentation Rejection
     * Assert structural tab \t in document indentation emits BAD_CHARACTER.
     */
    public void testV01_ZeroTabIndentationRejection() {
        var input = "{\n\t:type :Int\n\t:body 42\n}";
        var tokens = tokenize(input);
        var badChars = tokens.stream()
            .filter(t -> t.type() == TokenType.BAD_CHARACTER && t.text().contains("\t"))
            .toList();
        assertFalse("V01: Structural tab in document indentation must emit BAD_CHARACTER", badChars.isEmpty());
    }

    /**
     * V02: Tab in Metadata Block Rejection
     * Assert structural tab \t in metadata block emits BAD_CHARACTER.
     */
    public void testV02_TabInMetadataBlockRejection() {
        var input = "{:defs { :Port { #unsigned\t} :Int } :type :Port :body 8080}";
        var tokens = tokenize(input);
        var badChars = tokens.stream()
            .filter(t -> t.type() == TokenType.BAD_CHARACTER && t.text().contains("\t"))
            .toList();
        assertFalse("V02: Structural tab in metadata block must emit BAD_CHARACTER", badChars.isEmpty());
    }

    /**
     * V03: Tab Inside String Literal Acceptance
     * Assert escaped \t inside string literal parses cleanly as valid token.
     */
    public void testV03_TabInsideStringLiteralAccepted() {
        var input = "{:type :String :body \"hello\\tworld\"}";
        var tokens = tokenize(input);
        var badChars = tokens.stream()
            .filter(t -> t.type() == TokenType.BAD_CHARACTER)
            .toList();
        assertTrue("V03: Escaped tab inside string literal must not emit BAD_CHARACTER", badChars.isEmpty());

        var strTokens = tokens.stream()
            .filter(t -> t.type() == StvnTypes.LITERAL_STRING_SIMPLE)
            .toList();
        assertEquals("V03: Expected 1 simple string token", 1, strTokens.size());
        assertEquals("\"hello\\tworld\"", strTokens.get(0).text());

        var file = myFixture.configureByText("v03.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V04: Bare Infix Colon Rejection
     * Assert bare ':' in infix position (JSON-style) emits BAD_CHARACTER.
     */
    public void testV04_BareInfixColonRejection() {
        var input = "{:defs { :P :Tuple(:Int : :Int)}}";
        var tokens = tokenize(input);
        var badChars = tokens.stream()
            .filter(t -> t.type() == TokenType.BAD_CHARACTER && t.text().equals(":"))
            .toList();
        assertFalse("V04: Bare colon ':' in infix position must emit BAD_CHARACTER", badChars.isEmpty());
    }

    /**
     * V05: Space-Separated Bare Colon Rejection
     * Assert space-separated colon (': Int') emits BAD_CHARACTER.
     */
    public void testV05_SpaceSeparatedBareColonRejection() {
        var input = "{:defs { : Int } }";
        var tokens = tokenize(input);
        var badChars = tokens.stream()
            .filter(t -> t.type() == TokenType.BAD_CHARACTER && t.text().equals(":"))
            .toList();
        assertFalse("V05: Bare colon ':' followed by space must emit BAD_CHARACTER", badChars.isEmpty());
    }

    /**
     * V06: Raw Hash in Comment Position Rejection
     * Assert raw '#' in comment position emits BAD_CHARACTER.
     */
    public void testV06_RawHashInCommentPositionRejection() {
        var input = "{\n # Illegal Hash Comment\n :type :Int :body 1\n}";
        var tokens = tokenize(input);
        var badChars = tokens.stream()
            .filter(t -> t.type() == TokenType.BAD_CHARACTER && t.text().contains("#"))
            .toList();
        assertFalse("V06: Raw '#' in comment position must emit BAD_CHARACTER", badChars.isEmpty());
    }

    /**
     * V07: Double-Slash Single-Line Comment Routing
     * Assert '// ...' routes to trivia/comment channel without error.
     */
    public void testV07_SingleLineDoubleSlashCommentRouting() {
        var input = "{\n // Valid comment\n :type :Int :body 1\n}";
        var tokens = tokenize(input);
        var commentTokens = tokens.stream()
            .filter(t -> t.type() == StvnTypes.COMMENT)
            .toList();
        assertEquals("V07: Expected 1 comment token", 1, commentTokens.size());
        assertEquals("// Valid comment", commentTokens.get(0).text());

        var badChars = tokens.stream().filter(t -> t.type() == TokenType.BAD_CHARACTER).toList();
        assertTrue("V07: Valid comment must not produce BAD_CHARACTER", badChars.isEmpty());

        var file = myFixture.configureByText("v07.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V08: Escaped Backslash at String End
     * Assert 'dir\\' terminates cleanly without consuming following tokens.
     */
    public void testV08_EscapedBackslashAtStringEnd() {
        var input = "{:type :String :body \"dir\\\\\" }";
        var tokens = tokenize(input);
        var badChars = tokens.stream().filter(t -> t.type() == TokenType.BAD_CHARACTER).toList();
        assertTrue("V08: Escaped backslash at string end must not produce BAD_CHARACTER", badChars.isEmpty());

        var strTokens = tokens.stream()
            .filter(t -> t.type() == StvnTypes.LITERAL_STRING_SIMPLE)
            .toList();
        assertEquals("V08: Expected exactly 1 string token", 1, strTokens.size());
        assertEquals("\"dir\\\\\"", strTokens.get(0).text());

        var rbraceTokens = tokens.stream().filter(t -> t.type() == StvnTypes.RBRACE).toList();
        assertEquals("V08: Closing brace must be tokenized as RBRACE", 1, rbraceTokens.size());

        var file = myFixture.configureByText("v08.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V09: Unescaped Newline in Simple String Rejection
     * Assert raw unescaped physical line breaks inside double quotes emit BAD_CHARACTER on the newline.
     */
    public void testV09_UnescapedNewlineInSimpleStringRejection() {
        var input = "\"line1\nline2\"";
        var tokens = tokenize(input);
        var badChars = tokens.stream()
            .filter(t -> t.type() == TokenType.BAD_CHARACTER)
            .toList();
        assertFalse("V09: Unescaped newline in simple string must emit BAD_CHARACTER", badChars.isEmpty());

        var hasSimpleString = tokens.stream().anyMatch(t -> t.type() == StvnTypes.LITERAL_STRING_SIMPLE);
        assertFalse("V09: Unescaped newline must not produce LITERAL_STRING_SIMPLE", hasSimpleString);

        var doc = "{:type :String :body \"line1\nline2\"}";
        var file = myFixture.configureByText("v09.stvn", doc);
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        assertFalse("V09: Unescaped newline in document string must produce syntax errors", errors.isEmpty());
    }

    /**
     * V10: CRLF Block Strings
     * Assert '\"\"\"\r\nHello\r\n\"\"\"' produces LITERAL_STRING_BLOCK.
     */
    public void testV10_CrlfBlockStringParsing() {
        var input = "\"\"\"\r\nHello\r\n\"\"\"";
        var tokens = tokenize(input);
        var badChars = tokens.stream().filter(t -> t.type() == TokenType.BAD_CHARACTER).toList();
        assertTrue("V10: CRLF block string must not produce BAD_CHARACTER", badChars.isEmpty());

        var blockTokens = tokens.stream()
            .filter(t -> t.type() == StvnTypes.LITERAL_STRING_BLOCK)
            .toList();
        assertEquals("V10: Expected 1 LITERAL_STRING_BLOCK token", 1, blockTokens.size());
        assertEquals(input, blockTokens.get(0).text());
    }

    /**
     * V11: Unclosed Fenced String at EOF Rejection
     * Assert '\"\"\"[TAG]\nUnclosed' reaching EOF emits BAD_CHARACTER.
     */
    public void testV11_UnclosedFencedStringAtEofRejection() {
        var input = "\"\"\"[TAG]\nUnclosed content reaching EOF";
        var tokens = tokenize(input);
        var hasBadCharacter = tokens.stream().anyMatch(t -> t.type() == TokenType.BAD_CHARACTER);
        assertTrue("V11: Unclosed fenced string reaching EOF must emit BAD_CHARACTER", hasBadCharacter);

        var hasFencedToken = tokens.stream().anyMatch(t -> t.type() == StvnTypes.LITERAL_STRING_FENCED);
        assertFalse("V11: Unclosed fenced string must NOT emit LITERAL_STRING_FENCED", hasFencedToken);
    }

    /**
     * V12: Deprecated Fence Arrow Rejection
     * Assert '\"\"\"->[TAG]' emits BAD_CHARACTER.
     */
    public void testV12_DeprecatedFenceArrowRejection() {
        var input = "\"\"\"->[TAG]\nContent\n[TAG]\"\"\"";
        var tokens = tokenize(input);
        var badChars = tokens.stream().filter(t -> t.type() == TokenType.BAD_CHARACTER).toList();
        assertFalse("V12: Deprecated fence arrow '->' must emit BAD_CHARACTER", badChars.isEmpty());
    }

    // =========================================================================
    // Dimension 2: Literal & Numeric Radices (V13 - V24)
    // =========================================================================

    /**
     * V13: Hexadecimal Integer Literal
     * Assert '0x1A4F' parses as LITERAL_INTEGER.
     */
    public void testV13_HexadecimalIntegerLiteral() {
        var tokens = tokenize("0x1A4F");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_INTEGER, tokens.get(0).type());
        assertEquals("0x1A4F", tokens.get(0).text());
    }

    /**
     * V14: Negative Hexadecimal Integer Literal
     * Assert '-0x7FFF' parses as LITERAL_INTEGER.
     */
    public void testV14_NegativeHexadecimalIntegerLiteral() {
        var tokens = tokenize("-0x7FFF");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_INTEGER, tokens.get(0).type());
        assertEquals("-0x7FFF", tokens.get(0).text());
    }

    /**
     * V15: Binary Integer Literal
     * Assert '0b101010' parses as LITERAL_INTEGER.
     */
    public void testV15_BinaryIntegerLiteral() {
        var tokens = tokenize("0b101010");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_INTEGER, tokens.get(0).type());
        assertEquals("0b101010", tokens.get(0).text());
    }

    /**
     * V16: Negative Binary Integer Literal
     * Assert '-0b1111' parses as LITERAL_INTEGER.
     */
    public void testV16_NegativeBinaryIntegerLiteral() {
        var tokens = tokenize("-0b1111");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_INTEGER, tokens.get(0).type());
        assertEquals("-0b1111", tokens.get(0).text());
    }

    /**
     * V17: Octal Integer Literal
     * Assert '0o755' parses as LITERAL_INTEGER.
     */
    public void testV17_OctalIntegerLiteral() {
        var tokens = tokenize("0o755");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_INTEGER, tokens.get(0).type());
        assertEquals("0o755", tokens.get(0).text());
    }

    /**
     * V18: Negative Octal Integer Literal
     * Assert '-0o644' parses as LITERAL_INTEGER.
     */
    public void testV18_NegativeOctalIntegerLiteral() {
        var tokens = tokenize("-0o644");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_INTEGER, tokens.get(0).type());
        assertEquals("-0o644", tokens.get(0).text());
    }

    /**
     * V19: Standard Decimal Float Literal
     * Assert '10.5' parses as LITERAL_FLOAT.
     */
    public void testV19_StandardFloatLiteral() {
        var tokens = tokenize("10.5");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_FLOAT, tokens.get(0).type());
        assertEquals("10.5", tokens.get(0).text());
    }

    /**
     * V20: Float Literal with Exponent
     * Assert '2.5E-3' parses as LITERAL_FLOAT.
     */
    public void testV20_FloatWithExponent() {
        var tokens = tokenize("2.5E-3");
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_FLOAT, tokens.get(0).type());
        assertEquals("2.5E-3", tokens.get(0).text());
    }

    /**
     * V21: Float Missing Leading Digit Fails Closed
     * Assert '.5' fails closed with BAD_CHARACTER for '.'.
     */
    public void testV21_FloatMissingLeadingDigitFailsClosed() {
        var tokens = tokenize(".5");
        assertFalse("V21: Must emit tokens", tokens.isEmpty());
        assertEquals("V21: Leading dot must be BAD_CHARACTER", TokenType.BAD_CHARACTER, tokens.get(0).type());
        assertEquals(".", tokens.get(0).text());
    }

    /**
     * V22: Float Missing Trailing Digit Fails Closed
     * Assert '5.' fails closed with LITERAL_INTEGER followed by BAD_CHARACTER.
     */
    public void testV22_FloatMissingTrailingDigitFailsClosed() {
        var tokens = tokenize("5.");
        assertTrue("V22: Must produce at least 2 tokens", tokens.size() >= 2);
        assertEquals(StvnTypes.LITERAL_INTEGER, tokens.get(0).type());
        assertEquals("5", tokens.get(0).text());
        assertEquals(TokenType.BAD_CHARACTER, tokens.get(1).type());
        assertEquals(".", tokens.get(1).text());
    }

    /**
     * V23: Canonical Booleans
     * Assert '#TRUE' and '#FALSE' parse to KW_TRUE and KW_FALSE.
     */
    public void testV23_CanonicalBooleans() {
        var tokensTrue = tokenize("#TRUE");
        assertEquals(1, tokensTrue.size());
        assertEquals(StvnTypes.KW_TRUE, tokensTrue.get(0).type());

        var tokensFalse = tokenize("#FALSE");
        assertEquals(1, tokensFalse.size());
        assertEquals(StvnTypes.KW_FALSE, tokensFalse.get(0).type());
    }

    /**
     * V24: Compressed Booleans
     * Assert '#T' and '#F' parse to KW_TRUE_SHORT and KW_FALSE_SHORT.
     */
    public void testV24_CompressedBooleans() {
        var tokensT = tokenize("#T");
        assertEquals(1, tokensT.size());
        assertEquals(StvnTypes.KW_TRUE_SHORT, tokensT.get(0).type());

        var tokensF = tokenize("#F");
        assertEquals(1, tokensF.size());
        assertEquals(StvnTypes.KW_FALSE_SHORT, tokensF.get(0).type());
    }

    // =========================================================================
    // Dimension 3: Coordinates & Spans (V25 - V28)
    // =========================================================================

    /**
     * V25: LF Coordinate Offset Matching
     * Assert token TextRange offsets match expected character spans on LF endings.
     */
    public void testV25_LfCoordinateOffsetMatching() {
        var input = "{\n  :type :Int\n}";
        var tokens = tokenize(input);
        var typeKw = tokens.stream().filter(t -> ":type".equals(t.text())).findFirst().orElseThrow();
        assertEquals("V25 :type start offset", 4, typeKw.start());
        assertEquals("V25 :type end offset", 9, typeKw.end());

        var intKw = tokens.stream().filter(t -> ":Int".equals(t.text())).findFirst().orElseThrow();
        assertEquals("V25 :Int start offset", 10, intKw.start());
        assertEquals("V25 :Int end offset", 14, intKw.end());
    }

    /**
     * V26: CRLF Coordinate Offset Matching
     * Assert token TextRange offsets match expected character spans on CRLF endings.
     */
    public void testV26_CrlfCoordinateOffsetMatching() {
        var input = "{\r\n  :type :Int\r\n}";
        var tokens = tokenize(input);
        var typeKw = tokens.stream().filter(t -> ":type".equals(t.text())).findFirst().orElseThrow();
        assertEquals("V26 :type start offset", 5, typeKw.start());
        assertEquals("V26 :type end offset", 10, typeKw.end());

        var intKw = tokens.stream().filter(t -> ":Int".equals(t.text())).findFirst().orElseThrow();
        assertEquals("V26 :Int start offset", 11, intKw.start());
        assertEquals("V26 :Int end offset", 15, intKw.end());
    }

    /**
     * V27: Trailing Content After Root Document Rejection
     * Assert non-whitespace tokens trailing the root closing brace trigger a syntax error against <<eof>>.
     */
    public void testV27_TrailingContentAfterDocumentRejection() {
        var input = "{:type :Int :body 1} :extra";
        var file = myFixture.configureByText("v27.stvn", input);
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        assertFalse("V27: Trailing tokens after root '}' must produce a syntax error against <<eof>>", errors.isEmpty());
    }

    /**
     * V28: Fenced String Delimiter Span Coverage
     * Assert fenced string produces LITERAL_STRING_FENCED spanning the complete delimiter range.
     */
    public void testV28_FencedStringSpanCoverage() {
        var input = "\"\"\"[TAG]\nHello\n[TAG]\"\"\"";
        var tokens = tokenize(input);
        assertEquals(1, tokens.size());
        assertEquals(StvnTypes.LITERAL_STRING_FENCED, tokens.get(0).type());
        assertEquals(0, tokens.get(0).start());
        assertEquals(input.length(), tokens.get(0).end());
    }

    // =========================================================================
    // Dimension 4: Syntactic Production Parity (V29 - V40)
    // =========================================================================

    /**
     * V29: Bare Metadata Flag
     * Assert '{ #unsigned }' parses as valid metadata entry without syntax errors.
     */
    public void testV29_BareMetadataFlag() {
        var input = "{:defs { :Port { #unsigned } :Int } :type :Port :body 8080}";
        var file = myFixture.configureByText("v29.stvn", input);
        assertNoSyntaxErrors(file);

        var bareFlags = PsiTreeUtil.findChildrenOfType(file, MetadataBareFlag.class);
        assertFalse("V29: MetadataBareFlag element must exist", bareFlags.isEmpty());
        assertEquals("#unsigned", bareFlags.iterator().next().getFirstChild().getText());
    }

    /**
     * V30: Metadata Flag with Explicit Boolean
     * Assert '{ #unsigned #TRUE }' parses as valid metadata entry with boolean value.
     */
    public void testV30_MetadataFlagWithExplicitBoolean() {
        var input = "{:defs { :Port { #unsigned #TRUE } :Int } :type :Port :body 8080}";
        var file = myFixture.configureByText("v30.stvn", input);
        assertNoSyntaxErrors(file);

        var bareFlags = PsiTreeUtil.findChildrenOfType(file, MetadataBareFlag.class);
        assertFalse("V30: MetadataBareFlag element must exist", bareFlags.isEmpty());
        var flag = bareFlags.iterator().next();
        assertNotNull("V30: MetadataBareFlag must have boolean value", flag.getBooleanValue());
        assertEquals("#TRUE", flag.getBooleanValue().getText());
    }

    /**
     * V31: Preserve Indent Explicit Boolean
     * Assert '{ #preserveIndent #TRUE }' parses without PEG ordered-choice shadowing.
     */
    public void testV31_PreserveIndentBoolean() {
        var input = "{:defs { :Txt { #preserveIndent #TRUE } :String } :type :Txt :body \"hello\"}";
        var file = myFixture.configureByText("v31.stvn", input);
        assertNoSyntaxErrors(file);

        var bareFlags = PsiTreeUtil.findChildrenOfType(file, MetadataBareFlag.class);
        assertFalse("V31: MetadataBareFlag element must exist", bareFlags.isEmpty());
        var flag = bareFlags.iterator().next();
        assertNotNull("V31: PreserveIndent flag must retain its boolean value", flag.getBooleanValue());
        assertEquals("#TRUE", flag.getBooleanValue().getText());
    }

    /**
     * V32: Temporal Flags in Enum Definition
     * Assert ':Units :Enum [ #s #ms #us #ns ]' parses with 0 errors (value_keyword_start coverage).
     */
    public void testV32_TemporalFlagsInEnumDefinition() {
        var input = "{:defs { :Units :Enum [ #s #ms #us #ns ] } :type :Units :body #s}";
        var file = myFixture.configureByText("v32.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V33: Flags in Variant Filters
     * Assert ':E { #filterIncl [ #exact #unsigned ] }' parses with 0 errors (KW_FILTER_INCL coverage).
     */
    public void testV33_FlagsInVariantFilters() {
        var input = "{:defs { :E { #filterIncl [ #exact #unsigned ] } :Enum [ #exact #unsigned #other ] } :type :E :body #exact}";
        var file = myFixture.configureByText("v33.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V34: Union Tag Prefixes in Value Keyword
     * Assert ':TagVal #1' parses cleanly via UNION_TAG_PREFIX in value_keyword_start.
     */
    public void testV34_UnionTagPrefixesInValueKeyword() {
        // 1. In enum definitions, UNION_TAG_PREFIX (#1, #2) acts as valid value_keyword
        var enumInput = "{:defs { :TagVal :Enum [ #1 #2 ] } :type :Int :body 1}";
        var enumFile = myFixture.configureByText("v34_enum.stvn", enumInput);
        assertNoSyntaxErrors(enumFile);

        // 2. In variant filters, UNION_TAG_PREFIX acts as valid value_keyword
        var filterInput = "{:defs { :TagVal { #filterIncl [ #1 ] } :Enum [ #1 #2 ] } :type :Int :body 1}";
        var filterFile = myFixture.configureByText("v34_filter.stvn", filterInput);
        assertNoSyntaxErrors(filterFile);

        // 3. In explicit union payload values, UNION_TAG_PREFIX parses cleanly with payload value
        var unionInput = "{:defs { :TagVal :Union(:Int :String) } :type :TagVal :body #1 42}";
        var unionFile = myFixture.configureByText("v34_union.stvn", unionInput);
        assertNoSyntaxErrors(unionFile);
    }

    /**
     * V35: Filter Token Naming Parity
     * Assert '#filterIncl' and '#filterExcl' emit KW_FILTER_INCL and KW_FILTER_EXCL.
     */
    public void testV35_FilterTokenNamingParity() {
        var tokensIncl = tokenize("#filterIncl");
        assertEquals(1, tokensIncl.size());
        assertEquals(StvnTypes.KW_FILTER_INCL, tokensIncl.get(0).type());

        var tokensExcl = tokenize("#filterExcl");
        assertEquals(1, tokensExcl.size());
        assertEquals(StvnTypes.KW_FILTER_EXCL, tokensExcl.get(0).type());
    }

    /**
     * V36: Include Alias Mapping
     * Assert ':include [\"m.stvn_incl\" { :A :B }]' parses cleanly with 0 syntax errors.
     */
    public void testV36_IncludeAliasMapping() {
        var input = "{:defs { :include [\"m.stvn_incl\" { :A :B }] } :type :B :body 1}";
        var file = myFixture.configureByText("v36.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V37: Empty Root Document
     * Assert '{}' parses cleanly with 0 syntax errors.
     */
    public void testV37_EmptyRootDocument() {
        var input = "{}";
        var file = myFixture.configureByText("v37.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V38: Include-Only Document
     * Assert '{:defs { :Port :Int } }' parses cleanly with 0 syntax errors.
     */
    public void testV38_IncludeOnlyDocument() {
        var input = "{:defs { :Port :Int } }";
        var file = myFixture.configureByText("v38.stvn_incl", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V39: Reserved Keyword in Type Target
     * Assert '{:defs { :defs :Int } :type :defs :body 1}' parses cleanly at syntax level.
     */
    public void testV39_ReservedKeywordInTypeTarget() {
        var input = "{:defs { :defs :Int } :type :defs :body 1}";
        var file = myFixture.configureByText("v39.stvn", input);
        assertNoSyntaxErrors(file);
    }

    /**
     * V40: Shallow Nominal Type Reference
     * Assert shallow nominal type references resolve to declaration tokens without cyclic recursion.
     */
    public void testV40_ShallowNominalTypeReference() {
        var input = "{:defs { :Base :Int :Derived :Base } :type :Derived :body 42}";
        var file = myFixture.configureByText("v40.stvn", input);
        assertNoSyntaxErrors(file);

        var typeKeywords = PsiTreeUtil.findChildrenOfType(file, TypeKeyword.class);
        var derivedUsage = typeKeywords.stream()
            .filter(tk -> ":Derived".equals(tk.getText()) && !org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(tk))
            .findFirst()
            .orElseThrow();

        var ref = derivedUsage.getReference();
        assertNotNull("V40: TypeKeyword reference must not be null", ref);
        var target = ref.resolve();
        assertNotNull("V40: Reference should resolve to declaration", target);
        assertTrue("V40: Resolved target must be a TypeKeyword", target instanceof TypeKeyword);
        assertEquals(":Derived", ((TypeKeyword) target).getText());

        // Recursive cycle safety check: circular reference must not stack-overflow
        var cyclicInput = "{:defs { :A :B :B :A } :type :A :body 42}";
        var cyclicFile = myFixture.configureByText("v40_cyclic.stvn", cyclicInput);
        assertNoSyntaxErrors(cyclicFile);
        var resolvedCyclic = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(cyclicFile, ":A", new HashSet<>());
        assertNotNull("V40: Cyclic reference must resolve without infinite recursion", resolvedCyclic);
    }
}
