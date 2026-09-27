package org.stvnadore.plugin;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.fileTypes.PlainTextLikeFileType;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * File type representation for STVN intermediate representation documents (.stvn_ir).
 */
@NullMarked
public final class StvnIrFileType extends LanguageFileType implements PlainTextLikeFileType {

    /**
     * Singleton instance representing STVN IR documents.
     */
    public static final StvnIrFileType INSTANCE = new StvnIrFileType();

    private StvnIrFileType() {
        super(PlainTextLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "STVN_IR";
    }

    @Override
    public @NotNull String getDescription() {
        return "STVN Intermediate Representation Document";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "stvn_ir";
    }

    @Override
    public @NotNull Icon getIcon() {
        return StvnIcons.STVN_IR;
    }
}
