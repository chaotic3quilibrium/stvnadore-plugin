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

    public void testCtrlHoverUnderlinePreconditionsAtReferenceSite() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
                :MyId { #regex #POSIX_ID_REGEX } :String
              }
            }
            """;
        var psiFile = myFixture.configureByText("ctrl_hover_test.stvn_d", content);
        int offset = content.indexOf("#POSIX_ID_REGEX");

        // 1. Verify findReference discovers the reference at mouse offset
        var ref = TargetElementUtil.findReference(myFixture.getEditor(), offset + 1);
        assertNotNull("TargetElementUtil.findReference must locate reference at offset", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference must resolve to constant", resolved);

        // 2. Verify findTargetElement with ELEMENT_NAME_ACCEPTED returns null
        // (If this returns non-null, CtrlMouseHandler misidentifies reference site as declaration name and suppresses underline)
        var nameTarget = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.ELEMENT_NAME_ACCEPTED,
            offset + 1
        );
        assertNull("Reference site must NOT be identified as declaration name (required for Ctrl+Hover underline painting)", nameTarget);
    }

    public void testCtrlHoverDeclarationPreconditionsAtDeclarationSite() {
        var content = """
            {
              :defs {
                #LOCAL_PATTERN :String "^[0-9]+$"
              }
            }
            """;
        var psiFile = myFixture.configureByText("ctrl_decl_test.stvn", content);
        int offset = content.indexOf("#LOCAL_PATTERN");

        // Verify findTargetElement with ELEMENT_NAME_ACCEPTED returns declaration name identifier
        var nameTarget = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.ELEMENT_NAME_ACCEPTED,
            offset + 1
        );
        assertNotNull("Declaration site MUST be identified as declaration name to prevent false navigation underline", nameTarget);
        assertTrue("Name target must be ValueKeyword or ConstantDefinition",
            nameTarget instanceof ValueKeyword || nameTarget instanceof ConstantDefinition);
    }

    public void testCtrlQQuickDocOnConstantReference() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
                :MyId { #regex #POSIX_ID_REGEX } :String
              }
            }
            """;
        var psiFile = myFixture.configureByText("quickdoc_test.stvn_d", content);
        int offset = content.indexOf("#POSIX_ID_REGEX");
        var element = psiFile.findElementAt(offset + 1);
        assertNotNull(element);

        var provider = new StvnDocumentationProvider();
        var customElement = provider.getCustomDocumentationElement(
            myFixture.getEditor(),
            psiFile,
            element,
            offset + 1
        );
        assertNotNull("getCustomDocumentationElement must resolve constant reference", customElement);

        var doc = provider.generateDoc(customElement, element);
        assertNotNull("Quick-doc (Ctrl+Q) card must not be null", doc);
        assertTrue("Doc card must contain standard library badge", doc.contains("Standard Library Prelude Constant"));
    }

    public void testCtrlBGotoDeclarationOnConstant() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
                :MyId { #regex #POSIX_ID_REGEX } :String
              }
            }
            """;
        var psiFile = myFixture.configureByText("goto_decl_test.stvn_d", content);
        int offset = content.indexOf("#POSIX_ID_REGEX");
        var element = psiFile.findElementAt(offset + 1);
        assertNotNull(element);

        var handler = new org.stvnadore.plugin.navigation.StvnGotoDeclarationHandler();
        var targets = handler.getGotoDeclarationTargets(element, offset + 1, myFixture.getEditor());
        assertNotNull("Goto declaration targets must not be null", targets);
        assertTrue("Targets must contain at least one element", targets.length > 0);
        assertNotNull("Target element must not be null", targets[0]);
        assertTrue("Target must be ConstantDefinition or ValueKeyword",
            targets[0] instanceof ConstantDefinition || targets[0] instanceof ValueKeyword);
    }

    public void testTypeAndValueTrackParity() {
        var content = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { #strip } ]
                :MyType :PosixId
                :MyConst { #regex #POSIX_ID_REGEX } :String
              }
            }
            """;
        var psiFile = myFixture.configureByText("parity_test.stvn_d", content);
        int typeOffset = content.indexOf(":PosixId");
        int constOffset = content.indexOf("#POSIX_ID_REGEX");

        // 1. Reference discovery parity
        var typeRef = TargetElementUtil.findReference(myFixture.getEditor(), typeOffset + 1);
        var constRef = TargetElementUtil.findReference(myFixture.getEditor(), constOffset + 1);
        assertNotNull("Type track reference must be found", typeRef);
        assertNotNull("Value track reference must be found", constRef);

        // 2. ELEMENT_NAME_ACCEPTED suppression parity (both null at reference sites for Ctrl+Hover)
        var typeNameTarget = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.ELEMENT_NAME_ACCEPTED,
            typeOffset + 1
        );
        var constNameTarget = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.ELEMENT_NAME_ACCEPTED,
            constOffset + 1
        );
        assertNull("Type track reference site must not be declaration name", typeNameTarget);
        assertNull("Value track reference site must not be declaration name", constNameTarget);

        // 3. Hover documentation generation parity
        var typeTarget = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.getInstance().getAllAccepted(),
            typeOffset + 1
        );
        var constTarget = TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            TargetElementUtil.getInstance().getAllAccepted(),
            constOffset + 1
        );
        assertNotNull("Type track hover target must exist", typeTarget);
        assertNotNull("Value track hover target must exist", constTarget);

        var provider = new StvnDocumentationProvider();
        var typeDoc = provider.generateHoverDoc(typeTarget, psiFile.findElementAt(typeOffset + 1));
        var constDoc = provider.generateHoverDoc(constTarget, psiFile.findElementAt(constOffset + 1));
        assertNotNull("Type track hover doc must not be null", typeDoc);
        assertNotNull("Value track hover doc must not be null", constDoc);
    }
}
