package org.stvnadore.plugin.navigation;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnPreludeBridge;

/**
 * Centralized navigation utility resolving virtual files and dispatching editor navigation
 * across physical documents, non-physical synthetic files, and the canonical standard library prelude.
 */
@NullMarked
public final class StvnNavigationUtil {

    private StvnNavigationUtil() {
    }

    /**
     * Resolves the canonical VirtualFile for navigation, safely handling non-physical files
     * and synthetic prelude AST elements where PsiFile.getVirtualFile() evaluates to null.
     *
     * @param element the target PSI element
     * @return the valid VirtualFile, or null if unresolvable
     */
    public static @Nullable VirtualFile resolveVirtualFile(@Nullable PsiElement element) {
        if (element == null || !element.isValid()) {
            return null;
        }
        var file = element.getContainingFile();
        if (file == null || !file.isValid()) {
            return null;
        }
        var vFile = file.getVirtualFile();
        if (vFile != null && vFile.isValid()) {
            return vFile;
        }
        var viewProvider = file.getViewProvider();
        vFile = viewProvider.getVirtualFile();
        if (vFile != null && vFile.isValid()) {
            return vFile;
        }
        if (StvnPreludeBridge.isPreludeFile(file)) {
            return StvnPreludeBridge.getPreludeVirtualFile(element.getProject());
        }
        return null;
    }

    /**
     * Creates an OpenFileDescriptor pointing to the precise token offset of the target element.
     *
     * @param element the target PSI element
     * @return the configured OpenFileDescriptor, or null if the virtual file is unresolvable
     */
    public static @Nullable OpenFileDescriptor getDescriptor(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        var vFile = resolveVirtualFile(element);
        if (vFile == null) {
            return null;
        }
        return new OpenFileDescriptor(element.getProject(), vFile, element.getTextOffset());
    }

    /**
     * Checks whether navigation dispatch is possible for the target PSI element.
     *
     * @param element the target PSI element
     * @return true if a valid navigation descriptor can be constructed
     */
    public static boolean canNavigate(@Nullable PsiElement element) {
        var descriptor = getDescriptor(element);
        return descriptor != null && descriptor.canNavigate();
    }

    /**
     * Checks whether source navigation dispatch is possible for the target PSI element.
     *
     * @param element the target PSI element
     * @return true if a valid navigation descriptor pointing to source can be constructed
     */
    public static boolean canNavigateToSource(@Nullable PsiElement element) {
        var descriptor = getDescriptor(element);
        return descriptor != null && descriptor.canNavigateToSource();
    }

    /**
     * Dispatches editor navigation to the target PSI element.
     *
     * @param element the target PSI element
     * @param requestFocus whether to focus the newly opened editor
     */
    public static void navigate(@Nullable PsiElement element, boolean requestFocus) {
        var descriptor = getDescriptor(element);
        if (descriptor != null && element != null) {
            FileEditorManager.getInstance(element.getProject()).openTextEditor(descriptor, requestFocus);
        }
    }
}
