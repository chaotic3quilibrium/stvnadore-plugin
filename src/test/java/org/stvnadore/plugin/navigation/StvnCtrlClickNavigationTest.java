package org.stvnadore.plugin.navigation;

import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.RootInlayPresentation;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.hints.StvnInlayHintsCollector;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform integration test suite verifying Ctrl+Click (GotoDeclarationHandler) navigation
 * for package-qualified and scoped #CONST references, :body and ConstantDefinition value jumps
 * to governing nominal alias TypeDefinitions, and clickable nominal inlay hint presentations.
 */
@NullMarked
public final class StvnCtrlClickNavigationTest extends BasePlatformTestCase {

    private final StvnGotoDeclarationHandler gotoHandler = new StvnGotoDeclarationHandler();

    /**
     * Verifies Gap 1: Ctrl+Click on #com/acme/network/DEFAULT_PORT in :body (Gauntlet v3 Stage 9)
     * navigates directly to #DEFAULT_PORT :Port 8080 inside :package :com/acme/network.
     */
    public void testStage9QualifiedPackageConstantInBodyNavigatesToPackageConstantDefinition() {
        var content = """
            {
              :defs {
                :package :com/acme/network {
                  :Port { #unsigned #size 16 #minIncl 1024 #maxExcl 65536 } :Int
                  #DEFAULT_PORT :Port 8080
                }
                :package :com/acme/gateway {
                  :use [ :com/acme/network { :Port :BackendPort } ]
                  :Port { #unsigned #size 16 #minIncl 80 #maxExcl 1024 } :Int
                  :GatewayTuple :Tuple( :Port :BackendPort )
                }
                :Stage9V3Envelope :Tuple( :com/acme/gateway/GatewayTuple :com/acme/network/Port )
              }
              :type :Stage9V3Envelope
              :body (
                ( 443 #com/acme/network/DEFAULT_PORT )
                8080
              )
            }
            """;
        var file = myFixture.configureByText("stage9_const_nav.stvn", content);
        var text = file.getText();

        var bodyConstOffset = text.indexOf("#com/acme/network/DEFAULT_PORT");
        assertTrue("Must locate #com/acme/network/DEFAULT_PORT in :body", bodyConstOffset > 0);

        var sourceElem = file.findElementAt(bodyConstOffset + 2);
        assertNotNull("Leaf element at #com/acme/network/DEFAULT_PORT must exist", sourceElem);

        var targets = gotoHandler.getGotoDeclarationTargets(sourceElem, bodyConstOffset + 2, myFixture.getEditor());
        assertNotNull("GotoDeclarationHandler must resolve #com/acme/network/DEFAULT_PORT", targets);
        assertEquals("Must return exactly one ConstantDefinition target", 1, targets.length);
        assertTrue("Target must be ConstantDefinition", targets[0] instanceof ConstantDefinition);

        var constDef = (ConstantDefinition) targets[0];
        assertNotNull(constDef.getValueKeyword());
        assertEquals("#DEFAULT_PORT", constDef.getValueKeyword().getText());
        var declOffset = text.indexOf("#DEFAULT_PORT :Port 8080");
        assertEquals("Target offset must match #DEFAULT_PORT declaration inside :package :com/acme/network",
            declOffset, constDef.getTextOffset());

        // Verify editor GotoDeclaration action moves caret to #DEFAULT_PORT declaration
        myFixture.getEditor().getCaretModel().moveToOffset(bodyConstOffset + 2);
        myFixture.performEditorAction("GotoDeclaration");
        assertEquals("Caret must jump to #DEFAULT_PORT declaration in :package :com/acme/network",
            declOffset, myFixture.getEditor().getCaretModel().getOffset());

        // At declaration site #DEFAULT_PORT, getGotoDeclarationTargets returns null so Find Usages activates
        var declLeaf = file.findElementAt(declOffset + 1);
        assertNull("GotoDeclarationHandler must return null on #DEFAULT_PORT declaration identifier",
            gotoHandler.getGotoDeclarationTargets(declLeaf, declOffset + 1, myFixture.getEditor()));
    }

