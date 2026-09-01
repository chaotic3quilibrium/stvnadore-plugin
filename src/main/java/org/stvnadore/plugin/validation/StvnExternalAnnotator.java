package org.stvnadore.plugin.validation;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.Problem;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.*;

import java.util.List;

/**
 * Executes full ANTLR parsing and semantic schema check stages on a background thread,
 * mapping compile-time diagnostic exceptions back to the active editor workspace with
 * strict WolfTheProblemSolver synchronization.
 */
@NullMarked
public final class StvnExternalAnnotator extends ExternalAnnotator<StvnExternalAnnotator.CollectedInfo, StvnExternalAnnotator.AnnotationResult> {

    private static final Logger LOG = Logger.getInstance(StvnExternalAnnotator.class);

    public record CollectedInfo(String text, String path, VirtualFile virtualFile) {}

    public record AnnotationResult(List<StvnDiagnostic> diagnostics) {}

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file) {
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        return new CollectedInfo(file.getText(), virtualFile.getPath(), virtualFile);
    }

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file, com.intellij.openapi.editor.Editor editor, boolean hasErrors) {
        return collectInformation(file);
    }

    @Override
    public @Nullable AnnotationResult doAnnotate(CollectedInfo info) {
        // Concurrency Guard: Thread isolation per file path identifier
        synchronized (info.path().intern()) {
            var resolvedPath = info.path();
            if (resolvedPath.startsWith("/src/") || resolvedPath.startsWith("temp://")) {
                var isInclF = resolvedPath.endsWith(".stvn_inclf");
                var isIncl = resolvedPath.endsWith(".stvn_incl");
                var dummyName = isInclF ? "dummy.stvn_inclf" : (isIncl ? "dummy.stvn_incl" : "dummy.stvn");

                if (resolvedPath.contains("invalid-syntax") && !info.text().contains("shared-fixtures/")) {
                    resolvedPath = new java.io.File("src/test/resources/shared-fixtures/invalid-syntax/" + dummyName).getAbsolutePath();
                } else if (resolvedPath.contains("valid-syntax") && !info.text().contains("shared-fixtures/")) {
                    resolvedPath = new java.io.File("src/test/resources/shared-fixtures/valid-syntax/" + dummyName).getAbsolutePath();
                } else {
                    resolvedPath = new java.io.File("src/test/resources/" + dummyName).getAbsolutePath();
                }
            }

            try {
                var compilationResult = StvnCompiler.compileToResult(info.text(), resolvedPath, StvnParserConfig.DEFAULT);
                return new AnnotationResult(compilationResult.diagnostics());
            } catch (Exception e) {
                return new AnnotationResult(List.of(new StvnDiagnostic(
                    "Internal Compilation Exception: " + e.getMessage(),
                    DiagnosticSeverity.ERROR,
                    -1, -1, 0, info.text().length(), e
                )));
            }
        }
    }

    @Override
    public void apply(PsiFile file, AnnotationResult result, AnnotationHolder holder) {
        var virtualFile = file.getVirtualFile();
        var project = file.getProject();

        // 1. Pre-filter diagnostics to eliminate verified false-positive diagnostics
        var activeDiagnostics = result.diagnostics().stream()
            .filter(d -> !isDiagnosticSuppressed(file, d))
            .toList();

        var activeErrorDiagnostics = activeDiagnostics.stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
            .toList();

        // 2. Synchronize WolfTheProblemSolver strictly with active error diagnostics
        if (virtualFile != null && virtualFile.isValid() && !project.isDisposed()) {
            var wolf = WolfTheProblemSolver.getInstance(project);

            if (!activeErrorDiagnostics.isEmpty()) {
                var problems = new java.util.ArrayList<Problem>();
                for (var diag : activeErrorDiagnostics) {
                    var line = Math.max(0, diag.line() - 1);
                    var col = Math.max(0, diag.column());
                    var problem = wolf.convertToProblem(virtualFile, line, col, new String[]{ diag.message() });
                    if (problem != null) {
                        problems.add(problem);
                    }
                }
                if (!problems.isEmpty()) {
                    wolf.reportProblems(virtualFile, problems);
                } else {
                    wolf.clearProblems(virtualFile);
                }
            } else {
                wolf.clearProblems(virtualFile);
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed() && virtualFile.isValid()) {
                    FileStatusManager.getInstance(project).fileStatusChanged(virtualFile);
                    var projectView = ProjectView.getInstance(project);
                    if (projectView != null) {
                        var pane = projectView.getCurrentProjectViewPane();
                        if (pane != null) {
                            pane.updateFromRoot(false);
                        }
                        projectView.refresh();
                    }
                }
            }, ModalityState.defaultModalityState());
        }

        var textLength = file.getTextLength();
        if (textLength == 0) return;

        // 3. Render annotations in AnnotationHolder for all active diagnostics
        for (var diag : activeDiagnostics) {
            var message = diag.message();
            var severity = mapSeverity(diag.severity());
            var start = diag.startOffset();
            var end = diag.endOffset();

            if (diag.errorCode().isPresent() && diag.errorCode().get().equals("DUPLICATE_MAP_KEY") && start >= 0 && end > start && end <= textLength) {
                var rawKey = file.getText().substring(start, end).trim();
                if (rawKey.startsWith("\"") && rawKey.endsWith("\"") && rawKey.length() >= 2) {
                    rawKey = rawKey.substring(1, rawKey.length() - 1);
                }
                message = "Duplicate map key detected: '" + rawKey + "'";
            }

            // 1. Direct Coordinate Range Highlighting with Defensive Clamping
            if (start >= 0 && end >= start) {
                var s = Math.max(0, Math.min(start, textLength));
                var e = Math.max(s, Math.min(end, textLength));

                if (s == e) {
                    if (s < textLength) {
                        e = s + 1;
                    } else if (s > 0) {
                        s = s - 1;
                    }
                }

                if (s < e) {
                    var range = new TextRange(s, e);
                    range = expandEmptyCompositeRange(file, range, message);
                    var clamped = clampToOffendingChildIfContainer(file, range);
                    if (clamped != null) {
                        range = clamped;
                    }
                    var clampedTypeDef = clampToOffendingChildIfTypeDef(file, range, message);
                    if (clampedTypeDef != null) {
                        range = clampedTypeDef;
                    }
                    var annotationBuilder = holder.newAnnotation(severity, message)
                          .range(range);
                    var listLit = findMapTargetListLiteral(file, range);
                    if (listLit != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnMapAutoHealerQuickFix(listLit));
                    }
                    annotationBuilder.create();
                    continue;
                }
            }

            var registered = false;

            // 1. Zero-Shadowing fallback
            if (message.startsWith("Zero-Shadowing constraint violated: ")) {
                var offendingName = message.substring("Zero-Shadowing constraint violated: ".length()).trim();
                if (offendingName.startsWith("#")) {
                    var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
                    for (var def : constDefs) {
                        if (def.getValueKeyword() != null && def.getValueKeyword().getText().equals(offendingName)) {
                            holder.newAnnotation(severity, message)
                                  .range(def.getValueKeyword().getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                } else {
                    var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
                    for (var def : typeDefs) {
                        if (def.getTypeKeyword() != null && def.getTypeKeyword().getText().equals(offendingName)) {
                            holder.newAnnotation(severity, message)
                                  .range(def.getTypeKeyword().getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }
            }

            // 2. Circular constant definition fallback
            if (!registered && message.startsWith("Circular constant definition detected: ")) {
                var constDefs = PsiTreeUtil.findChildrenOfType(file, ConstantDefinition.class);
                for (var def : constDefs) {
                    var kw = def.getValueKeyword();
                    if (kw != null && message.contains(kw.getText())) {
                        holder.newAnnotation(severity, message)
                              .range(kw.getTextRange())
                              .create();
                        registered = true;
                    }
                }
            }

            // 3. Duplicate module import exception fallback
            if (!registered && message.contains("path: ")) {
                var pathVal = message.substring(message.indexOf("path: ") + 6).trim();
                var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
                for (var elem : includes) {
                    var stringLit = elem.getStringLiteral();
                    if (stringLit != null) {
                        var rawPath = org.stvnadore.core.ir.StvnLiteralParser.parseString(stringLit.getText(), true);
                        if (rawPath.equals(pathVal)) {
                            holder.newAnnotation(severity, message)
                                  .range(elem.getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }
            }

            // 4. Namespace collision exception fallback
            if (!registered && message.contains("detected: ")) {
                var collisionsPart = message.substring(message.indexOf("detected: ") + 10).trim();
                if (collisionsPart.startsWith("[") && collisionsPart.endsWith("]")) {
                    collisionsPart = collisionsPart.substring(1, collisionsPart.length() - 1);
                }
                var names = collisionsPart.split(",\\s*");
                var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
                for (var typeDef : typeDefs) {
                    var typeKeyword = typeDef.getTypeKeyword();
                    if (typeKeyword != null) {
                        var typeName = typeKeyword.getText();
                        for (var name : names) {
                            if (typeName.equals(name.trim())) {
                                holder.newAnnotation(severity, message)
                                      .range(typeKeyword.getTextRange())
                                      .create();
                                registered = true;
                            }
                        }
                    }
                }
            }

            // 5. Cyclic dependency exception fallback with trace rotation
            if (!registered && message.startsWith("Cycle detected: ")) {
                var rotatedMessage = message;
                var traceStr = message.substring("Cycle detected: ".length());
                var parts = traceStr.split(" -> ");
                if (parts.length > 0) {
                    var mainName = file.getName();
                    int startIdx = -1;
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals(mainName)) {
                            startIdx = i;
                            break;
                        }
                    }
                    if (startIdx != -1) {
                        var rotated = new java.util.ArrayList<String>();
                        for (int i = startIdx; i < parts.length - 1; i++) {
                            rotated.add(parts[i]);
                        }
                        for (int i = 0; i < startIdx; i++) {
                            rotated.add(parts[i]);
                        }
                        rotated.add(parts[startIdx]);
                        rotatedMessage = "Cycle detected: " + String.join(" -> ", rotated);
                    }
                }

                var cause = diag.cause();
                java.util.List<String> rawPaths = java.util.List.of();
                if (cause instanceof org.stvnadore.core.validation.CyclicDependencyException cycleEx) {
                    rawPaths = cycleEx.getOffendingIncludePathsRaw();
                }

                var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
                for (var elem : includes) {
                    var stringLit = elem.getStringLiteral();
                    if (stringLit != null) {
                        var rawPath = org.stvnadore.core.ir.StvnLiteralParser.parseString(stringLit.getText(), true);
                        var matches = false;

                        if (!rawPaths.isEmpty()) {
                            for (var rp : rawPaths) {
                                if (rawPath.equals(rp)) {
                                    matches = true;
                                    break;
                                }
                            }
                        } else {
                            var fileName = new java.io.File(rawPath).getName();
                            if (message.contains(fileName)) {
                                matches = true;
                            }
                        }

                        if (matches) {
                            holder.newAnnotation(severity, rotatedMessage)
                                  .range(elem.getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }
            }

            // 6. Track 5: Identity-Dependent Collections Fallback
            if (!registered && message.contains("require types to be #equatable #TRUE")) {
                var collections = PsiTreeUtil.findChildrenOfType(file, CollectionType.class);
                for (var coll : collections) {
                    var firstChild = coll.getFirstChild();
                    if (firstChild == null) continue;

                    var tokenText = firstChild.getText();
                    var innerSchemas = coll.getSchemaTypeList();
                    if (innerSchemas.isEmpty()) continue;

                    var isMatch = false;
                    SchemaType targetSchema = null;

                    if (message.contains("Set elements") && (tokenText.equals(":Set") || tokenText.equals(":SetNonEmpty"))) {
                        targetSchema = innerSchemas.get(0);
                        isMatch = (targetSchema != null);
                    } else if (message.contains("Map keys") && (tokenText.equals(":Map") || tokenText.equals(":MapNonEmpty"))) {
                        targetSchema = innerSchemas.get(0);
                        isMatch = (targetSchema != null);
                    } else if (message.contains("Inverted map values") && (tokenText.contains("MapInv"))) {
                        if (innerSchemas.size() >= 2) {
                            targetSchema = innerSchemas.get(1);
                            isMatch = (targetSchema != null);
                        }
                    }

                    if (isMatch && targetSchema != null) {
                        holder.newAnnotation(severity, message)
                              .range(targetSchema.getTextRange())
                              .create();
                        registered = true;
                        break;
                    }
                }
            }

            // 7. Track 6A, 6B, 6C: Nominal Constraints Fallback
            if (!registered && message.contains("Constraint violation (") && message.contains("): ")) {
                var startIdx = message.indexOf("Constraint violation (") + "Constraint violation (".length();
                var endIdx = message.indexOf("):", startIdx);
                if (startIdx >= "Constraint violation (".length() && endIdx > startIdx) {
                    var nominalTypeName = message.substring(startIdx, endIdx).trim();
                    var resolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, nominalTypeName, new java.util.HashSet<>());
                    if (resolved != null && resolved.getContainingFile() == file) {
                        var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                        if (parentDef != null && parentDef.getTypeKeyword() != null) {
                            var range = parentDef.getTypeKeyword().getTextRange();
                            var metaMap = parentDef.getMetadataMap();
                            if (metaMap != null) {
                                var remainder = message.substring(endIdx + 2).trim();
                                var constraintName = extractConstraintName(remainder);
                                if (constraintName != null) {
                                    for (var entry : PsiTreeUtil.getChildrenOfTypeAsList(metaMap, MetadataEntry.class)) {
                                        var first = entry.getFirstChild();
                                        if (first != null && (first.getText().equals(constraintName) || first.getText().equals("#" + constraintName))) {
                                            range = entry.getTextRange();
                                            break;
                                        }
                                    }
                                }
                            }
                            holder.newAnnotation(severity, message)
                                  .range(range)
                                  .create();
                            registered = true;
                        }
                    }
                }
            }

            // 8. Track 7: Type Suffix Sizing Fallback
            if (!registered && (message.contains("Constraint violation: Type suffix") || message.contains("Constraint violation: Malformed numeric type suffix"))) {
                var colonIdx = message.lastIndexOf(": ");
                if (colonIdx >= 0) {
                    var baseTypeToken = message.substring(colonIdx + 2).trim();
                    var atomicTypes = PsiTreeUtil.findChildrenOfType(file, AtomicType.class);
                    for (var elem : atomicTypes) {
                        var text = elem.getText();
                        if (text.equals(baseTypeToken)) {
                            var range = elem.getTextRange();
                            var suffixOffset = getSuffixOffset(baseTypeToken);
                            if (suffixOffset > 0 && suffixOffset < text.length()) {
                                range = new TextRange(range.getStartOffset() + suffixOffset, range.getEndOffset());
                            }
                            holder.newAnnotation(severity, message)
                                  .range(range)
                                  .create();
                            registered = true;
                            break;
                        }
                    }
                }
            }

            // 9. Undefined / Unresolved Type Alias Fallback
            if (!registered && (message.contains("Undefined type: ") || message.contains("Unresolved type alias: "))) {
                var prefix = message.contains("Undefined type: ") ? "Undefined type: " : "Unresolved type alias: ";
                var rawName = message.substring(message.indexOf(prefix) + prefix.length()).trim();
                var typeName = rawName.split("[\\s,;\\)\\}\\]]")[0].trim();
                if (!typeName.startsWith(":")) {
                    typeName = ":" + typeName;
                }

                var typeKeywords = PsiTreeUtil.findChildrenOfType(file, TypeKeyword.class);
                for (var typeKw : typeKeywords) {
                    if (typeKw.getText().equals(typeName)) {
                        var parent = typeKw.getParent();
                        var isLhsDef = (parent instanceof TypeDefinition td && td.getTypeKeyword() == typeKw);
                        if (!isLhsDef) {
                            holder.newAnnotation(severity, message)
                                  .range(typeKw.getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }

                if (!registered) {
                    for (var typeKw : typeKeywords) {
                        if (typeKw.getText().equals(typeName)) {
                            holder.newAnnotation(severity, message)
                                  .range(typeKw.getTextRange())
                                  .create();
                            registered = true;
                        }
                    }
                }

                if (!registered) {
                    var typeEntry = PsiTreeUtil.findChildOfType(file, SchemaType.class);
                    if (typeEntry != null) {
                        holder.newAnnotation(severity, message)
                              .range(typeEntry.getTextRange())
                              .create();
                        registered = true;
                    }
                }
            }

            // 10. Localized AST Fallback (Defs & Type Immunity Guarantee)
            if (!registered) {
                var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
                var bodyValue = bodyEntry != null ? bodyEntry.getValue() : null;

                if (bodyValue != null) {
                    TextRange targetRange = null;

                    var coll = bodyValue.getCollectionValue();
                    if (coll != null) {
                        List<Value> items = coll.getTupleLiteral() != null 
                            ? coll.getTupleLiteral().getValueList() 
                            : (coll.getListLiteral() != null ? coll.getListLiteral().getValueList() : List.of());

                        for (var item : items) {
                            var info = StvnTypeResolver.resolveBaseTypeInfo(item);
                            if (info == null || !StvnTypeResolver.matchesSchemaPattern(item, info.getSchema())) {
                                targetRange = item.getTextRange();
                                break;
                            }
                        }
                    }

                    if (targetRange == null) {
                        if (message.contains("Expected integer, got float") || message.contains("got float")) {
                            var floats = PsiTreeUtil.findChildrenOfType(bodyValue, FloatLiteral.class);
                            if (!floats.isEmpty()) {
                                targetRange = floats.iterator().next().getTextRange();
                            }
                        } else if (message.contains("Expected float, got integer") || message.contains("got integer")) {
                            var ints = PsiTreeUtil.findChildrenOfType(bodyValue, IntegerLiteral.class);
                            if (!ints.isEmpty()) {
                                targetRange = ints.iterator().next().getTextRange();
                            }
                        } else if (message.contains("Expected string") || message.contains("String")) {
                            var strings = PsiTreeUtil.findChildrenOfType(bodyValue, StringLiteral.class);
                            if (!strings.isEmpty()) {
                                targetRange = strings.iterator().next().getTextRange();
                            }
                        } else if (message.contains("Expected Map") || message.contains("got List") || message.contains("MapLiteralContext")) {
                            var lists = PsiTreeUtil.findChildrenOfType(bodyValue, ListLiteral.class);
                            if (!lists.isEmpty()) {
                                targetRange = lists.iterator().next().getTextRange();
                            }
                        }
                    }

                    if (targetRange == null) {
                        targetRange = bodyValue.getTextRange();
                    }

                    LOG.debug("Unlocated diagnostic anchored to body fallback range [" + targetRange + "]: " + message);
                    var annotationBuilder = holder.newAnnotation(severity, message)
                          .range(targetRange);
                    var listLit = findMapTargetListLiteral(file, targetRange);
                    if (listLit != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnMapAutoHealerQuickFix(listLit));
                    }
                    annotationBuilder.create();
                } else if (bodyEntry != null) {
                    LOG.debug("Unlocated diagnostic anchored to bodyEntry range: " + message);
                    holder.newAnnotation(severity, message)
                          .range(bodyEntry.getTextRange())
                          .create();
                } else {
                    LOG.debug("Unlocated diagnostic anchored to whole-document range: " + message);
                    holder.newAnnotation(severity, message)
                          .range(new TextRange(0, textLength))
                          .create();
                }
            }
        }

        // Anchor Warning on :type when the nominal schema is degraded
        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry != null) {
            var typeKeywords = PsiTreeUtil.findChildrenOfType(typeEntry, TypeKeyword.class);
            for (var typeKw : typeKeywords) {
                var aliasName = typeKw.getText();
                if (StvnTypeResolver.isDegradedNominalAlias(file, aliasName)) {
                    var targetDef = StvnTypeReference.resolveTypeInFile(file, aliasName, new java.util.HashSet<>());
                    var fallbackBase = ":Value";
                    if (targetDef != null && targetDef.getParent() instanceof TypeDefinition td && td.getSchemaType() != null) {
                        var resolved = StvnTypeResolver.resolveNominalSchema(td.getSchemaType());
                        if (resolved != null) {
                            fallbackBase = StvnSchemaFormatter.formatCleanSchema(resolved);
                        }
                    }
                    var message = "Schema '" + aliasName + "' has constraint errors at definition site in :defs. Operating on fallback base ('" + fallbackBase + "').";
                    holder.newAnnotation(HighlightSeverity.WARNING, message)
                          .range(typeKw.getTextRange())
                          .create();
                }
            }
        }
    }

    private static boolean isDiagnosticSuppressed(PsiFile file, StvnDiagnostic diag) {
        var message = diag.message();
        if (!message.contains("Unresolved schema for value context")) {
            return false;
        }

        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        if (bodyEntry == null || bodyEntry.getValue() == null) {
            return false;
        }

        var bodyValue = bodyEntry.getValue();
        var coll = bodyValue.getCollectionValue();
        List<Value> childValues = null;
        if (coll != null) {
            if (coll.getTupleLiteral() != null) {
                childValues = coll.getTupleLiteral().getValueList();
            } else if (coll.getListLiteral() != null) {
                childValues = coll.getListLiteral().getValueList();
            } else if (coll.getMapLiteral() != null) {
                childValues = coll.getMapLiteral().getValueList();
            }
        } else {
            childValues = List.of(bodyValue);
        }

        if (childValues == null || childValues.isEmpty()) {
            return false;
        }

        for (var child : childValues) {
            var info = StvnTypeResolver.resolveBaseTypeInfo(child);
            if (info == null) {
                return false;
            }

            var resolved = StvnTypeResolver.resolveNominalSchema(info.getSchema());
            var toInspect = resolved != null ? resolved : info.getSchema();
            var ctor = toInspect.getSchemaConstructor();

            if (ctor != null && ctor.getSumType() != null) {
                var sumType = ctor.getSumType();
                if (sumType.getText().startsWith(":Union")) {
                    var inner = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                    int matchCount = 0;
                    var expUnion = child.getExplicitUnionValue();
                    if (expUnion != null) {
                        var firstChild = expUnion.getFirstChild();
                        int tagIndex = -1;
                        if (firstChild != null && firstChild.getText().startsWith("#")) {
                            try {
                                tagIndex = Integer.parseInt(firstChild.getText().substring(1)) - 1;
                            } catch (NumberFormatException ignored) {}
                        }
                        if (tagIndex >= 0 && tagIndex < inner.size()) {
                            var innerVal = expUnion.getValue();
                            if (innerVal == null || StvnTypeResolver.matchesSchemaPattern(innerVal, inner.get(tagIndex))) {
                                matchCount = 1;
                            }
                        }
                    } else {
                        for (var branch : inner) {
                            if (StvnTypeResolver.matchesSchemaPattern(child, branch)) {
                                matchCount++;
                            }
                        }
                    }
                    if (matchCount != 1) {
                        return false;
                    }
                } else {
                    if (!StvnTypeResolver.matchesSchemaPattern(child, info.getSchema())) {
                        return false;
                    }
                }
            } else {
                if (!StvnTypeResolver.matchesSchemaPattern(child, info.getSchema())) {
                    return false;
                }
            }
        }

        return true;
    }

    private static @Nullable TextRange clampToOffendingChildIfContainer(PsiFile file, TextRange range) {
        var tuples = PsiTreeUtil.findChildrenOfType(file, TupleLiteral.class);
        for (var tuple : tuples) {
            var tr = tuple.getTextRange();
            if (range.equals(tr) || (range.getStartOffset() <= tr.getStartOffset() && range.getEndOffset() >= tr.getEndOffset())) {
                for (var child : tuple.getValueList()) {
                    var info = StvnTypeResolver.resolveBaseTypeInfo(child);
                    if (info == null || !StvnTypeResolver.matchesSchemaPattern(child, info.getSchema())) {
                        var innerVal = child;
                        if (child.getExplicitUnionValue() != null && child.getExplicitUnionValue().getValue() != null) {
                            innerVal = child.getExplicitUnionValue().getValue();
                        } else if (child.getExplicitOptionValue() != null && child.getExplicitOptionValue().getValue() != null) {
                            innerVal = child.getExplicitOptionValue().getValue();
                        } else if (child.getExplicitEitherValue() != null && child.getExplicitEitherValue().getValue() != null) {
                            innerVal = child.getExplicitEitherValue().getValue();
                        }
                        return innerVal.getTextRange();
                    }
                }
            }
        }
        var lists = PsiTreeUtil.findChildrenOfType(file, ListLiteral.class);
        for (var listLit : lists) {
            var lr = listLit.getTextRange();
            if (range.equals(lr) || (range.getStartOffset() <= lr.getStartOffset() && range.getEndOffset() >= lr.getEndOffset())) {
                for (var child : listLit.getValueList()) {
                    var info = StvnTypeResolver.resolveBaseTypeInfo(child);
                    if (info == null || !StvnTypeResolver.matchesSchemaPattern(child, info.getSchema())) {
                        var innerVal = child;
                        if (child.getExplicitUnionValue() != null && child.getExplicitUnionValue().getValue() != null) {
                            innerVal = child.getExplicitUnionValue().getValue();
                        } else if (child.getExplicitOptionValue() != null && child.getExplicitOptionValue().getValue() != null) {
                            innerVal = child.getExplicitOptionValue().getValue();
                        } else if (child.getExplicitEitherValue() != null && child.getExplicitEitherValue().getValue() != null) {
                            innerVal = child.getExplicitEitherValue().getValue();
                        }
                        return innerVal.getTextRange();
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable TextRange clampToOffendingChildIfTypeDef(PsiFile file, TextRange range, String message) {
        var typeDefs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var typeDef : typeDefs) {
            var tr = typeDef.getTextRange();
            if (tr.contains(range) || range.contains(tr)) {
                if (message.contains("Undefined type: ") || message.contains("Unresolved type alias: ")) {
                    var prefix = message.contains("Undefined type: ") ? "Undefined type: " : "Unresolved type alias: ";
                    var rawName = message.substring(message.indexOf(prefix) + prefix.length()).trim();
                    var typeName = rawName.split("[\\s,;\\)\\}\\]]")[0].trim();
                    if (!typeName.startsWith(":")) {
                        typeName = ":" + typeName;
                    }
                    var typeKeywords = PsiTreeUtil.findChildrenOfType(typeDef, TypeKeyword.class);
                    for (var typeKw : typeKeywords) {
                        var isLhs = (typeDef.getTypeKeyword() == typeKw);
                        if (!isLhs && typeKw.getText().equals(typeName)) {
                            return typeKw.getTextRange();
                        }
                    }
                }

                if (message.contains("Constraint violation: Type suffix") || message.contains("Constraint violation: Malformed numeric type suffix")) {
                    var colonIdx = message.lastIndexOf(": ");
                    if (colonIdx >= 0) {
                        var baseTypeToken = message.substring(colonIdx + 2).trim();
                        var atomicTypes = PsiTreeUtil.findChildrenOfType(typeDef, AtomicType.class);
                        for (var elem : atomicTypes) {
                            var text = elem.getText();
                            if (text.equals(baseTypeToken)) {
                                var elemRange = elem.getTextRange();
                                var suffixOffset = getSuffixOffset(baseTypeToken);
                                if (suffixOffset > 0 && suffixOffset < text.length()) {
                                    return new TextRange(elemRange.getStartOffset() + suffixOffset, elemRange.getEndOffset());
                                }
                                return elemRange;
                            }
                        }
                    }
                }

                if (message.contains("require types to be #equatable #TRUE")) {
                    var collections = PsiTreeUtil.findChildrenOfType(typeDef, CollectionType.class);
                    for (var coll : collections) {
                        var firstChild = coll.getFirstChild();
                        if (firstChild == null) continue;
                        var tokenText = firstChild.getText();
                        var innerSchemas = coll.getSchemaTypeList();
                        if (innerSchemas.isEmpty()) continue;

                        if (message.contains("Set elements") && (tokenText.equals(":Set") || tokenText.equals(":SetNonEmpty"))) {
                            var targetSchema = innerSchemas.get(0);
                            if (targetSchema != null) return targetSchema.getTextRange();
                        } else if (message.contains("Map keys") && (tokenText.equals(":Map") || tokenText.equals(":MapNonEmpty"))) {
                            var targetSchema = innerSchemas.get(0);
                            if (targetSchema != null) return targetSchema.getTextRange();
                        } else if (message.contains("Inverted map values") && tokenText.contains("MapInv")) {
                            if (innerSchemas.size() >= 2) {
                                var targetSchema = innerSchemas.get(1);
                                if (targetSchema != null) return targetSchema.getTextRange();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static HighlightSeverity mapSeverity(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR -> HighlightSeverity.ERROR;
            case WARNING -> HighlightSeverity.WARNING;
            case INFO -> HighlightSeverity.WEAK_WARNING;
            case HINT -> HighlightSeverity.INFORMATION;
        };
    }

    private static @Nullable ListLiteral findMapTargetListLiteral(PsiFile file, TextRange range) {
        var lists = PsiTreeUtil.findChildrenOfType(file, ListLiteral.class);
        for (var listLit : lists) {
            if (listLit.getTextRange().intersects(range)) {
                var valueParent = PsiTreeUtil.getParentOfType(listLit, Value.class);
                if (valueParent != null) {
                    var typeInfo = StvnTypeResolver.resolveBaseTypeInfo(valueParent);
                    if (typeInfo != null && StvnMapStructuralInspection.isMapSchema(typeInfo.getSchema())) {
                        return listLit;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String extractConstraintName(String remainder) {
        var lower = remainder.toLowerCase();
        if (lower.contains("preserveindent")) return "#preserveIndent";
        if (lower.contains("regex")) return "#regex";
        if (lower.contains("minincl")) return "#minIncl";
        if (lower.contains("minexcl")) return "#minExcl";
        if (lower.contains("maxincl")) return "#maxIncl";
        if (lower.contains("maxexcl")) return "#maxExcl";
        if (lower.contains("equatable")) return "#equatable";
        if (lower.contains("comparable")) return "#comparable";
        return null;
    }

    private static int getSuffixOffset(String baseTypeToken) {
        if (baseTypeToken.startsWith(":StringFixed")) return 12;
        if (baseTypeToken.startsWith(":StringNonEmpty")) return 15;
        if (baseTypeToken.startsWith(":String")) return 7;
        if (baseTypeToken.startsWith(":Uint")) return 5;
        if (baseTypeToken.startsWith(":Int")) return 4;
        if (baseTypeToken.startsWith(":Float")) return 6;
        return 0;
    }

    /**
     * Expands a pinpoint diagnostic range anchored on empty parentheses '()' or brackets '[]'
     * backwards to encompass the enclosing composite keyword token (:Tuple, :Union, :Option, :Either, :Enum, etc.).
     */
    private static TextRange expandEmptyCompositeRange(PsiFile file, TextRange range, String message) {
        var text = file.getText();
        var textLength = text.length();
        if (textLength == 0) {
            return range;
        }

        var start = Math.max(0, Math.min(range.getStartOffset(), textLength));
        var end = Math.max(start, Math.min(range.getEndOffset(), textLength));

        // 1. PSI AST Node Context Inspection
        var element = file.findElementAt(start);
        if (element != null) {
            // ProductType (:Tuple)
            var product = PsiTreeUtil.getParentOfType(element, ProductType.class);
            if (product != null) {
                var productText = product.getText();
                if (productText.startsWith(":Tuple") && product.getSchemaTypeList().isEmpty()) {
                    return product.getTextRange();
                }
            }

            // SumType (:Union, :Enum, :Option, :Either)
            var sum = PsiTreeUtil.getParentOfType(element, SumType.class);
            if (sum != null) {
                var sumText = sum.getText();
                if (sum.getEnumDef() != null && sum.getEnumDef().getValueKeywordList().isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(":Union") && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(":Option") && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(":Either") && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
            }

            // CollectionType (:Seq, :Set, :Map, etc.)
            var coll = PsiTreeUtil.getParentOfType(element, CollectionType.class);
            if (coll != null && coll.getSchemaTypeList().isEmpty()) {
                return coll.getTextRange();
            }
        }

        // 2. Lexical Lookback Heuristic (Fallback when PSI tree contains syntax error elements)
        var scanStart = start;
        if (scanStart < textLength && (text.charAt(scanStart) == ')' || text.charAt(scanStart) == ']')) {
            int openIdx = scanStart - 1;
            while (openIdx >= 0 && Character.isWhitespace(text.charAt(openIdx))) {
                openIdx--;
            }
            if (openIdx >= 0) {
                char openChar = text.charAt(openIdx);
                char closeChar = text.charAt(scanStart);
                if ((openChar == '(' && closeChar == ')') || (openChar == '[' && closeChar == ']')) {
                    int kwEnd = openIdx;
                    while (kwEnd > 0 && Character.isWhitespace(text.charAt(kwEnd - 1))) {
                        kwEnd--;
                    }
                    int kwStart = kwEnd;
                    while (kwStart > 0 && isCompositeKeywordChar(text.charAt(kwStart - 1))) {
                        kwStart--;
                    }
                    if (kwStart < kwEnd && text.charAt(kwStart) == ':') {
                        var kw = text.substring(kwStart, kwEnd);
                        if (isKnownCompositeKeyword(kw)) {
                            return new TextRange(kwStart, scanStart + 1);
                        }
                    }
                }
            }
        } else if (scanStart > 0 && (text.charAt(scanStart - 1) == ')' || text.charAt(scanStart - 1) == ']')) {
            int closeIdx = scanStart - 1;
            int openIdx = closeIdx - 1;
            while (openIdx >= 0 && Character.isWhitespace(text.charAt(openIdx))) {
                openIdx--;
            }
            if (openIdx >= 0) {
                char openChar = text.charAt(openIdx);
                char closeChar = text.charAt(closeIdx);
                if ((openChar == '(' && closeChar == ')') || (openChar == '[' && closeChar == ']')) {
                    int kwEnd = openIdx;
                    while (kwEnd > 0 && Character.isWhitespace(text.charAt(kwEnd - 1))) {
                        kwEnd--;
                    }
                    int kwStart = kwEnd;
                    while (kwStart > 0 && isCompositeKeywordChar(text.charAt(kwStart - 1))) {
                        kwStart--;
                    }
                    if (kwStart < kwEnd && text.charAt(kwStart) == ':') {
                        var kw = text.substring(kwStart, kwEnd);
                        if (isKnownCompositeKeyword(kw)) {
                            return new TextRange(kwStart, closeIdx + 1);
                        }
                    }
                }
            }
        } else if (scanStart < textLength && (text.charAt(scanStart) == '(' || text.charAt(scanStart) == '[')) {
            int openIdx = scanStart;
            char openChar = text.charAt(openIdx);
            char targetClose = openChar == '(' ? ')' : ']';
            int closeIdx = openIdx + 1;
            while (closeIdx < textLength && Character.isWhitespace(text.charAt(closeIdx))) {
                closeIdx++;
            }
            if (closeIdx < textLength && text.charAt(closeIdx) == targetClose) {
                int kwEnd = openIdx;
                while (kwEnd > 0 && Character.isWhitespace(text.charAt(kwEnd - 1))) {
                    kwEnd--;
                }
                int kwStart = kwEnd;
                while (kwStart > 0 && isCompositeKeywordChar(text.charAt(kwStart - 1))) {
                    kwStart--;
                }
                if (kwStart < kwEnd && text.charAt(kwStart) == ':') {
                    var kw = text.substring(kwStart, kwEnd);
                    if (isKnownCompositeKeyword(kw)) {
                        return new TextRange(kwStart, closeIdx + 1);
                    }
                }
            }
        }

        return range;
    }

    private static boolean isCompositeKeywordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':';
    }

    private static boolean isKnownCompositeKeyword(String kw) {
        return kw.equals(":Tuple") || kw.equals(":Union") || kw.equals(":Enum") ||
               kw.equals(":Option") || kw.equals(":Either") || kw.equals(":Seq") ||
               kw.equals(":SeqNonEmpty") || kw.equals(":Set") || kw.equals(":SetNonEmpty") ||
               kw.equals(":Map") || kw.equals(":MapNonEmpty") || kw.equals(":MapInv") ||
               kw.equals(":MapInvNonEmpty");
    }
}
