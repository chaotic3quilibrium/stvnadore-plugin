package org.stvnadore.plugin.documentation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Test suite verifying quick documentation hover pop-up card generation
 * for single-level, transitive, and aliased enum subsets.
 */
@NullMarked
public final class StvnDocumentationProviderTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider provider = new StvnDocumentationProvider();

    private String getDocAtOffset(String text, int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Documentation element at offset " + offset + " must not be null", docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Generated documentation at offset " + offset + " must not be null", doc);
        return doc;
    }

    public void testSingleLevelFilterExclSubsetHover() {
        var text = """
            {
              :defs {
                :PieceRole :Enum [ #PAWN #KNIGHT #BISHOP #ROOK #QUEEN #KING ]
                :PromotionRole { #filterExcl [ #PAWN #KING ] } :PieceRole
              }
              :type :PromotionRole
              :body #KNIGHT
            }
            """;
        myFixture.configureByText("chess_turn.stvn_inclf", text);

        var offset = text.indexOf(":PromotionRole");
        var doc = getDocAtOffset(text, offset);

        // 1. Variant count must reflect allowed variants (4), not root enum count (6)
        assertTrue("Expected 'Variant Count:</b> 4'", doc.contains("<b>Variant Count:</b> 4"));
        assertFalse("Must NOT display root enum variant count", doc.contains("<b>Variant Count:</b> 6"));

        // 2. Underlying structure must unroll allowed variants, not nominal reference
        assertTrue("Expected unrolled enum structure",
            doc.contains("<b>Underlying Structure:</b> :Enum [ #KNIGHT #BISHOP #ROOK #QUEEN ]"));
        assertFalse("Must NOT display raw nominal reference as underlying structure",
            doc.contains("<b>Underlying Structure:</b> :PieceRole"));

        // 3. Derivation metadata must display immediate parent and exclusion facet
        assertTrue("Expected derivation lineage with filterExcl",
            doc.contains("<b>Derivation:</b> Parent: :PieceRole via #filterExcl [ #PAWN #KING ]"));
    }

    public void testSingleLevelFilterInclSubsetHover() {
        var text = """
            {
              :defs {
                :Environment :Enum [ #LOCAL #DEV #STAGING #CANARY #PROD ]
                :DeployEnv { #filterIncl [ #DEV #STAGING #CANARY ] } :Environment
              }
              :type :DeployEnv
              :body #DEV
            }
            """;
        myFixture.configureByText("deploy_env.stvn", text);

        var offset = text.indexOf(":DeployEnv");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Expected 'Variant Count:</b> 3'", doc.contains("<b>Variant Count:</b> 3"));
        assertFalse("Must NOT display root enum variant count", doc.contains("<b>Variant Count:</b> 5"));

        assertTrue("Expected unrolled enum structure",
            doc.contains("<b>Underlying Structure:</b> :Enum [ #DEV #STAGING #CANARY ]"));

        assertTrue("Expected derivation lineage with filterIncl",
            doc.contains("<b>Derivation:</b> Parent: :Environment via #filterIncl [ #DEV #STAGING #CANARY ]"));
    }

    public void testTransitiveSubsetChainHover() {
        var text = """
            {
              :defs {
                :TaskStatus :Enum [ #BACKLOG #TODO #IN_PROGRESS #CODE_REVIEW #TESTING #DONE #BLOCKED #CANCELLED ]
                :ActiveStatus { #filterExcl [ #BACKLOG #DONE #CANCELLED ] } :TaskStatus
                :WorkableStatus { #filterIncl [ #TODO #IN_PROGRESS #CODE_REVIEW #TESTING ] } :ActiveStatus
                :ExecutionStatus { #filterExcl [ #CODE_REVIEW #TESTING ] } :WorkableStatus
              }
              :type :ExecutionStatus
              :body #TODO
            }
            """;
        myFixture.configureByText("transitive_status.stvn", text);

        // Terminal level (:ExecutionStatus)
        var execOffset = text.indexOf(":ExecutionStatus");
        var execDoc = getDocAtOffset(text, execOffset);
        assertTrue("ExecutionStatus variant count must be 2", execDoc.contains("<b>Variant Count:</b> 2"));
        assertTrue("ExecutionStatus structure must unroll [#TODO, #IN_PROGRESS]",
            execDoc.contains("<b>Underlying Structure:</b> :Enum [ #TODO #IN_PROGRESS ]"));
        assertTrue("ExecutionStatus derivation must show parent :WorkableStatus and filterExcl",
            execDoc.contains("<b>Derivation:</b> Parent: :WorkableStatus via #filterExcl [ #CODE_REVIEW #TESTING ]"));

        // Intermediate level (:WorkableStatus)
        var workOffset = text.indexOf(":WorkableStatus");
        var workDoc = getDocAtOffset(text, workOffset);
        assertTrue("WorkableStatus variant count must be 4", workDoc.contains("<b>Variant Count:</b> 4"));
        assertTrue("WorkableStatus structure must unroll 4 allowed variants",
            workDoc.contains("<b>Underlying Structure:</b> :Enum [ #TODO #IN_PROGRESS #CODE_REVIEW #TESTING ]"));
        assertTrue("WorkableStatus derivation must show parent :ActiveStatus and filterIncl",
            workDoc.contains("<b>Derivation:</b> Parent: :ActiveStatus via #filterIncl [ #TODO #IN_PROGRESS #CODE_REVIEW #TESTING ]"));

        // First subset tier (:ActiveStatus)
        var activeOffset = text.indexOf(":ActiveStatus");
        var activeDoc = getDocAtOffset(text, activeOffset);
        assertTrue("ActiveStatus variant count must be 5", activeDoc.contains("<b>Variant Count:</b> 5"));
        assertTrue("ActiveStatus structure must unroll 5 allowed variants",
            activeDoc.contains("<b>Underlying Structure:</b> :Enum [ #TODO #IN_PROGRESS #CODE_REVIEW #TESTING #BLOCKED ]"));
        assertTrue("ActiveStatus derivation must show parent :TaskStatus and filterExcl",
            activeDoc.contains("<b>Derivation:</b> Parent: :TaskStatus via #filterExcl [ #BACKLOG #DONE #CANCELLED ]"));
    }

    public void testPassThroughSubsetAliasHover() {
        var text = """
            {
              :defs {
                :PieceRole :Enum [ #PAWN #KNIGHT #BISHOP #ROOK #QUEEN #KING ]
                :PromotionRole { #filterExcl [ #PAWN #KING ] } :PieceRole
                :FooRole :PromotionRole
              }
              :type :FooRole
              :body #QUEEN
            }
            """;
        myFixture.configureByText("pass_through.stvn", text);

        var offset = text.indexOf(":FooRole");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Expected 'Variant Count:</b> 4'", doc.contains("<b>Variant Count:</b> 4"));
        assertTrue("Expected unrolled enum structure",
            doc.contains("<b>Underlying Structure:</b> :Enum [ #KNIGHT #BISHOP #ROOK #QUEEN ]"));
        assertTrue("Expected derivation to show parent alias",
            doc.contains("<b>Derivation:</b> Parent: :PromotionRole"));
    }

    public void testUsageSiteSubsetHover() {
        var text = """
            {
              :defs {
                :PieceRole :Enum [ #PAWN #KNIGHT #BISHOP #ROOK #QUEEN #KING ]
                :PromotionRole { #filterExcl [ #PAWN #KING ] } :PieceRole
              }
              :type :PromotionRole
              :body #KNIGHT
            }
            """;
        myFixture.configureByText("usage_site.stvn", text);

        // Hover over :PromotionRole at the :type usage site
        var usageOffset = text.lastIndexOf(":PromotionRole");
        var doc = getDocAtOffset(text, usageOffset);

        assertTrue("Usage site hover must resolve variant count", doc.contains("<b>Variant Count:</b> 4"));
        assertTrue("Usage site hover must resolve unrolled structure",
            doc.contains("<b>Underlying Structure:</b> :Enum [ #KNIGHT #BISHOP #ROOK #QUEEN ]"));
        assertTrue("Usage site hover must resolve derivation lineage",
            doc.contains("<b>Derivation:</b> Parent: :PieceRole via #filterExcl [ #PAWN #KING ]"));
    }

    public void testCompositeSchemaSubsetHover() {
        var text = """
            {
              :defs {
                :PieceRole :Enum [ #PAWN #KNIGHT #BISHOP #ROOK #QUEEN #KING ]
                :PromotionRole { #filterExcl [ #PAWN #KING ] } :PieceRole
              }
              :type :Tuple( :PromotionRole :Int32 )
              :body ( #KNIGHT 42 )
            }
            """;
        myFixture.configureByText("composite_usage.stvn", text);

        var compositeOffset = text.indexOf(":PromotionRole :Int32");
        var doc = getDocAtOffset(text, compositeOffset);

        assertTrue("Composite schema hover must resolve variant count", doc.contains("<b>Variant Count:</b> 4"));
        assertTrue("Composite schema hover must resolve unrolled structure",
            doc.contains("<b>Underlying Structure:</b> :Enum [ #KNIGHT #BISHOP #ROOK #QUEEN ]"));
    }

    public void testIncludeMapAliasSubsetHover() {
        myFixture.addFileToProject(
            "chess_turn.stvn_inclf",
            """
            {
              :defs {
                :PieceRole :Enum [ #PAWN #KNIGHT #BISHOP #ROOK #QUEEN #KING ]
                :PromotionRole { #filterExcl [ #PAWN #KING ] } :PieceRole
              }
            }
            """
        );

        var text = """
            {
              :defs {
                :include [ "chess_turn.stvn_inclf" { :PromotionRole :LocalPromo } ]
              }
              :type :LocalPromo
              :body #KNIGHT
            }
            """;
        myFixture.configureByText("play_turn.stvn", text);

        var offset = text.indexOf(":LocalPromo");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Include alias hover must resolve variant count", doc.contains("<b>Variant Count:</b> 4"));
        assertTrue("Include alias hover must resolve unrolled structure",
            doc.contains("<b>Underlying Structure:</b> :Enum [ #KNIGHT #BISHOP #ROOK #QUEEN ]"));
        assertTrue("Include alias hover must resolve derivation lineage",
            doc.contains("<b>Derivation:</b> Parent: :PieceRole via #filterExcl [ #PAWN #KING ]"));
    }
}
