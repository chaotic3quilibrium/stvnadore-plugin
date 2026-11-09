package org.stvnadore.plugin.documentation;

import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.psi.TypeDefinition;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

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
        myFixture.configureByText("chess_turn.stvn_di", text);

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
            "chess_turn.stvn_di",
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
                :include [ "chess_turn.stvn_di" { :PromotionRole :LocalPromo } ]
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
        myFixture.configureByText("scoped_use.stvn_di", text);
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
        myFixture.configureByText("renamed_use.stvn_di", text);
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
        myFixture.configureByText("scoped_use_chain.stvn_di", text);
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
        myFixture.configureByText("renamed_use_chain.stvn_di", text);
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
                :BlockStringB { #minSize 4 #maxSize 1024 #preserveIndent #TRUE } :String
                :BlockStringF { #preserveIndent #FALSE } :BlockStringB
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
        assertTrue("Doc must display 'Inherited from' with bound sigil hyperlink to :BlockStringB for minSize",
                doc.contains("Inherited from<br/><a href=\"psi_element://TypeDefinition/:BlockStringB\"><code>:BlockStringB</code></a>"));
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
        assertFalse("Must NOT attribute equatable to intermediate parent :BlockStringE",
                doc.contains("TypeDefinition/:BlockStringE") || doc.contains("Inherited from :BlockStringE"));
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
        assertTrue("PreserveIndent origin must trace back to :BlockStringA",
                doc.contains("Inherited from<br/><a href=\"psi_element://TypeDefinition/:BlockStringA\"><code>:BlockStringA</code></a>"));
        assertFalse("Must NOT misattribute origin to immediate parent :BlockStringC",
                doc.contains("TypeDefinition/:BlockStringC") || doc.contains("Inherited from :BlockStringC"));
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
        assertTrue("PreserveIndent origin must stop at pinning ancestor :BlockStringC",
                doc.contains("Inherited from<br/><a href=\"psi_element://TypeDefinition/:BlockStringC\"><code>:BlockStringC</code></a>"));
        assertFalse("Must NOT report root ancestor :BlockStringA",
                doc.contains("TypeDefinition/:BlockStringA") || doc.contains("Inherited from :BlockStringA"));
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

    public void testPackagedCollectionDocumentationRendersPackageFqniCommentsAndTraits() {
        var content = """
            {
              :meta {
                #name "rfc8259_json_substrate"
                #domain "json_schema"
                #kind #DEFS
              }
              :defs {
                :package :org/ietf/rfc8259/json {
                  :JsonNull :Enum [#NULL]
                  :JsonBoolean :Boolean
                  :JsonNumberInteger {#size 53} :Int
                  :JsonNumberFloat :Float
                  :JsonString :String
                  :JsonObject :Map(:JsonString :JsonValue)
                  :JsonArray :Seq(:JsonValue) // Structural Category: SEQUENCE (Heterogeneous JSON Array)
                  :JsonValue :Union(
                    :JsonNull
                    :JsonBoolean
                    :JsonNumberInteger
                    :JsonNumberFloat
                    :JsonString
                    :JsonObject
                    :JsonArray
                  )
                }
              }
            }
            """;
        myFixture.configureByText("rfc8259_json_substrate.json_schema.stvn_d", content);
        var offset = content.indexOf(":JsonArray :Seq");
        var doc = getDocAtOffset(content, offset);

        assertTrue("Doc must render Package header", doc.contains("<b>Package:</b> <code>:org/ietf/rfc8259/json</code>"));
        assertTrue("Doc must render FQNI header", doc.contains("<b>FQNI:</b> <code>:org/ietf/rfc8259/json/JsonArray</code>"));
        assertFalse("Doc inside package must NOT render 'Declared In: Local Definitions'", doc.contains("Declared In: Local Definitions"));
        assertFalse("Doc must NOT scrape adjacent trailing comment", doc.contains("Structural Category: SEQUENCE (Heterogeneous JSON Array)"));
        assertTrue("Doc must render Effective Facets & Traits table", doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Doc must render #equatable trait", doc.contains("<code>#equatable</code>"));
        assertTrue("Doc must render #comparable trait", doc.contains("<code>#comparable</code>"));
        assertTrue("Doc must render underlying structure", doc.contains("<b>Underlying Structure:</b> :Seq( :JsonValue )"));
    }

    public void testPackagedScalarWithFacetsDocumentationRendersSizeAndTraits() {
        var content = """
            {
              :meta {
                #name "rfc8259_json_substrate"
                #domain "json_schema"
                #kind #DEFS
              }
              :defs {
                :package :org/ietf/rfc8259/json {
                  :JsonNumberInteger {#size 53} :Int // Structural Category: INTEGER
                }
              }
            }
            """;
        myFixture.configureByText("rfc8259_json_substrate.json_schema.stvn_d", content);
        var offset = content.indexOf(":JsonNumberInteger {");
        var doc = getDocAtOffset(content, offset);

        assertTrue("Doc must render Package header", doc.contains("<b>Package:</b> <code>:org/ietf/rfc8259/json</code>"));
        assertTrue("Doc must render FQNI header", doc.contains("<b>FQNI:</b> <code>:org/ietf/rfc8259/json/JsonNumberInteger</code>"));
        assertFalse("Doc must NOT scrape adjacent trailing comment", doc.contains("Structural Category: INTEGER"));
        assertTrue("Doc must render Effective Facets table", doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Doc must render #size facet with value 53", doc.contains("<code>#size</code></td><td style=\"padding: 2px 4px;\"><code>53</code>"));
        assertTrue("Doc must render #size before #equatable in 5-tier order", doc.indexOf("#size") < doc.indexOf("#equatable"));
        assertTrue("Doc must render #equatable before #comparable in 5-tier order", doc.indexOf("#equatable") < doc.indexOf("#comparable"));
        assertTrue("Doc must render underlying structure", doc.contains("<b>Underlying Structure:</b> :Int"));
    }

    /**
     * Verifies that hovering over #name displays :org/stvnadore/prelude/meta/StvnPosixId64 documentation.
     */
    public void testMetaNameHoverRendersPreludePosixId64Documentation() {
        var content = """
            {
              :meta {
                #name "rfc8259_json_substrate"
                #kind #DEFS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("rfc8259_json_substrate.stvn_d", content);
        var offset = content.indexOf("#name");
        var doc = getDocAtOffset(content, offset);
        assertNotNull("Documentation on #name must not be null", doc);
        assertTrue("Must reference :StvnPosixId64", doc.contains(":StvnPosixId64"));
        assertTrue("Must reference :org/stvnadore/prelude/meta", doc.contains(":org/stvnadore/prelude/meta"));
        assertTrue("Must render #maxSize facet", doc.contains("<code>#maxSize</code>"));
        assertTrue("Must render 64 limit", doc.contains("64"));
    }

    /**
     * Verifies that hovering over #kind displays :org/stvnadore/prelude/meta/StvnDocumentKind documentation.
     */
    public void testMetaKindHoverRendersPreludeDocumentKindDocumentation() {
        var content = """
            {
              :meta {
                #name "rfc8259_json_substrate"
                #kind #DEFS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("rfc8259_json_substrate.stvn_d", content);
        var offset = content.indexOf("#kind");
        var doc = getDocAtOffset(content, offset);
        assertNotNull("Documentation on #kind must not be null", doc);
        assertTrue("Must reference :StvnDocumentKind", doc.contains(":StvnDocumentKind"));
        assertTrue("Must list enum variants", doc.contains("#BODY"));
    }

    /**
     * Verifies that hovering over #DEFS kind value displays enum variant documentation.
     */
    public void testMetaKindValueHoverRendersEnumVariantDocumentation() {
        var content = """
            {
              :meta {
                #name "rfc8259_json_substrate"
                #kind #DEFS
              }
              :defs {}
            }
            """;
        myFixture.configureByText("rfc8259_json_substrate.stvn_d", content);
        var offset = content.indexOf("#DEFS");
        var doc = getDocAtOffset(content, offset);
        assertNotNull("Documentation on #DEFS must not be null", doc);
        assertTrue("Must display variant name #DEFS", doc.contains("<b>Document Kind Variant:</b> <code>#DEFS</code>"));
        assertTrue("Must display enclosing enum", doc.contains(":StvnDocumentKind"));
    }

    public void testContinuousFloatZone2Defaults() {
        var text = """
            {
              :defs {
                :ContinuousWeight :Float
              }
              :type :ContinuousWeight
              :body 123.45
            }
            """;
        myFixture.configureByText("continuous_weight.stvn", text);
        var offset = text.indexOf(":ContinuousWeight");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Continuous float must default #equatable to #FALSE in Zone 2",
            doc.contains("#equatable") && doc.contains("#FALSE") && doc.contains("Default (:Float)"));
        assertTrue("Continuous float must default #comparable to #TRUE in Zone 2",
            doc.contains("#comparable") && doc.contains("#TRUE") && doc.contains("Default (:Float)"));
    }

    public void testExactFloatZone2Defaults() {
        var text = """
            {
              :defs {
                :ExactPrice { #exact } :Float
              }
              :type :ExactPrice
              :body 19.99
            }
            """;
        myFixture.configureByText("exact_price.stvn", text);
        var offset = text.indexOf(":ExactPrice");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Exact float must default #equatable to #TRUE in Zone 2",
            doc.contains("#equatable") && doc.contains("#TRUE") && doc.contains("Default (:Float)"));
        assertTrue("Exact float must default #comparable to #TRUE in Zone 2",
            doc.contains("#comparable") && doc.contains("#TRUE") && doc.contains("Default (:Float)"));
    }

    public void testExactFloat50DigitMantissaFidelityDoc() {
        var fiftyDigits = "3.14159265358979323846264338327950288419716939937510";
        var text = "{\n  :defs {\n    :Pi { #exact } :Float\n  }\n  :type :Pi\n  :body " + fiftyDigits + "\n}\n";
        myFixture.configureByText("exact_pi.stvn", text);
        var offset = text.indexOf(fiftyDigits);
        var doc = getDocAtOffset(text, offset);

        assertTrue("Must contain full 50 digits without truncation", doc.contains(fiftyDigits));
        assertTrue("Compliance status must be Satisfied", doc.contains("Satisfied"));
        assertTrue("Must report exact decimal representation", doc.contains("Exact Decimal: <code>" + fiftyDigits + "</code>"));
        assertTrue("Must report precision (50 or 51 digits)", doc.contains("Precision (Digits): <code>51</code>") || doc.contains("Precision (Digits): <code>50</code>"));
    }

    /**
     * TC-DOC-UNION-01: Branch 1 of nominal union resolves with qualified header and metadata block.
     */
    public void testUnionVariant1QualifiedHeader() {
        var text = """
            {
              :defs {
                :Measurement :Union( { #size 32 } :Float :Float { #exact } :Float )
              }
              :type :Measurement
              :body #1 42.0
            }
            """;
        myFixture.configureByText("union_var1.stvn", text);
        var offset = text.indexOf("42.0");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Governing Schema must reflect variant #1 with #size 32: " + doc,
            doc.contains("<b>Governing Schema:</b> <code>:Measurement #1 ({ #size 32 } :Float)</code>"));
    }

    /**
     * TC-DOC-UNION-02: Branch 2 of nominal union resolves with qualified header and naked :Float.
     */
    public void testUnionVariant2QualifiedHeader() {
        var text = """
            {
              :defs {
                :Measurement :Union( { #size 32 } :Float :Float { #exact } :Float )
              }
              :type :Measurement
              :body #2 42.0
            }
            """;
        myFixture.configureByText("union_var2.stvn", text);
        var offset = text.indexOf("42.0");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Governing Schema must reflect variant #2 with naked :Float: " + doc,
            doc.contains("<b>Governing Schema:</b> <code>:Measurement #2 (:Float)</code>"));
    }

    /**
     * TC-DOC-UNION-03: Branch 3 of nominal union resolves with qualified header and #exact :Float.
     */
    public void testUnionVariant3QualifiedHeader() {
        var text = """
            {
              :defs {
                :Measurement :Union( { #size 32 } :Float :Float { #exact } :Float )
              }
              :type :Measurement
              :body #3 42.0
            }
            """;
        myFixture.configureByText("union_var3.stvn", text);
        var offset = text.indexOf("42.0");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Governing Schema must reflect variant #3 with #exact :Float: " + doc,
            doc.contains("<b>Governing Schema:</b> <code>:Measurement #3 ({ #exact } :Float)</code>"));
    }

    /**
     * TC-DOC-FPL-01: Continuous float precision loss documentation card presentation.
     */
    public void testContinuousFloatPrecisionLossDocCard() {
        var fiftyDigits = "3.14159265358979323846264338327950288419716939937510";
        var text = """
            {
              :defs {
                :ContinuousPi :Float
              }
              :type :ContinuousPi
              :body %s
            }
            """.formatted(fiftyDigits);
        myFixture.configureByText("continuous_pi.stvn", text);
        var offset = text.indexOf(fiftyDigits);
        var doc = getDocAtOffset(text, offset);

        assertTrue("Compliance status must be Satisfied (Precision Loss): " + doc,
            doc.contains("Satisfied (Precision Loss)"));
        assertTrue("Must include warning badge color #F57C00: " + doc,
            doc.toLowerCase().contains("f57c00"));
        assertTrue("Must report Authored Literal: " + doc,
            doc.contains("Authored Literal: <code>" + fiftyDigits + "</code>"));
        assertTrue("Must report Encoded Binary Float: " + doc,
            doc.contains("Encoded Binary Float: <code>3.141592653589793</code>"));
    }

    /**
     * TC-DOC-FPL-02: Continuous 32-bit float precision loss documentation presentation with ~8 digits.
     */
    public void testContinuous32BitFloatPrecisionLossDocCard() {
        var literal = "42.1234567890123456789";
        var text = """
            {
              :defs {
                :Weight { #size 32 } :Float
              }
              :type :Weight
              :body %s
            }
            """.formatted(literal);
        myFixture.configureByText("continuous_32bit_doc.stvn", text);
        var offset = text.indexOf(literal);
        var doc = getDocAtOffset(text, offset);

        assertTrue("Compliance status must be Satisfied (Precision Loss): " + doc,
            doc.contains("Satisfied (Precision Loss)"));
        assertTrue("Must report Single (32-bit) capacity (~8 digits): " + doc,
            doc.contains("Single (32-bit) capacity (~8 digits)"));
        assertTrue("Must report Encoded Binary Float 42.123455: " + doc,
            doc.contains("Encoded Binary Float: <code>42.123455</code>"));
    }

    /**
     * TC-DOC-FPL-03: Compliant 8-digit continuous 32-bit float produces Satisfied status without precision loss warning.
     */
    public void testContinuous32BitFloatCompliantDocCard() {
        var literal = "42.123455";
        var text = """
            {
              :defs {
                :Weight { #size 32 } :Float
              }
              :type :Weight
              :body %s
            }
            """.formatted(literal);
        myFixture.configureByText("continuous_32bit_clean_doc.stvn", text);
        var offset = text.indexOf(literal);
        var doc = getDocAtOffset(text, offset);

        assertTrue("Compliance status must be Satisfied: " + doc, doc.contains("Satisfied"));
        assertFalse("Must not report Precision Loss: " + doc, doc.contains("Precision Loss"));
        assertTrue("Must report conformation to 32-bit continuous float: " + doc,
            doc.contains("conforms to 32-bit continuous float representation"));
    }

    /**
     * Verifies TC-DOC-CTOR-01, TC-DOC-CTOR-02, TC-DOC-CTOR-03:
     * Quick documentation on a :Tuple constructor with declared metadata override {#equatable #FALSE}
     * renders Zone 2 Effective Semantic Contract table (with #equatable #FALSE [Declared / Active Override]
     * and derived #comparable #FALSE under STVN 2.0.0 trait implication), and renders Zone 3 Configurable Options (Unset) (#comparable).
     */
    public void testTupleConstructorZone2AndZone3Hover() {
        var text = """
            {
              :defs {
                :Values { #equatable #FALSE } :Tuple ( :Int :String )
              }
              :type :Values
              :body ( 42 "hello" )
            }
            """;
        myFixture.configureByText("tuple_doc.stvn", text);
        var offset = text.indexOf(":Tuple");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must contain 'Effective Facets & Traits:' header: " + doc, doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Doc must contain '#equatable' row: " + doc, doc.contains("<code>#equatable</code>"));
        assertTrue("Doc must display '#FALSE' for equatable: " + doc, doc.contains("<code>#FALSE</code>"));
        assertTrue("Doc must display 'Active Override' for equatable: " + doc, doc.contains("Active Override"));
        assertTrue("Doc must contain '#comparable' row: " + doc, doc.contains("<code>#comparable</code>"));
        assertTrue("Doc must display derived '#FALSE' for comparable: " + doc,
            doc.contains("<code>#comparable</code></td><td style=\"padding: 2px 4px;\"><code>#FALSE</code></td>"));
        assertTrue("Doc must contain 'Configurable Options (Unset):': " + doc, doc.contains("<b>Configurable Options (Unset):</b>"));
        assertTrue("Zone 3 unset options must include #comparable: " + doc, doc.contains("<code>#comparable</code>"));
    }

    /**
     * Verifies TC-DOC-CTOR-04:
     * Quick documentation on a :Seq collection constructor with declared size bounds
     * renders Zone 2 Effective Semantic Contract table with #minSize and #maxSize.
     */
    public void testSeqConstructorZone2AndZone3Hover() {
        var text = """
            {
              :defs {
                :List { #minSize 2 #maxSize 10 } :Seq ( :Int )
              }
              :type :List
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("seq_doc.stvn", text);
        var offset = text.indexOf(":Seq");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must contain 'Effective Facets & Traits:' header: " + doc, doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Doc must contain '#minSize' row: " + doc, doc.contains("<code>#minSize</code>"));
        assertTrue("Doc must display '2' for minSize: " + doc, doc.contains("<code>2</code>"));
        assertTrue("Doc must contain '#maxSize' row: " + doc, doc.contains("<code>#maxSize</code>"));
        assertTrue("Doc must display '10' for maxSize: " + doc, doc.contains("<code>10</code>"));
        assertTrue("Doc must contain 'Configurable Options (Unset):': " + doc, doc.contains("<b>Configurable Options (Unset):</b>"));
    }

    /**
     * Verifies TC-DOC-CTOR-05:
     * Quick documentation on a :Map collection constructor with #invertible
     * renders Zone 2 Effective Semantic Contract table with #invertible Active.
     */
    public void testMapConstructorZone2AndZone3Hover() {
        var text = """
            {
              :defs {
                :Dict { #invertible } :Map ( :String :Int )
              }
              :type :Dict
              :body { [ "key" 10 ] }
            }
            """;
        myFixture.configureByText("map_doc.stvn", text);
        var offset = text.indexOf(":Map");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must contain 'Effective Facets & Traits:' header: " + doc, doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Doc must contain '#invertible' row: " + doc, doc.contains("<code>#invertible</code>"));
        assertTrue("Doc must display 'Active' for invertible: " + doc, doc.contains("Active"));
        assertTrue("Doc must contain 'Configurable Options (Unset):': " + doc, doc.contains("<b>Configurable Options (Unset):</b>"));
        assertTrue("Zone 3 unset options must include #minSize: " + doc, doc.contains("<code>#minSize</code>"));
        assertTrue("Zone 3 unset options must include #maxSize: " + doc, doc.contains("<code>#maxSize</code>"));
    }

    /**
     * Verifies TC-DOC-CTOR-06:
     * Quick documentation on a :Union sum constructor with trait override {#comparable #FALSE}
     * renders Zone 2 Effective Semantic Contract table and Zone 3 Unset Facets list.
     */
    public void testUnionConstructorZone2AndZone3Hover() {
        var text = """
            {
              :defs {
                :Branch { #comparable #FALSE } :Union ( :Int :String )
              }
              :type :Branch
              :body #1 42
            }
            """;
        myFixture.configureByText("union_doc.stvn", text);
        var offset = text.indexOf(":Union");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Doc must contain 'Effective Facets & Traits:' header: " + doc, doc.contains("<b>Effective Facets &amp; Traits:</b>"));
        assertTrue("Doc must contain '#comparable' row: " + doc, doc.contains("<code>#comparable</code>"));
        assertTrue("Doc must display '#FALSE' for comparable: " + doc, doc.contains("<code>#FALSE</code>"));
        assertTrue("Doc must contain 'Configurable Options (Unset):': " + doc, doc.contains("<b>Configurable Options (Unset):</b>"));
        assertTrue("Zone 3 unset options must include #equatable: " + doc, doc.contains("<code>#equatable</code>"));
    }

    public void testCapabilityRestoringTraitOverrideSuppressesW003HoverWarning() {
        var text = """
            {
              :defs {
                :SensorSample { #equatable #TRUE } :Tuple( :String :Float )
              }
              :type :SensorSample
              :body ( "sensor1" 98.6 )
            }
            """;
        myFixture.configureByText("sensor_sample.stvn", text);
        var offset = text.indexOf("#equatable #TRUE");
        var doc = getDocAtOffset(text, offset);

        assertFalse("Capability-restoring trait override must NOT trigger Warning W003 hover banner:\n" + doc,
                doc.contains("Warning W003: Redundant Metadata Facet"));
        assertTrue("Must display standard metadata facet documentation for #equatable:\n" + doc,
                doc.contains("<b>Metadata Facet:</b> <code>#equatable #TRUE</code>"));
    }

    public void testRedundantTraitOverrideOnCompositeRendersW003WithoutScalarPrimitiveJargon() {
        var text = """
            {
              :defs {
                :RedundantTuple { #equatable #TRUE } :Tuple( :String :Int )
              }
              :type :RedundantTuple
              :body ( "node1" 8080 )
            }
            """;
        myFixture.configureByText("redundant_tuple.stvn", text);
        var offset = text.indexOf("#equatable #TRUE");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Genuinely redundant trait override on composite must render Warning W003 hover banner:\n" + doc,
                doc.contains("Warning W003: Redundant Metadata Facet"));
        assertFalse("Warning W003 must NOT mislabel composite tuple as 'scalar primitive':\n" + doc,
                doc.contains("scalar primitive"));
        assertTrue("Warning W003 must label composite tuple as 'composite schema':\n" + doc,
                doc.contains("composite schema <code>:Tuple( :String :Int )</code> is intrinsically equatable"));
    }

    public void testDateTimeThreeTupleHoverComplianceSatisfiedAndViolatedWithoutStringCapacityLeak() {
        var text = """
            {
              :defs {
                :DateTimes :Tuple (
                  {#offset} :DateTime
                  {#zoned} :DateTime
                  {#audited} :DateTime
                )
              }
              :type { #maxSize 4096 } :Seq(:DateTimes)
              :body [
                (
                  "2026-09-12T15:30:00.001-04:00"
                  "2026-09-12T15:30:00.002[America/New_York]"
                  "2026-09-12T15:30:00.003-04:00[America/New_York]"
                )

                (
                  "2026-09-12T15:30:00.004-04:00[America/New_York]"
                  "2026-09-12T15:30:00.005-04:00"
                  "2026-09-12T15:30:00.006[America/New_York]"
                )

                (
                  "2026-09-12T15:30:00.007[America/New_York]"
                  "2026-09-12T15:30:00.008-04:00[America/New_York]"
                  "2026-09-12T15:30:00.009-04:00"
                )
              ]
            }
            """;
        myFixture.configureByText("date_time.stvn", text);

        var validExpectations = List.of(
            List.of("\"2026-09-12T15:30:00.001-04:00\"", "{ #offset } :DateTime"),
            List.of("\"2026-09-12T15:30:00.002[America/New_York]\"", "{ #zoned } :DateTime"),
            List.of("\"2026-09-12T15:30:00.003-04:00[America/New_York]\"", "{ #audited } :DateTime")
        );
        for (var entry : validExpectations) {
            String literal = entry.get(0);
            String expectedMode = entry.get(1);
            String doc = getDocAtOffset(text, text.indexOf(literal) + 2);
            assertTrue("Valid Tuple 1 literal must display Satisfied:\n" + doc,
                doc.contains(">Satisfied</b>"));
            assertTrue("Valid Tuple 1 literal must display active mode " + expectedMode + ":\n" + doc,
                doc.contains(expectedMode));
            assertTrue("DateTime hover must list Simple Quoted lexical form:\n" + doc,
                doc.contains("Simple Quoted:"));
            assertFalse("DateTime hover must NEVER list Verbatim Fenced lexical form:\n" + doc,
                doc.contains("Verbatim Fenced"));
            assertFalse("DateTime hover must NEVER leak :String capacity bounds [0, 16777216]:\n" + doc,
                doc.contains("16777216"));
        }

        var invalidExpectations = List.of(
            List.of("\"2026-09-12T15:30:00.004-04:00[America/New_York]\"",
                "Invalid { #offset } :DateTime literal: IANA time zone brackets '[...]' are prohibited; expected \"YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm)\""),
            List.of("\"2026-09-12T15:30:00.005-04:00\"",
                "Invalid { #zoned } :DateTime literal: UTC offset ('Z' or '±HH:mm') is prohibited and missing required IANA time zone '[Region/City]'; expected \"YYYY-MM-DDTHH:mm:ss[.fraction][Region/City]\""),
            List.of("\"2026-09-12T15:30:00.006[America/New_York]\"",
                "Invalid { #audited } :DateTime literal: missing required UTC offset ('Z' or '±HH:mm'); expected \"YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm)[Region/City]\""),
            List.of("\"2026-09-12T15:30:00.007[America/New_York]\"",
                "Invalid { #offset } :DateTime literal: IANA time zone brackets '[...]' are prohibited and missing required UTC offset ('Z' or '±HH:mm'); expected \"YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm)\""),
            List.of("\"2026-09-12T15:30:00.008-04:00[America/New_York]\"",
                "Invalid { #zoned } :DateTime literal: UTC offset ('Z' or '±HH:mm') is prohibited; expected \"YYYY-MM-DDTHH:mm:ss[.fraction][Region/City]\""),
            List.of("\"2026-09-12T15:30:00.009-04:00\"",
                "Invalid { #audited } :DateTime literal: missing required IANA time zone '[Region/City]'; expected \"YYYY-MM-DDTHH:mm:ss[.fraction](Z|±HH:mm)[Region/City]\"")
        );
        for (var entry : invalidExpectations) {
            String literal = entry.get(0);
            String expectedThreePartError = entry.get(1);
            String doc = getDocAtOffset(text, text.indexOf(literal) + 2);
            assertTrue("Invalid literal " + literal + " must display Violated:\n" + doc,
                doc.contains(">Violated</b>"));
            assertTrue("Invalid literal " + literal + " must display unified 3-part Core message:\n" + doc,
                doc.contains(expectedThreePartError));
            assertFalse("Invalid DateTime hover must NEVER list Verbatim Fenced:\n" + doc,
                doc.contains("Verbatim Fenced"));
            assertFalse("Invalid DateTime hover must NEVER leak :String capacity bounds:\n" + doc,
                doc.contains("satisfies capacity bounds"));
        }
    }

    public void testDateTimeHoverBoundsAndFencedStringProhibition() {
        var text = """
            {
              :defs {
                :BoundedWindow { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
              }
              :type :Tuple(:BoundedWindow :BoundedWindow)
              :body (
                "2025-12-31T23:59:59Z"
                \"\"\"[TS]
            2026-06-01T00:00:00Z
            [TS]\"\"\"
              )
            }
            """;
        myFixture.configureByText("datetime_bounds_hover.stvn", text);

        var outOfBoundsDoc = getDocAtOffset(text, text.indexOf("\"2025-12-31T23:59:59Z\"") + 2);
        assertTrue("Below-#minIncl timestamp must display Violated:\n" + outOfBoundsDoc, outOfBoundsDoc.contains(">Violated</b>"));
        assertTrue("Below-#minIncl timestamp must report interval bound failure:\n" + outOfBoundsDoc,
            outOfBoundsDoc.contains("below minimum inclusive bound '2026-01-01T00:00:00Z'"));

        var fencedDoc = getDocAtOffset(text, text.indexOf("\"\"\"[TS]") + 3);
        assertTrue("Fenced string on :DateTime must display Violated:\n" + fencedDoc, fencedDoc.contains(">Violated</b>"));
        assertTrue("Fenced string on :DateTime must display Core prohibition message:\n" + fencedDoc,
            fencedDoc.contains("fenced multi-line strings are prohibited"));
        assertFalse("Fenced string on :DateTime must NEVER advertise Verbatim Fenced as valid:\n" + fencedDoc,
            fencedDoc.contains("Verbatim Fenced"));
    }

    public void testHoverOnPayloadSlotGovernedByPoisonedSchemaShowsDegradedCompliance() {
        var text = """
            {
              :defs {
                :OffsetTs { #ms #offset } :DateTime
                :ExactPrice { #exact } :Float
                :EventRow :Tuple(:OffsetTs :ExactPrice)
              }
              :type :EventRow
              :body (
                "2026-03-29T12:00:00Z"
                19.95
              )
            }
            """;
        myFixture.configureByText("degraded_slot_hover.stvn", text);

        var slot0Doc = getDocAtOffset(text, text.indexOf("\"2026-03-29T12:00:00Z\"") + 2);
        assertTrue("Slot 0 governed by PoisonedSchema :OffsetTs must display Satisfied (Degraded Schema):\n" + slot0Doc,
            slot0Doc.contains("Satisfied (Degraded Schema)"));
        assertTrue("Slot 0 must include degraded schema explanation note:\n" + slot0Doc,
            slot0Doc.contains("Governing schema <code>:OffsetTs</code> is degraded"));

        var slot1Doc = getDocAtOffset(text, text.indexOf("19.95") + 1);
        assertTrue("Healthy Slot 1 (:ExactPrice) must display Satisfied without degraded notice:\n" + slot1Doc,
            slot1Doc.contains(">Satisfied</b>"));
        assertFalse("Healthy Slot 1 (:ExactPrice) must NOT mention degraded schema:\n" + slot1Doc,
            slot1Doc.contains("Degraded Schema"));
    }

    public void testDeepSumTaggedTupleHoverAtLine136_TimeEpochSlotTypeMismatch() {
        var text = """
            {
              :defs {
                :DeepTelemetryMatrix :Union(
                  :String
                  :Tuple(
                    { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
                    { #ns } :TimeEpoch
                    { #maxSize 4096 } :Set( :Tuple( { #unsigned #size 8 } :Int :Boolean ) )
                  )
                )
                :Envelope :Option( :Either( :String :DeepTelemetryMatrix ) )
              }
              :type :Envelope
              :body #Some #Right #2 ( 999 "not-an-epoch" [ (7 #TRUE) ] )
            }
            """;
        myFixture.configureByText("deep_sum_tuple_epoch.stvn", text);

        var offset = text.indexOf("\"not-an-epoch\"") + 2;
        var doc = getDocAtOffset(text, offset);

        assertTrue("Value Type must resolve to { #ns } :TimeEpoch:\n" + doc,
            doc.contains("<b>Value Type:</b> { #ns } :TimeEpoch"));
        assertTrue("Governing Schema must resolve to { #ns } :TimeEpoch:\n" + doc,
            doc.contains("<b>Governing Schema:</b> <code>{ #ns } :TimeEpoch</code>"));
        assertFalse("Governing Schema must NOT stop at outer :DeepTelemetryMatrix #2 (:Tuple(...)):\n" + doc,
            doc.contains(":DeepTelemetryMatrix #2"));
        assertTrue("Compliance Status must be Violated:\n" + doc,
            doc.contains(">Violated</b>"));
        assertTrue("Explanation must report Type mismatch for :TimeEpoch integer tick literal:\n" + doc,
            doc.contains("Type mismatch: expected { #ns } :TimeEpoch integer tick literal, found :String literal"));
        assertTrue("Hover card must include Exemplar Value:\n" + doc,
            doc.contains("<b>Exemplar Value:</b> <code>"));
        assertTrue("Valid Lexical Forms must include Decimal, Hexadecimal, Binary, and Octal:\n" + doc,
            doc.contains("Decimal:") && doc.contains("Hexadecimal:") && doc.contains("Binary:") && doc.contains("Octal:"));
    }

    public void testDeepSumTaggedTupleHoverAtLine164_DateTimeOffsetViolation() {
        var text = """
            {
              :defs {
                :Port { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } :Int
                :DeepFaultIsolationProbe :Union(
                  :String
                  :Tuple(
                    :Port
                    { #offset } :DateTime
                    { #unsigned #size 8 #maxExcl 100 } :Int
                  )
                )
                :FaultEnvelope :Option( :Either( :String :DeepFaultIsolationProbe ) )
              }
              :type :FaultEnvelope
              :body #Some #Right ( #2 ( 8080 "2026-11-03T20:45:00[America/Chicago]" 250 ) )
            }
            """;
        myFixture.configureByText("deep_sum_tuple_datetime.stvn", text);

        var offset = text.indexOf("\"2026-11-03T20:45:00[America/Chicago]\"") + 2;
        var doc = getDocAtOffset(text, offset);

        assertTrue("Value Type must resolve to { #offset } :DateTime:\n" + doc,
            doc.contains("<b>Value Type:</b> { #offset } :DateTime"));
        assertTrue("Governing Schema must resolve to { #offset } :DateTime:\n" + doc,
            doc.contains("<b>Governing Schema:</b> <code>{ #offset } :DateTime</code>"));
        assertFalse("Governing Schema must NOT stop at outer :DeepFaultIsolationProbe #2 (:Tuple(...)):\n" + doc,
            doc.contains(":DeepFaultIsolationProbe #2"));
        assertTrue("Compliance Status must be Violated:\n" + doc,
            doc.contains(">Violated</b>"));
        assertTrue("Compliance explanation must report prohibited IANA time zone brackets in #offset mode:\n" + doc,
            doc.contains("IANA time zone brackets '[...]' are prohibited"));
        assertTrue("Hover card must include Exemplar Value:\n" + doc,
            doc.contains("<b>Exemplar Value:</b> <code>\""));
        assertTrue("Valid Lexical Forms must include ISO-8601 String:\n" + doc,
            doc.contains("ISO-8601 String"));
    }

    public void testExemplarValueGenerationAcrossAllScalarAndTemporalModes() {
        Clock fixedClock = Clock.fixed(
            Instant.parse("2026-11-04T14:17:00Z"),
            ZoneId.of("America/Chicago")
        );

        assertEquals(
            "\"2026-11-04T08:17:00-06:00\"",
            StvnExemplarValueGenerator.generateDateTimeExemplar(DateTimeMode.OFFSET, null, null, fixedClock)
        );
        assertEquals(
            "\"2026-11-04T08:17:00[America/Chicago]\"",
            StvnExemplarValueGenerator.generateDateTimeExemplar(DateTimeMode.ZONED, null, null, fixedClock)
        );
        assertEquals(
            "\"2026-11-04T08:17:00-06:00[America/Chicago]\"",
            StvnExemplarValueGenerator.generateDateTimeExemplar(DateTimeMode.AUDITED, null, null, fixedClock)
        );

        assertEquals(
            "1793801820",
            StvnExemplarValueGenerator.generateTimeEpochExemplar(TemporalScale.S, false, 64, null, null, fixedClock)
        );
        assertEquals(
            "1793801820000",
            StvnExemplarValueGenerator.generateTimeEpochExemplar(TemporalScale.MS, false, 64, null, null, fixedClock)
        );
        assertEquals(
            "1793801820000000",
            StvnExemplarValueGenerator.generateTimeEpochExemplar(TemporalScale.US, false, 64, null, null, fixedClock)
        );
        assertEquals(
            "1793801820000000000",
            StvnExemplarValueGenerator.generateTimeEpochExemplar(TemporalScale.NS, false, 128, null, null, fixedClock)
        );

        assertEquals(
            "1",
            StvnExemplarValueGenerator.generateIntExemplar(
                true,
                16,
                BigInteger.ONE,
                BigInteger.valueOf(1000)
            )
        );
        assertEquals(
            "0",
            StvnExemplarValueGenerator.generateIntExemplar(
                true,
                8,
                null,
                BigInteger.valueOf(100)
            )
        );
        assertEquals("0.0", StvnExemplarValueGenerator.generateFloatExemplar(null, null, null, null));
        assertEquals("\"example\"", StvnExemplarValueGenerator.generateStringExemplar(null, null, null, null, false));

        var text = """
            {
              :defs {
                :Method :Enum [ #GET #POST #DELETE ]
                :WriteMethod { #filterExcl [ #GET ] } :Method
                :Pair :Tuple( :WriteMethod :Boolean )
                :Lookup { #minSize 1 } :Map( :String :WriteMethod )
              }
              :type :Pair
              :body ( #POST #TRUE )
            }
            """;
        var file = myFixture.configureByText("exemplar_composite.stvn", text);
        var defs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : defs) {
            var name = def.getTypeKeyword() != null ? def.getTypeKeyword().getText().trim() : "";
            var exemplar = StvnExemplarValueGenerator.generateExemplar(def.getSchemaType(), null, fixedClock);
            switch (name) {
                case ":Method" -> assertEquals("#GET", exemplar);
                case ":WriteMethod" -> assertEquals("#POST", exemplar);
                case ":Pair" -> assertEquals("(#POST #TRUE)", exemplar);
                case ":Lookup" -> assertEquals("{ [\"example\" #POST] }", exemplar);
                default -> {}
            }
        }
    }

    public void testStage9NestedPackageIllegalSuppressesFalseTypeAliasHoverCard() {
        var text = """
            {
              :defs {
                :package :com/acme/identity {
                  :UserId { #unsigned #size 64 #minIncl 1000 } :Int
                  :package :com/acme/identity/nested {
                    :IllegalNestedType :Int
                  }
                }
              }
              :type :Int
              :body 0
            }
            """;
        var file = myFixture.configureByText("stage9_nested_pkg_hover.stvn_i", text);
        var editor = myFixture.getEditor();

        int nestedKwOffset = text.indexOf(":package :com/acme/identity/nested") + 2;
        var kwElem = file.findElementAt(nestedKwOffset);
        assertNotNull(kwElem);
        var kwDocElem = provider.getCustomDocumentationElement(editor, file, kwElem, nestedKwOffset);
        var kwDoc = kwDocElem != null ? provider.generateDoc(kwDocElem, kwElem) : provider.generateDoc(kwElem, kwElem);
        assertNull("Hovering illegal nested ':package' keyword must return null documentation", kwDoc);

        int nestedPathOffset = text.indexOf(":com/acme/identity/nested") + 2;
        var pathElem = file.findElementAt(nestedPathOffset);
        assertNotNull(pathElem);
        var pathDocElem = provider.getCustomDocumentationElement(editor, file, pathElem, nestedPathOffset);
        var pathDoc = pathDocElem != null ? provider.generateDoc(pathDocElem, pathElem) : provider.generateDoc(pathElem, pathElem);
        assertNull("Hovering illegal nested package path must return null documentation", pathDoc);
    }

    public void testStage7OptionSomeArityOneTupleHoverDisplaysViolationAndGoverningSchemaOnBothTupleAndInnerScalar() {
        var text = """
            {
              :defs {
                :OptionalCount :Option({ #unsigned #size 16 } :Int)
              }
              :type :OptionalCount
              :body #Some (42)
            }
            """;
        myFixture.configureByText("stage7_option_some_tuple_hover.stvn", text);

        int parenOffset = text.indexOf("(42)");
        String tupleDoc = getDocAtOffset(text, parenOffset);
        assertTrue(
            "Tuple hover must preserve enclosing :OptionalCount nominal schema:\n" + tupleDoc,
            tupleDoc.contains("<b>Governing Schema:</b> <code>:OptionalCount ({ #unsigned #size 16 } :Int)</code>")
        );
        assertTrue(
            "Tuple hover must display In Violation:\n" + tupleDoc,
            tupleDoc.contains(">In Violation</b>")
        );
        assertTrue(
            "Tuple hover must explain scalar vs product constructor mismatch:\n" + tupleDoc,
            tupleDoc.contains("Type mismatch: expected scalar :Int, found product constructor :Tuple")
        );

        int scalarOffset = text.indexOf("42");
        String scalarDoc = getDocAtOffset(text, scalarOffset);
        assertTrue(
            "Inner scalar hover must preserve enclosing :OptionalCount nominal schema:\n" + scalarDoc,
            scalarDoc.contains("<b>Governing Schema:</b> <code>:OptionalCount ({ #unsigned #size 16 } :Int)</code>")
        );
        assertTrue(
            "Inner scalar hover must display In Violation:\n" + scalarDoc,
            scalarDoc.contains(">In Violation</b>")
        );
        assertTrue(
            "Inner scalar hover must explain scalar vs product constructor mismatch:\n" + scalarDoc,
            scalarDoc.contains("Type mismatch: expected scalar :Int, found product constructor :Tuple")
        );
    }

    public void testHoverOnNominalInt32AndViolatingDerivedInt16bRendersAccurateContractAndUnsetOptions() {
        var text = """
            {
              :meta {
                #name "uhoh"
                #kind #BODY
              }
              :defs {
                :Int32 :Int
                :Int16b { #size 16 } :Int32
              }
              :type :Int32
              :body 1
            }
            """;
        myFixture.configureByText("uhoh.stvn", text);

        // 1. Hover on ':Int32' in ':Int32 :Int'
        int lhsInt32Offset = text.indexOf(":Int32 :Int") + 2;
        String int32Doc = getDocAtOffset(text, lhsInt32Offset);
        assertTrue("Expected ':Int32 -> :Int' resolution path:\n" + int32Doc, int32Doc.contains(":Int32 &rarr; :Int"));
        assertTrue("Expected 'Underlying Structure: :Int':\n" + int32Doc, int32Doc.contains("<b>Underlying Structure:</b> :Int"));
        assertTrue("Expected '#unsigned' in Configurable Options (Unset) on ':Int32':\n" + int32Doc, int32Doc.contains("<code>#unsigned</code>"));

        // 2. Hover on ':Int16b' in ':Int16b { #size 16 } :Int32'
        int lhsInt16bOffset = text.indexOf(":Int16b") + 2;
        String int16bDoc = getDocAtOffset(text, lhsInt16bOffset);
        assertTrue("Expected Degraded Schema Warning banner on ':Int16b':\n" + int16bDoc, int16bDoc.contains("Degraded Schema Warning"));
        assertTrue("Expected '#size' row marked 'Invalid' on ':Int16b':\n" + int16bDoc, int16bDoc.contains("#size") && int16bDoc.contains("Invalid"));
        assertFalse("Locked Tier-1 facet '#unsigned' must not be listed as unset on derived ':Int16b':\n" + int16bDoc, int16bDoc.contains("<code>#unsigned</code>"));
    }

    public void testStage12CircularTypeAliasesAndGuardedRecursiveTreeNodeHoverWithoutStackOverflow() {
        var text = """
            {
              :meta {
                #name "gauntlet_v3_circular_types_recursion_and_duplicate_defs"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :CycleAlpha :CycleBeta
                :CycleBeta  :CycleAlpha

                :TreeLabel { #minSize 1 #maxSize 32 } :String
                :TreeNode  :Tuple(
                  :TreeLabel
                  :Seq(:TreeNode)
                )
              }
              :type :TreeNode
              :body (
                "root"
                [
                  ("leaf-1" [])
                ]
              )
            }
            """;
        myFixture.configureByText("gauntlet_v3_circular_types_recursion_and_duplicate_defs.core_test.stvn_i", text);

        // 1. Hover on LHS ':CycleAlpha'
        int lhsAlphaOffset = text.indexOf(":CycleAlpha :CycleBeta") + 2;
        String lhsAlphaDoc = getDocAtOffset(text, lhsAlphaOffset);
        assertNotNull("Hover on LHS :CycleAlpha must not be null", lhsAlphaDoc);
        assertTrue("LHS :CycleAlpha must show Degraded Schema Warning:\n" + lhsAlphaDoc, lhsAlphaDoc.contains("Degraded Schema Warning"));
        assertTrue("LHS :CycleAlpha must explain ungrounded lineage:\n" + lhsAlphaDoc, lhsAlphaDoc.contains("does not resolve to a base type in <code>:defs</code>"));
        assertTrue("LHS :CycleAlpha must state that payload evaluation is suspended:\n" + lhsAlphaDoc, lhsAlphaDoc.contains("Payload evaluation is suspended."));
        assertFalse("LHS :CycleAlpha must NEVER fabricate ':Value':\n" + lhsAlphaDoc, lhsAlphaDoc.contains(":Value"));
        assertTrue("LHS :CycleAlpha must show cycle resolution path:\n" + lhsAlphaDoc, lhsAlphaDoc.contains(":CycleAlpha &rarr; :CycleBeta &rarr; :CycleAlpha"));

        // 2. Hover on RHS ':CycleBeta' on line ':CycleAlpha :CycleBeta'
        int rhsBetaOffset = text.indexOf(":CycleAlpha :CycleBeta") + ":CycleAlpha ".length() + 2;
        String rhsBetaDoc = getDocAtOffset(text, rhsBetaOffset);
        assertNotNull("Hover on RHS :CycleBeta must not be null", rhsBetaDoc);
        assertTrue("RHS :CycleBeta must show Degraded Schema Warning:\n" + rhsBetaDoc, rhsBetaDoc.contains("Degraded Schema Warning"));

        // 3. Hover on LHS ':CycleBeta' on line ':CycleBeta  :CycleAlpha'
        int lhsBetaOffset = text.indexOf(":CycleBeta  :CycleAlpha") + 2;
        String lhsBetaDoc = getDocAtOffset(text, lhsBetaOffset);
        assertNotNull("Hover on LHS :CycleBeta must not be null", lhsBetaDoc);
        assertTrue("LHS :CycleBeta must show Degraded Schema Warning:\n" + lhsBetaDoc, lhsBetaDoc.contains("Degraded Schema Warning"));

        // 4. Hover on guarded recursive ':TreeNode'
        int treeNodeOffset = text.indexOf(":TreeNode  :Tuple") + 2;
        String treeNodeDoc = getDocAtOffset(text, treeNodeOffset);
        assertNotNull("Hover on :TreeNode must not be null", treeNodeDoc);
        assertTrue("Guarded recursive :TreeNode must render Arity 2:\n" + treeNodeDoc, treeNodeDoc.contains("<b>Arity:</b> 2"));
        assertTrue("Guarded recursive :TreeNode must render exemplar value:\n" + treeNodeDoc, treeNodeDoc.contains("(\"example\" [])"));

        // 5. Hover on :body value governed by ungrounded :CycleAlpha reports Suspended (Ungrounded Schema) and zero ':Value'
        var bodyCycleText = """
            {
              :defs {
                :CycleAlpha :CycleBeta
                :CycleBeta  :CycleAlpha
              }
              :type :CycleAlpha
              :body 100
            }
            """;
        myFixture.configureByText("stage12_body_cycle_hover.stvn_i", bodyCycleText);
        int bodyValOffset = bodyCycleText.indexOf("100") + 1;
        String bodyCycleValDoc = getDocAtOffset(bodyCycleText, bodyValOffset);
        assertNotNull("Hover on :body value governed by :CycleAlpha must not be null", bodyCycleValDoc);
        assertTrue("Hover on :body value governed by :CycleAlpha must report Suspended (Ungrounded Schema):\n" + bodyCycleValDoc,
            bodyCycleValDoc.contains("Suspended (Ungrounded Schema)"));
        assertFalse("Hover on :body value governed by :CycleAlpha must NEVER fabricate ':Value':\n" + bodyCycleValDoc,
            bodyCycleValDoc.contains(":Value"));
    }

    public void testSignedBit1SizeFacetDoesNotRenderSpuriousScaleSRow() {
        var text = """
            {
              :meta {
                #name "gauntlet_v4_stage2_discrete_integers_sizing_and_radixes"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :SignedBit1 { #size 1 } :Int
                :Float64Explicit { #size 64 } :Float
                :BoundedStr { #minSize 1 #maxSize 8 } :String
              }
              :type :SignedBit1
              :body -1
            }
            """;
        myFixture.configureByText("gauntlet_v4_stage2_discrete_integers_sizing_and_radixes.core_test.stvn_i", text);

        int signedBit1Offset = text.indexOf(":SignedBit1 { #size 1 } :Int") + 2;
        String signedBit1Doc = getDocAtOffset(text, signedBit1Offset);
        assertNotNull("Hover on :SignedBit1 must not be null", signedBit1Doc);
        assertTrue("Hover on :SignedBit1 must include #size row:\n" + signedBit1Doc,
            signedBit1Doc.contains("<code>#size</code>"));
        assertTrue("Hover on :SignedBit1 must report Active Override for #size:\n" + signedBit1Doc,
            signedBit1Doc.contains("Active Override"));
        assertFalse("Hover on :SignedBit1 must NEVER synthesize a spurious #s facet row:\n" + signedBit1Doc,
            signedBit1Doc.contains("<code>#s</code>"));

        int float64Offset = text.indexOf(":Float64Explicit") + 2;
        String float64Doc = getDocAtOffset(text, float64Offset);
        assertNotNull("Hover on :Float64Explicit must not be null", float64Doc);
        assertTrue("Hover on :Float64Explicit must include #size row:\n" + float64Doc,
            float64Doc.contains("<code>#size</code>"));
        assertFalse("Hover on :Float64Explicit must NEVER synthesize a spurious #s facet row:\n" + float64Doc,
            float64Doc.contains("<code>#s</code>"));

        int boundedStrOffset = text.indexOf(":BoundedStr") + 2;
        String boundedStrDoc = getDocAtOffset(text, boundedStrOffset);
        assertNotNull("Hover on :BoundedStr must not be null", boundedStrDoc);
        assertFalse("Hover on :BoundedStr must NEVER synthesize a spurious #s facet row:\n" + boundedStrDoc,
            boundedStrDoc.contains("<code>#s</code>"));
    }

    public void testStage4HoverZone3NeverAdvertisesMinSizeOrMaxSizeOnDerivedNominalAliases() {
        var text = """
            {
              :defs {
                :ImplicitString :String
                :DerivedString :ImplicitString
                :ImplicitSeq :Seq( :Int )
                :DerivedSeq :ImplicitSeq
                :ImplicitSet :Set( :Int )
                :DerivedSet :ImplicitSet
                :ImplicitMap :Map( :String :Int )
                :DerivedMap :ImplicitMap
                :ExactFloat { #exact } :Float
                :DerivedExactFloat :ExactFloat
              }
              :type :DerivedString
              :body "ok"
            }
            """;
        myFixture.configureByText("stage4_hover_zone3_locks.stvn", text);

        for (String alias : java.util.List.of(
                ":DerivedString", ":DerivedSeq", ":DerivedSet", ":DerivedMap", ":DerivedExactFloat")) {
            int offset = text.indexOf(alias + " :");
            String doc = getDocAtOffset(text, offset + 2);
            assertNotNull("Hover on " + alias + " must not be null", doc);
            int zone3Idx = doc.indexOf("Configurable Options (Unset):");
            if (zone3Idx >= 0) {
                String zone3 = doc.substring(zone3Idx);
                assertFalse("Zone 3 on " + alias + " must never advertise #minSize:\n" + zone3,
                    zone3.contains("<code>#minSize</code>"));
                assertFalse("Zone 3 on " + alias + " must never advertise #maxSize:\n" + zone3,
                    zone3.contains("<code>#maxSize</code>"));
                assertFalse("Zone 3 on " + alias + " must never advertise #invertible:\n" + zone3,
                    zone3.contains("<code>#invertible</code>"));
            }
        }
    }

    public void testStage6ContainerHoverComplianceViolatedOnSlots7Through13AndSatisfiedOnHealthySiblings() {
        var text = """
            {
              :defs {
                :BoundedSeq { #minSize 2 #maxSize 2 } :Seq( :Int )
                :BoundedSet { #minSize 2 #maxSize 3 } :Set( :String )
                :SimpleMap :Map( :String :Int )
                :BijectiveMap { #invertible } :Map( :String :Int )
              }
              :type :Tuple(
                :Tuple( :Int :String )
                :Tuple( :Int :String )
                :BoundedSeq
                :BoundedSeq
                :BoundedSet
                :SimpleMap
                :BijectiveMap
                :BoundedSeq
                :BoundedSet
                :BijectiveMap
              )
              :body (
                ( 10 )
                ( 10 "alpha" #TRUE )
                [ 10 ]
                [ 10 20 30 ]
                [ "a" "a" ]
                { [ "k1" 1 ] [ "k1" 2 ] }
                { [ "k1" 100 ] [ "k2" 100 ] }
                [ 10 20 ]
                [ "a" "b" ]
                { [ "k1" 100 ] [ "k2" 200 ] }
              )
            }
            """;
        myFixture.configureByText("stage6_container_hover_compliance.stvn", text);
        int bodyIdx = text.indexOf(":body");

        for (String violatedSnippet : java.util.List.of(
                "( 10 )",
                "( 10 \"alpha\" #TRUE )",
                "[ 10 ]",
                "[ 10 20 30 ]",
                "[ \"a\" \"a\" ]",
                "{ [ \"k1\" 1 ] [ \"k1\" 2 ] }",
                "{ [ \"k1\" 100 ] [ \"k2\" 100 ] }")) {
            int offset = text.indexOf(violatedSnippet, bodyIdx);
            String doc = getDocAtOffset(text, offset);
            assertNotNull("Hover on " + violatedSnippet + " must not be null", doc);
            assertTrue("Hover on violating container " + violatedSnippet + " must report Violated:\n" + doc,
                doc.contains("Violated"));
        }

        for (String healthySnippet : java.util.List.of(
                "[ 10 20 ]",
                "[ \"a\" \"b\" ]",
                "{ [ \"k1\" 100 ] [ \"k2\" 200 ] }")) {
            int offset = text.indexOf(healthySnippet, bodyIdx);
            String doc = getDocAtOffset(text, offset);
            assertNotNull("Hover on " + healthySnippet + " must not be null", doc);
            assertTrue("Hover on healthy container " + healthySnippet + " must report Satisfied:\n" + doc,
                doc.contains("Satisfied"));
            assertFalse("Hover on healthy container " + healthySnippet + " must not report Violated:\n" + doc,
                doc.contains("Violated"));
        }
    }

    public void testStage7SingleBranchUnionHoverInDefsAndBody() {
        var text = """
            {
              :meta {
                #name "single_branch_union_poisoning"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :ErrorCode { #unsigned #size 16 #minIncl 400 #maxExcl 600 } :Int
                :TrapSingleBranchUnion :Union(:ErrorCode)
                :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 4096 } :Int
                :Stage7PoisonEnvelope :Tuple(
                  :TrapSingleBranchUnion
                  :BoundedPort
                )
              }
              :type :Stage7PoisonEnvelope
              :body (
                #1 404
                5000
              )
            }
            """;
        myFixture.configureByText("single_branch_union_poisoning.core_test.stvn_i", text);

        int defsOffset = text.indexOf(":TrapSingleBranchUnion");
        String defsDoc = getDocAtOffset(text, defsOffset + 2);
        assertNotNull("Hover on :TrapSingleBranchUnion in :defs must not be null", defsDoc);
        assertTrue("Hover on :TrapSingleBranchUnion in :defs must show Case A ungrounded banner:\n" + defsDoc,
            defsDoc.contains("Degraded Schema Warning")
                && defsDoc.contains("does not resolve to a base type in <code>:defs</code>")
                && defsDoc.contains("Payload evaluation is suspended."));
        assertFalse("Hover on :TrapSingleBranchUnion in :defs must not fabricate ':Value':\n" + defsDoc,
            defsDoc.contains(":Value"));

        int bodyIdx = text.indexOf(":body");
        int tagOffset = text.indexOf("#1 404", bodyIdx);
        String tagDoc = getDocAtOffset(text, tagOffset + 1);
        assertNotNull("Hover on #1 in Slot 13 must not be null", tagDoc);
        assertTrue("Hover on #1 in Slot 13 must show Suspended (Ungrounded Schema):\n" + tagDoc,
            tagDoc.contains("Suspended (Ungrounded Schema)"));
        assertFalse("Hover on #1 in Slot 13 must not unwrap into :ErrorCode:\n" + tagDoc,
            tagDoc.contains("Active Branch"));

        int innerOffset = text.indexOf("404", bodyIdx);
        String innerDoc = getDocAtOffset(text, innerOffset + 1);
        assertNotNull("Hover on 404 in Slot 13 must not be null", innerDoc);
        assertTrue("Hover on 404 in Slot 13 must show Suspended (Ungrounded Schema):\n" + innerDoc,
            innerDoc.contains("Suspended (Ungrounded Schema)"));
        assertFalse("Hover on 404 in Slot 13 must not unwrap into :ErrorCode:\n" + innerDoc,
            innerDoc.contains("Active Branch"));
    }
}