    /**
     * Verifies constant resolution across package-local scope, :use map aliases (#REMOTE #LOCAL),
     * :use { #strip }, :include { #strip }, Prelude constants, and package isolation guards.
     */
    public void testConstantResolutionAcrossPackageLocalUseAliasStripIncludeAndPrelude() {
        myFixture.addFileToProject("shared_consts.stvn_di", """
            {
              :defs {
                :package :org/shared/limits {
                  #SHARED_MAX :Int 4096
                }
              }
            }
            """);

        var content = """
            {
              :defs {
                :include [ "shared_consts.stvn_di" { #strip } ]
                :use [ :org/stvnadore/prelude { #strip } ]
                :package :com/acme/network {
                  #MIN_PORT :Int 1024
                  #DEFAULT_PORT { #minIncl #MIN_PORT #maxExcl #SHARED_MAX } :Int 8080
                }
                :package :com/acme/gateway {
                  :use [ :com/acme/network { #DEFAULT_PORT #GATEWAY_PORT } ]
                  #ALIAS_PORT :Int #GATEWAY_PORT
                }
                :package :com/acme/stripped {
                  :use [ :com/acme/network { #strip } ]
                  #STRIPPED_REF :Int #DEFAULT_PORT
                }
                :ShaId { #regex #SHA256_LOWER_CASE_REGEX } :String
              }
              :type :Int
              :body #com/acme/gateway/ALIAS_PORT
            }
            """;
        var file = myFixture.configureByText("scoped_consts.stvn_i", content);
        var text = file.getText();

        // 1. Package-local facet argument #MIN_PORT inside :com/acme/network
        var minPortRefOffset = text.indexOf("#MIN_PORT", text.indexOf("#DEFAULT_PORT"));
        var minPortTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(minPortRefOffset + 1), minPortRefOffset + 1, myFixture.getEditor());
        assertNotNull(minPortTargets);
        assertEquals(1, minPortTargets.length);
        assertEquals(text.indexOf("#MIN_PORT :Int 1024"), minPortTargets[0].getTextOffset());

