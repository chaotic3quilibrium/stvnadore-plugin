package org.stvnadore.plugin;

import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Factory class to instantiate the StvnSyntaxHighlighter.
 */
@NullMarked
public final class StvnSyntaxHighlighterFactory extends SyntaxHighlighterFactory {

    /** Constructs an StvnSyntaxHighlighterFactory instance. */
    public StvnSyntaxHighlighterFactory() {}

    @Override
    public SyntaxHighlighter getSyntaxHighlighter(@Nullable Project project, @Nullable VirtualFile virtualFile) {
        return new StvnSyntaxHighlighter();
    }
}