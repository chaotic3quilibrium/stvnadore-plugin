package org.stvnadore.plugin.structure;

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;

/**
 * Tests the Structure View model and element tree hierarchy for STVN documents.
 */
@NullMarked
public final class StvnStructureViewTest extends BasePlatformTestCase {

    public void testStructureViewTreeHierarchyAndMetadataPreservation() {
        var content = """
            {
              :defs {
                :State :Either( :Float { #size 32 } :Int )
                #PORT { #unsigned #size 16 } :Int 8080
              }
              :type :State
              :body #Left 3.14
            }
            """;
        var psiFile = (StvnFile) myFixture.configureByText("test.stvn", content);
        var factory = new StvnStructureViewFactory();
        var builder = factory.getStructureViewBuilder(psiFile);
        assertNotNull("StructureViewBuilder must not be null", builder);

        var treeBuilder = (TreeBasedStructureViewBuilder) builder;
        var model = treeBuilder.createStructureViewModel(myFixture.getEditor());

        var root = model.getRoot();
        assertEquals(psiFile, root.getValue());

        var children = root.getChildren();
        assertEquals("Root must contain :defs, :type, and :body", 3, children.length);

        var defsElem = children[0];
        assertEquals(":defs", defsElem.getPresentation().getPresentableText());

        var typeElem = children[1];
        assertEquals(":type", typeElem.getPresentation().getPresentableText());
        assertEquals(":State", typeElem.getPresentation().getLocationString());

        var bodyElem = children[2];
        assertEquals(":body", bodyElem.getPresentation().getPresentableText());

        var defsChildren = defsElem.getChildren();
        assertEquals("Defs section must contain 2 definitions", 2, defsChildren.length);

        var typeDefElem = defsChildren[0];
        assertEquals(":State", typeDefElem.getPresentation().getPresentableText());
        assertEquals(":Either( :Float { #size 32 } :Int )", typeDefElem.getPresentation().getLocationString());

        var constDefElem = defsChildren[1];
        assertEquals("#PORT", constDefElem.getPresentation().getPresentableText());
        assertEquals("{ #unsigned #size 16 } :Int", constDefElem.getPresentation().getLocationString());
    }
}
