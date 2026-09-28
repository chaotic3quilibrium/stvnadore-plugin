package org.stvnadore.plugin.reference;

import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

/**
 * Unit test suite verifying package-relative short-name PSI reference resolution,
 * multi-variant resolution, and quick documentation hover rendering.
 */
@NullMarked
public final class StvnTypeReferenceTest extends BasePlatformTestCase {

    /**
     * Verifies that short type names within a package enclosure resolve to sibling declarations.
     */
    public void testPackageSiblingTypeResolution() {
        var file = myFixture.configureByText("package_test.stvn_inclf", """
            {
              :defs {
                :package :org/ietf/rfc8259/json {
                  :JsonNull :Enum [ #null ]
                  :JsonValue :Union( :JsonNull :String )
                }
              }
            }
            """);

        var keywords = PsiTreeUtil.findChildrenOfType(file, TypeKeyword.class);
        TypeKeyword usageKeyword = null;
        for (var kw : keywords) {
            if (kw.getText().equals(":JsonNull") && !StvnPsiUtils.isTypeDefinitionTarget(kw)) {
                usageKeyword = kw;
                break;
            }
        }
        assertNotNull("Usage site :JsonNull must be present", usageKeyword);

        var reference = usageKeyword.getReference();
        assertNotNull("Reference must be registered on usage keyword", reference);
        assertTrue("Reference must implement PsiPolyVariantReference", reference instanceof PsiPolyVariantReference);

        var polyRef = (PsiPolyVariantReference) reference;
        var results = polyRef.multiResolve(false);
        assertEquals("Must resolve to exactly one target candidate", 1, results.length);

        var resolved = reference.resolve();
        assertNotNull("Reference must resolve within package enclosure", resolved);
        assertTrue("Resolved target must be TypeKeyword", resolved instanceof TypeKeyword);

        var targetDef = StvnPsiUtils.getParentTypeDefinition(resolved);
        assertNotNull("Parent TypeDefinition must exist for target keyword", targetDef);
        assertNotNull("Target definition keyword must not be null", targetDef.getTypeKeyword());
        assertEquals(":JsonNull", targetDef.getTypeKeyword().getText());

        var resolvedViaHelper = StvnTypeResolver.findTypeDefinition(file, ":JsonNull", usageKeyword);
        assertNotNull("StvnTypeResolver.findTypeDefinition must resolve package sibling", resolvedViaHelper);
        assertEquals(targetDef, resolvedViaHelper);
    }

    /**
     * Verifies that hover documentation generates for package-enclosed sibling types.
     */
    public void testPackageSiblingHoverDocumentation() {
        var file = myFixture.configureByText("package_hover.stvn_inclf", """
            {
              :defs {
                :package :org/ietf/rfc8259/json {
                  :JsonNull :Enum [ #null ]
                  :JsonValue :Union( :JsonNull :String )
                }
              }
            }
            """);

        var text = file.getText();
        var secondIndex = text.indexOf(":JsonNull", text.indexOf(":JsonNull") + 1);
        assertTrue("Must locate second occurrence of :JsonNull", secondIndex > 0);

        var leaf = file.findElementAt(secondIndex);
        assertNotNull("Leaf element at caret position must exist", leaf);

        var provider = new StvnDocumentationProvider();
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, secondIndex);
        assertNotNull("Documentation element must not be null", docElem);

        var doc = provider.generateDoc(docElem, leaf);
        assertNotNull("Hover documentation must generate", doc);
        assertTrue("Must contain Type Alias: :JsonNull", doc.contains("<b>Type Alias:</b> :JsonNull"));
        assertTrue("Must contain Enum structure", doc.contains(":Enum [ #null ]"));
    }

    /**
     * Verifies that package-enclosed definitions shadow global definitions with the same name.
     */
    public void testPackageShadowingPrecedenceOverGlobalDefs() {
        var file = myFixture.configureByText("package_shadow.stvn", """
            {
              :defs {
                :Item :String
                :package :scope/local {
                  :Item :Int
                  :Container :Tuple( :Item )
                }
              }
              :type :scope/local/Container
              :body ( 42 )
            }
            """);

        var keywords = PsiTreeUtil.findChildrenOfType(file, TypeKeyword.class);
        TypeKeyword containerItemKw = null;
        for (var kw : keywords) {
            if (kw.getText().equals(":Item") && !StvnPsiUtils.isTypeDefinitionTarget(kw)) {
                containerItemKw = kw;
                break;
            }
        }
        assertNotNull("Usage site :Item must exist", containerItemKw);

        var resolved = containerItemKw.getReference().resolve();
        assertNotNull("Must resolve :Item", resolved);
        assertTrue("Resolved element must be TypeKeyword", resolved instanceof TypeKeyword);

        var typeDef = StvnPsiUtils.getParentTypeDefinition(resolved);
        assertNotNull("Resolved element must belong to a TypeDefinition", typeDef);
        assertNotNull("TypeDefinition schema type must exist", typeDef.getSchemaType());
        assertEquals(":Int", typeDef.getSchemaType().getText().trim());

        var resolvedViaHelper = StvnTypeResolver.findTypeDefinition(file, ":Item", containerItemKw);
        assertNotNull("findTypeDefinition must resolve scoped :Item", resolvedViaHelper);
        assertEquals(typeDef, resolvedViaHelper);
    }
}
