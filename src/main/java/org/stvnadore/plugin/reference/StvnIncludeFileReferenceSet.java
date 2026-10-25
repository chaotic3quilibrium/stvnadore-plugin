package org.stvnadore.plugin.reference;

import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.StringLiteral;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Customized FileReferenceSet for STVN include statements (:include [ "path" ]).
 * <p>
 * Anchors resolution strictly to the parent directory of the active containing file.
 * Filters completion candidates to valid schema files (.stvn_d, .stvn_di, .stvn_bd),
 * excluding payload documents (.stvn, .stvn_i), self-inclusion, and non-project directories.
 */
@NullMarked
public final class StvnIncludeFileReferenceSet extends FileReferenceSet {

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
        "build", "target", ".git", ".gradle", ".idea", ".intellijPlatform", "out"
    );

    /**
     * Constructs an StvnIncludeFileReferenceSet for the given string literal.
     *
     * @param element the string literal PSI element inside an include directive
     */
    public StvnIncludeFileReferenceSet(StringLiteral element) {
        super(getCleanPath(element), element, getStartOffset(element), null, true, true);
    }

    private static String getCleanPath(StringLiteral element) {
        var text = element.getText();
        if (text.startsWith("\"\"\"") && text.endsWith("\"\"\"") && text.length() >= 6) {
            return text.substring(3, text.length() - 3);
        } else if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        } else if (text.startsWith("\"")) {
            return text.substring(1);
        }
        return text;
    }

    private static int getStartOffset(StringLiteral element) {
        var text = element.getText();
        if (text.startsWith("\"\"\"")) {
            return 3;
        } else if (text.startsWith("\"")) {
            return 1;
        }
        return 0;
    }

    @Override
    public FileReference createFileReference(TextRange range, int index, String text) {
        return new StvnIncludeReference(this, range, index, text);
    }

    @Override
    public @NotNull Collection<PsiFileSystemItem> computeDefaultContexts() {
        var containingFile = getElement().getContainingFile();
        if (containingFile == null) {
            return Collections.emptyList();
        }
        var originalFile = containingFile.getOriginalFile();
        var virtualFile = originalFile.getVirtualFile();
        if (virtualFile == null) {
            virtualFile = containingFile.getVirtualFile();
        }
        if (virtualFile == null) {
            return Collections.emptyList();
        }
        var parentDir = virtualFile.getParent();
        if (parentDir == null) {
            return Collections.emptyList();
        }
        var psiManager = PsiManager.getInstance(getElement().getProject());
        var psiDir = psiManager.findDirectory(parentDir);
        if (psiDir != null) {
            return Collections.singletonList(psiDir);
        }
        return Collections.emptyList();
    }

    @Override
    protected Condition<PsiFileSystemItem> getReferenceCompletionFilter() {
        return item -> {
            if (item instanceof PsiDirectory dir) {
                return !isIgnoredDirectory(dir.getName());
            }
            if (item instanceof PsiFile psiFile) {
                var containingFile = getElement().getContainingFile();
                if (containingFile != null) {
                    var originalFile = containingFile.getOriginalFile();
                    if (psiFile.getOriginalFile().equals(originalFile)) {
                        return false; // Self-exclusion invariant
                    }
                }
                return isAllowedSchemaFile(psiFile.getName());
            }
            return false;
        };
    }

    /**
     * Determines whether a directory should be excluded from include resolution and completion.
     *
     * @param directoryName the directory name to check
     * @return true if the directory is ignored, false otherwise
     */
    public static boolean isIgnoredDirectory(String directoryName) {
        return IGNORED_DIRECTORIES.contains(directoryName);
    }

    /**
     * Determines whether a file name matches valid schema file extensions under STVN Specification § 1.2.
     *
     * @param fileName the file name to check
     * @return true if the file is a valid schema extension, false otherwise
     */
    public static boolean isAllowedSchemaFile(String fileName) {
        return fileName.endsWith(".stvn_d")
            || fileName.endsWith(".stvn_di")
            || fileName.endsWith(".stvn_bd");
    }
}
