package org.stvnadore.plugin;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Verifies single-line comment insertion, multi-line comment toggling,
 * and uncommenting behavior for STVN documents.
 */
@NullMarked
public final class StvnCommenterTest extends BasePlatformTestCase {

    public void testSingleLineCommentInsertion() {
        myFixture.configureByText("payload.stvn", "<caret>#Some 42");
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE);
        myFixture.checkResult("//#Some 42");
    }

    public void testSingleLineUncomment() {
        myFixture.configureByText("payload.stvn", "<caret>//#Some 42");
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE);
        myFixture.checkResult("#Some 42");
    }

    public void testMultiLineSelectionCommentToggle() {
        var initial = """
            <selection>:defs {
              #A :Int32 10
              #B :Int32 20
            }</selection>
            """;
        myFixture.configureByText("definitions.stvn_incl", initial);
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE);

        var expectedCommented = """
            //:defs {
            //  #A :Int32 10
            //  #B :Int32 20
            //}
            """;
        myFixture.checkResult(expectedCommented);

        // Toggle back to uncommented state
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE);
        var expectedUncommented = """
            :defs {
              #A :Int32 10
              #B :Int32 20
            }
            """;
        myFixture.checkResult(expectedUncommented);
    }

    public void testCommentInCompoundPayload() {
        var text = """
            {
              :type :Tuple( :Int32 :String )
              :body (
                <caret>100
                "hello"
              )
            }
            """;
        myFixture.configureByText("test_compound.stvn", text);
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE);

        var expected = """
            {
              :type :Tuple( :Int32 :String )
              :body (
            //    100
                "hello"
              )
            }
            """;
        myFixture.checkResult(expected);
    }
}
