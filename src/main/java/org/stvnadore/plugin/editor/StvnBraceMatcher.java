package org.stvnadore.plugin.editor;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.StvnTypes;

/**
 * Paired brace matcher for STVN syntax delimiters (braces, brackets, and parentheses).
 */
@NullMarked
public final class StvnBraceMatcher implements PairedBraceMatcher {

    private static final BracePair[] PAIRS = new BracePair[]{
        new BracePair(StvnTypes.LBRACE, StvnTypes.RBRACE, true),
        new BracePair(StvnTypes.LBRACK, StvnTypes.RBRACK, false),
        new BracePair(StvnTypes.LPAREN, StvnTypes.RPAREN, false)
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType, @Nullable IElementType contextType) {
        return true;
    }

    @Override
    public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
