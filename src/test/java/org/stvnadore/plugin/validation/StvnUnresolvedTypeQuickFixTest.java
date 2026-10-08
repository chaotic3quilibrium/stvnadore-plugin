package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
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

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnUnresolvedTypeInspection());
    }

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
        assertTrue("Expected :use statement in :defs", text.contains(":use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]"));
        assertTrue("Expected original :IpAddress to remain unchanged", text.contains(":IpAddress") && text.contains(":Union(:IPv4 :StringFixed15)"));
    }

    /**
     * Verifies that re-highlighting the document after applying Fix 2 produces zero syntax errors.
     */
    public void testFix2ProducesZeroSyntaxErrorsAfterExecution() {
        myFixture.configureByText("network_primitives.stvn_inclf", NETWORK_PRIMITIVES_FIXTURE);
        var caretOffset = NETWORK_PRIMITIVES_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Import ':IPv4' from Prelude via :use (preserves type identity)");
        assertFalse("Expected Fix 2 intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var errorElements = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiErrorElement.class);
        assertTrue("AST must contain zero PsiErrorElement nodes after Fix 2. Found: " + errorElements, errorElements.isEmpty());

        var highlights = myFixture.doHighlighting();
        var syntaxErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("expected") || h.getDescription().contains("Syntax error") || h.getDescription().contains(":use")))
            .toList();
        assertTrue("Re-highlighting document after Fix 2 must produce zero syntax errors. Found: " + syntaxErrors, syntaxErrors.isEmpty());
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
     * Verifies that after applying Fix 3 (:IPv4 :org/stvnadore/prelude/IPv4), zero string capacity warnings are emitted.
     */
    public void testFix3ProducesZeroStringCapacityWarnings() {
        myFixture.enableInspections(new StvnUnresolvedTypeInspection(), new StvnStringCapacityInspection());
        myFixture.configureByText("network_primitives.stvn_inclf", NETWORK_PRIMITIVES_FIXTURE);
        var caretOffset = NETWORK_PRIMITIVES_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Brand new nominal type ':IPv4' from Prelude (creates distinct type)");
        assertFalse("Expected Fix 3 intention", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var highlights = myFixture.doHighlighting();
        var capacityWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();
        assertTrue("Applying Fix 3 must produce zero string capacity warnings because prelude IPv4 specifies #maxSize 15. Found: " + capacityWarnings,
            capacityWarnings.isEmpty());
    }

    /**
     * Verifies the canonical intention priority sequence (Qualify -> Import -> Brand).
     */
    public void testCanonicalOrderOfUnresolvedTypeIntentions() {
        myFixture.configureByText("network_primitives.stvn_inclf", NETWORK_PRIMITIVES_FIXTURE);
        var caretOffset = NETWORK_PRIMITIVES_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var matchingIntentions = myFixture.getAvailableIntentions().stream()
                .map(com.intellij.codeInsight.intention.IntentionAction::getText)
                .filter(text -> text.contains("IPv4"))
                .toList();

        assertFalse("Intentions matching IPv4 must not be empty", matchingIntentions.isEmpty());
        assertEquals("Exactly 3 quick-fix intentions must be available for unresolved :IPv4", 3, matchingIntentions.size());

        assertEquals("Index 0 must be Qualify fix",
                "Qualify as ':org/stvnadore/prelude/IPv4'",
                matchingIntentions.get(0));

        assertEquals("Index 1 must be Import fix",
                "Import ':IPv4' from Prelude via :use (preserves type identity)",
                matchingIntentions.get(1));

        assertEquals("Index 2 must be Brand fix",
                "Brand new nominal type ':IPv4' from Prelude (creates distinct type)",
                matchingIntentions.get(2));
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

    /**
     * Verifies Fix 2 on a document completely lacking :defs (e.g. nonempty_torture.stvn pattern).
     * Proves that :defs is synthesized before :type and the :use statement is inserted.
     */
    public void testFix2SynthesizesDefsBlockWhenDefsIsAbsent() {
        var content = """
            {
              :type :IPv4
              :body "127.0.0.1"
            }
            """;
        myFixture.configureByText("no_defs_fix2.stvn", content);
        var caretOffset = content.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Import ':IPv4' from Prelude via :use (preserves type identity)");
        assertFalse("Fix 2 intention must be available on document lacking :defs", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var resultText = myFixture.getFile().getText();
        assertTrue("Must synthesize :defs block", resultText.contains(":defs {"));
        assertTrue("Must insert :use statement into synthesized :defs",
            resultText.contains(":use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]"));

        // Verify spatial ordering: :defs must precede :type
        int defsPos = resultText.indexOf(":defs");
        int typePos = resultText.indexOf(":type");
        assertTrue("Synthesized :defs block must be positioned before :type", defsPos < typePos);

        // Verify 0 syntax errors
        var errors = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiErrorElement.class);
        assertTrue("Must produce zero PsiErrorElement nodes. Found: " + errors, errors.isEmpty());
    }

    /**
     * Verifies Fix 3 on a document completely lacking :defs.
     * Proves that :defs is synthesized before :type and the nominal brand is inserted.
     */
    public void testFix3SynthesizesDefsBlockWhenDefsIsAbsent() {
        var content = """
            {
              :type :IPv4
              :body "127.0.0.1"
            }
            """;
        myFixture.configureByText("no_defs_fix3.stvn", content);
        var caretOffset = content.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Brand new nominal type ':IPv4' from Prelude (creates distinct type)");
        assertFalse("Fix 3 intention must be available on document lacking :defs", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var resultText = myFixture.getFile().getText();
        assertTrue("Must synthesize :defs block", resultText.contains(":defs {"));
        assertTrue("Must insert nominal brand into synthesized :defs",
            resultText.contains(":IPv4 :org/stvnadore/prelude/IPv4"));

        int defsPos = resultText.indexOf(":defs");
        int typePos = resultText.indexOf(":type");
        assertTrue("Synthesized :defs block must precede :type", defsPos < typePos);

        var errors = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiErrorElement.class);
        assertTrue("Must produce zero PsiErrorElement nodes. Found: " + errors, errors.isEmpty());
    }

    /**
     * Verifies that Fix 2 does NOT duplicate :defs when :defs already exists.
     */
    public void testFix2DoesNotDuplicateExistingDefsBlock() {
        var content = """
            {
              :defs {
                :ExistingType :Int
              }
              :type :IPv4
              :body "127.0.0.1"
            }
            """;
        myFixture.configureByText("existing_defs_fix2.stvn", content);
        var caretOffset = content.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Import ':IPv4' from Prelude via :use (preserves type identity)");
        assertFalse("Fix 2 must be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var resultText = myFixture.getFile().getText();
        int firstDefs = resultText.indexOf(":defs");
        int secondDefs = resultText.indexOf(":defs", firstDefs + 1);
        assertEquals("Must contain exactly ONE :defs block; duplicate was synthesized", -1, secondDefs);
        assertTrue("Existing definition must remain intact", resultText.contains(":ExistingType :Int"));
        assertTrue("Import must be present", resultText.contains(":use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]"));
    }

    /**
     * Verifies creating a fresh nominal type for an unknown domain type on a document without :defs.
     */
    public void testCreateNominalTypeQuickFixSynthesizesDefs() {
        var content = """
            {
              :type :Customer
              :body "Acme Corp"
            }
            """;
        myFixture.configureByText("customer_no_defs.stvn", content);
        var caretOffset = content.indexOf(":Customer");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Create nominal type ':Customer' in :defs");
        assertFalse("Create nominal type intention must be available for unknown symbol", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var resultText = myFixture.getFile().getText();
        assertTrue("Must synthesize :defs block", resultText.contains(":defs {"));
        assertTrue("Must insert ':Customer :String' into :defs", resultText.contains(":Customer :String"));

        int defsPos = resultText.indexOf(":defs");
        int typePos = resultText.indexOf(":type");
        assertTrue("Synthesized :defs block must precede :type", defsPos < typePos);
    }

    /**
     * Verifies Fix 2 on nonempty_torture.stvn referencing :Uuid in :type.
     */
    public void testNonemptyTortureQuickFixDefsSynthesis() {
        var content = """
            {
              :type :Tuple(
                      { #minSize 1 } :Seq(:Int)
                      { #minSize 1 } :Set(:String)
                      { #minSize 1 } :Map(:Uuid :String)
                      { #minSize 1 } :Map(:String :Int))
              :body (
                [1]
                ["A" "B" "C"]
                {
                  ["12345678-1234-1234-1234-123456789012" "A"]
                  ["12345678-1234-1234-1234-123456789013" "A"]
                }
                {
                  ["1" 2]
                  ["2" 3]
                }
              )
            }
            """;
        myFixture.configureByText("nonempty_torture_test.stvn", content);
        var caretOffset = content.indexOf(":Uuid");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Import ':Uuid' from Prelude via :use (preserves type identity)");
        assertFalse("Fix 2 must be available for :Uuid in nonempty_torture.stvn", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Must synthesize :defs block before :type", text.indexOf(":defs") < text.indexOf(":type"));
        assertTrue("Must contain :use statement for :Uuid", text.contains(":use [ :org/stvnadore/prelude { :Uuid :Uuid } ]"));

        var errors = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiErrorElement.class);
        assertTrue("Must contain zero PsiErrorElement nodes. Found: " + errors, errors.isEmpty());
    }

    /**
     * Verifies declaring a constant for an unresolved value keyword on a document without :defs.
     */
    public void testDeclareConstantQuickFixSynthesizesDefs() {
        var content = """
            {
              :type :Int
              :body #DEFAULT_PORT
            }
            """;
        myFixture.configureByText("const_no_defs.stvn", content);
        var caretOffset = content.indexOf("#DEFAULT_PORT");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Declare constant '#DEFAULT_PORT' in :defs");
        assertFalse("Declare constant intention must be available for unresolved constant", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var resultText = myFixture.getFile().getText();
        assertTrue("Must synthesize :defs block", resultText.contains(":defs {"));
        assertTrue("Must insert '#DEFAULT_PORT :Int 0' into :defs", resultText.contains("#DEFAULT_PORT :Int 0"));

        int defsPos = resultText.indexOf(":defs");
        int typePos = resultText.indexOf(":type");
        assertTrue("Synthesized :defs block must precede :type", defsPos < typePos);
    }
}
