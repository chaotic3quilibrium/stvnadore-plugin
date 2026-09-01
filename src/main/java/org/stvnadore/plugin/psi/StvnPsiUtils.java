package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.psi.*;

import java.util.ArrayList;
import java.util.List;

/**
 * PSI utility helpers for AST inspection and token sequence scanning.
 */
@NullMarked
public final class StvnPsiUtils {

    private StvnPsiUtils() {}

    /**
     * Scans backward from the given position within the current value expression
     * to collect preceding constructor tag tokens in sequential order.
     *
     * @param position Leaf PSI element at caret.
     * @return List of tag strings (e.g., ["#Some", "#Right"]).
     */
    public static List<String> findPrecedingTagsInExpression(PsiElement position) {
        var tags = new ArrayList<String>();
        var prev = PsiTreeUtil.prevLeaf(position);

        while (prev != null) {
            if (prev instanceof PsiWhiteSpace || prev.getText().equals("IntellijIdeaRulezzz")) {
                prev = PsiTreeUtil.prevLeaf(prev);
                continue;
            }

            var text = prev.getText().trim();
            if (isConstructorTag(text)) {
                tags.add(0, text);
                prev = PsiTreeUtil.prevLeaf(prev);
                continue;
            }

            // Non-constructor token or boundary encountered; terminate backward chain scan
            break;
        }

        // Also inspect enclosing explicit sum AST nodes if already constructed
        var curr = position.getParent();
        while (curr != null && !(curr instanceof BodyEntry) && !(curr instanceof ConstantDefinition)) {
            if (curr instanceof ExplicitOptionValue opt) {
                var first = opt.getFirstChild();
                if (first != null && first.getText().startsWith("#") && !tags.contains(first.getText())) {
                    tags.add(0, first.getText());
                }
            } else if (curr instanceof ExplicitEitherValue either) {
                var first = either.getFirstChild();
                if (first != null && first.getText().startsWith("#") && !tags.contains(first.getText())) {
                    tags.add(0, first.getText());
                }
            } else if (curr instanceof ExplicitUnionValue union) {
                var first = union.getFirstChild();
                if (first != null && first.getText().startsWith("#") && !tags.contains(first.getText())) {
                    tags.add(0, first.getText());
                }
            }
            curr = curr.getParent();
        }

        return List.copyOf(tags);
    }

    /**
     * Checks if the given text corresponds to a standard STVN sum type constructor tag.
     */
    public static boolean isConstructorTag(String text) {
        if (text.equals("#Some") || text.equals("#S") ||
            text.equals("#None") || text.equals("#N") ||
            text.equals("#Left") || text.equals("#L") ||
            text.equals("#Right") || text.equals("#R")) {
            return true;
        }
        return text.matches("^#[1-9][0-9]*$");
    }
}
