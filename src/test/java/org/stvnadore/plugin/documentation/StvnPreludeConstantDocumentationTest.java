package org.stvnadore.plugin.documentation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ValueKeyword;

import java.util.HashSet;

public final class StvnPreludeConstantDocumentationTest extends BasePlatformTestCase {

    public void testHoverOnPosixIdRegexDisplaysPreludeDocumentationCard() {
        var content = """
            {
              :meta {
                #name "consumer"
                #kind #DEFS
              }
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
                :MyId { #regex #POSIX_ID_REGEX } :String
              }
            }
            """;
        var psiFile = myFixture.configureByText("consumer.stvn_d", content);
        int offset = content.indexOf("#POSIX_ID_REGEX");
        var element = psiFile.findElementAt(offset);
        assertNotNull(element);

        var provider = new StvnDocumentationProvider();
        var doc = provider.generateDoc(element, element);
        assertNotNull("Hover documentation must not be null for prelude constant", doc);
        assertTrue("Must contain prelude constant badge", doc.contains("Standard Library Prelude Constant"));
        assertTrue("Must contain FQNI", doc.contains(":org/stvnadore/prelude/#POSIX_ID_REGEX"));
        assertTrue("Must contain pattern value", doc.contains("^[a-zA-Z0-9_-]+$"));
    }

    public void testResolveUnqualifiedPreludeConstantFallback() {
        var content = """
            {
              :defs {
                :MyType { #regex #EMAIL_REGEX } :String
              }
            }
            """;
        var psiFile = myFixture.configureByText("test_email.stvn_d", content);
        var resolved = StvnConstantReference.resolveConstantInFile(psiFile, "#EMAIL_REGEX", new HashSet<>());
        assertNotNull("Unqualified prelude constant must resolve via bridge fallback", resolved);
        assertTrue("Resolved element must be ValueKeyword in ConstantDefinition",
            resolved instanceof ValueKeyword && resolved.getParent() instanceof ConstantDefinition);
    }
}
