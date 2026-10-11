package org.stvnadore.plugin.reference;

import com.intellij.pom.Navigatable;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.psi.TypeDefinition;

/**
 * Platform unit test suite verifying standard library prelude navigation (Ctrl+Click)
 * and Find Usages symbol discovery.
 */
@NullMarked
public final class StvnPreludeNavigationTest extends BasePlatformTestCase {

    /**
     * Verifies that within prelude.stvn_d, references resolve locally and Find Usages discovers usages.
     */
    public void testPreludeLocalNavigationAndFindUsages() {
        var file = myFixture.configureByText("prelude.stvn_d", StvnPrelude.PRELUDE_STVN_D);
        myFixture.doHighlighting();

        var text = file.getText();
        var posixIdDefIdx = text.indexOf(":PosixId {");
        assertTrue("Must locate :PosixId declaration in prelude.stvn_d", posixIdDefIdx >= 0);

        // Find the definition TypeKeyword node
        myFixture.getEditor().getCaretModel().moveToOffset(posixIdDefIdx + 1);
        var defElement = myFixture.getElementAtCaret();
        assertNotNull("Declaration element at caret must not be null", defElement);

        // Locate usage of :PosixId inside :StvnPosixId64 definition
        var posixIdUsageIdx = text.indexOf(":PosixId", posixIdDefIdx + ":PosixId".length());
        assertTrue("Must locate :PosixId usage site inside :StvnPosixId64", posixIdUsageIdx >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(posixIdUsageIdx + 1);

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at :PosixId usage site must exist", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference must resolve locally", resolved);
        assertEquals("Resolved target must belong to active prelude.stvn_d file",
            file, resolved.getContainingFile());
        assertEquals(":PosixId", resolved.getText());

        // Find usages of the :PosixId declaration
        var typeDef = PsiTreeUtil.getParentOfType(defElement, TypeDefinition.class);
        assertNotNull("Parent TypeDefinition must exist for :PosixId", typeDef);
        var usages = myFixture.findUsages(typeDef);
        assertFalse("Find Usages must discover usages of :PosixId in prelude.stvn_d", usages.isEmpty());
    }

    /**
     * Verifies that an external file referencing prelude types navigates to synthetic prelude elements.
     */
    public void testExternalFileResolvesAndNavigatesToPrelude() {
        var content = """
            {
              :defs {
                :MyId :PosixId
              }
            }
            """;
        var file = myFixture.configureByText("external_test.stvn", content);
        myFixture.doHighlighting();

        var text = file.getText();
        var posixIdIdx = text.indexOf(":PosixId");
        assertTrue("Must locate :PosixId reference in external document", posixIdIdx >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(posixIdIdx + 1);

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at :PosixId must exist", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference must resolve to standard library prelude", resolved);
        assertTrue("Resolved target must be Navigatable", resolved instanceof Navigatable);
        var navigatable = (Navigatable) resolved;
        assertTrue("canNavigate() must return true", navigatable.canNavigate());
        assertTrue("canNavigateToSource() must return true", navigatable.canNavigateToSource());
        assertEquals("prelude.stvn_d", resolved.getContainingFile().getName());
    }

    /**
     * Verifies that navigating #name in root :meta resolves to :org/stvnadore/prelude/meta/StvnPosixId64.
     */
    public void testMetaNameNavigatesToPreludePosixId64() {
        var content = """
            {
              :meta {
                #n<caret>ame "test_doc"
                #kind #DEFS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("test_doc.stvn_d", content);
        myFixture.doHighlighting();

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #name must exist", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference at #name must resolve to prelude", resolved);
        assertEquals(":StvnPosixId64", resolved.getText());
        assertEquals("prelude.stvn_d", resolved.getContainingFile().getName());
    }

    /**
     * Verifies that navigating #domain in root :meta resolves to :org/stvnadore/prelude/meta/StvnPosixId64.
     */
    public void testMetaDomainNavigatesToPreludePosixId64() {
        var content = """
            {
              :meta {
                #name "test_doc"
                #dom<caret>ain "test_domain"
                #kind #DEFS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("test_doc.test_domain.stvn_d", content);
        myFixture.doHighlighting();

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #domain must exist", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference at #domain must resolve to prelude", resolved);
        assertEquals(":StvnPosixId64", resolved.getText());
        assertEquals("prelude.stvn_d", resolved.getContainingFile().getName());
    }

    /**
     * Verifies that navigating #kind in root :meta resolves to :org/stvnadore/prelude/meta/StvnDocumentKind.
     */
    public void testMetaKindNavigatesToPreludeDocumentKind() {
        var content = """
            {
              :meta {
                #name "test_doc"
                #ki<caret>nd #DEFS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("test_doc.stvn_d", content);
        myFixture.doHighlighting();

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #kind must exist", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference at #kind must resolve to prelude", resolved);
        assertEquals(":StvnDocumentKind", resolved.getText());
        assertEquals("prelude.stvn_d", resolved.getContainingFile().getName());
    }

    /**
     * Verifies that navigating #DEFS kind value resolves to #DEFS variant in :StvnDocumentKind.
     */
    public void testMetaKindValueNavigatesToPreludeDocumentKindVariant() {
        var content = """
            {
              :meta {
                #name "test_doc"
                #kind #DE<caret>FS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("test_doc.stvn_d", content);
        myFixture.doHighlighting();

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #DEFS must exist", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference at #DEFS must resolve to prelude variant", resolved);
        assertEquals("#DEFS", resolved.getText());
        assertEquals("prelude.stvn_d", resolved.getContainingFile().getName());
    }
}
