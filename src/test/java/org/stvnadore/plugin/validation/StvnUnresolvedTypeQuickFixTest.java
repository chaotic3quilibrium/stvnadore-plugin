package org.stvnadore.plugin.validation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;

/**
 * Platform test suite verifying Fix 1, Fix 2, Fix 3, and hover notices on network_primitives.stvn_inclf.
 */
public final class StvnUnresolvedTypeQuickFixTest extends BasePlatformTestCase {

    private static final String NETWORK_PRIMITIVES_FIXTURE = """
        {
          // network_primitives.stvn_inclf
          :defs {
            :BitFlag        :Uint1
            :UnixPermission :Uint3
            :Port           { #minIncl 1 #maxIncl 65535 } :Uint16
            :HostName       { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
            :IpAddress      :Union( :IPv4 :StringFixed15 )
            :Protocol       :Enum [ #HTTP #HTTPS #TCP #UDP ]
          }
        }
        """;

    /**
     * Verifies Fix 1 in-place qualification replacing bare :IPv4 with :org/stvnadore/prelude/IPv4.
     */
    public void testFix1QualifiesPreludeTypeInPlace() {
        myFixture.configureByText("network_primitives.stvn_inclf", NETWORK_PRIMITIVES_FIXTURE);
        var caretOffset = NETWORK_PRIMITIVES_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Qualify as ':org/stvnadore/prelude/IPv4'");
        if (actions.isEmpty()) {
            var all = myFixture.getAvailableIntentions().stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList();
            fail("Expected Fix 1 intention. Available intentions at offset " + caretOffset + ": " + all);
        }
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              // network_primitives.stvn_inclf
              :defs {
                :BitFlag        :Uint1
                :UnixPermission :Uint3
                :Port           { #minIncl 1 #maxIncl 65535 } :Uint16
                :HostName       { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :IpAddress      :Union(:org/stvnadore/prelude/IPv4 :StringFixed15 )
                :Protocol       :Enum [ #HTTP #HTTPS #TCP #UDP ]
              }
            }
            """);
    }

    /**
     * Verifies Fix 2 scoped :use import injection into :defs preserving FQNI type identity.
     */
    public void testFix2InjectsScopedUseImportIntoDefs() {
        myFixture.configureByText("network_primitives.stvn_inclf", NETWORK_PRIMITIVES_FIXTURE);
        var caretOffset = NETWORK_PRIMITIVES_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Import ':IPv4' from Prelude via :use (preserves type identity)");
        if (actions.isEmpty()) {
            var all = myFixture.getAvailableIntentions().stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList();
            fail("Expected Fix 2 intention. Available intentions at offset " + caretOffset + ": " + all);
        }
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected :use statement in :defs", text.contains(":use [ :org/stvnadore/prelude { :IPv4 } ]"));
        assertTrue("Expected original :IpAddress to remain unchanged", text.contains(":IpAddress") && text.contains(":Union(:IPv4 :StringFixed15)"));
    }

    /**
     * Verifies Fix 3 nominal branding injection into :defs creating a distinct opaque nominal type.
     */
    public void testFix3InjectsNominalBrandingIntoDefs() {
        myFixture.configureByText("network_primitives.stvn_inclf", NETWORK_PRIMITIVES_FIXTURE);
        var caretOffset = NETWORK_PRIMITIVES_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Brand new nominal type ':IPv4' from Prelude (creates distinct type)");
        if (actions.isEmpty()) {
            var all = myFixture.getAvailableIntentions().stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList();
            fail("Expected Fix 3 intention. Available intentions at offset " + caretOffset + ": " + all);
        }
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Expected :IPv4 nominal branding definition in :defs", text.contains(":IPv4 :org/stvnadore/prelude/IPv4"));
    }

    /**
     * Verifies hover documentation displays unqualified reference warning and underlying structure.
     */
    public void testHoverDisplaysUnqualifiedReferenceWarning() {
        myFixture.configureByText("network_primitives.stvn_inclf", NETWORK_PRIMITIVES_FIXTURE);
        var offset = myFixture.getFile().getText().indexOf(":IPv4");
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);

        var provider = new StvnDocumentationProvider();
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull(docElem);

        var doc = provider.generateDoc(docElem, elem);
        assertNotNull(doc);
        assertTrue("Doc must contain Unqualified Reference Warning",
            doc.contains("Unqualified reference to Standard Library Prelude type '<code>:org/stvnadore/prelude/IPv4</code>'"));
        assertTrue("Doc must include underlying prelude structure", doc.contains("<b>Underlying Structure:</b>"));
    }
}
