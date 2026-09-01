package org.stvnadore.plugin.reference;

import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.Value;

/**
 * Dedicated unit test suite verifying type path trajectory unspooling
 * across nominal scalar aliases, sums, options, and eithers.
 */
@NullMarked
public final class StvnTypeResolverTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    private void setUseLongFormSumTypes(boolean value) {
        var settings = StvnSettings.getInstance(getProject());
        settings.getState().useLongFormSumTypes = value;
    }

    public void testNominalScalarAliasTrajectory() {
        var psiFile = myFixture.configureByText(
            "scalar_alias.stvn",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :RemoteHost :HostName
              }
              :type :RemoteHost
              :body "auth.internal"
            }
            """);

        var values = PsiTreeUtil.findChildrenOfType(psiFile, Value.class);
        assertFalse(values.isEmpty());
        var bodyValue = values.iterator().next();

        var resolvedType = StvnTypeResolver.resolveValueType(bodyValue);
        assertEquals(":RemoteHost (-> :HostName -> :StringNonEmpty64)", resolvedType);
    }

    public void testNominalUnionMultiHopTrajectory() {
        setUseLongFormSumTypes(true);
        var psiFile = myFixture.configureByText(
            "union_multihop.stvn",
            """
            {
              :defs {
                :IPBase :StringFixed15
                :IPv4 { #regex "^(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}$" } :IPBase
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }
              :type :Tuple( :IpAddress :IpAddress )
              :body (
                #1 "10.0.0.1"
                #2 "100.000.000.002"
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var tupleValues = tuple.getValueList();
        assertEquals(2, tupleValues.size());

        var firstVariant = tupleValues.get(0);
        var secondVariant = tupleValues.get(1);

        assertEquals(":IpAddress #1 (-> :IPv4 -> :IPBase -> :StringFixed15)", StvnTypeResolver.resolveValueType(firstVariant));
        assertEquals(":IpAddress #2 (-> :StringFixed15)", StvnTypeResolver.resolveValueType(secondVariant));
    }

    public void testNominalOptionTrajectoryLongAndShortForms() {
        var psiFile = myFixture.configureByText(
            "opt_forms.stvn",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :RemoteHost :HostName
                :HostOption :Option( :RemoteHost )
              }
              :type :Tuple( :HostOption :HostOption )
              :body (
                "auth.internal"
                #None
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var tupleValues = tuple.getValueList();
        assertEquals(2, tupleValues.size());

        var someValue = tupleValues.get(0);
        var noneValue = tupleValues.get(1);

        // 1. Long Form Setting
        setUseLongFormSumTypes(true);
        assertEquals(":HostOption [#Some] (-> :RemoteHost -> :HostName -> :StringNonEmpty64)", StvnTypeResolver.resolveValueType(someValue));
        assertEquals(":HostOption #None", StvnTypeResolver.resolveValueType(noneValue));

        // 2. Short Form Setting
        setUseLongFormSumTypes(false);
        assertEquals(":HostOption [#S] (-> :RemoteHost -> :HostName -> :StringNonEmpty64)", StvnTypeResolver.resolveValueType(someValue));
        assertEquals(":HostOption #N", StvnTypeResolver.resolveValueType(noneValue));
    }

    public void testNominalEitherTrajectoryLeftAndRight() {
        setUseLongFormSumTypes(true);
        var psiFile = myFixture.configureByText(
            "either_test.stvn",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :RemoteHost :HostName
                :IPv4 { #regex "^(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}$" } :StringFixed15
                :IpAddress :Union( :IPv4 :StringFixed15 )
                :Endpoint :Either( :RemoteHost :IpAddress )
              }
              :type :Tuple( :Endpoint :Endpoint )
              :body (
                #Left "auth.internal"
                #Right #1 "10.0.0.1"
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var tupleValues = tuple.getValueList();
        assertEquals(2, tupleValues.size());

        var leftValue = tupleValues.get(0);
        var rightValue = tupleValues.get(1);

        assertEquals(":Endpoint #Left (-> :RemoteHost -> :HostName -> :StringNonEmpty64)", StvnTypeResolver.resolveValueType(leftValue));
        assertEquals(":Endpoint #Right (-> :IpAddress #1 (-> :IPv4 -> :StringFixed15))", StvnTypeResolver.resolveValueType(rightValue));
    }

    public void testMixedEnumVsConstantDisambiguation() {
        var psiFile = myFixture.configureByText(
            "mixed_enum_const.stvn",
            """
            {
              :defs {
                :Mode :Enum [ #Left #Right ]
                #Left :Uint7 99
              }
              :type :Tuple( :Mode :Uint7 )
              :body (
                #Left
                #Left
              )
            }
            """);

        var text = psiFile.getText();
        var bodyIdx = text.indexOf(":body");

        var firstLeftIdx = text.indexOf("#Left", bodyIdx);
        var secondLeftIdx = text.indexOf("#Left", firstLeftIdx + 5);

        var firstElem = psiFile.findElementAt(firstLeftIdx);
        assertNotNull(firstElem);
        var firstVal = PsiTreeUtil.getParentOfType(firstElem, Value.class);
        assertNotNull(firstVal);

        var secondElem = psiFile.findElementAt(secondLeftIdx);
        assertNotNull(secondElem);
        var secondVal = PsiTreeUtil.getParentOfType(secondElem, Value.class);
        assertNotNull(secondVal);

        assertEquals(":Mode (-> :Enum)", StvnTypeResolver.resolveValueType(firstVal));
        assertEquals(":Uint7", StvnTypeResolver.resolveValueType(secondVal));
    }

    public void testUhohEightAryTupleTypeInlayResolutions() {
        var psiFile = myFixture.configureByText(
            "uhoh_inlays.stvn",
            """
            {
              :defs {
                #Some  :Uint5 1
                #None  :Uint5 2
                #Left  :Uint5 3
                #Right :Uint5 4
                #TRUE  :Uint5 5
                #FALSE :Uint5 6
                #True  :Uint5 7
                #False :Uint5 8
              }
              :type :Tuple(
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
              )
              :body (
                #Some
                #None
                #Left
                #Right
                #TRUE
                #FALSE
                #True
                #False
              )
            }
            """);

        var text = psiFile.getText();
        var bodyIdx = text.indexOf(":body");
        var keywords = java.util.List.of("#Some", "#None", "#Left", "#Right", "#TRUE", "#FALSE", "#True", "#False");

        for (var kw : keywords) {
            var offset = text.indexOf(kw, bodyIdx);
            assertTrue("Keyword " + kw + " not found", offset > 0);
            var elem = psiFile.findElementAt(offset);
            assertNotNull("Element at offset for " + kw + " not found", elem);
            var valAncestor = PsiTreeUtil.getParentOfType(elem, Value.class);
            assertNotNull("Value ancestor for " + kw + " not found", valAncestor);
            var resolvedType = StvnTypeResolver.resolveValueType(valAncestor);
            assertEquals("Expected :Uint5 for keyword " + kw, ":Uint5", resolvedType);
        }
    }

    public void testConstantSubstitutionInlayAndHover() {
        var psiFile = myFixture.configureByText(
            "const_subst.stvn",
            """
            {
              :defs {
                #Some :Uint7 10
              }
              :type :Tuple( :Uint7 :Uint7 )
              :body (
                #Some
                42
              )
            }
            """);

        var text = psiFile.getText();
        var bodyIdx = text.indexOf(":body");

        var someIdx = text.indexOf("#Some", bodyIdx);
        var someElem = psiFile.findElementAt(someIdx);
        assertNotNull(someElem);
        var someVal = PsiTreeUtil.getParentOfType(someElem, Value.class);
        assertNotNull(someVal);
        assertEquals(":Uint7", StvnTypeResolver.resolveValueType(someVal));

        var numIdx = text.indexOf("42", bodyIdx);
        var numElem = psiFile.findElementAt(numIdx);
        assertNotNull(numElem);
        var numVal = PsiTreeUtil.getParentOfType(numElem, Value.class);
        assertNotNull(numVal);
        assertEquals(":Uint7", StvnTypeResolver.resolveValueType(numVal));
    }

    public void testNominalEnumMultiHopResolution() {
        var psiFile = myFixture.configureByText(
            "enum_resolver_multihop.stvn",
            """
            {
              :defs {
                :AppMode :SubMode
                :SubMode :RootMode
                :RootMode :Enum [ #Safe #Unsafe ]
              }
              :type :Tuple( :AppMode )
              :body ( #Safe )
            }
            """);

        var values = PsiTreeUtil.findChildrenOfType(psiFile, Value.class);
        assertFalse(values.isEmpty());
        var safeVal = values.stream().filter(v -> v.getText().equals("#Safe")).findFirst().orElseThrow();
        assertEquals(":AppMode (-> :SubMode -> :RootMode -> :Enum)", StvnTypeResolver.resolveValueType(safeVal));
    }

    public void testAnonymousEnumResolution() {
        var psiFile = myFixture.configureByText(
            "anon_enum_resolver.stvn",
            """
            {
              :type :Tuple( :Enum [ #Safe #Unsafe ] )
              :body ( #Safe )
            }
            """);

        var values = PsiTreeUtil.findChildrenOfType(psiFile, Value.class);
        assertFalse(values.isEmpty());
        var safeVal = values.stream().filter(v -> v.getText().equals("#Safe")).findFirst().orElseThrow();
        assertEquals(":Enum", StvnTypeResolver.resolveValueType(safeVal));
    }

    public void testResolveBaseTypeInfoUntaggedUnionBranches() {
        var psiFile = myFixture.configureByText(
            "union_rule_c_base.stvn",
            """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple( :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion )
              :body (
                42
                #TRUE
                3.14159
                "production"
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(4, values.size());

        var intInfo = StvnTypeResolver.resolveBaseTypeInfo(values.get(0));
        assertNotNull(intInfo);
        assertEquals(":DisjointUnion", intInfo.getLabel());
        assertEquals(":DisjointUnion [#1] (-> :Int32)", StvnTypeResolver.resolveValueType(values.get(0)));

        var boolInfo = StvnTypeResolver.resolveBaseTypeInfo(values.get(1));
        assertNotNull(boolInfo);
        assertEquals(":DisjointUnion", boolInfo.getLabel());
        assertEquals(":DisjointUnion [#2] (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(1)));

        var floatInfo = StvnTypeResolver.resolveBaseTypeInfo(values.get(2));
        assertNotNull(floatInfo);
        assertEquals(":DisjointUnion", floatInfo.getLabel());
        assertEquals(":DisjointUnion [#3] (-> :Float64)", StvnTypeResolver.resolveValueType(values.get(2)));

        var stringInfo = StvnTypeResolver.resolveBaseTypeInfo(values.get(3));
        assertNotNull(stringInfo);
        assertEquals(":DisjointUnion", stringInfo.getLabel());
        assertEquals(":DisjointUnion [#4] (-> :String)", StvnTypeResolver.resolveValueType(values.get(3)));
    }

    public void testResolveBaseTypeInfoExplicitUnionBranches() {
        var psiFile = myFixture.configureByText(
            "union_explicit_base.stvn",
            """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple( :DisjointUnion :DisjointUnion )
              :body (
                #1 42
                #2 #TRUE
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(2, values.size());

        var firstUnion = values.get(0).getExplicitUnionValue();
        assertNotNull(firstUnion);
        var innerIntVal = firstUnion.getValue();
        assertNotNull(innerIntVal);
        var intInfo = StvnTypeResolver.resolveBaseTypeInfo(innerIntVal);
        assertNotNull(intInfo);
        assertEquals(":Int32", intInfo.getLabel());

        var secondUnion = values.get(1).getExplicitUnionValue();
        assertNotNull(secondUnion);
        var innerBoolVal = secondUnion.getValue();
        assertNotNull(innerBoolVal);
        var boolInfo = StvnTypeResolver.resolveBaseTypeInfo(innerBoolVal);
        assertNotNull(boolInfo);
        assertEquals(":Boolean", boolInfo.getLabel());
    }

    public void testResolvesToBooleanOnDisjointUnionBranches() {
        var psiFile = myFixture.configureByText(
            "union_boolean_guard.stvn",
            """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple( :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion )
              :body (
                42
                #TRUE
                3.14159
                "production"
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(4, values.size());

        assertFalse("Int32 must not resolve to boolean", StvnTypeResolver.resolvesToBoolean(values.get(0)));
        assertTrue("Boolean #TRUE must resolve to boolean", StvnTypeResolver.resolvesToBoolean(values.get(1)));
        assertFalse("Float64 must not resolve to boolean", StvnTypeResolver.resolvesToBoolean(values.get(2)));
        assertFalse("String must not resolve to boolean", StvnTypeResolver.resolvesToBoolean(values.get(3)));
    }

    public void testResolveBaseTypeInfoImpliedOptionAndEither() {
        var psiFile = myFixture.configureByText(
            "implied_sums_base.stvn",
            """
            {
              :defs {
                :OptInt    :Option( :Int32 )
                :Disjoint  :Either( :String :Boolean )
              }
              :type :Tuple( :OptInt :Disjoint )
              :body (
                42
                #TRUE
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(2, values.size());

        // Rule A: resolveBaseTypeInfo returns :OptInt and resolveValueType unspools [#Some]
        var optInfo = StvnTypeResolver.resolveBaseTypeInfo(values.get(0));
        assertNotNull(optInfo);
        assertEquals(":OptInt", optInfo.getLabel());
        assertEquals(":OptInt [#Some] (-> :Int32)", StvnTypeResolver.resolveValueType(values.get(0)));

        // Rule B: resolveBaseTypeInfo returns :Disjoint and resolveValueType unspools [#Right]
        var eitherInfo = StvnTypeResolver.resolveBaseTypeInfo(values.get(1));
        assertNotNull(eitherInfo);
        assertEquals(":Disjoint", eitherInfo.getLabel());
        assertEquals(":Disjoint [#Right] (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(1)));
    }

    public void testMatchesSchemaPatternUntaggedSumTypes() {
        var psiFile = myFixture.configureByText(
            "matches_schema_sums.stvn",
            """
            {
              :defs {
                :OptInt    :Option( :Int32 )
                :Disjoint  :Either( :String :Boolean )
              }
              :type :Tuple( :OptInt :Disjoint )
              :body (
                42
                #TRUE
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(2, values.size());

        var typeEntry = PsiTreeUtil.findChildOfType(psiFile, org.stvnadore.psi.TypeEntry.class);
        assertNotNull(typeEntry);
        var product = typeEntry.getSchemaType().getSchemaConstructor().getProductType();
        assertNotNull(product);
        var schemaList = product.getSchemaTypeList();
        assertEquals(2, schemaList.size());

        assertTrue("42 must match :OptInt schema pattern", StvnTypeResolver.matchesSchemaPattern(values.get(0), schemaList.get(0)));
        assertTrue("#TRUE must match :Disjoint schema pattern", StvnTypeResolver.matchesSchemaPattern(values.get(1), schemaList.get(1)));
    }

    public void testResolvesToBooleanOnImpliedSums() {
        var psiFile = myFixture.configureByText(
            "implied_sums_boolean.stvn",
            """
            {
              :defs {
                :OptBool   :Option( :Boolean )
                :Disjoint  :Either( :Int32 :Boolean )
                :OptInt    :Option( :Int32 )
              }
              :type :Tuple( :OptBool :Disjoint :OptInt )
              :body (
                #TRUE
                #FALSE
                100
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(psiFile, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(3, values.size());

        assertTrue("Untagged #TRUE targeting :OptBool must resolve to boolean", StvnTypeResolver.resolvesToBoolean(values.get(0)));
        assertTrue("Untagged #FALSE targeting :Disjoint must resolve to boolean", StvnTypeResolver.resolvesToBoolean(values.get(1)));
        assertFalse("Untagged 100 targeting :OptInt must not resolve to boolean", StvnTypeResolver.resolvesToBoolean(values.get(2)));
    }

    public void testUhohDeepNestedSumPositionalTrajectoryResolutions() {
        setUseLongFormSumTypes(true);
        var psiFile = myFixture.configureByText(
            "uhoh_resolver.stvn",
            """
            {
              :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
              :body [
                #Some #Right #Some #Right 1.234
                #Right #Some #Right 1.234
                #Some #Right 1.234
                #Right 1.234
                1.234
              ]
            }
            """);

        var list = PsiTreeUtil.findChildOfType(psiFile, ListLiteral.class);
        assertNotNull(list);
        var values = list.getValueList();
        assertEquals(5, values.size());

        var expectedSchema = ":Option( :Either( :String :Option( :Either( :String :Float ) ) ) )";

        // Line 4: 4 explicit tags
        assertEquals(expectedSchema + " #Some #Right #Some #Right", StvnTypeResolver.resolveValueType(values.get(0)));

        // Line 5: Level 1 inferred, Levels 2, 3, 4 explicit
        assertEquals(expectedSchema + " [#Some] #Right #Some #Right", StvnTypeResolver.resolveValueType(values.get(1)));

        // Line 6: Levels 1, 2 explicit, Levels 3, 4 inferred
        assertEquals(expectedSchema + " #Some #Right [#Some] [#Right]", StvnTypeResolver.resolveValueType(values.get(2)));

        // Line 7: Level 1 inferred, Level 2 explicit, Levels 3, 4 inferred
        assertEquals(expectedSchema + " [#Some] #Right [#Some] [#Right]", StvnTypeResolver.resolveValueType(values.get(3)));

        // Line 8: All 4 levels inferred
        assertEquals(expectedSchema + " [#Some] [#Right] [#Some] [#Right]", StvnTypeResolver.resolveValueType(values.get(4)));
    }

    public void testUhohDeepNestedSumShortFormTrajectoryResolutions() {
        setUseLongFormSumTypes(false);
        var psiFile = myFixture.configureByText(
            "uhoh_resolver_short.stvn",
            """
            {
              :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
              :body [
                #Some #Right #Some #Right 1.234
                #Right #Some #Right 1.234
                #Some #Right 1.234
                #Right 1.234
                1.234
              ]
            }
            """);

        var list = PsiTreeUtil.findChildOfType(psiFile, ListLiteral.class);
        assertNotNull(list);
        var values = list.getValueList();
        assertEquals(5, values.size());

        var expectedSchema = ":Option( :Either( :String :Option( :Either( :String :Float ) ) ) )";

        assertEquals(expectedSchema + " #S #R #S #R", StvnTypeResolver.resolveValueType(values.get(0)));
        assertEquals(expectedSchema + " [#S] #R #S #R", StvnTypeResolver.resolveValueType(values.get(1)));
        assertEquals(expectedSchema + " #S #R [#S] [#R]", StvnTypeResolver.resolveValueType(values.get(2)));
        assertEquals(expectedSchema + " [#S] #R [#S] [#R]", StvnTypeResolver.resolveValueType(values.get(3)));
        assertEquals(expectedSchema + " [#S] [#R] [#S] [#R]", StvnTypeResolver.resolveValueType(values.get(4)));
    }
}
