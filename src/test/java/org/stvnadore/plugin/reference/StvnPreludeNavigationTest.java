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
import org.stvnadore.psi.TypeKeyword;

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
        assertTrue("Resolved target must be TypeKeyword", resolved instanceof TypeKeyword);
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
        assertTrue("Resolved target must be TypeKeyword", resolved instanceof TypeKeyword);
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
        assertTrue("Resolved target must be TypeKeyword", resolved instanceof TypeKeyword);
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
        assertTrue("Resolved target must be TypeKeyword", resolvedName instanceof TypeKeyword);
        assertEquals(":StvnPosixId64", resolvedName.getText());
        assertTrue("Resolved target must be Navigatable", resolvedName instanceof Navigatable);
        myFixture.performEditorAction("GotoDeclaration");
        assertEquals("GotoDeclaration on #name must relocate caret to :StvnPosixId64 declaration",
            resolvedName.getTextOffset(), myFixture.getEditor().getCaretModel().getOffset());
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
        assertTrue("Resolved target must be TypeKeyword", resolvedDomain instanceof TypeKeyword);
        assertEquals(":StvnPosixId64", resolvedDomain.getText());
        myFixture.performEditorAction("GotoDeclaration");
        assertEquals("GotoDeclaration on #domain must relocate caret to :StvnPosixId64 declaration",
            resolvedDomain.getTextOffset(), myFixture.getEditor().getCaretModel().getOffset());
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
        assertTrue("Resolved target must be TypeKeyword", resolvedKind instanceof TypeKeyword);
        assertEquals(":StvnDocumentKind", resolvedKind.getText());
        myFixture.performEditorAction("GotoDeclaration");
        assertEquals("GotoDeclaration on #kind must relocate caret to :StvnDocumentKind declaration",
            resolvedKind.getTextOffset(), myFixture.getEditor().getCaretModel().getOffset());
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
        myFixture.performEditorAction("GotoDeclaration");
        assertEquals("GotoDeclaration on #DEFS must relocate caret to #DEFS variant declaration",
            resolvedDefs.getTextOffset(), myFixture.getEditor().getCaretModel().getOffset());
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
        var nameDocNullOrig = docProvider.generateDoc(nameCustom, null);
        assertNotNull("Generated doc for #name with null originalElement must not be null", nameDocNullOrig);
        assertEquals(nameDoc, nameDocNullOrig);

        // 2. Verify #domain hover documentation card
        var domainIdx = text.indexOf("#domain \"stvn_core\"");
        var domainElem = file.findElementAt(domainIdx + 1);
        assertNotNull(domainElem);
        var domainCustom = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, domainElem, domainIdx + 1);
        assertNotNull("Custom doc element for #domain must not be null", domainCustom);
        var domainDoc = docProvider.generateDoc(domainCustom, domainElem);
        assertNotNull("Generated doc for #domain must not be null", domainDoc);
        assertTrue("Doc must reference #domain", domainDoc.contains("#domain"));
        assertTrue("Doc must reference :StvnPosixId64", domainDoc.contains(":StvnPosixId64"));
        var domainDocNullOrig = docProvider.generateDoc(domainCustom, null);
        assertNotNull("Generated doc for #domain with null originalElement must not be null", domainDocNullOrig);
        assertEquals(domainDoc, domainDocNullOrig);

        // 3. Verify #kind hover documentation card
        var kindIdx = text.indexOf("#kind #DEFS");
        var kindElem = file.findElementAt(kindIdx + 1);
        assertNotNull(kindElem);
        var kindCustom = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, kindElem, kindIdx + 1);
        assertNotNull("Custom doc element for #kind must not be null", kindCustom);
        var kindDoc = docProvider.generateDoc(kindCustom, kindElem);
        assertNotNull("Generated doc for #kind must not be null", kindDoc);
        assertTrue("Doc must reference #kind", kindDoc.contains("#kind"));
        assertTrue("Doc must reference :StvnDocumentKind", kindDoc.contains(":StvnDocumentKind"));
        var kindDocNullOrig = docProvider.generateDoc(kindCustom, null);
        assertNotNull("Generated doc for #kind with null originalElement must not be null", kindDocNullOrig);
        assertEquals(kindDoc, kindDocNullOrig);

        // 4. Verify #DEFS kind value hover documentation card
        var defsIdx = text.indexOf("#DEFS", kindIdx);
        var defsElem = file.findElementAt(defsIdx + 1);
        assertNotNull(defsElem);
        var defsCustom = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, defsElem, defsIdx + 1);
        assertNotNull("Custom doc element for #DEFS must not be null", defsCustom);
        var defsDoc = docProvider.generateDoc(defsCustom, defsElem);
        assertNotNull("Generated doc for #DEFS must not be null", defsDoc);
        assertTrue("Doc must display variant name #DEFS", defsDoc.contains("<b>Document Kind Variant:</b> <code>#DEFS</code>"));
        assertTrue("Doc must reference enclosing enum", defsDoc.contains(":StvnDocumentKind"));
        var defsDocNullOrig = docProvider.generateDoc(defsCustom, null);
        assertNotNull("Generated doc for #DEFS with null originalElement must not be null", defsDocNullOrig);
        assertEquals(defsDoc, defsDocNullOrig);

        // 5. Verify getCustomDocumentationElement on leading '#' character token
        var nameHashElem = file.findElementAt(nameIdx);
        assertNotNull(nameHashElem);
        var nameHashCustom = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, nameHashElem, nameIdx);
        assertNotNull("Custom doc element for '#' in #name must not be null", nameHashCustom);
        var nameHashDoc = docProvider.generateDoc(nameHashCustom, nameHashElem);
        assertNotNull("Generated doc for '#' in #name must not be null", nameHashDoc);

        // 6. Verify GotoDeclarationHandler getGotoDeclarationRange
        var gotoHandler = new org.stvnadore.plugin.navigation.StvnGotoDeclarationHandler();
        var nameRange = gotoHandler.getGotoDeclarationRange(nameElem, nameIdx + 1, myFixture.getEditor());
        assertNotNull("Goto declaration range for #name must not be null", nameRange);
        var domainRange = gotoHandler.getGotoDeclarationRange(domainElem, domainIdx + 1, myFixture.getEditor());
        assertNotNull("Goto declaration range for #domain must not be null", domainRange);
        var kindRange = gotoHandler.getGotoDeclarationRange(kindElem, kindIdx + 1, myFixture.getEditor());
        assertNotNull("Goto declaration range for #kind must not be null", kindRange);
        var defsRange = gotoHandler.getGotoDeclarationRange(defsElem, defsIdx + 1, myFixture.getEditor());
        assertNotNull("Goto declaration range for #DEFS must not be null", defsRange);
    }

    /**
     * Verifies that TargetElementUtil and StvnTargetElementEvaluator successfully resolve
     * #name, #domain, #kind, and #DEFS to their declarations in prelude.stvn_core.stvn_d,
     * enabling mouse hover documentation and Ctrl+Hover reference hyperlink underlines.
     */
    public void testPreludeTargetElementEvaluatorIntegration() {
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", StvnPrelude.PRELUDE_STVN_D);
        myFixture.doHighlighting();

        var evaluator = new org.stvnadore.plugin.navigation.StvnTargetElementEvaluator();
        var text = file.getText();

        // 1. Verify #name resolution via evaluator and TargetElementUtil
        var nameIdx = text.indexOf("#name \"prelude\"");
        var targetName = evaluator.getTargetElement(myFixture.getEditor(), nameIdx + 1);
        assertNotNull("Evaluator must resolve target element for #name", targetName);
        assertTrue("Resolved target for #name must be TypeKeyword", targetName instanceof TypeKeyword);
        assertEquals(":StvnPosixId64", targetName.getText());
        assertTrue("Evaluator must accept TypeKeyword as acceptable named parent", evaluator.isAcceptableNamedParent(targetName));
        assertEquals(targetName, evaluator.getNamedElement(targetName));

        myFixture.getEditor().getCaretModel().moveToOffset(nameIdx + 1);
        var nameRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for #name must exist", nameRef);
        assertEquals(targetName, evaluator.getElementByReference(nameRef, 0));

        var targetNameFromUtil = com.intellij.codeInsight.TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            com.intellij.codeInsight.TargetElementUtil.getInstance().getAllAccepted(),
            nameIdx + 1
        );
        assertNotNull("TargetElementUtil.findTargetElement must return declaration for #name", targetNameFromUtil);
        assertEquals(targetName, targetNameFromUtil);

        // 2. Verify #domain resolution via evaluator and TargetElementUtil
        var domainIdx = text.indexOf("#domain \"stvn_core\"");
        var targetDomain = evaluator.getTargetElement(myFixture.getEditor(), domainIdx + 1);
        assertNotNull("Evaluator must resolve target element for #domain", targetDomain);
        assertTrue("Resolved target for #domain must be TypeKeyword", targetDomain instanceof TypeKeyword);
        assertEquals(":StvnPosixId64", targetDomain.getText());

        myFixture.getEditor().getCaretModel().moveToOffset(domainIdx + 1);
        var domainRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for #domain must exist", domainRef);
        assertEquals(targetDomain, evaluator.getElementByReference(domainRef, 0));

        var targetDomainFromUtil = com.intellij.codeInsight.TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            com.intellij.codeInsight.TargetElementUtil.getInstance().getAllAccepted(),
            domainIdx + 1
        );
        assertNotNull("TargetElementUtil.findTargetElement must return declaration for #domain", targetDomainFromUtil);
        assertEquals(targetDomain, targetDomainFromUtil);

        // 3. Verify #kind resolution via evaluator and TargetElementUtil
        var kindIdx = text.indexOf("#kind #DEFS");
        var targetKind = evaluator.getTargetElement(myFixture.getEditor(), kindIdx + 1);
        assertNotNull("Evaluator must resolve target element for #kind", targetKind);
        assertTrue("Resolved target for #kind must be TypeKeyword", targetKind instanceof TypeKeyword);
        assertEquals(":StvnDocumentKind", targetKind.getText());

        myFixture.getEditor().getCaretModel().moveToOffset(kindIdx + 1);
        var kindRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for #kind must exist", kindRef);
        assertEquals(targetKind, evaluator.getElementByReference(kindRef, 0));

        var targetKindFromUtil = com.intellij.codeInsight.TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            com.intellij.codeInsight.TargetElementUtil.getInstance().getAllAccepted(),
            kindIdx + 1
        );
        assertNotNull("TargetElementUtil.findTargetElement must return declaration for #kind", targetKindFromUtil);
        assertEquals(targetKind, targetKindFromUtil);

        // 4. Verify #DEFS resolution via evaluator and TargetElementUtil
        var defsIdx = text.indexOf("#DEFS", kindIdx);
        var targetDefs = evaluator.getTargetElement(myFixture.getEditor(), defsIdx + 1);
        assertNotNull("Evaluator must resolve target element for #DEFS", targetDefs);
        assertTrue("Resolved target for #DEFS must be ValueKeyword", targetDefs instanceof org.stvnadore.psi.ValueKeyword);
        assertEquals("#DEFS", targetDefs.getText());
        assertTrue("Evaluator must accept ValueKeyword as acceptable named parent", evaluator.isAcceptableNamedParent(targetDefs));
        assertEquals(targetDefs, evaluator.getNamedElement(targetDefs));

        myFixture.getEditor().getCaretModel().moveToOffset(defsIdx + 1);
        var defsRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for #DEFS must exist", defsRef);
        assertEquals(targetDefs, evaluator.getElementByReference(defsRef, 0));

        var targetDefsFromUtil = com.intellij.codeInsight.TargetElementUtil.getInstance().findTargetElement(
            myFixture.getEditor(),
            com.intellij.codeInsight.TargetElementUtil.getInstance().getAllAccepted(),
            defsIdx + 1
        );
        assertNotNull("TargetElementUtil.findTargetElement must return declaration for #DEFS", targetDefsFromUtil);
        assertEquals(targetDefs, targetDefsFromUtil);

        // 5. Verify plain hover documentation generation via resolved targets and original elements
        var docProvider = new StvnDocumentationProvider();
        var hoverDocName = docProvider.generateDoc(targetNameFromUtil, file.findElementAt(nameIdx + 1));
        assertNotNull("Hover doc for #name must not be null", hoverDocName);
        assertTrue("Hover doc must contain #name facet header", hoverDocName.contains("<b>Root Metadata Facet:</b> <code>#name</code>"));

        var hoverDocDomain = docProvider.generateDoc(targetDomainFromUtil, file.findElementAt(domainIdx + 1));
        assertNotNull("Hover doc for #domain must not be null", hoverDocDomain);
        assertTrue("Hover doc must contain #domain facet header", hoverDocDomain.contains("<b>Root Metadata Facet:</b> <code>#domain</code>"));

        var hoverDocKind = docProvider.generateDoc(targetKindFromUtil, file.findElementAt(kindIdx + 1));
        assertNotNull("Hover doc for #kind must not be null", hoverDocKind);
        assertTrue("Hover doc must contain #kind facet header", hoverDocKind.contains("<b>Root Metadata Facet:</b> <code>#kind</code>"));

        var hoverDocDefs = docProvider.generateDoc(targetDefsFromUtil, file.findElementAt(defsIdx + 1));
        assertNotNull("Hover doc for #DEFS must not be null", hoverDocDefs);
        assertTrue("Hover doc must contain #DEFS variant header", hoverDocDefs.contains("<b>Document Kind Variant:</b> <code>#DEFS</code>"));
    }
}
