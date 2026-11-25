package org.stvnadore.plugin.hints;

import com.intellij.codeInsight.hints.ImmediateConfigurable;
import com.intellij.codeInsight.hints.InlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsProvider;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.NoSettings;
import com.intellij.codeInsight.hints.SettingsKey;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnProjectSettings;

/**
 * Provides inlay type hints in the editor for inferred sum variants, nominal lineages, and {@code :use} aliases.
 */
@NullMarked
public final class StvnTypeInlayHintsProvider implements InlayHintsProvider<NoSettings> {

    /** Constructs a {@code StvnTypeInlayHintsProvider} instance. */
    public StvnTypeInlayHintsProvider() {
    }

    private static final SettingsKey<NoSettings> KEY = new SettingsKey<>("stvn.type.hints");

    @Override
    public NoSettings createSettings() {
        return new NoSettings();
    }

    @Override
    public @NotNull SettingsKey<NoSettings> getKey() {
        return KEY;
    }

    @Override
    public @NotNull String getName() {
        return "Type Inlay Hints";
    }

    @Override
    public @Nullable String getPreviewText() {
        return "{\n" +
            "  :type :Tuple(:Either(:Int :String))\n" +
            "  :body (#Left -105)\n" +
            "}";
    }

    @Override
    public boolean isVisibleInSettings() {
        return true;
    }

    @Override
    public @NotNull ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return changeListener -> new javax.swing.JPanel();
    }

    @Nullable
    @Override
    public InlayHintsCollector getCollectorFor(
        @NotNull PsiFile file,
        @NotNull Editor editor,
        @NotNull NoSettings settings,
        @NotNull InlayHintsSink sink
    ) {
        var projectSettings = StvnProjectSettings.getInstance(file.getProject());
        if (projectSettings != null && !projectSettings.getState().showTypeHints) {
            return null;
        }
        return new StvnInlayHintsCollector(editor);
    }
}
