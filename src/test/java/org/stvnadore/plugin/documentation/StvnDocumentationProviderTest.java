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

    public void testBareHashSigilHoverSuppressesContainerDocumentation() {
        var text = """
            {
              :defs {
                :EitherA :Either( :Int32 :String )
                :EitherB :Either( :Int32 :String )
                :UnionLikeEitherC :Union( :Int32 :String )
              }
              :type :Tuple( :EitherA :EitherB :Boolean :UnionLikeEitherC :UnionLikeEitherC :UnionLikeEitherC )
              :body (
                #Right 9
                #Right 1
                #TRUE
                #1 1
                #2 2
                #
              )
            }
            """;
        myFixture.configureByText("bare_hash_hover.stvn", text);

        var offset = text.lastIndexOf("#");
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        var doc = (docElem != null) ? provider.generateDoc(docElem, elem) : provider.generateDoc(elem, elem);

        assertNull("Hovering on bare '#' must return null and never leak container documentation", doc);
    }

    public void testPsiErrorElementHoverSuppressesContainerDocumentation() {
        var text = """
            {
              :type :Tuple( :Int32 :Int32 )
              :body (
                42
                )
            }
            """;
        myFixture.configureByText("error_hover.stvn", text);

        var offset = text.indexOf(")");
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        var doc = (docElem != null) ? provider.generateDoc(docElem, elem) : provider.generateDoc(elem, elem);

        // Container documentation must only display when hovering on valid container delimiters of valid containers
        assertFalse("Must NOT leak tuple expression card on mismatched error parenthesis",
            doc != null && doc.contains("<b>Expression:</b>"));
    }

    public void testStrictUpwardTraversalSuppression() {
        var text = """
            {
              :defs {
                :package :com/example {
                  :MyType :String
                }
              }
              :type :Tuple( :Int :String )
              :body (
                #
                :
                /
              )
            }
            """;
        myFixture.configureByText("upward_suppression.stvn", text);

        var file = myFixture.getFile();
        var editor = myFixture.getEditor();

        // 1. Caret on bare '#'
        var hashOffset = text.indexOf("#");
        var hashElem = file.findElementAt(hashOffset);
        assertNotNull(hashElem);
        var hashDocElem = provider.getCustomDocumentationElement(editor, file, hashElem, hashOffset);
        var hashDoc = hashDocElem != null ? provider.generateDoc(hashDocElem, hashElem) : null;
        assertNull("Caret on bare '#' must return null documentation", hashDoc);

        // 2. Caret on bare ':'
        var colonOffset = text.indexOf(":", text.indexOf(":body"));
        var colonElem = file.findElementAt(colonOffset);
        assertNotNull(colonElem);
        var colonDocElem = provider.getCustomDocumentationElement(editor, file, colonElem, colonOffset);
        var colonDoc = colonDocElem != null ? provider.generateDoc(colonDocElem, colonElem) : null;
        assertNull("Caret on bare ':' in body must return null documentation", colonDoc);

        // 3. Caret on bare '/'
        var slashOffset = text.indexOf("/", text.indexOf(":body"));
        var slashElem = file.findElementAt(slashOffset);
        assertNotNull(slashElem);
        var slashDocElem = provider.getCustomDocumentationElement(editor, file, slashElem, slashOffset);
        var slashDoc = slashDocElem != null ? provider.generateDoc(slashDocElem, slashElem) : null;
        assertNull("Caret on bare '/' must return null documentation", slashDoc);

        // 4. Caret on interior whitespace
        var wsOffset = text.indexOf("/", text.indexOf(":body")) + 2;
        var wsElem = file.findElementAt(wsOffset);
        assertNotNull(wsElem);
        var wsDocElem = provider.getCustomDocumentationElement(editor, file, wsElem, wsOffset);
        var wsDoc = wsDocElem != null ? provider.generateDoc(wsDocElem, wsElem) : null;
        assertNull("Caret on interior whitespace must return null documentation", wsDoc);
    }

    /**
     * Verifies that hover and quick navigate info emit an unqualified reference warning
     * when a bare standard library prelude token is referenced without qualification or :use.
     */
    public void testUnqualifiedPreludeReferenceWarning() {
        var text = """
            {
              :defs {
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }
              :type :IpAddress
              :body "127.0.0.1"
            }
            """;
        var file = myFixture.configureByText("net.stvn", text);
        var offset = text.indexOf(":IPv4");
        var elem = file.findElementAt(offset);
        assertNotNull(elem);

        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), file, elem, offset);
        assertNotNull(docElem);

        var doc = provider.generateDoc(docElem, elem);
        assertNotNull(doc);
        assertTrue("Doc must include unqualified reference warning",
            doc.contains("Unqualified reference to Standard Library Prelude type '<code>:org/stvnadore/prelude/IPv4</code>'"));
        assertTrue("Doc must include underlying structure", doc.contains("<b>Underlying Structure:</b>"));

        var quickInfo = provider.getQuickNavigateInfo(docElem, elem);
        assertEquals("Unqualified reference to Standard Library Prelude type ':org/stvnadore/prelude/IPv4'", quickInfo);
    }

    /**
     * Verifies that hovering over :IPv4 in an identity :use import renders 'Scoped Import:' instead of 'Type Alias:'.
     */
    public void testScopedUseImportHoverRendersScopedImportNotTypeAlias() {
        var text = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]
              }
            }
            """;
        myFixture.configureByText("scoped_use.stvn_inclf", text);
        var offset = text.indexOf(":IPv4");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Doc must render 'Scoped Import:'", doc.contains("<b>Scoped Import:</b> :IPv4"));
        assertFalse("Doc must NOT render 'Type Alias:'", doc.contains("<b>Type Alias:</b>"));
        assertTrue("Doc must include namespace", doc.contains("<code>:org/stvnadore/prelude</code>"));

        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull(docElem);
        var quickInfo = provider.getQuickNavigateInfo(docElem, elem);
        assertNotNull(quickInfo);
        assertTrue("Quick navigate info must start with 'Scoped Import:'", quickInfo.startsWith("Scoped Import: :IPv4"));
    }

    /**
     * Verifies that hovering over a renamed reference in :use renders 'Renamed Reference:' with arrow and source namespace.
     */
    public void testRenamedUseHoverRendersRenamedReference() {
        var text = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :IPv4 :LocalIp } ]
              }
            }
            """;
        myFixture.configureByText("renamed_use.stvn_inclf", text);
        var offset = text.indexOf(":LocalIp");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Doc must render 'Renamed Reference:'", doc.contains("<b>Renamed Reference:</b> :LocalIp &rarr; :org/stvnadore/prelude/IPv4"));
        assertTrue("Doc must render 'Source Namespace:'", doc.contains("<b>Source Namespace:</b> :org/stvnadore/prelude"));
        assertFalse("Doc must NOT render 'Type Alias:'", doc.contains("Type Alias"));

        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull(docElem);
        var quickInfo = provider.getQuickNavigateInfo(docElem, elem);
        assertNotNull(quickInfo);
        assertEquals("Renamed Reference: :LocalIp -> :org/stvnadore/prelude/IPv4", quickInfo);
    }

    public void testHoverOnScopedUseImportAppendsFullTargetDocumentation() {
        var text = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :IPv4 :IPv4 } ]
              }
            }
            """;
        myFixture.configureByText("scoped_use_chain.stvn_inclf", text);
        var offset = text.indexOf(":IPv4");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must render 'Scoped Import:' header", doc.contains("<b>Scoped Import:</b> :IPv4"));
        assertTrue("Doc must chain Prelude card containing 'Underlying Type: <code>:String</code>'",
            doc.contains("<b>Underlying Type:</b> <code>:String</code>"));
        assertTrue("Doc must chain Prelude prose description",
            doc.contains("Represents a standard <b>dotted-quad IPv4"));
    }

    public void testHoverOnRenamedUseAppendsFullTargetDocumentation() {
        var text = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :IPv4 :LocalIp } ]
              }
            }
            """;
        myFixture.configureByText("renamed_use_chain.stvn_inclf", text);
        var offset = text.indexOf(":LocalIp");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must render 'Renamed Reference:' header",
            doc.contains("<b>Renamed Reference:</b> :LocalIp &rarr; :org/stvnadore/prelude/IPv4"));
        assertTrue("Doc must chain Prelude card containing 'Underlying Type: <code>:String</code>'",
            doc.contains("<b>Underlying Type:</b> <code>:String</code>"));
        assertTrue("Doc must chain Prelude prose description",
            doc.contains("Represents a standard <b>dotted-quad IPv4"));
    }

    public void testNominalAliasEffectiveFacetsTable() {
        var text = """
            {
              :defs {
                :BlockStringB { #minSize 4 #preserveIndent #TRUE } :String
                :BlockStringF { #preserveIndent #FALSE #maxSize 1024 } :BlockStringB
              }
              :type :BlockStringF
              :body "sample"
            }
            """;
        myFixture.configureByText("alias_facets.stvn", text);
        var offset = text.indexOf(":BlockStringF");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must contain 'Effective Facets & Traits:' header", doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Doc must contain '#preserveIndent' facet row", doc.contains("<code>#preserveIndent</code>"));
        assertTrue("Doc must display '#FALSE' as effective value for preserveIndent", doc.contains("<code>#FALSE</code>"));
        assertTrue("Doc must display 'Active Override' for preserveIndent", doc.contains("Active Override"));
        assertTrue("Doc must contain '#minSize' facet row", doc.contains("<code>#minSize</code>"));
        assertTrue("Doc must display '4' as effective value for minSize", doc.contains("<code>4</code>"));
        assertTrue("Doc must display 'Inherited from :BlockStringB' for minSize", doc.contains("Inherited from :BlockStringB"));
        assertTrue("Doc must contain '#maxSize' facet row", doc.contains("<code>#maxSize</code>"));
        assertTrue("Doc must display '1024' as effective value for maxSize", doc.contains("<code>1024</code>"));
    }

    public void testMultiTierLineageOriginEquatableAndComparableDefaultToString() {
        var text = """
            {
              :defs {
                :BlockStringA :String
                :BlockStringB :BlockStringA
                :BlockStringC :BlockStringB
                :BlockStringD :BlockStringC
                :BlockStringE :BlockStringD
                :BlockStringF :BlockStringE
              }
              :type :BlockStringF
              :body "multi-tier sample"
            }
            """;
        myFixture.configureByText("multi_tier_defaults.stvn", text);
        var offset = text.indexOf(":BlockStringF");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must render Effective Facets table", doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Equatable must report Default (:String)", doc.contains("<code>#equatable</code></td><td style=\"padding: 2px 4px;\"><code>#TRUE</code></td><td style=\"padding: 2px 4px;\">Default (:String)"));
        assertTrue("Comparable must report Default (:String)", doc.contains("<code>#comparable</code></td><td style=\"padding: 2px 4px;\"><code>#TRUE</code></td><td style=\"padding: 2px 4px;\">Default (:String)"));
        assertFalse("Must NOT attribute equatable to intermediate parent :BlockStringE", doc.contains("Inherited from :BlockStringE"));
    }

    public void testMultiTierLineagePreserveIndentInheritedFromAncestor() {
        var text = """
            {
              :defs {
                :BlockStringA { #preserveIndent #FALSE } :String
                :BlockStringB :BlockStringA
                :BlockStringC :BlockStringB
                :BlockStringD :BlockStringC
              }
              :type :BlockStringD
              :body "sample"
            }
            """;
        myFixture.configureByText("multi_tier_inherit.stvn", text);
        var offset = text.indexOf(":BlockStringD");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must display effective value #FALSE", doc.contains("<code>#FALSE</code>"));
        assertTrue("PreserveIndent origin must trace back to :BlockStringA", doc.contains("Inherited from :BlockStringA"));
        assertFalse("Must NOT misattribute origin to immediate parent :BlockStringC", doc.contains("Inherited from :BlockStringC"));
    }

    public void testMultiTierLineagePreserveIndentStopsAtIntermediatePinningAncestor() {
        var text = """
            {
              :defs {
                :BlockStringA { #preserveIndent #FALSE } :String
                :BlockStringB :BlockStringA
                :BlockStringC { #preserveIndent #TRUE } :BlockStringB
                :BlockStringD :BlockStringC
              }
              :type :BlockStringD
              :body "sample"
            }
            """;
        myFixture.configureByText("multi_tier_pin.stvn", text);
        var offset = text.indexOf(":BlockStringD");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must display effective value #TRUE", doc.contains("<code>#TRUE</code>"));
        assertTrue("PreserveIndent origin must stop at pinning ancestor :BlockStringC", doc.contains("Inherited from :BlockStringC"));
        assertFalse("Must NOT report root ancestor :BlockStringA", doc.contains("Inherited from :BlockStringA"));
    }

    public void testCompositeTupleDerivedNonEquatableOriginDisplaysOffendingFields() {
        var text = """
            {
              :defs {
                :FloatExactA :Float
                :FloatExactATo32 :Float
                :Values :Tuple( :FloatExactA :FloatExactATo32 )
              }
              :type :Values
              :body ( 1.0 2.0 )
            }
            """;
        myFixture.configureByText("composite_tuple_traits.stvn", text);
        var offset = text.indexOf(":Values");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must render Effective Facets table", doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Status column must display Derived for equatable",
            doc.contains("<b style=\"color: #9E9E9E;\">Derived</b>"));
        assertTrue("Origin column must itemize non-equatable constituent fields",
            doc.contains("Derived (non-equatable: :FloatExactA, :FloatExactATo32)"));
        assertFalse("Must NOT display raw tuple constructor in origin",
            doc.contains("Default (:Tuple("));
        assertTrue("Comparable must display Derived (all constituent fields conform)",
            doc.contains("Derived (all constituent fields conform)"));
    }

    public void testCompositeTupleAllFieldsConformingDisplaysDerivedConform() {
        var text = """
            {
              :defs {
                :IntField :Int
                :StringField :String
                :Values :Tuple( :IntField :StringField )
              }
              :type :Values
              :body ( 42 "valid" )
            }
            """;
        myFixture.configureByText("conforming_tuple.stvn", text);
        var offset = text.indexOf(":Values");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must render Effective Facets table", doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Equatable must display all constituent fields conform",
            doc.contains("Derived (all constituent fields conform)"));
        assertTrue("Comparable must display all constituent fields conform",
            doc.contains("Derived (all constituent fields conform)"));
    }
}

