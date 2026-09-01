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
              :type :DateTimeAudited
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
              :type :DateTimeOffset
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
              :type :DateTimeZoned
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
                :Uuid :StringFixed36
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
                :EitherTest :Either(:Uint32 :DateTimeAudited)
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
                :EitherTest :Either(:Uint32 :DateTimeAudited)
                #DEFAULT_PORT :Uint32 8080
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
              :type :Option(:DateTimeZoned)
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
              :type :Union(:Bool :DateTimeAudited :String)
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
              :type :Option(:Either(:String :DateTimeAudited))
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
              :type :Option(:Either(:String :DateTimeAudited))
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
                :EitherTest :Either(:Uint32 :DateTimeAudited)
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
                :EitherTest :Either(:Uint32 :DateTimeAudited)
                #PORT :Uint32 8080
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
              :type :Option(:DateTimeZoned)
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
              :type :Union(:Bool :DateTimeAudited)
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
                #CONST_A :Int32 10
                #CONST_B :Uint32 20
              }
              :type :Union(:Int32 :Uint32)
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
                :EitherTest :Either(:Uint32 :DateTimeAudited)
                #START_TIME :DateTimeAudited "2026-08-25T07:48:57+00:00[UTC]"
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
                :EitherTest :Option(:Either(:Uint32 :DateTimeAudited))
                #PORT :Uint32 8080
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
              :type :Option(:Option(:Either(:String :DateTimeAudited)))
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
              :type :Option(:Union(:Bool :DateTimeAudited))
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
                #CONST_A :Int32 10
                #CONST_B :Uint32 20
              }
              :type :Option(:Union(:Int32 :Uint32))
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
              :type :Seq(:Option(:Option(:Either(:Enum[#A #B] :DateTimeAudited))))
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
              :type :Option(:Union(:Enum[#RED #GREEN] :DateTimeAudited))
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
}

