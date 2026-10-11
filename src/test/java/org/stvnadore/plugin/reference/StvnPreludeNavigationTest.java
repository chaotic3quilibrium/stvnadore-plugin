package org.stvnadore.plugin.reference;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.pom.Navigatable;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.navigation.StvnNavigationUtil;
import org.stvnadore.psi.TypeDefinition;

/**
 * Platform unit test suite verifying standard library prelude navigation (Ctrl+Click)
 * and Find Usages symbol discovery.
 */
@NullMarked
public final class StvnPreludeNavigationTest extends BasePlatformTestCase {

    /**
     * Verifies that within prelude.stvn_core.stvn_d, references resolve locally and Find Usages discovers usages.
     */
    public void testPreludeLocalNavigationAndFindUsages() {
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", StvnPrelude.PRELUDE_STVN_D);
        myFixture.doHighlighting();

        var text = file.getText();
        var defIdx = text.indexOf(":StvnPosixId64 {");
        assertTrue("Must locate :StvnPosixId64 declaration in prelude.stvn_core.stvn_d", defIdx >= 0);

        // Find the definition TypeKeyword node
        myFixture.getEditor().getCaretModel().moveToOffset(defIdx + 1);
        var defElement = myFixture.getElementAtCaret();
        assertNotNull("Declaration element at caret must not be null", defElement);

        // Locate usage of :StvnPosixId64 inside :StvnDocumentMeta definition
        var usageIdx = text.indexOf(":StvnPosixId64", defIdx + ":StvnPosixId64".length());
        assertTrue("Must locate :StvnPosixId64 usage site inside :StvnDocumentMeta", usageIdx >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(usageIdx + 1);

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at :StvnPosixId64 usage site must exist", ref);
        var resolved = ref.resolve();
        assertNotNull("Reference must resolve locally", resolved);
        assertEquals("Resolved target must belong to active prelude.stvn_core.stvn_d file",
            file, resolved.getContainingFile());
        assertEquals(":StvnPosixId64", resolved.getText());

        // Find usages of the :StvnPosixId64 declaration
        var typeDef = PsiTreeUtil.getParentOfType(defElement, TypeDefinition.class);
        assertNotNull("Parent TypeDefinition must exist for :StvnPosixId64", typeDef);
        var usages = myFixture.findUsages(typeDef);
        assertFalse("Find Usages must discover usages of :StvnPosixId64 in prelude.stvn_core.stvn_d", usages.isEmpty());
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
        assertEquals("prelude.stvn_core.stvn_d", resolved.getContainingFile().getName());
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
        assertEquals("prelude.stvn_core.stvn_d", resolved.getContainingFile().getName());
        assertTrue("Resolved target must be Navigatable", resolved instanceof Navigatable);
        var navigatable = (Navigatable) resolved;
        assertTrue("canNavigate() must return true", navigatable.canNavigate());
        assertTrue("canNavigateToSource() must return true", navigatable.canNavigateToSource());
        var descriptor = StvnNavigationUtil.getDescriptor(resolved);
        assertNotNull("Navigation descriptor must not be null", descriptor);
        assertEquals("prelude.stvn_core.stvn_d", descriptor.getFile().getName());
        assertEquals(resolved.getTextOffset(), descriptor.getOffset());
        navigatable.navigate(true);
        var activeEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull("Active editor must not be null after navigation dispatch", activeEditor);
        assertEquals("prelude.stvn_core.stvn_d", activeEditor.getVirtualFile().getName());
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
        assertEquals("prelude.stvn_core.stvn_d", resolved.getContainingFile().getName());
        assertTrue("Resolved target must be Navigatable", resolved instanceof Navigatable);
        var navigatable = (Navigatable) resolved;
        assertTrue("canNavigate() must return true", navigatable.canNavigate());
        assertTrue("canNavigateToSource() must return true", navigatable.canNavigateToSource());
        var descriptor = StvnNavigationUtil.getDescriptor(resolved);
        assertNotNull("Navigation descriptor must not be null", descriptor);
        assertEquals("prelude.stvn_core.stvn_d", descriptor.getFile().getName());
        assertEquals(resolved.getTextOffset(), descriptor.getOffset());
        navigatable.navigate(true);
        var activeEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull("Active editor must not be null after navigation dispatch", activeEditor);
        assertEquals("prelude.stvn_core.stvn_d", activeEditor.getVirtualFile().getName());
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
        assertEquals("prelude.stvn_core.stvn_d", resolved.getContainingFile().getName());
        assertTrue("Resolved target must be Navigatable", resolved instanceof Navigatable);
        var navigatable = (Navigatable) resolved;
        assertTrue("canNavigate() must return true", navigatable.canNavigate());
        assertTrue("canNavigateToSource() must return true", navigatable.canNavigateToSource());
        var descriptor = StvnNavigationUtil.getDescriptor(resolved);
        assertNotNull("Navigation descriptor must not be null", descriptor);
        assertEquals("prelude.stvn_core.stvn_d", descriptor.getFile().getName());
        assertEquals(resolved.getTextOffset(), descriptor.getOffset());
        navigatable.navigate(true);
        var activeEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull("Active editor must not be null after navigation dispatch", activeEditor);
        assertEquals("prelude.stvn_core.stvn_d", activeEditor.getVirtualFile().getName());
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
        assertEquals("prelude.stvn_core.stvn_d", resolved.getContainingFile().getName());
        assertTrue("Resolved target must be Navigatable", resolved instanceof Navigatable);
        var navigatable = (Navigatable) resolved;
        assertTrue("canNavigate() must return true", navigatable.canNavigate());
        assertTrue("canNavigateToSource() must return true", navigatable.canNavigateToSource());
        var descriptor = StvnNavigationUtil.getDescriptor(resolved);
        assertNotNull("Navigation descriptor must not be null", descriptor);
        assertEquals("prelude.stvn_core.stvn_d", descriptor.getFile().getName());
        assertEquals(resolved.getTextOffset(), descriptor.getOffset());
        navigatable.navigate(true);
        var activeEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull("Active editor must not be null after navigation dispatch", activeEditor);
        assertEquals("prelude.stvn_core.stvn_d", activeEditor.getVirtualFile().getName());
        assertEquals(resolved.getTextOffset(), activeEditor.getCaretModel().getOffset());
    }

    /**
     * Verifies end-to-end navigation dispatch across all kind variants (#BODY, #BODY_INCLUDE, #DEFS_INCLUDE).
     */
    public void testMetaAllKindVariantsNavigateToPreludeEnumVariants() {
        String[] variants = {"#BODY", "#BODY_INCLUDE", "#DEFS_INCLUDE"};
        for (var variant : variants) {
            var content = "{\n  :meta {\n    #name \"test_doc\"\n    #kind " + variant + "\n  }\n  :defs {}\n}\n";
            myFixture.configureByText("test_" + variant.substring(1).toLowerCase() + ".stvn_d", content);
            myFixture.doHighlighting();

            var offset = content.indexOf(variant);
            myFixture.getEditor().getCaretModel().moveToOffset(offset + 1);
            var ref = myFixture.getReferenceAtCaretPosition();
            assertNotNull("Reference at " + variant + " must exist", ref);
            var resolved = ref.resolve();
            assertNotNull("Reference at " + variant + " must resolve to prelude variant", resolved);
            assertEquals(variant, resolved.getText());
            assertTrue("Resolved target must be Navigatable", resolved instanceof Navigatable);
            var navigatable = (Navigatable) resolved;
            assertTrue("canNavigate() must return true for " + variant, navigatable.canNavigate());
            var descriptor = StvnNavigationUtil.getDescriptor(resolved);
            assertNotNull("Descriptor for " + variant + " must not be null", descriptor);
            navigatable.navigate(true);
            var activeEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
            assertNotNull("Active editor must not be null", activeEditor);
            assertEquals("prelude.stvn_core.stvn_d", activeEditor.getVirtualFile().getName());
            assertEquals(resolved.getTextOffset(), activeEditor.getCaretModel().getOffset());
        }
    }

    /**
     * Verifies that within prelude.stvn_core.stvn_d itself, interacting with #name, #domain,
     * #kind, and #DEFS resolves to local declarations and navigates the caret within the active editor.
     */
    public void testPreludeSelfReferentialMetadataNavigation() {
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", StvnPrelude.PRELUDE_STVN_D);
        myFixture.doHighlighting();

        var text = file.getText();

        // 1. Test #name -> :StvnPosixId64 navigation within prelude
        var nameIdx = text.indexOf("#name \"prelude\"");
        assertTrue("Must locate #name in prelude", nameIdx >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(nameIdx + 1);
        var nameRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #name must not be null", nameRef);
        var resolvedName = nameRef.resolve();
        assertNotNull("Resolved element for #name must not be null", resolvedName);
        assertEquals("Resolved target must belong to active prelude file", file, resolvedName.getContainingFile());
        assertEquals(":StvnPosixId64", resolvedName.getText());
        assertTrue("Resolved target must be Navigatable", resolvedName instanceof Navigatable);
        ((Navigatable) resolvedName).navigate(true);
        var activeEditor = FileEditorManager.getInstance(getProject()).getSelectedTextEditor();
        assertNotNull("Active editor must not be null", activeEditor);
        assertEquals("prelude.stvn_core.stvn_d", activeEditor.getVirtualFile().getName());
        assertEquals(resolvedName.getTextOffset(), activeEditor.getCaretModel().getOffset());

        // 2. Test #domain -> :StvnPosixId64 navigation within prelude
        var domainIdx = text.indexOf("#domain \"stvn_core\"");
        assertTrue("Must locate #domain in prelude", domainIdx >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(domainIdx + 1);
        var domainRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #domain must not be null", domainRef);
        var resolvedDomain = domainRef.resolve();
        assertNotNull("Resolved element for #domain must not be null", resolvedDomain);
        assertEquals("Resolved target must belong to active prelude file", file, resolvedDomain.getContainingFile());
        assertEquals(":StvnPosixId64", resolvedDomain.getText());
        ((Navigatable) resolvedDomain).navigate(true);
        assertEquals(resolvedDomain.getTextOffset(), activeEditor.getCaretModel().getOffset());

        // 3. Test #kind -> :StvnDocumentKind navigation within prelude
        var kindIdx = text.indexOf("#kind #DEFS");
        assertTrue("Must locate #kind in prelude", kindIdx >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(kindIdx + 1);
        var kindRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #kind must not be null", kindRef);
        var resolvedKind = kindRef.resolve();
        assertNotNull("Resolved element for #kind must not be null", resolvedKind);
        assertEquals("Resolved target must belong to active prelude file", file, resolvedKind.getContainingFile());
        assertEquals(":StvnDocumentKind", resolvedKind.getText());
        ((Navigatable) resolvedKind).navigate(true);
        assertEquals(resolvedKind.getTextOffset(), activeEditor.getCaretModel().getOffset());

        // 4. Test #DEFS -> enum variant navigation within prelude
        var defsIdx = text.indexOf("#DEFS", kindIdx);
        assertTrue("Must locate #DEFS in prelude", defsIdx >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(defsIdx + 1);
        var defsRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference at #DEFS must not be null", defsRef);
        var resolvedDefs = defsRef.resolve();
        assertNotNull("Resolved element for #DEFS must not be null", resolvedDefs);
        assertEquals("Resolved target must belong to active prelude file", file, resolvedDefs.getContainingFile());
        assertEquals("#DEFS", resolvedDefs.getText());
        assertTrue("Target must be inside :StvnDocumentKind enum definition",
            resolvedDefs.getParent() instanceof org.stvnadore.psi.EnumDef);
        ((Navigatable) resolvedDefs).navigate(true);
        assertEquals(resolvedDefs.getTextOffset(), activeEditor.getCaretModel().getOffset());
    }

    /**
     * Verifies that within prelude.stvn_core.stvn_d itself, hovering over #name, #domain,
     * #kind, and #DEFS generates complete Quick Documentation cards.
     */
    public void testPreludeSelfReferentialMetadataHoverDocumentation() {
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", StvnPrelude.PRELUDE_STVN_D);
        myFixture.doHighlighting();

        var docProvider = new StvnDocumentationProvider();
        var text = file.getText();

        // 1. Verify #name hover documentation card
        var nameIdx = text.indexOf("#name \"prelude\"");
        var nameElem = file.findElementAt(nameIdx + 1);
        assertNotNull(nameElem);
        var nameCustom = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, nameElem, nameIdx + 1);
        assertNotNull("Custom doc element for #name must not be null", nameCustom);
        var nameDoc = docProvider.generateDoc(nameCustom, nameElem);
        assertNotNull("Generated doc for #name must not be null", nameDoc);
        assertTrue("Doc must reference #name", nameDoc.contains("#name"));
        assertTrue("Doc must reference :StvnPosixId64", nameDoc.contains(":StvnPosixId64"));

        // 2. Verify #kind hover documentation card
        var kindIdx = text.indexOf("#kind #DEFS");
        var kindElem = file.findElementAt(kindIdx + 1);
        assertNotNull(kindElem);
        var kindCustom = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, kindElem, kindIdx + 1);
        assertNotNull("Custom doc element for #kind must not be null", kindCustom);
        var kindDoc = docProvider.generateDoc(kindCustom, kindElem);
        assertNotNull("Generated doc for #kind must not be null", kindDoc);
        assertTrue("Doc must reference #kind", kindDoc.contains("#kind"));
        assertTrue("Doc must reference :StvnDocumentKind", kindDoc.contains(":StvnDocumentKind"));

        // 3. Verify #DEFS kind value hover documentation card
        var defsIdx = text.indexOf("#DEFS", kindIdx);
        var defsElem = file.findElementAt(defsIdx + 1);
        assertNotNull(defsElem);
        var defsCustom = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, defsElem, defsIdx + 1);
        assertNotNull("Custom doc element for #DEFS must not be null", defsCustom);
        var defsDoc = docProvider.generateDoc(defsCustom, defsElem);
        assertNotNull("Generated doc for #DEFS must not be null", defsDoc);
        assertTrue("Doc must display variant name #DEFS", defsDoc.contains("<b>Document Kind Variant:</b> <code>#DEFS</code>"));
        assertTrue("Doc must reference enclosing enum", defsDoc.contains(":StvnDocumentKind"));
    }
}
