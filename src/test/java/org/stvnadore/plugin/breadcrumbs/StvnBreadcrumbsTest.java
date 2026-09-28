package org.stvnadore.plugin.breadcrumbs;

import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.psi.*;

/**
 * Tests the breadcrumb info provider for STVN elements.
 */
@NullMarked
public final class StvnBreadcrumbsTest extends BasePlatformTestCase {

    public void testBreadcrumbsInfoAndTruncation() {
        var content = """
            {
              :defs {
                :State :Either( :Float { #size 32 } :Int )
                #PORT { #unsigned #size 16 } :Int 8080
                :VeryLongType :Map( :String :Tuple( :Int :Either( :Float { #size 32 } :Int ) ) )
              }
              :type :State
              :body #Left 3.14
            }
            """;
        var psiFile = (StvnFile) myFixture.configureByText("test.stvn", content);
        var provider = new StvnBreadcrumbsInfoProvider();

        var typeDefs = PsiTreeUtil.findChildrenOfType(psiFile, TypeDefinition.class).stream().toList();
        assertEquals(2, typeDefs.size());

        var stateDef = typeDefs.get(0);
        assertTrue(provider.acceptElement(stateDef));
        assertEquals(":State -> :Either( :Float { #size 32 } :Int )", provider.getElementInfo(stateDef));
        assertEquals("Type Definition: :Either( :Float { #size 32 } :Int )", provider.getElementTooltip(stateDef));

        var constDefs = PsiTreeUtil.findChildrenOfType(psiFile, ConstantDefinition.class).stream().toList();
        assertEquals(1, constDefs.size());

        var portDef = constDefs.get(0);
        assertTrue(provider.acceptElement(portDef));
        assertEquals("#PORT : { #unsigned #size 16 } :Int", provider.getElementInfo(portDef));
        assertEquals("Constant: { #unsigned #size 16 } :Int", provider.getElementTooltip(portDef));

        var longDef = typeDefs.get(1);
        var longInfo = provider.getElementInfo(longDef);
        assertTrue("Long crumb info must be truncated with ellipsis: " + longInfo, longInfo.endsWith("..."));
        assertTrue("Truncated length must not exceed 48 chars", longInfo.length() <= 48);

        var typeEntry = PsiTreeUtil.findChildOfType(psiFile, TypeEntry.class);
        assertNotNull(typeEntry);
        assertTrue(provider.acceptElement(typeEntry));
        assertEquals(":type :State", provider.getElementInfo(typeEntry));

        var defsEntry = PsiTreeUtil.findChildOfType(psiFile, DefsEntry.class);
        assertNotNull(defsEntry);
        assertTrue(provider.acceptElement(defsEntry));
        assertEquals(":defs", provider.getElementInfo(defsEntry));

        var bodyEntry = PsiTreeUtil.findChildOfType(psiFile, BodyEntry.class);
        assertNotNull(bodyEntry);
        assertTrue(provider.acceptElement(bodyEntry));
        assertEquals(":body", provider.getElementInfo(bodyEntry));
    }
}
