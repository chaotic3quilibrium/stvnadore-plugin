package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Dedicated platform test suite verifying schema-directed code completion
 * across enums, booleans, sum constructors, matching constants, and dynamic temporal generators.
 */
@NullMarked
public final class StvnCompletionTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    public void testEnumVariantCompletions() {
        myFixture.configureByText(
            "enum_completion.stvn",
            """
            {
              :defs {
                :Environment :Enum [ #DEV #STAGING #PROD ]
              }
              :type :Environment
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#DEV"));
        assertTrue(lookupStrings.contains("#STAGING"));
        assertTrue(lookupStrings.contains("#PROD"));

        // Verify presentation metadata for #DEV
        var devElement = findLookupElement(elements, "#DEV");
        assertNotNull(devElement);
        var presentation = new LookupElementPresentation();
        devElement.renderElement(presentation);

        assertEquals(" (1/3)", presentation.getTailText());
        assertEquals(":Environment", presentation.getTypeText());
    }

    public void testBooleanLiteralCompletions() {
        myFixture.configureByText(
            "boolean_completion.stvn",
            """
            {
              :type :Boolean
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#TRUE"));
        assertTrue(lookupStrings.contains("#FALSE"));
        assertTrue(lookupStrings.contains("#T"));
        assertTrue(lookupStrings.contains("#F"));

        // Verify long-form priority over short-form
        int trueIdx = lookupStrings.indexOf("#TRUE");
        int falseIdx = lookupStrings.indexOf("#FALSE");
        int tIdx = lookupStrings.indexOf("#T");
        int fIdx = lookupStrings.indexOf("#F");

        assertTrue(trueIdx < tIdx);
        assertTrue(falseIdx < fIdx);
    }

    public void testSumVariantConstructorCompletions() {
        // 1. Option completion
        myFixture.configureByText(
            "opt_completion.stvn",
            """
            {
              :type :Option( :String )
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#Some ") || lookupStrings.contains("#Some"));
        assertTrue(lookupStrings.contains("#S ") || lookupStrings.contains("#S"));
        assertTrue(lookupStrings.contains("#None"));
        assertTrue(lookupStrings.contains("#N"));

        var someElement = findLookupElement(elements, "#Some ") != null
            ? findLookupElement(elements, "#Some ")
            : findLookupElement(elements, "#Some");
        assertNotNull(someElement);
        var presentation = new LookupElementPresentation();
        someElement.renderElement(presentation);
        assertTrue(presentation.getTailText() != null && presentation.getTailText().contains(":String"));

        // 2. Either completion
        myFixture.configureByText(
            "either_completion.stvn",
            """
            {
              :type :Either( :Int32 :String )
              :body <caret>
            }
            """
        );

        elements = myFixture.completeBasic();
        assertNotNull(elements);
        lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#Right ") || lookupStrings.contains("#Right"));
        assertTrue(lookupStrings.contains("#Left ") || lookupStrings.contains("#Left"));

        // 3. Union completion
        myFixture.configureByText(
            "union_completion.stvn",
            """
            {
              :type :Union( :Int32 :Float64 :Boolean )
              :body <caret>
            }
            """
        );

        elements = myFixture.completeBasic();
        assertNotNull(elements);
        lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#1 ") || lookupStrings.contains("#1"));
        assertTrue(lookupStrings.contains("#2 ") || lookupStrings.contains("#2"));
        assertTrue(lookupStrings.contains("#3 ") || lookupStrings.contains("#3"));
    }

    public void testMatchingDefinedConstantCompletions() {
        myFixture.configureByText(
            "constants_completion.stvn",
            """
            {
              :defs {
                #DEFAULT_TIMEOUT :Int32 30
                #MAX_RETRIES :Int32 5
                #SERVICE_NAME :String "auth"
              }
              :type :Tuple( :Int32 :String )
              :body (
                <caret>
                "auth"
              )
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);

        // #DEFAULT_TIMEOUT and #MAX_RETRIES match :Int32
        assertTrue(lookupStrings.contains("#DEFAULT_TIMEOUT"));
        assertTrue(lookupStrings.contains("#MAX_RETRIES"));

        // #SERVICE_NAME is :String, so it must not match :Int32 slot
        assertFalse(lookupStrings.contains("#SERVICE_NAME"));
    }

    public void testDynamicTemporalAndUuidGenerators() {
        // 1. :DateTimeAudited
        myFixture.configureByText(
            "audited_completion.stvn",
            """
            {
              :type { #audited } :DateTime
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertFalse(lookupStrings.isEmpty());

        var auditedPattern = Pattern.compile("^\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}\\[[a-zA-Z0-9_/+-]+\\]\"$");
        boolean matchedAudited = lookupStrings.stream().anyMatch(s -> auditedPattern.matcher(s).matches());
        assertTrue("Audited timestamp pattern not matched in: " + lookupStrings, matchedAudited);

        // 2. :DateTimeOffset
        myFixture.configureByText(
            "offset_completion.stvn",
            """
            {
              :type { #offset } :DateTime
              :body <caret>
            }
            """
        );

        elements = myFixture.completeBasic();
        assertNotNull(elements);
        lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        var offsetPattern = Pattern.compile("^\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([+-][0-9]{2}:[0-9]{2}|Z)\"$");
        boolean matchedOffset = lookupStrings.stream().anyMatch(s -> offsetPattern.matcher(s).matches());
        assertTrue("Offset timestamp pattern not matched in: " + lookupStrings, matchedOffset);

        // 3. :DateTimeZoned
        myFixture.configureByText(
            "zoned_completion.stvn",
            """
            {
              :type { #zoned } :DateTime
              :body <caret>
            }
            """
        );

        elements = myFixture.completeBasic();
        assertNotNull(elements);
        lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        var zonedPattern = Pattern.compile("^\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\[[a-zA-Z0-9_/+-]+\\]\"$");
        boolean matchedZoned = lookupStrings.stream().anyMatch(s -> zonedPattern.matcher(s).matches());
        assertTrue("Zoned timestamp pattern not matched in: " + lookupStrings, matchedZoned);

        // 4. :Uuid
        myFixture.configureByText(
            "uuid_completion.stvn",
            """
            {
              :defs {
                :Uuid { #minSize 36 #maxSize 36 } :String
              }
              :type :Uuid
              :body <caret>
            }
            """
        );

        elements = myFixture.completeBasic();
        assertNotNull(elements);
        lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        var uuidPattern = Pattern.compile("^\"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\"$");
        boolean matchedUuid = lookupStrings.stream().anyMatch(s -> uuidPattern.matcher(s).matches());
        assertTrue("UUID pattern not matched in: " + lookupStrings, matchedUuid);
    }

    public void testInsertionHandlersAndCaretPlacement() {
        myFixture.configureByText(
            "insert_handler.stvn",
            """
            {
              :type :Option( :Int32 )
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var someElement = findLookupElement(elements, "#Some ") != null
            ? findLookupElement(elements, "#Some ")
            : findLookupElement(elements, "#Some");
        assertNotNull(someElement);

        myFixture.getLookup().setCurrentItem(someElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        myFixture.checkResult(
            """
            {
              :type :Option( :Int32 )
              :body #Some <caret>
            }
            """
        );
    }

    public void testTaggedEitherRightTemporalSnippet() {
        myFixture.configureByText("test.stvn", """
            {
              :defs {
                :EitherTest :Either(:Int { #audited } :DateTime)
              }
              :type :EitherTest
              :body #Right <caret>
            }
            """);
        myFixture.completeBasic();
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.stream().anyMatch(s -> s.contains("America/Chicago") || s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
        assertFalse(strings.contains("#Right"));
        assertFalse(strings.contains("#Left"));
    }

    public void testTaggedEitherLeftUint32() {
        myFixture.configureByText("test.stvn", """
            {
              :defs {
                :EitherTest :Either(:Int { #audited } :DateTime)
                #DEFAULT_PORT :Int 8080
              }
              :type :EitherTest
              :body #Left <caret>
            }
            """);
        myFixture.completeBasic();
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#DEFAULT_PORT"));
        assertFalse(strings.contains("#Right"));
        assertFalse(strings.contains("#Left"));
    }

    public void testTaggedOptionSomeDateTime() {
        myFixture.configureByText("test.stvn", """
            {
              :type :Option({ #zoned } :DateTime)
              :body #Some <caret>
            }
            """);
        myFixture.completeBasic();
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
        assertFalse(strings.contains("#Some"));
        assertFalse(strings.contains("#None"));
    }

    public void testTaggedUnionBranchIndexed() {
        myFixture.configureByText("test.stvn", """
            {
              :type :Union(:Bool { #audited } :DateTime :String)
              :body #2 <caret>
            }
            """);
        myFixture.completeBasic();
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
        assertFalse(strings.contains("#1"));
        assertFalse(strings.contains("#2"));
        assertFalse(strings.contains("#3"));
        assertFalse(strings.contains("true"));
    }

    public void testNestedSumConstructorNarrowing() {
        myFixture.configureByText("test.stvn", """
            {
              :type :Option(:Either(:String { #audited } :DateTime))
              :body #Some #Right <caret>
            }
            """);
        myFixture.completeBasic();
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
        assertFalse(strings.contains("#Some"));
        assertFalse(strings.contains("#None"));
        assertFalse(strings.contains("#Left"));
        assertFalse(strings.contains("#Right"));
    }

    public void testNestedSumConstructorIntermediate() {
        myFixture.configureByText("test.stvn", """
            {
              :type :Option(:Either(:String { #audited } :DateTime))
              :body #Some <caret>
            }
            """);
        myFixture.completeBasic();
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Left") || strings.contains("#Left "));
        assertTrue(strings.contains("#Right") || strings.contains("#Right "));
        assertFalse(strings.contains("#Some"));
        assertFalse(strings.contains("#None"));
    }

    public void testUntaggedEitherOffersInferredRightSnippet() {
        myFixture.configureByText(
            "test_untagged_either.stvn",
            """
            {
              :defs {
                :EitherTest :Either(:Int { #audited } :DateTime)
              }
              :type :EitherTest
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Left ") || strings.contains("#Left"));
        assertTrue(strings.contains("#Right ") || strings.contains("#Right"));
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
    }

    public void testUntaggedEitherRuleESuppressesUntaggedLeft() {
        myFixture.configureByText(
            "test_untagged_either_rule_e.stvn",
            """
            {
              :defs {
                :EitherTest :Either(:Int { #audited } :DateTime)
                #PORT :Int 8080
              }
              :type :EitherTest
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Left ") || strings.contains("#Left"));
        assertTrue(strings.contains("#Right ") || strings.contains("#Right"));
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
        assertFalse(strings.contains("#PORT"));
    }

    public void testUntaggedOptionOffersInferredSomeSnippet() {
        myFixture.configureByText(
            "test_untagged_option.stvn",
            """
            {
              :type :Option({ #zoned } :DateTime)
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Some ") || strings.contains("#Some"));
        assertTrue(strings.contains("#None"));
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
    }

    public void testUntaggedUnionOffersDisjointBranchSnippets() {
        myFixture.configureByText(
            "test_untagged_union.stvn",
            """
            {
              :type :Union(:Bool { #audited } :DateTime)
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#1 ") || strings.contains("#1"));
        assertTrue(strings.contains("#2 ") || strings.contains("#2"));
        assertTrue(strings.contains("#TRUE"));
        assertTrue(strings.contains("#FALSE"));
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
    }

    public void testUntaggedUnionSuppressesAmbiguousNumericBranchInference() {
        myFixture.configureByText(
            "test_untagged_union_numeric.stvn",
            """
            {
              :defs {
                #CONST_A :Int 10
                #CONST_B :Float 20.0
              }
              :type :Union(:Int :Float)
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#1 ") || strings.contains("#1"));
        assertTrue(strings.contains("#2 ") || strings.contains("#2"));
        assertFalse(strings.contains("#CONST_A"));
        assertFalse(strings.contains("#CONST_B"));
    }

    public void testUntaggedEitherOffersInferredRightConstant() {
        myFixture.configureByText(
            "test_untagged_either_right_const.stvn",
            """
            {
              :defs {
                :EitherTest :Either(:Int { #audited } :DateTime)
                #START_TIME { #audited } :DateTime "2026-08-25T07:48:57+00:00[UTC]"
              }
              :type :EitherTest
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Left ") || strings.contains("#Left"));
        assertTrue(strings.contains("#Right ") || strings.contains("#Right"));
        assertTrue(strings.contains("#START_TIME"));
    }

    public void testRecursiveUntaggedOptionEither() {
        myFixture.configureByText(
            "test_recursive_option_either.stvn",
            """
            {
              :defs {
                :EitherTest :Option(:Either(:Int { #audited } :DateTime))
                #PORT :Int 8080
              }
              :type :EitherTest
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Some ") || strings.contains("#Some"));
        assertTrue(strings.contains("#None"));
        assertTrue(strings.contains("#Left ") || strings.contains("#Left"));
        assertTrue(strings.contains("#Right ") || strings.contains("#Right"));
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
        assertFalse(strings.contains("#PORT"));
    }

    public void testRecursiveUntaggedOptionOptionEither() {
        myFixture.configureByText(
            "test_recursive_option_option_either.stvn",
            """
            {
              :type :Option(:Option(:Either(:String { #audited } :DateTime)))
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Some ") || strings.contains("#Some"));
        assertTrue(strings.contains("#None"));
        assertTrue(strings.contains("#Left ") || strings.contains("#Left"));
        assertTrue(strings.contains("#Right ") || strings.contains("#Right"));
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
    }

    public void testRecursiveUntaggedOptionUnion() {
        myFixture.configureByText(
            "test_recursive_option_union.stvn",
            """
            {
              :type :Option(:Union(:Bool { #audited } :DateTime))
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Some ") || strings.contains("#Some"));
        assertTrue(strings.contains("#None"));
        assertTrue(strings.contains("#1 ") || strings.contains("#1"));
        assertTrue(strings.contains("#2 ") || strings.contains("#2"));
        assertTrue(strings.contains("#TRUE"));
        assertTrue(strings.contains("#FALSE"));
        assertTrue(strings.stream().anyMatch(s -> s.matches(".*\\d{4}-\\d{2}-\\d{2}T.*")));
    }

    public void testRecursiveUntaggedOptionUnionSuppressesAmbiguous() {
        myFixture.configureByText(
            "test_recursive_option_union_ambiguous.stvn",
            """
            {
              :defs {
                #CONST_A :Int 10
                #CONST_B :Float 20.0
              }
              :type :Option(:Union(:Int :Float))
              :body <caret>
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var strings = myFixture.getLookupElementStrings();
        assertNotNull(strings);
        assertTrue(strings.contains("#Some ") || strings.contains("#Some"));
        assertTrue(strings.contains("#None"));
        assertTrue(strings.contains("#1 ") || strings.contains("#1"));
        assertTrue(strings.contains("#2 ") || strings.contains("#2"));
        assertFalse(strings.contains("#CONST_A"));
        assertFalse(strings.contains("#CONST_B"));
    }

    public void testImplicitOptionTagSkipToEitherLeftEnum() {
        myFixture.configureByText(
            "test_implicit_option_skip.stvn",
            """
            {
              :type :Seq(:Option(:Option(:Either(:Enum[#A #B] { #audited } :DateTime))))
              :body [
                #Some #Some #Left #A
                #Some #Left <caret>
              ]
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#A"));
        assertTrue(lookupStrings.contains("#B"));
        assertFalse(lookupStrings.contains("#Left"));
        assertFalse(lookupStrings.contains("#Right"));
    }

    public void testImplicitOptionRootEitherLeft() {
        myFixture.configureByText(
            "test_implicit_option_root_either_left.stvn",
            """
            {
              :type :Option(:Either(:Enum[#X #Y] :String))
              :body #Left <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#X"));
        assertTrue(lookupStrings.contains("#Y"));
        assertFalse(lookupStrings.contains("#Left"));
        assertFalse(lookupStrings.contains("#Right"));
        assertFalse(lookupStrings.contains("#Some"));
        assertFalse(lookupStrings.contains("#None"));
    }

    public void testImplicitOptionUnionBranch() {
        myFixture.configureByText(
            "test_implicit_option_union.stvn",
            """
            {
              :type :Option(:Union(:Enum[#RED #GREEN] { #audited } :DateTime))
              :body #1 <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#RED"));
        assertTrue(lookupStrings.contains("#GREEN"));
        assertFalse(lookupStrings.contains("#1"));
        assertFalse(lookupStrings.contains("#2"));
    }

    private static @Nullable LookupElement findLookupElement(LookupElement[] elements, String lookupString) {
        for (var el : elements) {
            if (el.getLookupString().equals(lookupString)) {
                return el;
            }
        }
        return null;
    }

    public void testDigitTokenTouchingLookaheadSuppression() {
        myFixture.configureByText(
            "temp_repro.stvn",
            """
            {
              :defs {
                :UnionLikeEitherB :Union( :Int32 :Boolean :String )
              }
              :type :Tuple(
                :Int32
                :Int32
                :Int32
                :Int32
                :UnionLikeEitherB
              )
              :body (
                10
                20
                30
                1<caret>
              )
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();

        // Caret touches '1' without space: Lookahead suggestions for element 5 (#1, #2, #3) must be suppressed!
        if (lookupStrings != null) {
            assertFalse("Element 5 tag #1 must not be offered when caret touches element 4 literal", lookupStrings.contains("#1 ") || lookupStrings.contains("#1"));
            assertFalse("Element 5 tag #2 must not be offered when caret touches element 4 literal", lookupStrings.contains("#2 ") || lookupStrings.contains("#2"));
            assertFalse("Element 5 tag #3 must not be offered when caret touches element 4 literal", lookupStrings.contains("#3 ") || lookupStrings.contains("#3"));
        }
    }

    public void testDigitTokenWhitespacePermitsLookahead() {
        myFixture.configureByText(
            "temp_permits.stvn",
            """
            {
              :defs {
                :UnionLikeEitherB :Union( :Int32 :Boolean :String )
              }
              :type :Tuple(
                :Int32
                :UnionLikeEitherB
              )
              :body (
                10
                <caret>
              )
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#1 ") || lookupStrings.contains("#1"));
        assertTrue(lookupStrings.contains("#2 ") || lookupStrings.contains("#2"));
        assertTrue(lookupStrings.contains("#3 ") || lookupStrings.contains("#3"));
    }

    public void testPrefixSigilDisciplineRawDigitRejection() {
        myFixture.configureByText(
            "sigil_discipline.stvn",
            """
            {
              :type :Either( :Int32 :String )
              :body <caret>
            }
            """
        );

        // 1. In empty whitespace: #Right and #Left must be offered
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);
        assertTrue(lookupStrings.contains("#Right ") || lookupStrings.contains("#Right"));

        // 2. Author types raw digit '1': Sigil discipline must reject '#'-prefixed tags
        myFixture.type('1');
        lookupStrings = myFixture.getLookupElementStrings();
        if (lookupStrings != null) {
            assertFalse("Raw digit '1' must not match '#Right'", lookupStrings.contains("#Right ") || lookupStrings.contains("#Right"));
            assertFalse("Raw digit '1' must not match '#Left'", lookupStrings.contains("#Left ") || lookupStrings.contains("#Left"));
        }
    }

    public void testHashSigilActivationInTupleSlot() {
        myFixture.configureByText(
            "hash_tuple_slot.stvn",
            """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple(
                :DisjointUnion
                :DisjointUnion
              )
              :body (
                #TRUE
                #<caret>
              )
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Completion items must be pro-offered when typing '#' in tuple slot", lookupStrings);
        assertTrue("Candidate sum tag #1 must be offered", lookupStrings.contains("#1 ") || lookupStrings.contains("#1"));
        assertTrue("Candidate sum tag #2 must be offered", lookupStrings.contains("#2 ") || lookupStrings.contains("#2"));
        assertTrue("Candidate sum tag #3 must be offered", lookupStrings.contains("#3 ") || lookupStrings.contains("#3"));
        assertTrue("Candidate sum tag #4 must be offered", lookupStrings.contains("#4 ") || lookupStrings.contains("#4"));
    }

    public void testHashSigilActivationInEitherSlot() {
        myFixture.configureByText(
            "hash_either_slot.stvn",
            """
            {
              :type :Either( :Int32 :String )
              :body #<caret>
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Completion items must be pro-offered when typing '#' in either slot", lookupStrings);
        assertTrue("Candidate tag #Right must be offered", lookupStrings.contains("#Right ") || lookupStrings.contains("#Right"));
        assertTrue("Candidate tag #Left must be offered", lookupStrings.contains("#Left ") || lookupStrings.contains("#Left"));
    }

    public void testSumTypeInferenceWithNominalPrefixDisjointBranches() {
        myFixture.configureByText(
            "nominal_disjoint_completion.stvn",
            """
            {
              :defs {
                :StringList :Seq( :String )
              }
              :type :Either( :String :StringList )
              :body <caret>
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);

        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull(lookupStrings);

        // Rule B: Right branch (:StringList) is inferable if disjoint from Left (:String)
        // Now that :String and :StringList are disjoint, #Right and #Left must appear
        assertTrue("Should offer #Right tag", lookupStrings.contains("#Right ") || lookupStrings.contains("#Right"));
        assertTrue("Should offer #Left tag", lookupStrings.contains("#Left ") || lookupStrings.contains("#Left"));
    }

    /**
     * Verifies the Sigil Symmetrical Consumption Invariant (§ 3.1, § 7.4):
     * Completing #minSize 1 when caret is on or after # in ':Type {# #maxSize 4096 } :String'
     * consumes the preceding bare '#' and produces exactly ':Type { #minSize 1 #maxSize 4096 } :String'
     * with zero '##' tokens.
     */
    public void testMetadataFacetSigilSymmetricalConsumption() {
        myFixture.configureByText(
            "sigil_consumption.stvn_d",
            """
            {
              :meta {
                #name "sigil_test"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Type {#<caret> #maxSize 4096 } :String
              }
            }
            """
        );

        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist inside metadata map", elements);

        var minSizeElement = findLookupElement(elements, "#minSize 1");
        assertNotNull("Completion must offer #minSize 1", minSizeElement);

        myFixture.getLookup().setCurrentItem(minSizeElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertFalse("Completion must never produce '##' tokens", resultText.contains("##"));
        assertTrue("Completion must cleanly insert '#minSize 1' with proper spacing:\n" + resultText,
            resultText.contains(":Type { #minSize 1 #maxSize 4096 } :String"));
    }

    /**
     * Verifies the Lexical Facet Deduplication Invariant (§ 5.1.4):
     * When #maxSize is already lexically present in the metadata block, completion must
     * strictly exclude #maxSize proposals even when preceding syntax contains incomplete tokens.
     */
    public void testMetadataFacetLexicalDeduplication() {
        myFixture.configureByText(
            "facet_dedup.stvn_d",
            """
            {
              :meta {
                #name "dedup_test"
                #domain "test"
                #kind #DEFS
              }
              :defs {
                :Type {#<caret> #maxSize 4096 } :String
              }
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Completion must offer candidate #minSize 1", lookupStrings.contains("#minSize 1"));
        assertFalse("Completion must NOT offer duplicate #maxSize 256", lookupStrings.contains("#maxSize 256"));
    }

    /**
     * Verifies TC-CMP-CTOR-01:
     * Completion in a :Tuple metadata block proposes Tier 2 trait overrides (#equatable, #comparable)
     * and strictly excludes #strip and non-applicable facets.
     */
    public void testTupleConstructorMetadataCompletion() {
        myFixture.configureByText(
            "tuple_meta.stvn",
            """
            {
              :defs {
                :Values {<caret>} :Tuple ( :Int :String )
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));
        assertTrue("Must propose #equatable #TRUE", lookupStrings.contains("#equatable #TRUE"));
        assertTrue("Must propose #comparable #FALSE", lookupStrings.contains("#comparable #FALSE"));
        assertTrue("Must propose #comparable #TRUE", lookupStrings.contains("#comparable #TRUE"));
        assertFalse("Must NOT propose #strip", lookupStrings.contains("#strip"));
        assertFalse("Must NOT propose #minSize 1", lookupStrings.contains("#minSize 1"));
    }

    /**
     * Verifies TC-CMP-CTOR-02:
     * Completion in a :Seq collection constructor metadata block proposes Tier 1 size bounds
     * and Tier 2 trait overrides, while strictly excluding #strip.
     */
    public void testSeqConstructorMetadataCompletion() {
        myFixture.configureByText(
            "seq_meta.stvn",
            """
            {
              :defs {
                :List {<caret>} :Seq ( :Int )
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #minSize 1", lookupStrings.contains("#minSize 1"));
        assertTrue("Must propose #maxSize 100", lookupStrings.contains("#maxSize 100"));
        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));
        assertTrue("Must propose #comparable #FALSE", lookupStrings.contains("#comparable #FALSE"));
        assertFalse("Must NOT propose #strip", lookupStrings.contains("#strip"));
        assertFalse("Must NOT propose #invertible", lookupStrings.contains("#invertible"));
    }

    /**
     * Verifies TC-CMP-CTOR-03:
     * Completion in a :Map collection constructor metadata block proposes #invertible,
     * size bounds, and trait overrides, while strictly excluding #strip.
     */
    public void testMapConstructorMetadataCompletion() {
        myFixture.configureByText(
            "map_meta.stvn",
            """
            {
              :defs {
                :Dictionary {<caret>} :Map ( :String :Int )
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #invertible", lookupStrings.contains("#invertible"));
        assertTrue("Must propose #minSize 1", lookupStrings.contains("#minSize 1"));
        assertTrue("Must propose #maxSize 100", lookupStrings.contains("#maxSize 100"));
        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));
        assertTrue("Must propose #comparable #FALSE", lookupStrings.contains("#comparable #FALSE"));
        assertFalse("Must NOT propose #strip", lookupStrings.contains("#strip"));
    }

    /**
     * Verifies TC-CMP-CTOR-04:
     * Completion in a :Union constructor metadata block proposes Tier 2 trait overrides,
     * while strictly excluding #strip.
     */
    public void testUnionConstructorMetadataCompletion() {
        myFixture.configureByText(
            "union_meta.stvn",
            """
            {
              :defs {
                :EitherOr {<caret>} :Union ( :Int :String )
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));
        assertTrue("Must propose #equatable #TRUE", lookupStrings.contains("#equatable #TRUE"));
        assertTrue("Must propose #comparable #FALSE", lookupStrings.contains("#comparable #FALSE"));
        assertTrue("Must propose #comparable #TRUE", lookupStrings.contains("#comparable #TRUE"));
        assertFalse("Must NOT propose #strip", lookupStrings.contains("#strip"));
    }

    /**
     * Verifies TC-CMP-CTOR-05:
     * Sigil consumption on constructor metadata completion:
     * Selecting #equatable #FALSE with caret at {#<caret>} leaves zero double ## tokens.
     */
    public void testConstructorSigilConsumption() {
        myFixture.configureByText(
            "sigil_ctor.stvn",
            """
            {
              :defs {
                :Values {#<caret>} :Tuple ( :Int :String )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);

        var eqElement = findLookupElement(elements, "#equatable #FALSE");
        assertNotNull("Must offer #equatable #FALSE", eqElement);

        myFixture.getLookup().setCurrentItem(eqElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertFalse("Completion must never produce '##' tokens", resultText.contains("##"));
        assertTrue("Completion must cleanly insert '#equatable #FALSE':\n" + resultText,
            resultText.contains(":Values { #equatable #FALSE} :Tuple") || resultText.contains(":Values { #equatable #FALSE } :Tuple"));
    }

    /**
     * Verifies TC-CMP-CTOR-06:
     * Lexical deduplication on constructor metadata completion:
     * When #equatable #FALSE is already declared, #equatable proposals are strictly excluded.
     */
    public void testConstructorLexicalDeduplication() {
        myFixture.configureByText(
            "dedup_ctor.stvn",
            """
            {
              :defs {
                :Values { #equatable #FALSE <caret>} :Tuple ( :Int :String )
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertFalse("Must NOT offer duplicate #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));
        assertFalse("Must NOT offer duplicate #equatable #TRUE", lookupStrings.contains("#equatable #TRUE"));
        assertTrue("Must offer candidate #comparable #FALSE", lookupStrings.contains("#comparable #FALSE"));
        assertTrue("Must offer candidate #comparable #TRUE", lookupStrings.contains("#comparable #TRUE"));
    }

    /**
     * Verifies TC-AW-CMP-01:
     * Pre-schema completion preceding a :Tuple constructor proposes trait overrides
     * with type text 'Wrap in { ... }', and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingTupleConstructorCompletion() {
        myFixture.configureByText(
            "aw_tuple.stvn",
            """
            {
              :defs {
                :Values <caret> :Tuple ( :Int :String )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));
        assertTrue("Must propose #equatable #TRUE", lookupStrings.contains("#equatable #TRUE"));
        assertTrue("Must propose #comparable #FALSE", lookupStrings.contains("#comparable #FALSE"));
        assertTrue("Must propose #comparable #TRUE", lookupStrings.contains("#comparable #TRUE"));
        assertFalse("Must NOT propose #strip", lookupStrings.contains("#strip"));

        var eqElement = findLookupElement(elements, "#equatable #FALSE");
        assertNotNull("Must find #equatable #FALSE element", eqElement);
        var presentation = new LookupElementPresentation();
        eqElement.renderElement(presentation);
        assertEquals("Wrap in { ... }", presentation.getTypeText());

        myFixture.getLookup().setCurrentItem(eqElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Values { #equatable #FALSE } :Tuple ( :Int :String )"));
        int caretOffset = myFixture.getEditor().getCaretModel().getOffset();
        int expectedCaretOffset = resultText.indexOf("#equatable #FALSE") + "#equatable #FALSE".length();
        assertEquals("Caret must be immediately after inserted facet inside braces", expectedCaretOffset, caretOffset);
    }

    /**
     * Verifies TC-AW-CMP-02:
     * Pre-schema completion preceding a :Seq collection constructor proposes bounds and overrides,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingSeqConstructorCompletion() {
        myFixture.configureByText(
            "aw_seq.stvn",
            """
            {
              :defs {
                :List <caret> :Seq ( :Int )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #minSize 1", lookupStrings.contains("#minSize 1"));
        assertTrue("Must propose #maxSize 100", lookupStrings.contains("#maxSize 100"));
        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));

        var minSizeElement = findLookupElement(elements, "#minSize 1");
        assertNotNull("Must find #minSize 1 element", minSizeElement);
        myFixture.getLookup().setCurrentItem(minSizeElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":List { #minSize 1 } :Seq ( :Int )"));
    }

    /**
     * Verifies TC-AW-CMP-03:
     * Pre-schema completion preceding a :Map constructor proposes #invertible and size bounds,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingMapConstructorCompletion() {
        myFixture.configureByText(
            "aw_map.stvn",
            """
            {
              :defs {
                :Dictionary <caret> :Map ( :String :Int )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #invertible", lookupStrings.contains("#invertible"));
        assertTrue("Must propose #minSize 1", lookupStrings.contains("#minSize 1"));

        var invElement = findLookupElement(elements, "#invertible");
        assertNotNull("Must find #invertible element", invElement);
        myFixture.getLookup().setCurrentItem(invElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Dictionary { #invertible } :Map ( :String :Int )"));
    }

    /**
     * Verifies TC-AW-CMP-04:
     * Pre-schema completion preceding a :Union constructor proposes trait overrides,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingUnionConstructorCompletion() {
        myFixture.configureByText(
            "aw_union.stvn",
            """
            {
              :defs {
                :EitherOr <caret> :Union ( :Int :String )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));

        var eqElement = findLookupElement(elements, "#equatable #FALSE");
        assertNotNull("Must find #equatable #FALSE element", eqElement);
        myFixture.getLookup().setCurrentItem(eqElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":EitherOr { #equatable #FALSE } :Union ( :Int :String )"));
    }

    /**
     * Verifies TC-AW-CMP-05:
     * Pre-schema completion preceding :Int scalar proposes numeric facets,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingIntScalarCompletion() {
        myFixture.configureByText(
            "aw_int.stvn",
            """
            {
              :defs {
                :Age <caret> :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #unsigned", lookupStrings.contains("#unsigned"));
        assertTrue("Must propose #size 64", lookupStrings.contains("#size 64"));
        assertTrue("Must propose #minIncl 1", lookupStrings.contains("#minIncl 1"));

        var unsElement = findLookupElement(elements, "#unsigned");
        assertNotNull("Must find #unsigned element", unsElement);
        myFixture.getLookup().setCurrentItem(unsElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Age { #unsigned } :Int"));
    }

    /**
     * Verifies TC-AW-CMP-06:
     * Pre-schema completion preceding :String scalar proposes string facets,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingStringScalarCompletion() {
        myFixture.configureByText(
            "aw_string.stvn",
            """
            {
              :defs {
                :Name <caret> :String
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #minSize 1", lookupStrings.contains("#minSize 1"));
        assertTrue("Must propose #maxSize 256", lookupStrings.contains("#maxSize 256"));
        assertTrue("Must propose #regex", lookupStrings.contains("#regex \"^[A-Za-z0-9]+$\""));

        var minElement = findLookupElement(elements, "#minSize 1");
        assertNotNull("Must find #minSize 1 element", minElement);
        myFixture.getLookup().setCurrentItem(minElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Name { #minSize 1 } :String"));
    }

    /**
     * Verifies TC-AW-CMP-07:
     * Pre-schema completion preceding :Float scalar proposes continuous float facets,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingFloatScalarCompletion() {
        myFixture.configureByText(
            "aw_float.stvn",
            """
            {
              :defs {
                :Weight <caret> :Float
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #exact", lookupStrings.contains("#exact"));
        assertTrue("Must propose #size 32", lookupStrings.contains("#size 32"));
        assertTrue("Must propose #equatable #TRUE", lookupStrings.contains("#equatable #TRUE"));

        var exactElement = findLookupElement(elements, "#exact");
        assertNotNull("Must find #exact element", exactElement);
        myFixture.getLookup().setCurrentItem(exactElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Weight { #exact } :Float"));
    }

    /**
     * Verifies TC-AW-CMP-08:
     * Pre-schema completion preceding :Boolean scalar proposes boolean facets,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingBooleanScalarCompletion() {
        myFixture.configureByText(
            "aw_bool.stvn",
            """
            {
              :defs {
                :Flag <caret> :Boolean
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));

        var eqElement = findLookupElement(elements, "#equatable #FALSE");
        assertNotNull("Must find #equatable #FALSE element", eqElement);
        myFixture.getLookup().setCurrentItem(eqElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Flag { #equatable #FALSE } :Boolean"));
    }

    /**
     * Verifies TC-AW-CMP-09:
     * Pre-schema completion preceding :DateTime scalar proposes temporal facets,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingDateTimeScalarCompletion() {
        myFixture.configureByText(
            "aw_datetime.stvn",
            """
            {
              :defs {
                :Timestamp <caret> :DateTime
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #zoned", lookupStrings.contains("#zoned"));
        assertTrue("Must propose #audited", lookupStrings.contains("#audited"));
        assertTrue("Must propose #offset", lookupStrings.contains("#offset"));

        var zonedElement = findLookupElement(elements, "#zoned");
        assertNotNull("Must find #zoned element", zonedElement);
        myFixture.getLookup().setCurrentItem(zonedElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Timestamp { #zoned } :DateTime"));
    }

    /**
     * Verifies TC-AW-CMP-10:
     * Pre-schema completion preceding :TimeEpoch scalar proposes epoch scale facets,
     * and wraps selection in a canonical metadata block.
     */
    public void testAutoWrappingTimeEpochScalarCompletion() {
        myFixture.configureByText(
            "aw_epoch.stvn",
            """
            {
              :defs {
                :Ticks <caret> :TimeEpoch
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #s", lookupStrings.contains("#s"));
        assertTrue("Must propose #ms", lookupStrings.contains("#ms"));
        assertTrue("Must propose #us", lookupStrings.contains("#us"));
        assertTrue("Must propose #ns", lookupStrings.contains("#ns"));
        assertTrue("Must propose #minIncl 0", lookupStrings.contains("#minIncl 0"));

        var sElement = findLookupElement(elements, "#s");
        assertNotNull("Must find #s element", sElement);
        myFixture.getLookup().setCurrentItem(sElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Result must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Ticks { #s } :TimeEpoch"));
    }

    /**
     * Verifies TC-AW-CMP-11:
     * Pre-schema completion when the user has already typed '#' sigil:
     * The insert handler consumes the bare '#' and produces zero '##' tokens.
     */
    public void testAutoWrappingPrecedingHashSigilConsumption() {
        myFixture.configureByText(
            "aw_sigil.stvn",
            """
            {
              :defs {
                :Values #<caret> :Tuple ( :Int :String )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        var eqElement = findLookupElement(elements, "#equatable #FALSE");
        assertNotNull("Must offer #equatable #FALSE", eqElement);

        myFixture.getLookup().setCurrentItem(eqElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertFalse("Must never produce double hash ##:\n" + resultText, resultText.contains("##"));
        assertTrue("Must wrap cleanly in braces without extra hashes:\n" + resultText,
            resultText.contains(":Values { #equatable #FALSE } :Tuple ( :Int :String )"));
    }

    /**
     * Verifies TC-AW-CMP-12:
     * Pre-schema completion on root document envelope schema (:type <caret> :Tuple (...)).
     */
    public void testAutoWrappingRootEnvelopeHeaderCompletion() {
        myFixture.configureByText(
            "aw_root.stvn",
            """
            {
              :type <caret> :Tuple ( :Int )
              :body ( 42 )
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #equatable #FALSE", lookupStrings.contains("#equatable #FALSE"));

        var eqElement = findLookupElement(elements, "#equatable #FALSE");
        assertNotNull("Must offer #equatable #FALSE", eqElement);

        myFixture.getLookup().setCurrentItem(eqElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Must wrap root schema cleanly:\n" + resultText,
            resultText.contains(":type { #equatable #FALSE } :Tuple ( :Int )"));
    }

    /**
     * Verifies TC-AW-CMP-13:
     * Pre-schema completion inside a constituent constructor slot (:Tuple ( <caret> :Int )).
     */
    public void testAutoWrappingConstituentSlotCompletion() {
        myFixture.configureByText(
            "aw_slot.stvn",
            """
            {
              :defs {
                :Pair :Tuple ( <caret> :Int :String )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);

        assertTrue("Must propose #minIncl 1", lookupStrings.contains("#minIncl 1"));

        var minElement = findLookupElement(elements, "#minIncl 1");
        assertNotNull("Must offer #minIncl 1", minElement);

        myFixture.getLookup().setCurrentItem(minElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var resultText = myFixture.getFile().getText();
        assertTrue("Must wrap constituent slot schema cleanly:\n" + resultText,
            resultText.contains(":Pair :Tuple ( { #minIncl 1 } :Int :String )"));
    }

    /**
     * Verifies TC-AW-CMP-14:
     * Pre-schema auto-wrapping suppression when a metadata block already exists immediately before.
     */
    public void testAutoWrappingPreSchemaSuppressionWhenMetadataBlockExists() {
        myFixture.configureByText(
            "aw_suppress.stvn",
            """
            {
              :defs {
                :Values { #minSize 1 } <caret> :Tuple ( :Int )
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        if (lookupStrings != null) {
            assertFalse("Must NOT propose #equatable in whitespace after existing block",
                lookupStrings.contains("#equatable #FALSE"));
            assertFalse("Must NOT propose #comparable in whitespace after existing block",
                lookupStrings.contains("#comparable #FALSE"));
        }
    }

    /**
     * Verifies TC-AW-CMP-15:
     * Directive #strip is strictly excluded from all pre-schema completion proposals.
     */
    public void testAutoWrappingStripDirectiveStrictExclusion() {
        String[] testSnippets = {
            "{ :defs { :T <caret> :Tuple ( :Int ) } }",
            "{ :defs { :S <caret> :Seq ( :Int ) } }",
            "{ :defs { :M <caret> :Map ( :String :Int ) } }",
            "{ :defs { :U <caret> :Union ( :Int :String ) } }",
            "{ :defs { :I <caret> :Int } }",
            "{ :defs { :Str <caret> :String } }",
            "{ :defs { :D <caret> :DateTime } }",
            "{ :defs { :E <caret> :TimeEpoch } }"
        };

        for (int i = 0; i < testSnippets.length; i++) {
            myFixture.configureByText("strip_excl_" + i + ".stvn", testSnippets[i]);
            myFixture.completeBasic();
            var lookupStrings = myFixture.getLookupElementStrings();
            if (lookupStrings != null) {
                assertFalse("Must NEVER propose #strip on type definition: " + testSnippets[i],
                    lookupStrings.contains("#strip"));
            }
        }
    }

    /**
     * Verifies TC-PAD-01:
     * Chained insert inside a metadata block touching an existing token (e.g. '#size 8<caret>')
     * injects a leading space before the inserted facet, avoiding '8#maxExcl' token smashing.
     */
    public void testChainedInsertPaddingInsideMetadataMap() {
        myFixture.configureByText(
            "chained_pad_01.stvn_d",
            """
            {
              :defs {
                :Type { #size 8<caret> } :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var maxExclElement = findLookupElement(elements, "#maxExcl 100");
        assertNotNull("Must offer #maxExcl 100 for 8-bit signed int", maxExclElement);
        myFixture.getLookup().setCurrentItem(maxExclElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        var resultText = myFixture.getFile().getText();
        assertFalse("Must never produce '8#' token smashing:\n" + resultText, resultText.contains("8#"));
        assertTrue("Must inject leading space before chained facet:\n" + resultText,
            resultText.contains(":Type { #size 8 #maxExcl 100 } :Int"));
    }

    /**
     * Verifies TC-PAD-02:
     * Chained insert inside a metadata block with a trailing sigil touching an existing token
     * (e.g. '#size 8#<caret>') consumes the sigil and ensures a single leading space.
     */
    public void testChainedInsertPaddingWithSigilInsideMetadataMap() {
        myFixture.configureByText(
            "chained_pad_02.stvn_d",
            """
            {
              :defs {
                :Type { #size 8#<caret> } :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var maxExclElement = findLookupElement(elements, "#maxExcl 100");
        assertNotNull("Must offer #maxExcl 100 for 8-bit signed int", maxExclElement);
        myFixture.getLookup().setCurrentItem(maxExclElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        var resultText = myFixture.getFile().getText();
        assertFalse("Must never produce '8#' or '##':\n" + resultText, resultText.contains("8#") || resultText.contains("##"));
        assertTrue("Must cleanly insert '#maxExcl 100' with single space:\n" + resultText,
            resultText.contains(":Type { #size 8 #maxExcl 100 } :Int"));
    }

    /**
     * Verifies TC-PAD-03:
     * Auto-wrapping pre-schema completion preceding :Int wraps cleanly in braces with proper spacing.
     */
    public void testPreSchemaAutoWrappingLeadingWhitespace() {
        myFixture.configureByText(
            "pre_schema_pad_03.stvn_d",
            """
            {
              :defs {
                :Age <caret> :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var unsElement = findLookupElement(elements, "#unsigned");
        assertNotNull("Must offer #unsigned", unsElement);
        myFixture.getLookup().setCurrentItem(unsElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        var resultText = myFixture.getFile().getText();
        assertTrue("Must wrap cleanly in '{ #unsigned }':\n" + resultText,
            resultText.contains(":Age { #unsigned } :Int"));
    }

    /**
     * Verifies TC-DEDUP-01:
     * When '#size 8' is declared in the metadata block, all #size variants
     * (#size 16, #size 64, #size 128) are suppressed from completion proposals.
     */
    public void testParameterizedSizeFacetDeduplication() {
        myFixture.configureByText(
            "dedup_size_01.stvn_d",
            """
            {
              :defs {
                :Type { #size 8 <caret> } :Int
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);
        assertFalse("Must NOT propose #size 16 when #size 8 declared", lookupStrings.contains("#size 16"));
        assertFalse("Must NOT propose #size 64 when #size 8 declared", lookupStrings.contains("#size 64"));
        assertFalse("Must NOT propose #size 128 when #size 8 declared", lookupStrings.contains("#size 128"));
        assertFalse("Must NOT propose duplicate #size 8", lookupStrings.contains("#size 8"));
    }

    /**
     * Verifies TC-BOUND-01:
     * Signed 8-bit integer ({ #size 8 }) proposes non-redundant #maxExcl 100 and strictly prohibits #maxIncl and #minExcl.
     */
    public void testSigned8BitIntegerUpperBoundsProposal() {
        myFixture.configureByText(
            "bound_01.stvn_d",
            """
            {
              :defs {
                :Byte { #size 8 <caret> } :Int
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);
        assertTrue("Must propose non-redundant #maxExcl 100 for signed 8-bit integer", lookupStrings.contains("#maxExcl 100"));
        assertFalse("Must NOT propose redundant #maxExcl 128", lookupStrings.contains("#maxExcl 128"));
        assertFalse("Must NOT propose #maxExcl 65536 on 8-bit integer", lookupStrings.contains("#maxExcl 65536"));
        // Discrete domain invariant: strictly no #maxIncl or #minExcl for :Int
        for (var str : lookupStrings) {
            assertFalse("Must NEVER propose #maxIncl for :Int: " + str, str.startsWith("#maxIncl"));
            assertFalse("Must NEVER propose #minExcl for :Int: " + str, str.startsWith("#minExcl"));
        }
    }

    /**
     * Verifies TC-BOUND-02:
     * Unsigned 8-bit integer ({ #unsigned #size 8 }) proposes non-redundant #maxExcl 100.
     */
    public void testUnsigned8BitIntegerUpperBoundsProposal() {
        myFixture.configureByText(
            "bound_02.stvn_d",
            """
            {
              :defs {
                :UByte { #unsigned #size 8 <caret> } :Int
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);
        assertTrue("Must propose non-redundant #maxExcl 100 for unsigned 8-bit integer", lookupStrings.contains("#maxExcl 100"));
        assertFalse("Must NOT propose redundant #maxExcl 256", lookupStrings.contains("#maxExcl 256"));
        assertFalse("Must NOT propose signed #maxExcl 128 for unsigned integer", lookupStrings.contains("#maxExcl 128"));
        assertFalse("Must NOT propose #maxExcl 65536 on 8-bit integer", lookupStrings.contains("#maxExcl 65536"));
    }

    /**
     * Verifies TC-BOUND-03:
     * Signed 16-bit integer ({ #size 16 }) proposes non-redundant #maxExcl 100.
     */
    public void testSigned16BitIntegerUpperBoundsProposal() {
        myFixture.configureByText(
            "bound_03.stvn_d",
            """
            {
              :defs {
                :Short { #size 16 <caret> } :Int
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);
        assertTrue("Must propose non-redundant #maxExcl 100 for signed 16-bit integer", lookupStrings.contains("#maxExcl 100"));
        assertFalse("Must NOT propose redundant #maxExcl 32768", lookupStrings.contains("#maxExcl 32768"));
        assertFalse("Must NOT propose #maxExcl 65536 for signed 16-bit integer", lookupStrings.contains("#maxExcl 65536"));
    }

    /**
     * Verifies TC-BOUND-04:
     * Unsigned 16-bit integer ({ #unsigned #size 16 }) proposes non-redundant #maxExcl 100.
     */
    public void testUnsigned16BitIntegerUpperBoundsProposal() {
        myFixture.configureByText(
            "bound_04.stvn_d",
            """
            {
              :defs {
                :UShort { #unsigned #size 16 <caret> } :Int
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);
        assertTrue("Must propose non-redundant #maxExcl 100 for unsigned 16-bit integer", lookupStrings.contains("#maxExcl 100"));
        assertFalse("Must NOT propose redundant #maxExcl 65536", lookupStrings.contains("#maxExcl 65536"));
    }

    /**
     * Verifies TC-BOUND-05:
     * Default 32-bit signed integer proposes non-redundant #maxExcl 100.
     */
    public void testDefault32BitSignedIntegerUpperBoundsProposal() {
        myFixture.configureByText(
            "bound_05.stvn_d",
            """
            {
              :defs {
                :StandardInt { <caret> } :Int
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);
        assertTrue("Must propose non-redundant #maxExcl 100 for default 32-bit signed integer",
            lookupStrings.contains("#maxExcl 100"));
        assertFalse("Must NOT propose redundant #maxExcl 2147483648", lookupStrings.contains("#maxExcl 2147483648"));
        assertFalse("Must NOT propose #maxExcl 65536 for 32-bit integer", lookupStrings.contains("#maxExcl 65536"));
    }

    /**
     * Verifies TC-BOUND-06:
     * Default 32-bit unsigned integer ({ #unsigned }) proposes non-redundant #maxExcl 100.
     */
    public void testDefault32BitUnsignedIntegerUpperBoundsProposal() {
        myFixture.configureByText(
            "bound_06.stvn_d",
            """
            {
              :defs {
                :UInt { #unsigned <caret> } :Int
              }
            }
            """
        );
        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must exist", lookupStrings);
        assertTrue("Must propose non-redundant #maxExcl 100 for default 32-bit unsigned integer",
            lookupStrings.contains("#maxExcl 100"));
        assertFalse("Must NOT propose redundant #maxExcl 4294967296", lookupStrings.contains("#maxExcl 4294967296"));
        assertFalse("Must NOT propose #maxExcl 2147483648 for unsigned integer",
            lookupStrings.contains("#maxExcl 2147483648"));
    }

    /**
     * Verifies TC-BOUND-SCALE-01 through TC-BOUND-SCALE-08:
     * Stepped scaling across signed and unsigned bit widths and discrete domain bounds.
     */
    public void testSteppedBoundsScalingAcrossWidths() {
        // 1. Signed s=7 (Vmax = 64 in (10, 100] => proposes 10)
        myFixture.configureByText("scale_s7.stvn_d", "{ :defs { :T { #size 7 <caret> } :Int } }");
        myFixture.completeBasic();
        var s7Strings = myFixture.getLookupElementStrings();
        assertNotNull(s7Strings);
        assertTrue("Signed s=7 (Vmax=64) must propose #maxExcl 10", s7Strings.contains("#maxExcl 10"));
        assertFalse("Signed s=7 must NOT propose #maxExcl 64", s7Strings.contains("#maxExcl 64"));

        // 2. Signed s=6 (Vmax = 32 in (10, 100] => proposes 10)
        myFixture.configureByText("scale_s6.stvn_d", "{ :defs { :T { #size 6 <caret> } :Int } }");
        myFixture.completeBasic();
        var s6Strings = myFixture.getLookupElementStrings();
        assertNotNull(s6Strings);
        assertTrue("Signed s=6 (Vmax=32) must propose #maxExcl 10", s6Strings.contains("#maxExcl 10"));

        // 3. Signed s=5 (Vmax = 16 in (10, 100] => proposes 10)
        myFixture.configureByText("scale_s5.stvn_d", "{ :defs { :T { #size 5 <caret> } :Int } }");
        myFixture.completeBasic();
        var s5Strings = myFixture.getLookupElementStrings();
        assertNotNull(s5Strings);
        assertTrue("Signed s=5 (Vmax=16) must propose #maxExcl 10", s5Strings.contains("#maxExcl 10"));

        // 4. Unsigned s=6 (Vmax = 64 in (10, 100] => proposes 10)
        myFixture.configureByText("scale_u6.stvn_d", "{ :defs { :T { #unsigned #size 6 <caret> } :Int } }");
        myFixture.completeBasic();
        var u6Strings = myFixture.getLookupElementStrings();
        assertNotNull(u6Strings);
        assertTrue("Unsigned s=6 (Vmax=64) must propose #maxExcl 10", u6Strings.contains("#maxExcl 10"));

        // 5. Unsigned s=4 (Vmax = 16 in (10, 100] => proposes 10)
        myFixture.configureByText("scale_u4.stvn_d", "{ :defs { :T { #unsigned #size 4 <caret> } :Int } }");
        myFixture.completeBasic();
        var u4Strings = myFixture.getLookupElementStrings();
        assertNotNull(u4Strings);
        assertTrue("Unsigned s=4 (Vmax=16) must propose #maxExcl 10", u4Strings.contains("#maxExcl 10"));

        // 6. Signed micro-widths s <= 4 (Vmax <= 10 => Vmax - 1)
        myFixture.configureByText("scale_s4.stvn_d", "{ :defs { :T { #size 4 <caret> } :Int } }");
        myFixture.completeBasic();
        var s4Strings = myFixture.getLookupElementStrings();
        assertNotNull(s4Strings);
        assertTrue("Signed s=4 (Vmax=8) must propose #maxExcl 7", s4Strings.contains("#maxExcl 7"));

        myFixture.configureByText("scale_s3.stvn_d", "{ :defs { :T { #size 3 <caret> } :Int } }");
        myFixture.completeBasic();
        var s3Strings = myFixture.getLookupElementStrings();
        assertNotNull(s3Strings);
        assertTrue("Signed s=3 (Vmax=4) must propose #maxExcl 3", s3Strings.contains("#maxExcl 3"));

        myFixture.configureByText("scale_s2.stvn_d", "{ :defs { :T { #size 2 <caret> } :Int } }");
        myFixture.completeBasic();
        var s2Strings = myFixture.getLookupElementStrings();
        assertNotNull(s2Strings);
        assertTrue("Signed s=2 (Vmax=2) must propose #maxExcl 1", s2Strings.contains("#maxExcl 1"));

        // 7. Unsigned micro-widths s <= 3 (Vmax <= 10 => Vmax - 1)
        myFixture.configureByText("scale_u3.stvn_d", "{ :defs { :T { #unsigned #size 3 <caret> } :Int } }");
        myFixture.completeBasic();
        var u3Strings = myFixture.getLookupElementStrings();
        assertNotNull(u3Strings);
        assertTrue("Unsigned s=3 (Vmax=8) must propose #maxExcl 7", u3Strings.contains("#maxExcl 7"));

        myFixture.configureByText("scale_u2.stvn_d", "{ :defs { :T { #unsigned #size 2 <caret> } :Int } }");
        myFixture.completeBasic();
        var u2Strings = myFixture.getLookupElementStrings();
        assertNotNull(u2Strings);
        assertTrue("Unsigned s=2 (Vmax=4) must propose #maxExcl 3", u2Strings.contains("#maxExcl 3"));

        myFixture.configureByText("scale_u1.stvn_d", "{ :defs { :T { #unsigned #size 1 <caret> } :Int } }");
        myFixture.completeBasic();
        var u1Strings = myFixture.getLookupElementStrings();
        assertNotNull(u1Strings);
        assertTrue("Unsigned s=1 (Vmax=2) must propose #maxExcl 1", u1Strings.contains("#maxExcl 1"));

        // 8. Auto-wrapping pre-schema context (:Type <caret> :Int)
        myFixture.configureByText("scale_aw.stvn_d", "{ :defs { :Age <caret> :Int } }");
        myFixture.completeBasic();
        var awStrings = myFixture.getLookupElementStrings();
        assertNotNull(awStrings);
        assertTrue("Pre-schema completion must propose #maxExcl 100", awStrings.contains("#maxExcl 100"));
    }

    /**
     * Verifies TC-ARG-SEL-01:
     * In-map template insertion pre-selects trailing numeric argument and positions caret at argEnd.
     */
    public void testInMapTemplateArgumentPreSelection() {
        myFixture.configureByText(
            "sel_in_map.stvn_d",
            """
            {
              :defs {
                :Age { #size 8 <caret> } :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var maxExclElement = findLookupElement(elements, "#maxExcl 100");
        assertNotNull(maxExclElement);

        myFixture.getLookup().setCurrentItem(maxExclElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var editor = myFixture.getEditor();
        var selectionModel = editor.getSelectionModel();
        assertTrue("Argument '100' must be pre-selected", selectionModel.hasSelection());
        assertEquals("100", selectionModel.getSelectedText());
        assertEquals("Caret must be placed at end of selection",
            selectionModel.getSelectionEnd(), editor.getCaretModel().getOffset());
    }

    /**
     * Verifies TC-ARG-SEL-02:
     * Pre-schema auto-wrapping template insertion pre-selects trailing numeric argument.
     */
    public void testAutoWrappingTemplateArgumentPreSelection() {
        myFixture.configureByText(
            "sel_auto_wrap.stvn_d",
            """
            {
              :defs {
                :Age <caret> :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var maxExclElement = findLookupElement(elements, "#maxExcl 100");
        assertNotNull(maxExclElement);

        myFixture.getLookup().setCurrentItem(maxExclElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var editor = myFixture.getEditor();
        var selectionModel = editor.getSelectionModel();
        assertTrue("Argument '100' must be pre-selected in auto-wrapping envelope", selectionModel.hasSelection());
        assertEquals("100", selectionModel.getSelectedText());
        assertEquals("Caret must be placed at end of selection",
            selectionModel.getSelectionEnd(), editor.getCaretModel().getOffset());

        var resultText = myFixture.getFile().getText();
        assertTrue("Document must contain wrapped metadata block:\n" + resultText,
            resultText.contains(":Age { #maxExcl 100 } :Int"));
    }

    /**
     * Verifies TC-ARG-SEL-03:
     * Immediate keystroke typing overwrites the pre-selected template argument.
     */
    public void testTemplateArgumentImmediateOverwrite() {
        myFixture.configureByText(
            "sel_overwrite.stvn_d",
            """
            {
              :defs {
                :Age { #size 8 <caret> } :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var maxExclElement = findLookupElement(elements, "#maxExcl 100");
        assertNotNull(maxExclElement);

        myFixture.getLookup().setCurrentItem(maxExclElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var editor = myFixture.getEditor();
        var selectionModel = editor.getSelectionModel();
        assertTrue("Template argument '100' must be selected for immediate overwrite",
            selectionModel.hasSelection());
        assertEquals("100", selectionModel.getSelectedText());

        // Overwrite pre-selected argument with '65'
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            editor.getDocument().replaceString(
                selectionModel.getSelectionStart(),
                selectionModel.getSelectionEnd(),
                "65"
            );
            selectionModel.removeSelection();
        });
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        var resultText = editor.getDocument().getText();
        assertTrue("Overwriting selection with '65' must produce clean syntax:\n" + resultText,
            resultText.contains(":Age { #size 8 #maxExcl 65 } :Int"));
        assertFalse("Must not leave residual digits from '100':\n" + resultText,
            resultText.contains("100"));
    }

    /**
     * Verifies TC-ARG-SEL-04:
     * Navigating away or pressing Tab preserves the template value without W003 compiler warnings.
     */
    public void testTemplateArgumentPreservationWithoutWarnings() {
        myFixture.configureByText(
            "sel_preserve.stvn_d",
            """
            {
              :defs {
                :Age { #size 8 <caret> } :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);
        var maxExclElement = findLookupElement(elements, "#maxExcl 100");
        assertNotNull(maxExclElement);

        myFixture.getLookup().setCurrentItem(maxExclElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        // Clear selection (simulating navigation away or tab)
        myFixture.getEditor().getSelectionModel().removeSelection();

        var resultText = myFixture.getFile().getText();
        assertTrue("Template '#maxExcl 100' must be preserved:\n" + resultText,
            resultText.contains(":Age { #size 8 #maxExcl 100 } :Int"));
    }

    /**
     * Verifies TC-ARG-SEL-05:
     * Pre-selection functions across collection cardinality templates (#maxSize 100, #minSize 1).
     */
    public void testCollectionCapacityTemplateArgumentPreSelection() {
        myFixture.configureByText(
            "sel_coll.stvn_d",
            """
            {
              :defs {
                :Items { <caret> } :Seq( :Int )
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull(elements);

        var maxSizeElement = findLookupElement(elements, "#maxSize 100");
        assertNotNull("Must offer #maxSize 100 for :Seq", maxSizeElement);

        myFixture.getLookup().setCurrentItem(maxSizeElement);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

        var editor = myFixture.getEditor();
        assertEquals("Argument '100' must be pre-selected for #maxSize",
            "100", editor.getSelectionModel().getSelectedText());
        assertEquals("Caret must be placed at end of selection",
            editor.getSelectionModel().getSelectionEnd(), editor.getCaretModel().getOffset());
    }

    /**
     * Verifies TC-UNICODE-01:
     * Presentation tail text for bounds facets displays Unicode '∞' instead of literal '\\infty'.
     */
    public void testUnicodeInfinityInCompletionPresentation() {
        myFixture.configureByText(
            "unicode_01.stvn_d",
            """
            {
              :defs {
                :Value { <caret> } :Int
              }
            }
            """
        );
        var elements = myFixture.completeBasic();
        assertNotNull("Completion elements must exist", elements);
        var unsElement = findLookupElement(elements, "#unsigned");
        assertNotNull("Must offer #unsigned", unsElement);
        var presentation = new LookupElementPresentation();
        unsElement.renderElement(presentation);
        var tailText = presentation.getTailText();
        assertNotNull("Tail text must exist on #unsigned", tailText);
        assertTrue("Tail text must contain Unicode '∞': " + tailText, tailText.contains("∞"));
        assertFalse("Tail text must NOT contain literal '\\infty': " + tailText, tailText.contains("\\infty"));
    }
}


