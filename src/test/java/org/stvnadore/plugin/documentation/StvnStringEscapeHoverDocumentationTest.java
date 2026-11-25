package org.stvnadore.plugin.documentation;

import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * IntelliJ Platform tests verifying {@link StvnDocumentationProvider} escape-sequence glyph and
 * emoji hover previews across {@code :meta}, {@code :defs}, {@code :type}, and {@code :body},
 * while confirming 100% preservation of existing hover cards outside valid escape sequences.
 */
@NullMarked
public final class StvnStringEscapeHoverDocumentationTest extends BasePlatformTestCase {

    private String generateDocAt(int offset) {
        var provider = new StvnDocumentationProvider();
        var file = myFixture.getFile();
        PsiElement leaf = file.findElementAt(offset);
        assertNotNull("Expected PSI leaf at offset " + offset, leaf);
        PsiElement custom = provider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, offset);
        PsiElement target = custom != null ? custom : leaf;
        return provider.generateDoc(target, leaf);
    }

    public void testEscapeHoverActivatesOnEscapesAcrossSectionsAndPreservesSurroundingCards() {
        String source = """
            {
              :meta {
                #name "hover_demo"
                #domain "docs"
                #kind #BODY
                #summary "Meta\\tSummary"
              }
              :defs {
                :PatternStr { #minSize 1 #maxSize 10 #regex "a\\nb" } :String
                #EMOJI_CONST :PatternStr "\\uD83D\\uDC68\\u200D\\uD83D\\uDCBB"
              }
              :type :PatternStr
              :body "Hello \\uD83D\\uDE80\\r\\nWorld"
            }
            """;
        myFixture.configureByText("hover_demo.docs.stvn", source);

        // 1. :meta escape (\t inside "Meta\tSummary")
        int metaTabOff = source.indexOf("\\tSummary") + 1;
        String metaTabDoc = generateDocAt(metaTabOff);
        assertNotNull(metaTabDoc);
        assertTrue(metaTabDoc.contains("STVN String Escape Sequence"));
        assertTrue(metaTabDoc.contains("U+0009"));
        assertTrue(metaTabDoc.contains("CHARACTER TABULATION"));
        assertTrue(metaTabDoc.contains(StvnStringEscapeHoverSupport.NON_PRINTABLE_INDICATOR));

        // 2. :defs #regex escape (\n inside "a\nb") vs non-escaped 'a' inside "a\nb"
        int regexNlOff = source.indexOf("\\nb") + 1;
        String regexNlDoc = generateDocAt(regexNlOff);
        assertNotNull(regexNlDoc);
        assertTrue(regexNlDoc.contains("STVN String Escape Sequence"));
        assertTrue(regexNlDoc.contains("U+000A"));

        int regexPlainCharOff = source.indexOf("\"a\\nb\"") + 1;
        String regexFacetDoc = generateDocAt(regexPlainCharOff);
        assertNotNull(regexFacetDoc);
        assertFalse("Plain character inside #regex string must preserve schema facet card",
            regexFacetDoc.contains("STVN String Escape Sequence"));
        assertTrue(regexFacetDoc.contains("#regex"));

        // 3. :defs #CONST ZWJ cluster ("\uD83D\uDC68\u200D\uD83D\uDCBB")
        int zwjOff = source.indexOf("\\u200D") + 3;
        String zwjDoc = generateDocAt(zwjOff);
        assertNotNull(zwjDoc);
        assertTrue(zwjDoc.contains("ZERO WIDTH JOINER"));
        assertTrue(zwjDoc.contains("Composed Sequence Preview:"));
        assertTrue(zwjDoc.contains("\uD83D\uDC68\u200D\uD83D\uDCBB"));
        assertTrue(zwjDoc.contains("U+1F468 + U+200D + U+1F4BB"));

        // 4. :body surrogate pair (\uD83D\uDE80) hovered on high half and low half
        int rocketHighOff = source.indexOf("\\uD83D\\uDE80") + 2;
        int rocketLowOff = source.indexOf("\\uD83D\\uDE80") + 8;
        String highDoc = generateDocAt(rocketHighOff);
        String lowDoc = generateDocAt(rocketLowOff);
        assertEquals(highDoc, lowDoc);
        assertTrue(highDoc.contains("U+1F680"));
        assertTrue(highDoc.contains("ROCKET"));
        assertTrue(highDoc.contains("F0 9F 9A 80"));
        assertTrue(highDoc.contains("\uD83D\uDE80"));

        // 5. :body opening quote and plain 'H' in "Hello \uD83D\uDE80\r\nWorld" preserve Payload Value card
        int bodyQuoteOff = source.indexOf("\"Hello ");
        int bodyPlainOff = bodyQuoteOff + 2;
        String quoteDoc = generateDocAt(bodyQuoteOff);
        String plainDoc = generateDocAt(bodyPlainOff);
        assertNotNull(quoteDoc);
        assertNotNull(plainDoc);
        assertFalse(quoteDoc.contains("STVN String Escape Sequence"));
        assertFalse(plainDoc.contains("STVN String Escape Sequence"));
        assertTrue(plainDoc.contains("Payload Value"));
    }

    public void testAstralEmojiCountedInUnicodeScalarValuesInComplianceHover() {
        String source = """
            {
              :meta {
                #name "emoji_len"
                #domain "docs"
                #kind #BODY
              }
              :type { #minSize 1 #maxSize 1 } :String
              :body "\\uD83D\\uDE80"
            }
            """;
        myFixture.configureByText("emoji_len.docs.stvn", source);

        // Hover on the opening quote so we inspect the slot compliance card rather than the escape card
        int quoteOff = source.indexOf("\"\\uD83D\\uDE80\"");
        String complianceDoc = generateDocAt(quoteOff);
        assertNotNull(complianceDoc);
        assertTrue("Single astral scalar U+1F680 must satisfy #maxSize 1 in hover compliance: " + complianceDoc,
            complianceDoc.contains(">Satisfied<"));
    }
}
