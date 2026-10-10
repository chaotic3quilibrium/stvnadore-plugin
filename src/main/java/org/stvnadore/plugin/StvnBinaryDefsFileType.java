package org.stvnadore.plugin;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vfs.VirtualFile;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * File type representation for binary STVN definitions schemas (.stvn_bd).
 */
@NullMarked
public final class StvnBinaryDefsFileType implements FileType {

    /**
     * Singleton instance representing binary STVN definitions documents.
     */
    public static final StvnBinaryDefsFileType INSTANCE = new StvnBinaryDefsFileType();

    private StvnBinaryDefsFileType() {}

    @Override
    public @NotNull String getName() {
        return "STVN_BINARY_DEFS";
    }

    @Override
    public @NotNull String getDescription() {
        return "STVN Binary Definitions Schema";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "stvn_bd";
    }

    @Override
    public @NotNull Icon getIcon() {
        return StvnIcons.STVN_BD;
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
