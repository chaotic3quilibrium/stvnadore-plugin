package org.stvnadore.plugin;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vfs.VirtualFile;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * File type representation for binary STVN payload documents (.stvn_b).
 */
@NullMarked
public final class StvnBinaryFileType implements FileType {

    /**
     * Singleton instance representing binary STVN documents.
     */
    public static final StvnBinaryFileType INSTANCE = new StvnBinaryFileType();

    private StvnBinaryFileType() {}

    @Override
    public @NotNull String getName() {
        return "STVN_BIN";
    }

    @Override
    public @NotNull String getDescription() {
        return "STVN Binary Payload Document";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "stvn_b";
    }

    @Override
    public @NotNull Icon getIcon() {
        return StvnIcons.STVN_B;
    }

    @Override
    public boolean isBinary() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public @Nullable String getCharset(@NotNull VirtualFile file, byte @NotNull [] content) {
        return null;
    }
}