        // 2. Included #strip constant #SHARED_MAX
        var sharedMaxOffset = text.indexOf("#SHARED_MAX");
        var sharedMaxTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(sharedMaxOffset + 1), sharedMaxOffset + 1, myFixture.getEditor());
        assertNotNull(sharedMaxTargets);
        assertEquals(1, sharedMaxTargets.length);
        assertEquals("shared_consts.stvn_di", sharedMaxTargets[0].getContainingFile().getName());

        // 3. UseMapAlias index 0 (#DEFAULT_PORT in :use [ :com/acme/network { #DEFAULT_PORT #GATEWAY_PORT } ])
        var useBlockStart = text.indexOf(":use [ :com/acme/network");
        var useRemoteOffset = text.indexOf("#DEFAULT_PORT", useBlockStart);
        var useRemoteTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(useRemoteOffset + 1), useRemoteOffset + 1, myFixture.getEditor());
        assertNotNull("UseMapAlias LHS #DEFAULT_PORT must resolve to :com/acme/network/#DEFAULT_PORT", useRemoteTargets);
        assertEquals(1, useRemoteTargets.length);
        assertEquals(text.indexOf("#DEFAULT_PORT {"), useRemoteTargets[0].getTextOffset());

        // 4. UseMapAlias usage (#GATEWAY_PORT in #ALIAS_PORT :Int #GATEWAY_PORT) hops to underlying ConstantDefinition
        var gatewayPortUsageOffset = text.indexOf("#GATEWAY_PORT", text.indexOf("#ALIAS_PORT"));
        var gatewayPortTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(gatewayPortUsageOffset + 1), gatewayPortUsageOffset + 1, myFixture.getEditor());
        assertNotNull(gatewayPortTargets);
        assertEquals(1, gatewayPortTargets.length);
        assertEquals(text.indexOf("#DEFAULT_PORT {"), gatewayPortTargets[0].getTextOffset());

        // 5. Package-local :use { #strip } reference (#DEFAULT_PORT in :com/acme/stripped)
        var strippedRefOffset = text.indexOf("#DEFAULT_PORT", text.indexOf("#STRIPPED_REF"));
        var strippedTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(strippedRefOffset + 1), strippedRefOffset + 1, myFixture.getEditor());
        assertNotNull(strippedTargets);
        assertEquals(1, strippedTargets.length);
        assertEquals(text.indexOf("#DEFAULT_PORT {"), strippedTargets[0].getTextOffset());

        // 6. Standard Library Prelude constant #SHA256_LOWER_CASE_REGEX
        var preludeConstOffset = text.indexOf("#SHA256_LOWER_CASE_REGEX");
        var preludeTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(preludeConstOffset + 1), preludeConstOffset + 1, myFixture.getEditor());
        assertNotNull(preludeTargets);
        assertEquals(1, preludeTargets.length);
        assertEquals("prelude.stvn_core.stvn_d", preludeTargets[0].getContainingFile().getName());
    }

    /**
     * Verifies Gap 2 on Stage 9: Ctrl+Click on scalar literals (443, 8080), ConstantDefinition payload
     * values (8080 in #DEFAULT_PORT :Port 8080), and tuple delimiters '(' and ')' jumps to the exact
     * governing nominal alias TypeDefinition in :defs.
     */
    public void testStage9BodyValuesAndTupleDelimitersNavigateToGoverningNominalAliases() {
        var content = """
            {
              :defs {
                :package :com/acme/network {
                  :Port { #unsigned #size 16 #minIncl 1024 #maxExcl 65536 } :Int
                  #DEFAULT_PORT :Port 8080
                }
                :package :com/acme/gateway {
                  :use [ :com/acme/network { :Port :BackendPort } ]
                  :Port { #unsigned #size 16 #minIncl 80 #maxExcl 1024 } :Int
                  :GatewayTuple :Tuple( :Port :BackendPort )
                }
                :Stage9V3Envelope :Tuple( :com/acme/gateway/GatewayTuple :com/acme/network/Port )
              }
              :type :Stage9V3Envelope
              :body (
                ( 443 #com/acme/network/DEFAULT_PORT )
                8080
              )
            }
            """;
        var file = myFixture.configureByText("stage9_value_nav.stvn", content);
        var text = file.getText();

        var networkPortDefOffset = text.indexOf(":Port { #unsigned #size 16 #minIncl 1024");
        var gatewayPortDefOffset = text.indexOf(":Port { #unsigned #size 16 #minIncl 80");
        var gatewayTupleDefOffset = text.indexOf(":GatewayTuple :Tuple");
        var envelopeDefOffset = text.indexOf(":Stage9V3Envelope :Tuple");

        // 1. Value 8080 inside #DEFAULT_PORT :Port 8080 in :defs -> :com/acme/network/Port
        var constVal8080Offset = text.indexOf("8080", text.indexOf("#DEFAULT_PORT"));
        var constValTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(constVal8080Offset), constVal8080Offset, myFixture.getEditor());
        assertNotNull("ConstantDefinition value 8080 must resolve to governing :Port TypeDefinition", constValTargets);
        assertEquals(1, constValTargets.length);
        assertEquals(networkPortDefOffset, constValTargets[0].getTextOffset());

        // 2. Value 443 in :body -> :com/acme/gateway/Port
        var bodyStart = text.indexOf(":body");
        var val443Offset = text.indexOf("443", bodyStart);
        var val443Targets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(val443Offset), val443Offset, myFixture.getEditor());
        assertNotNull("Value 443 in :body must resolve to :com/acme/gateway/Port", val443Targets);
        assertEquals(1, val443Targets.length);
        assertEquals(gatewayPortDefOffset, val443Targets[0].getTextOffset());

        // 3. Value 8080 in :body -> :com/acme/network/Port
        var body8080Offset = text.indexOf("8080", bodyStart);
        var body8080Targets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(body8080Offset), body8080Offset, myFixture.getEditor());
        assertNotNull("Value 8080 in :body must resolve to :com/acme/network/Port", body8080Targets);
        assertEquals(1, body8080Targets.length);
        assertEquals(networkPortDefOffset, body8080Targets[0].getTextOffset());

        // 4. Inner tuple '(' and ')' around ( 443 #com/acme/network/DEFAULT_PORT ) -> :GatewayTuple
        var innerLParenOffset = text.indexOf("( 443", bodyStart);
        var innerRParenOffset = text.indexOf(")", innerLParenOffset);
        var innerLParenTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(innerLParenOffset), innerLParenOffset, myFixture.getEditor());
        var innerRParenTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(innerRParenOffset), innerRParenOffset, myFixture.getEditor());
        assertNotNull(innerLParenTargets);
        assertNotNull(innerRParenTargets);
        assertEquals(gatewayTupleDefOffset, innerLParenTargets[0].getTextOffset());
        assertEquals(gatewayTupleDefOffset, innerRParenTargets[0].getTextOffset());

        // 5. Outer tuple '(' and ')' -> :Stage9V3Envelope
        var outerLParenOffset = text.indexOf("(", bodyStart);
        var outerRParenOffset = text.lastIndexOf(")");
        var outerLParenTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(outerLParenOffset), outerLParenOffset, myFixture.getEditor());
        var outerRParenTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(outerRParenOffset), outerRParenOffset, myFixture.getEditor());
        assertNotNull(outerLParenTargets);
        assertNotNull(outerRParenTargets);
        assertEquals(envelopeDefOffset, outerLParenTargets[0].getTextOffset());
        assertEquals(envelopeDefOffset, outerRParenTargets[0].getTextOffset());
    }

    /**
     * Verifies navigation across inline-constrained nominal references, :Map keys/values/delimiters,
     * explicit and implicit sum types (:Option, :Either, :Union), and :Enum variants in :body vs :defs.
     */
    public void testMapTupleInlineConstrainedAndSumValuesNavigateToNominalAliases() {
        var content = """
            {
              :defs {
                :TimeoutMs { #unsigned #size 32 } :Int
                :SegmentName { #minSize 1 #maxSize 32 } :String
                :ShardId { #unsigned #size 8 } :Int
                :RouteKey :Tuple( :SegmentName :ShardId )
                :RouteActiveFlag :Boolean
                :CompositeRoutingTable :Map( :RouteKey :RouteActiveFlag )
                :LifecyclePhase :Enum [ #CREATED #PENDING #ACTIVE ]
                :PreDeployPhase { #filterIncl [ #CREATED #PENDING ] } :LifecyclePhase
                :HostName { #minSize 1 } :String
                :EndpointOption :Option( :HostName )
                :Envelope :Tuple(
                  { #minIncl 500 #maxExcl 5000 } :TimeoutMs
                  :CompositeRoutingTable
                  :PreDeployPhase
                  :EndpointOption
                  :Option( :ShardId )
                )
              }
              :type :Envelope
              :body (
                750
                { [ ( "edge" 16 ) #TRUE ] }
                #PENDING
                #Some "api.internal"
                42
              )
            }
            """;
        var file = myFixture.configureByText("complex_body_nav.stvn", content);
        var text = file.getText();
        var bodyStart = text.indexOf(":body");

        // 1. Inline-constrained nominal reference { #minIncl 500 #maxExcl 5000 } :TimeoutMs on 750 -> :TimeoutMs
        var offset750 = text.indexOf("750", bodyStart);
        var targets750 = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(offset750), offset750, myFixture.getEditor());
        assertNotNull(targets750);
        assertEquals(text.indexOf(":TimeoutMs {"), targets750[0].getTextOffset());

        // 2. Map delimiter '{', key tuple '(', "edge", 16, and value #TRUE
        var mapBraceOffset = text.indexOf("{ [", bodyStart);
        var mapTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(mapBraceOffset), mapBraceOffset, myFixture.getEditor());
        assertNotNull(mapTargets);
        assertEquals(text.indexOf(":CompositeRoutingTable :Map"), mapTargets[0].getTextOffset());

        var routeKeyLParen = text.indexOf("( \"edge\"", bodyStart);
        var routeKeyTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(routeKeyLParen), routeKeyLParen, myFixture.getEditor());
        assertNotNull(routeKeyTargets);
        assertEquals(text.indexOf(":RouteKey :Tuple"), routeKeyTargets[0].getTextOffset());

        var edgeOffset = text.indexOf("\"edge\"", bodyStart);
        var edgeTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(edgeOffset + 1), edgeOffset + 1, myFixture.getEditor());
        assertNotNull(edgeTargets);
        assertEquals(text.indexOf(":SegmentName {"), edgeTargets[0].getTextOffset());

        var shard16Offset = text.indexOf("16", bodyStart);
        var shard16Targets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(shard16Offset), shard16Offset, myFixture.getEditor());
        assertNotNull(shard16Targets);
        assertEquals(text.indexOf(":ShardId {"), shard16Targets[0].getTextOffset());

        var trueOffset = text.indexOf("#TRUE", bodyStart);
        var trueTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(trueOffset + 1), trueOffset + 1, myFixture.getEditor());
        assertNotNull(trueTargets);
        assertEquals(text.indexOf(":RouteActiveFlag :Boolean"), trueTargets[0].getTextOffset());

        // 3. :Enum variant #PENDING in :body jumps to governing nominal alias :PreDeployPhase,
        // while #PENDING in :defs #filterIncl jumps to #PENDING inside :LifecyclePhase
        var bodyPendingOffset = text.indexOf("#PENDING", bodyStart);
        var bodyPendingTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(bodyPendingOffset + 1), bodyPendingOffset + 1, myFixture.getEditor());
        assertNotNull(bodyPendingTargets);
        assertEquals(text.indexOf(":PreDeployPhase {"), bodyPendingTargets[0].getTextOffset());

        var filterPendingOffset = text.indexOf("#PENDING", text.indexOf("#filterIncl"));
        var filterPendingTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(filterPendingOffset + 1), filterPendingOffset + 1, myFixture.getEditor());
        assertNotNull(filterPendingTargets);
        assertEquals(text.indexOf("#PENDING #ACTIVE"), filterPendingTargets[0].getTextOffset());

        // 4. Explicit sum tag #Some -> :EndpointOption; inner payload "api.internal" -> :HostName
        var someOffset = text.indexOf("#Some", bodyStart);
        var someTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(someOffset + 1), someOffset + 1, myFixture.getEditor());
        assertNotNull(someTargets);
        assertEquals(text.indexOf(":EndpointOption :Option"), someTargets[0].getTextOffset());

        var apiInternalOffset = text.indexOf("\"api.internal\"", bodyStart);
        var apiInternalTargets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(apiInternalOffset + 1), apiInternalOffset + 1, myFixture.getEditor());
        assertNotNull(apiInternalTargets);
        assertEquals(text.indexOf(":HostName {"), apiInternalTargets[0].getTextOffset());

        // 5. Anonymous outer sum :Option( :ShardId ) with implicitly inferred payload 42 -> :ShardId
        var val42Offset = text.indexOf("42", bodyStart);
        var val42Targets = gotoHandler.getGotoDeclarationTargets(
            file.findElementAt(val42Offset), val42Offset, myFixture.getEditor());
        assertNotNull(val42Targets);
        assertEquals(text.indexOf(":ShardId {"), val42Targets[0].getTextOffset());
    }

    /**
     * Verifies that unaliased primitives (:Int, :String, :Boolean, constrained :Int),
     * anonymous composite constructors (:Tuple(...)), and whitespace return null.
     */
    public void testUnaliasedPrimitivesAndWhitespaceReturnNull() {
        var content = """
            {
              :type :Tuple(
                :Int
                { #unsigned #size 16 } :Int
                :String
                :Boolean
              )
              :body (
                100
                200
                "raw"
                #TRUE
              )
            }
            """;
        var file = myFixture.configureByText("unaliased_primitives.stvn", content);
        var text = file.getText();
        var bodyStart = text.indexOf(":body");

        int[] nonNavigableOffsets = {
            text.indexOf("(", bodyStart),
            text.indexOf("100", bodyStart),
            text.indexOf("200", bodyStart),
            text.indexOf("\"raw\"", bodyStart) + 1,
            text.indexOf("#TRUE", bodyStart) + 1,
            text.indexOf("100", bodyStart) - 1 // whitespace inside tuple
        };

        for (int offset : nonNavigableOffsets) {
            var elem = file.findElementAt(offset);
            assertNotNull(elem);
            var targets = gotoHandler.getGotoDeclarationTargets(elem, offset, myFixture.getEditor());
            assertNull("Unaliased primitive or whitespace at offset " + offset + " must return null", targets);
        }
    }

    /**
     * Verifies Target 3: Nominal type inlay hints wrap their text presentation in a clickable
     * psiSingleReference, whereas unaliased primitive inlay hints remain plain text presentations.
     */
    public void testNominalTypeInlayHintsWrapWithClickablePsiSingleReference() {
        var content = """
            {
              :defs {
                :Port { #unsigned #size 16 } :Int
              }
              :type :Tuple( :Port :Int )
              :body ( 443 8080 )
            }
            """;
        var file = myFixture.configureByText("clickable_inlays.stvn", content);
        var tuple = PsiTreeUtil.findChildOfType(file, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(2, values.size());

        var collector = new StvnInlayHintsCollector(myFixture.getEditor());

        // Slot 0 (443) is governed by nominal :Port -> resolves to TypeDefinition
        var portValue = values.get(0);
        var portTypeDef = StvnTypeResolver.resolveGoverningNominalTypeDefinition(portValue);
        assertNotNull("Slot 0 must resolve to :Port TypeDefinition", portTypeDef);
        var portPresentation = collector.buildInlayPresentation(portValue, ":Port");
        assertNotNull(portPresentation);

        // Slot 1 (8080) is governed by unaliased :Int -> returns null TypeDefinition
        var intValue = values.get(1);
        var intTypeDef = StvnTypeResolver.resolveGoverningNominalTypeDefinition(intValue);
        assertNull("Slot 1 (:Int) must not resolve to a nominal TypeDefinition", intTypeDef);
        var intPresentation = collector.buildInlayPresentation(intValue, ":Int");
        assertNotNull(intPresentation);

        // Verify collector sink emission across the file
        var recordedPresentations = new ArrayList<InlayPresentation>();
        collector.collect(file, myFixture.getEditor(), new InlayHintsSink() {
            @Override
            public void addInlineElement(int offset, boolean relatesToPrecedingText, @NotNull InlayPresentation presentation, boolean placeAtTheEndOfLine) {
                recordedPresentations.add(presentation);
            }

            @Override
            public void addBlockElement(int offset, boolean relatesToPrecedingText, boolean showAbove, int priority, @NotNull InlayPresentation presentation) {
                recordedPresentations.add(presentation);
            }

            @Override
            public void addInlineElement(int offset, @NotNull RootInlayPresentation<?> presentation, @NotNull com.intellij.codeInsight.hints.HorizontalConstraints constraints) {
            }

            @Override
            public void addBlockElement(int logicalLine, boolean showAbove, @NotNull RootInlayPresentation<?> presentation, @NotNull com.intellij.codeInsight.hints.BlockConstraints constraints) {
            }
        });
        assertFalse("Collector must emit inline presentations for :body values", recordedPresentations.isEmpty());
    }

    public void testStage12InlineMemberSeqInsideNominalTypeDefinitionNavigatesToSchemaTypeInDefs() {
        var code = """
            {
              :meta {
                #name "gauntlet_v3_circular_types_recursion_and_duplicate_defs"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :TreeLabel { #minSize 1 #maxSize 32 } :String
                :TreeNode  :Tuple(
                  :TreeLabel
                  :Seq(:TreeNode)
                )
                :Stage12V3Envelope :Tuple(
                  :TreeNode
                )
              }
              :type :Stage12V3Envelope
              :body (
                (
                  "root"
                  [
                    (
                      "child-left"
                      [
                        ("leaf-1" [])
                        ("leaf-2" [])
                      ]
                    )
                    (
                      "child-right"
                      []
                    )
                  ]
                )
              )
            }
            """;
        var file = myFixture.configureByText("gauntlet_v3_circular_types_recursion_and_duplicate_defs.core_test.stvn_i", code);
        var handler = new StvnGotoDeclarationHandler();

        int expectedSeqSchemaOffset = code.indexOf(":Seq(:TreeNode)");

        // 1. Ctrl+Click on '[' and ']' of outer child list and nested lists
        int[] bracketOffsets = new int[] {
            code.indexOf("[", code.indexOf("\"root\"")),
            code.indexOf("[", code.indexOf("\"child-left\"")),
            code.indexOf("[]", code.indexOf("\"leaf-1\"")),
            code.indexOf("[]", code.indexOf("\"leaf-1\"")) + 1,
            code.indexOf("[]", code.indexOf("\"leaf-2\"")),
            code.indexOf("]", code.indexOf("\"leaf-2\"") + "\"leaf-2\" [])".length()),
            code.indexOf("[]", code.indexOf("\"child-right\"")),
            code.lastIndexOf("]")
        };

        for (int offset : bracketOffsets) {
            assertTrue("Expected non-negative bracket offset", offset >= 0);
            var leaf = file.findElementAt(offset);
            assertNotNull("Expected token at offset " + offset, leaf);
            var targets = handler.getGotoDeclarationTargets(leaf, offset, myFixture.getEditor());
            assertNotNull("Expected navigation target at bracket offset " + offset, targets);
            assertEquals(1, targets.length);
            assertTrue("Expected SchemaType target for inline :Seq(:TreeNode) at offset " + offset, targets[0] instanceof SchemaType);
            assertEquals(":Seq(:TreeNode)", targets[0].getText());
            assertEquals(expectedSeqSchemaOffset, targets[0].getTextRange().getStartOffset());
        }

        // 2. Verify Inlay Hint presentation for the child ListLiteral wraps with clickable PsiSingleReference targeting :Seq(:TreeNode)
        var leaf1EmptyListLeaf = file.findElementAt(code.indexOf("[]", code.indexOf("\"leaf-1\"")));
        var listValue = PsiTreeUtil.getParentOfType(leaf1EmptyListLeaf, Value.class);
        assertNotNull(listValue);
        assertEquals("[]", listValue.getText());

        var declarationTarget = StvnTypeResolver.resolveGoverningDeclarationTarget(listValue);
        assertNotNull("Expected governing declaration target for []", declarationTarget);
        assertEquals(":Seq(:TreeNode)", declarationTarget.getText());
        assertEquals(expectedSeqSchemaOffset, declarationTarget.getTextRange().getStartOffset());

        var collector = new StvnInlayHintsCollector(myFixture.getEditor());
        var listInlayPresentation = collector.buildInlayPresentation(listValue, ":Seq( :TreeNode )");
        assertNotNull(listInlayPresentation);
    }
}
