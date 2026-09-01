package org.stvnadore.plugin.lang;

import com.intellij.lang.Commenter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Defines single-line comment semantics for the STVN data grammar language.
 * STVN exclusively utilizes '//' single-line comments.
 */
@NullMarked
public final class StvnCommenter implements Commenter {

    @Override
    public @Nullable String getLineCommentPrefix() {
        return "//";
    }

    @Override
    public @Nullable String getBlockCommentPrefix() {
        return null;
    }

    @Override
    public @Nullable String getBlockCommentSuffix() {
        return null;
    }

    @Override
    public @Nullable String getCommentedBlockCommentPrefix() {
        return null;
    }

    @Override
    public @Nullable String getCommentedBlockCommentSuffix() {
        return null;
    }
}
