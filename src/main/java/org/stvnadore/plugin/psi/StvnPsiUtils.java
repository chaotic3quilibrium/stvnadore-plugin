package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnDefinitionsFile;
import org.stvnadore.plugin.StvnDefsIncludeFile;
import org.stvnadore.plugin.StvnPayloadFile;
import org.stvnadore.plugin.StvnPayloadIncludeFile;
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
            if (prev instanceof PsiWhiteSpace || prev instanceof PsiErrorElement ||
                prev.getParent() instanceof PsiErrorElement || prev.getText().equals("IntellijIdeaRulezzz") ||
                prev.getText().trim().isEmpty()) {
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

    /**
     * Retrieves the enclosing {@link TypeDefinition} if the element is its declared name target
     * (either directly as a child of TypeDefinition or via TypeDefTarget).
     */
    public static @Nullable TypeDefinition getParentTypeDefinition(@Nullable PsiElement element) {
        if (element == null) return null;
        if (element instanceof TypeDefinition td) return td;
        var parent = element.getParent();
        if (parent instanceof TypeDefTarget && parent.getParent() instanceof TypeDefinition td) {
            return td;
        }
        if (parent instanceof TypeDefinition td) {
            return td;
        }
        return null;
    }

    /**
     * Determines whether the given element is the declaration target of a {@link TypeDefinition}.
     */
    public static boolean isTypeDefinitionTarget(@Nullable PsiElement element) {
        if (element == null) return false;
        var parent = element.getParent();
        if (parent instanceof TypeDefTarget) {
            return true;
        }
        if (parent instanceof TypeDefinition td) {
            return td.getTypeKeyword() == element;
        }
        return false;
    }

    /**
     * Determines whether the given file represents a modular document (.stvn_i, .stvn_di).
     *
     * @param file target STVN PSI file
     * @return true if modular, false if flat or null
     */
    public static boolean isModularDocument(@Nullable PsiFile file) {
        if (file == null) return false;
        if (file instanceof StvnPayloadIncludeFile || file instanceof StvnDefsIncludeFile) {
            return true;
        }
        var name = file.getName();
        return name.endsWith(".stvn_i") || name.endsWith(".stvn_di");
    }

    /**
     * Determines whether the given file represents a hermetic document (.stvn, .stvn_d, .stvn_b, .stvn_bd).
     *
     * @param file target STVN PSI file
     * @return true if hermetic flat, false if modular or null
     */
    public static boolean isHermeticDocument(@Nullable PsiFile file) {
        if (file == null) return false;
        if (file instanceof StvnPayloadFile || file instanceof StvnDefinitionsFile) {
            return true;
        }
        var name = file.getName();
        return name.endsWith(".stvn") || name.endsWith(".stvn_d") || name.endsWith(".stvn_b") || name.endsWith(".stvn_bd");
    }

    /**
     * Determines whether a package namespace is declared locally within the given file's :defs block.
     *
     * @param file target STVN PSI file
     * @param packagePath target package path string (e.g. ":org/example/local")
     * @return true if declared locally in this file, false otherwise
     */
    public static boolean isPackageDeclaredLocally(@Nullable PsiFile file, String packagePath) {
        if (file == null) return false;
        var packages = PsiTreeUtil.findChildrenOfType(file, PackageEnclosure.class);
        for (var pkg : packages) {
            var path = pkg.getPackagePath();
            if (path != null && path.getText().equals(packagePath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether any local package within the file declares the specified type name.
     *
     * @param file target STVN PSI file
     * @param typeName bare or colon-prefixed type name (e.g. :Port)
     * @return true if declared inside a local :package enclosure, false otherwise
     */
    public static boolean isTypeDeclaredInLocalPackage(@Nullable PsiFile file, String typeName) {
        if (file == null) return false;
        var cleanName = typeName.startsWith(":") ? typeName : ":" + typeName;
        var packages = PsiTreeUtil.findChildrenOfType(file, PackageEnclosure.class);
        for (var pkg : packages) {
            for (var elem : pkg.getPackageElementList()) {
                var def = elem.getTypeDefinition();
                if (def != null && def.getTypeKeyword() != null) {
                    var kwText = def.getTypeKeyword().getText();
                    if (kwText.equals(cleanName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Resolves the existing {@link DefsEntry} block or synthesizes a canonical
     * {@code :defs { ... }} block inside the document's root object.
     *
     * @param context any PSI element within the target file
     * @return the resolved or synthesized DefsEntry, or null if file is invalid or flat
     */
    public static @Nullable DefsEntry getOrCreateDefsBlock(@Nullable PsiElement context) {
        if (context == null) return null;
        var file = context.getContainingFile();
        if (file == null) return null;
        return getOrCreateDefsBlock(file);
    }

    /**
     * Resolves the existing {@link DefsEntry} block or synthesizes a canonical
     * {@code :defs { ... }} block inside the document's root object.
     *
     * @param file target STVN PSI file
     * @return the resolved or synthesized DefsEntry, or null if file is invalid or flat
     */
    public static @Nullable DefsEntry getOrCreateDefsBlock(PsiFile file) {
        var existingDefs = PsiTreeUtil.findChildOfType(file, DefsEntry.class);
        if (existingDefs != null) {
            return existingDefs;
        }

        var project = file.getProject();
        var doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) {
            return null;
        }

        // Determine canonical insertion anchor in root object
        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);

        int insertOffset;
        String defsSnippet;

        if (typeEntry != null) {
            int lineNum = doc.getLineNumber(typeEntry.getTextRange().getStartOffset());
            insertOffset = doc.getLineStartOffset(lineNum);
            defsSnippet = "  :defs {\n  }\n";
        } else if (bodyEntry != null) {
            int lineNum = doc.getLineNumber(bodyEntry.getTextRange().getStartOffset());
            insertOffset = doc.getLineStartOffset(lineNum);
            defsSnippet = "  :defs {\n  }\n";
        } else {
            var rootLBrace = file.getNode().findChildByType(StvnTypes.LBRACE);
            if (rootLBrace != null) {
                insertOffset = rootLBrace.getStartOffset() + 1;
                defsSnippet = "\n  :defs {\n  }\n";
            } else {
                insertOffset = 0;
                defsSnippet = "{\n  :defs {\n  }\n}\n";
            }
        }

        doc.insertString(insertOffset, defsSnippet);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        return PsiTreeUtil.findChildOfType(file, DefsEntry.class);
    }
}
