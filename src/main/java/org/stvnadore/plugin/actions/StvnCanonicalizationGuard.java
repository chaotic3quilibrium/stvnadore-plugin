package org.stvnadore.plugin.actions;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DoNotAskOption;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Guard utility preventing unintended loss of authoring syntax during canonicalization projections.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnCanonicalizationGuard {

    private StvnCanonicalizationGuard() {
    }

    /**
     * Verifies whether the document contains any authoring construct that will be discarded or transformed
     * by compiler canonicalization.
     *
     * @param file the source STVN PSI file
     * @return {@code true} if comments, package enclaves, use statements, or unreferenced definitions are present
     */
    public static boolean hasDestructiveAuthoringConstructs(PsiFile file) {
        if (PsiTreeUtil.findChildOfType(file, PsiComment.class) != null) {
            return true;
        }
        if (PsiTreeUtil.findChildOfType(file, PackageEnclosure.class) != null) {
            return true;
        }
        if (PsiTreeUtil.findChildOfType(file, UseStmt.class) != null) {
            return true;
        }
        return hasUnreferencedDefinitions(file);
    }

    /**
     * Checks if the active STVN file contains unreferenced type or constant definitions in {@code :defs}.
     *
     * @param file the source STVN PSI file
     * @return {@code true} if any definition in {@code :defs} is unreferenced in the root contract or payload
     */
    public static boolean hasUnreferencedDefinitions(PsiFile file) {
        var defsEntry = PsiTreeUtil.findChildOfType(file, DefsEntry.class);
        if (defsEntry == null) {
            return false;
        }

        var localTypeDefs = new ArrayList<>(PsiTreeUtil.getChildrenOfTypeAsList(defsEntry, TypeDefinition.class));
        var localConstDefs = new ArrayList<>(PsiTreeUtil.getChildrenOfTypeAsList(defsEntry, ConstantDefinition.class));

        if (localTypeDefs.isEmpty() && localConstDefs.isEmpty()) {
            localTypeDefs.addAll(PsiTreeUtil.findChildrenOfType(defsEntry, TypeDefinition.class));
            localConstDefs.addAll(PsiTreeUtil.findChildrenOfType(defsEntry, ConstantDefinition.class));
        }

        if (localTypeDefs.isEmpty() && localConstDefs.isEmpty()) {
            return false;
        }

        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry == null || typeEntry.getSchemaType() == null) {
            return true;
        }

        var reachableTypes = new HashSet<String>();
        var queue = new ArrayDeque<String>();

        collectTypeKeywordNames(typeEntry.getSchemaType(), queue, reachableTypes);

        var defMap = new HashMap<String, TypeDefinition>();
        for (var td : localTypeDefs) {
            var kw = td.getTypeKeyword();
            if (kw != null) {
                defMap.put(kw.getText(), td);
            }
        }

        while (!queue.isEmpty()) {
            var currentName = queue.poll();
            var targetDef = defMap.get(currentName);
            if (targetDef != null && targetDef.getSchemaType() != null) {
                collectTypeKeywordNames(targetDef.getSchemaType(), queue, reachableTypes);
            }
        }

        for (var td : localTypeDefs) {
            var kw = td.getTypeKeyword();
            if (kw != null && !reachableTypes.contains(kw.getText())) {
                return true;
            }
        }

        if (!localConstDefs.isEmpty()) {
            var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
            if (bodyEntry == null || bodyEntry.getValue() == null) {
                return true;
            }
            var bodyKeywords = new HashSet<String>();
            var values = PsiTreeUtil.findChildrenOfType(bodyEntry, ValueKeyword.class);
            for (var vk : values) {
                bodyKeywords.add(vk.getText());
            }
            for (var cd : localConstDefs) {
                var kw = cd.getValueKeyword();
                if (kw != null && !bodyKeywords.contains(kw.getText())) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void collectTypeKeywordNames(
            SchemaType schemaType,
            Queue<String> queue,
            Set<String> reachableTypes
    ) {
        var kw = schemaType.getTypeKeyword();
        if (kw != null && reachableTypes.add(kw.getText())) {
            queue.add(kw.getText());
        }
        var children = PsiTreeUtil.findChildrenOfType(schemaType, TypeKeyword.class);
        for (var childKw : children) {
            if (reachableTypes.add(childKw.getText())) {
                queue.add(childKw.getText());
            }
        }
    }

    /**
     * Evaluates the active document against destructive canonicalization loss.
     * Displays a confirmation dialog if destructive constructs are detected and warning is not suppressed.
     *
     * @param project the active IntelliJ project
     * @param file the source STVN PSI file
     * @return {@code true} if execution can proceed, {@code false} if aborted by user
     */
    public static boolean confirmCanonicalization(Project project, PsiFile file) {
        var settings = StvnProjectSettings.getInstance(project);
        if (settings.getState().suppressCanonicalizationWarning) {
            return true;
        }

        if (!hasDestructiveAuthoringConstructs(file)) {
            return true;
        }

        DoNotAskOption doNotAsk = new DoNotAskOption.Adapter() {
            @Override
            public void rememberChoice(boolean isSelected, int exitCode) {
                if (isSelected) {
                    settings.getState().suppressCanonicalizationWarning = true;
                }
            }

            @Override
            public @NotNull String getDoNotShowMessage() {
                return "Do not show this warning again for this project";
            }

            @Override
            public boolean shouldSaveOptionsOnCancel() {
                return false;
            }
        };

        return MessageDialogBuilder.yesNo(
                "STVN: Canonicalize Projection",
                "Canonical projection lowers the document to a compiled AST. This action will desugar package enclaves, inline aliases, prune unreferenced definitions, and strip comments. This action cannot be reversed except via Undo. Do you want to proceed?"
        ).doNotAsk(doNotAsk).ask(project);
    }
}
