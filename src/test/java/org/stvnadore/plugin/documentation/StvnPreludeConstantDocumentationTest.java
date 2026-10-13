package org.stvnadore.plugin.documentation;

import com.intellij.codeInsight.TargetElementUtil;
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
        var element = psiFile.findElementAt(offset + 1);
        assertNotNull("Element at offset must exist", element);

        // 1. Verify TargetElementUtil discovers documentable target element (prerequisite for hover popup)
        var targetElement = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.getInstance().getAllAccepted(),
            offset + 1
        );
        assertNotNull("TargetElementUtil must find target element for hover popup", targetElement);
        assertTrue("Target must be ConstantDefinition or ValueKeyword",
            targetElement instanceof ConstantDefinition || (targetElement instanceof ValueKeyword && targetElement.getParent() instanceof ConstantDefinition));

        // 2. Verify DocumentationProvider generates rich card via generateHoverDoc
        var provider = new StvnDocumentationProvider();
        var hoverDoc = provider.generateHoverDoc(targetElement, element);
        assertNotNull("Hover documentation must not be null for prelude constant", hoverDoc);
        assertTrue("Must contain prelude constant badge", hoverDoc.contains("Standard Library Prelude Constant"));
        assertTrue("Must contain FQNI", hoverDoc.contains(":org/stvnadore/prelude/#POSIX_ID_REGEX"));
        assertTrue("Must contain pattern value", hoverDoc.contains("^[a-zA-Z0-9_-]+$"));
    }

    public void testHoverOnConstantDeclarationSiteDisplaysDocumentationCard() {
        var content = """
            {
              :defs {
                #LOCAL_PATTERN :String "^[0-9]+$"
              }
            }
            """;
        var psiFile = myFixture.configureByText("decl_test.stvn", content);
        int offset = content.indexOf("#LOCAL_PATTERN");
        var element = psiFile.findElementAt(offset + 1);
        assertNotNull(element);

        // Verify TargetElementUtil discovers declaration target
        var targetElement = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.getInstance().getAllAccepted(),
            offset + 1
        );
        assertNotNull("TargetElementUtil must find declaration target for hover popup", targetElement);
        assertTrue("Target must be ConstantDefinition or ValueKeyword",
            targetElement instanceof ConstantDefinition || (targetElement instanceof ValueKeyword && targetElement.getParent() instanceof ConstantDefinition));

        var provider = new StvnDocumentationProvider();
        var hoverDoc = provider.generateHoverDoc(targetElement, element);
        assertNotNull("Hover doc on constant declaration must not be null", hoverDoc);
        assertTrue("Must contain typed constant badge", hoverDoc.contains("Typed Constant:"));
        assertTrue("Must contain type String", hoverDoc.contains(":String"));
        assertTrue("Must contain value pattern", hoverDoc.contains("^[0-9]+$"));
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
