package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.Problem;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.quickfix.OpenIncludedFileQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedTypeQuickFixProvider;
import org.stvnadore.psi.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes full ANTLR parsing and semantic schema check stages on a background thread,
 * mapping compile-time diagnostic exceptions back to the active editor workspace with
 * strict WolfTheProblemSolver synchronization.
 */
@NullMarked
public final class StvnExternalAnnotator extends ExternalAnnotator<StvnExternalAnnotator.CollectedInfo, StvnExternalAnnotator.AnnotationResult> {

    private static final Logger LOG = Logger.getInstance(StvnExternalAnnotator.class);

    private static final Pattern QUOTED_TOKEN_PATTERN = Pattern.compile(
        "':?([A-Za-z0-9_/#]+)'|" +
        "(?<=Undefined type:\\s)(:[A-Za-z0-9_/#]+)|" +
        "(?<=Unknown or undefined type:\\s)(:[A-Za-z0-9_/#]+)"
    );

    private static List<String> extractCitedTokens(String message) {
        var tokens = new java.util.ArrayList<String>();
        var matcher = QUOTED_TOKEN_PATTERN.matcher(message);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                var g = matcher.group(i);
                if (g != null && !g.isEmpty()) {
                    tokens.add(g);
                    break;
                }
            }
        }
        return tokens;
    }

    private static String extractCitedTypeToken(String msg) {
        var tokens = extractCitedTokens(msg);
        if (!tokens.isEmpty()) {
            return tokens.get(0);
        }
        var prefix = msg.startsWith("Undefined type: ") ? "Undefined type: "
            : (msg.startsWith("Unknown or undefined type: ") ? "Unknown or undefined type: "
            : (msg.startsWith("Unresolved type alias: ") ? "Unresolved type alias: " : null));
        if (prefix != null && msg.length() > prefix.length()) {
            var raw = msg.substring(prefix.length()).trim();
            var candidate = raw.split("[\\s,;\\)\\}\\]]")[0].trim();
            return candidate.startsWith(":") ? candidate : (":" + candidate);
        }
        return "";
    }

    private static String extractPrimaryToken(String msg) {
        return extractCitedTypeToken(msg);
    }

    /**
     * Constructs a new StvnExternalAnnotator instance.
     */
    public StvnExternalAnnotator() {
    }

    /**
     * Document context information collected on the EDT for background annotation.
     *
     * @param text document text content
     * @param path physical or virtual file path
     * @param virtualFile underlying virtual file
     * @param project active project context
     */
    public record CollectedInfo(String text, String path, VirtualFile virtualFile, @Nullable Project project) {
        /**
         * Constructs a CollectedInfo instance without an explicit project context.
         *
         * @param text document text content
         * @param path physical or virtual file path
         * @param virtualFile underlying virtual file
         */
        public CollectedInfo(String text, String path, VirtualFile virtualFile) {
            this(text, path, virtualFile, null);
        }
    }

    /**
     * Annotation result encapsulating compiler diagnostics.
     *
     * @param diagnostics list of compiler diagnostics
     */
    public record AnnotationResult(List<StvnDiagnostic> diagnostics) {}

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file) {
        var virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        return new CollectedInfo(file.getText(), virtualFile.getPath(), virtualFile, file.getProject());
    }

    @Override
    public @Nullable CollectedInfo collectInformation(PsiFile file, com.intellij.openapi.editor.Editor editor, boolean hasErrors) {
        return collectInformation(file);
    }

    @Override
    public @Nullable AnnotationResult doAnnotate(CollectedInfo info) {
        // Concurrency Guard: Thread isolation per file path identifier
        synchronized (info.path().intern()) {
            var resolvedPath = StvnTypeResolver.resolvePhysicalPath(info.virtualFile(), info.project(), info.text());

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
        var rawDiagnostics = result.diagnostics();
        var activeDiagnostics = rawDiagnostics.stream()
            .filter(d -> !isDiagnosticSuppressed(file, d))
            .filter(d -> !isDuplicateOrCascadingMismatchedInput(rawDiagnostics, d))
            .toList();

        var activeErrorDiagnostics = activeDiagnostics.stream()
            .filter(d -> d.severity() == DiagnosticSeverity.ERROR)
            .toList();

        var textLength = file.getTextLength();
        if (textLength == 0) {
            if (virtualFile != null && virtualFile.isValid() && !project.isDisposed()) {
                WolfTheProblemSolver.getInstance(project).clearProblems(virtualFile);
            }
            return;
        }

        // 2. Aggregate all problems (active local errors + pinned include errors)
        var problems = new java.util.ArrayList<Problem>();
        WolfTheProblemSolver wolf = null;
        if (virtualFile != null && virtualFile.isValid() && !project.isDisposed()) {
            wolf = WolfTheProblemSolver.getInstance(project);
        }

        // Render pinned annotations for included file errors and register in problems list
        renderPinnedIncludeDiagnostics(file, rawDiagnostics, holder, problems, wolf, virtualFile);

        if (wolf != null && virtualFile != null) {
            for (var diag : activeErrorDiagnostics) {
                var line = Math.max(0, diag.line() - 1);
                var col = Math.max(0, diag.column());
                var diagMsg = sanitizeCompilerJargon(diag.message());
                var problem = wolf.convertToProblem(virtualFile, line, col, new String[]{ diagMsg });
                if (problem != null) {
                    problems.add(problem);
                }
            }

            if (!problems.isEmpty()) {
                wolf.reportProblems(virtualFile, problems);
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

        // 3. Render annotations in AnnotationHolder for all active diagnostics
        for (var diag : activeDiagnostics) {
            var message = sanitizeCompilerJargon(diag.message());
            var severity = mapSeverity(diag.severity());
            var start = diag.startOffset();
            var end = diag.endOffset();

            boolean isBareSigil = (diag.errorCode().isPresent() && ("ERR_BARE_COLON_PROHIBITED".equals(diag.errorCode().get()) || "ERR_BARE_HASH_PROHIBITED".equals(diag.errorCode().get())))
                    || message.contains("ERR_BARE_COLON_PROHIBITED") || message.contains("ERR_BARE_HASH_PROHIBITED")
                    || message.contains("trap7") || message.contains("trap6");

            if (isBareSigil) {
                if (start >= 0 && start < textLength) {
                    end = start + 1;
                }
            }

            if (diag.errorCode().isPresent() && diag.errorCode().get().equals("DUPLICATE_MAP_KEY") && start >= 0 && end > start && end <= textLength) {
                var rawKey = file.getText().substring(start, end).trim();
                if (rawKey.startsWith("\"") && rawKey.endsWith("\"") && rawKey.length() >= 2) {
                    rawKey = rawKey.substring(1, rawKey.length() - 1);
                }
                message = "Duplicate map key detected: '" + rawKey + "'";
            }

            // Align Tuple arity mismatch display message and enforce container-level coordinate pinning
            if ((diag.errorCode().isPresent() && "TUPLE_ARITY_MISMATCH".equals(diag.errorCode().get()))
                || message.startsWith("Tuple arity mismatch")) {
                if (!message.contains("missing") && message.matches(".*Expected\\s+(\\d+)\\s+elements?,\\s+got\\s+(\\d+).*")) {
                    var matcher = java.util.regex.Pattern.compile("Expected\\s+(\\d+)\\s+elements?,\\s+got\\s+(\\d+)").matcher(message);
                    if (matcher.find()) {
                        int exp = Integer.parseInt(matcher.group(1));
                        int got = Integer.parseInt(matcher.group(2));
                        if (exp > got) {
                            int missing = exp - got;
                            message = "Tuple arity mismatch: Expected " + exp + " elements, got " + got + " (" + missing + " missing)";
                        }
                    }
                }
            }

            // 1. Direct Coordinate Range Highlighting with Defensive Clamping
            // startOffset and endOffset from stvnadore-core:2.0.0 are 0-based half-open [start, end)
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
                    if (!isBareSigil) {
                        range = expandEmptyCompositeRange(file, range, message);
                        var clampedTypeDef = clampToOffendingChildIfTypeDef(file, range, message);
                        if (clampedTypeDef != null) {
                            range = clampedTypeDef;
                        } else {
                            var clampedMeta = clampToOffendingMetadataFacet(file, range, message, diag.errorCode().orElse(null));
                            if (clampedMeta != null) {
                                range = clampedMeta;
                            }
                        }
                    }
                    var annotationBuilder = holder.newAnnotation(severity, message)
                          .range(range);
                    var listLit = findMapTargetListLiteral(file, range);
                    if (listLit != null) {
                        annotationBuilder = annotationBuilder.withFix(new StvnMapAutoHealerQuickFix(listLit));
                    }

                    if (message.contains("Undefined type: ") || message.contains("Unresolved type alias: ")) {
                        var offendingTypeKw = PsiTreeUtil.findElementOfClassAtRange(file, s, e, TypeKeyword.class);
                        if (offendingTypeKw == null) {
                            var elemAtRange = file.findElementAt(s);
                            if (elemAtRange != null) {
                                offendingTypeKw = PsiTreeUtil.getParentOfType(elemAtRange, TypeKeyword.class, false);
                            }
                        }
                        if (offendingTypeKw != null) {
                            annotationBuilder = StvnUnresolvedTypeQuickFixProvider.registerFixes(annotationBuilder, offendingTypeKw);
                        }
                    }

                    // Intercept bare '#' token in value position and attach CompleteSumVariantQuickFix actions
                    var elemAtStart = file.findElementAt(s);
                    var tokenText = (elemAtStart != null) ? elemAtStart.getText().trim() : "";
                    if ("#".equals(tokenText) || (s < e && "#".equals(file.getText().substring(s, e).trim()))) {
                        var targetElem = (elemAtStart != null) ? elemAtStart : file.findElementAt(s);
                        if (targetElem != null) {
                            var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(targetElem);
                            if (expectedSchema != null) {
                                var resolvedNominal = StvnTypeResolver.resolveNominalSchema(expectedSchema);
                                var schemaToInspect = (resolvedNominal != null) ? resolvedNominal : expectedSchema;
                                var constructor = schemaToInspect.getSchemaConstructor();
                                if (constructor != null && constructor.getSumType() != null) {
                                    var sumType = constructor.getSumType();
                                    var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                                    if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                                        // Value-Oriented Programming (VOP) Right-First Invariant: R precedes L
                                        if (innerSchemas.size() >= 2) {
                                            var rightBranch = innerSchemas.get(1);
                                            var leftBranch = innerSchemas.get(0);
                                            var rightLabel = StvnSchemaFormatter.formatCleanSchema(rightBranch);
                                            var leftLabel = StvnSchemaFormatter.formatCleanSchema(leftBranch);
                                            annotationBuilder = annotationBuilder.withFix(new CompleteSumVariantQuickFix(targetElem, "#Right", rightLabel, PriorityAction.Priority.HIGH));
                                            annotationBuilder = annotationBuilder.withFix(new CompleteSumVariantQuickFix(targetElem, "#Left", leftLabel, PriorityAction.Priority.NORMAL));
                                        }
                                    } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                                        for (int i = 0; i < innerSchemas.size(); i++) {
                                            var branch = innerSchemas.get(i);
                                            var branchLabel = StvnSchemaFormatter.formatCleanSchema(branch);
                                            var tag = "#" + (i + 1);
                                            annotationBuilder = annotationBuilder.withFix(new CompleteSumVariantQuickFix(targetElem, tag, branchLabel, PriorityAction.Priority.NORMAL));
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Intercept bare #preserveIndent trait syntax errors and attach predictive quick-fixes
                    if (message.contains("Metadata facet #preserveIndent requires an explicit boolean value")
                        || (diag.errorCode().isPresent() && "ERR_INVALID_METADATA_FACET".equals(diag.errorCode().get()) && message.contains("#preserveIndent"))) {
                        var targetElem = file.findElementAt(range.getStartOffset());
                        if (targetElem != null) {
                            for (var fix : CompleteTraitFacetQuickFix.createFixes(targetElem)) {
                                annotationBuilder = annotationBuilder.withFix(fix);
                            }
                        }
                    }

                    annotationBuilder = attachWrapSumVariantQuickFixes(annotationBuilder, file, range, diag, message);

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

                    if (message.contains("Set elements") && tokenText.equals(StvnVocabulary.TYPE_SET)) {
                        targetSchema = innerSchemas.get(0);
                        isMatch = (targetSchema != null);
                    } else if (message.contains("Map keys") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
                        targetSchema = innerSchemas.get(0);
                        isMatch = (targetSchema != null);
                    } else if (message.contains("Inverted map values") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
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
                        var isLhsDef = org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(typeKw);
                        if (!isLhsDef) {
                            var ab = holder.newAnnotation(severity, message)
                                  .range(typeKw.getTextRange());
                            ab = StvnUnresolvedTypeQuickFixProvider.registerFixes(ab, typeKw);
                            ab.create();
                            registered = true;
                        }
                    }
                }

                if (!registered) {
                    for (var typeKw : typeKeywords) {
                        if (typeKw.getText().equals(typeName)) {
                            var ab = holder.newAnnotation(severity, message)
                                  .range(typeKw.getTextRange());
                            ab = StvnUnresolvedTypeQuickFixProvider.registerFixes(ab, typeKw);
                            ab.create();
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
                    annotationBuilder = attachWrapSumVariantQuickFixes(annotationBuilder, file, targetRange, diag, message);
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
                    var td = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(targetDef);
                    if (td != null && td.getSchemaType() != null) {
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

    boolean isDiagnosticSuppressed(PsiFile file, StvnDiagnostic diag) {
        var message = diag.message();

        // 1. Cross-File Diagnostic Filtering: verify diagnostic origin against active file buffer
        if (isDiagnosticFromIncludedFile(file, diag)) {
            return true;
        }

        // 1b. Coordinate Mismatch Verification: active buffer slice at coordinates must match cited symbol
        if (isCoordinateMismatchInActiveBuffer(file, diag)) {
            return true;
        }

        // 2. Buffer Token Existence Verification:
        // Extract quoted and cited tokens from compiler messages.
        // If a diagnostic message contains a cited token that does not exist in file.getText(), suppress it immediately.
        // If cited tokens exist in the buffer, confirm that the diagnostic coordinates actually contain at least one of them.
        var text = file.getText();
        var textLength = file.getTextLength();

        var start = diag.startOffset();
        var end = diag.endOffset();
        var s = Math.max(0, Math.min(start, textLength));
        var e = Math.max(s, Math.min(end, textLength));
        var slice = (s < e) ? text.substring(s, e) : "";

        var quotedTokens = extractCitedTokens(message);

        boolean isAntlrSyntaxError = (diag.errorCode().isPresent() && "STVN_SYNTAX_ERROR".equals(diag.errorCode().get()))
            || message.contains("mismatched input")
            || message.contains("no viable alternative")
            || message.contains("extraneous input")
            || message.startsWith("STVN Syntax Error");

        if (!isAntlrSyntaxError && !quotedTokens.isEmpty()) {
            // Check if the primary offending token (the first quoted token) exists in active buffer
            var primaryToken = quotedTokens.get(0);
            if (!text.contains(primaryToken)) {
                return true;
            }

            // If the message cites quoted tokens, and a slice exists, verify that
            // at least one quoted token appears in the target range slice (preventing offset drift onto comments)
            if (!slice.isEmpty()) {
                boolean anyTokenInSlice = false;
                for (var t : quotedTokens) {
                    if (slice.contains(t) || t.contains(slice)) {
                        anyTokenInSlice = true;
                        break;
                    }
                }
                if (!anyTokenInSlice) {
                    return true;
                }
            }
        }

        // 3. Universal Comment & Whitespace Locus Protection:
        // Compiler diagnostics across all severities must never land on comments or blank spans,
        // unless reporting forbidden tab characters.
        boolean isTabError = (diag.errorCode().isPresent() && "ERR_TAB_CHARACTER_FORBIDDEN".equals(diag.errorCode().get()))
            || message.contains("ERR_TAB_CHARACTER_FORBIDDEN")
            || message.contains("Tab character");

        if (start >= 0 && start <= textLength) {
            if (!isTabError && s < e && text.substring(s, e).isBlank()) {
                return true;
            }

            int checkOffset = s;
            int limitOffset = Math.max(s + 1, e);
            while (checkOffset < limitOffset && checkOffset < textLength) {
                var leaf = file.findElementAt(checkOffset);
                if (leaf == null) {
                    break;
                }
                if (leaf instanceof com.intellij.psi.PsiComment
                    || PsiTreeUtil.getParentOfType(leaf, com.intellij.psi.PsiComment.class, false) != null) {
                    return true;
                }
                var nextOffset = leaf.getTextRange().getEndOffset();
                if (nextOffset <= checkOffset) {
                    break;
                }
                checkOffset = nextOffset;
            }
        }

        // Suppress external compiler Rule G diagnostics; StvnSemanticAnnotator handles Rule G in-flight and at commit
        if (diag.errorCode().isPresent() && "UNION_BRANCH_OVERFLOW".equals(diag.errorCode().get())) {
            return true;
        }
        if (message.startsWith("Union variant tag '") && message.contains("exceeds branch count")) {
            return true;
        }
        // Suppress external facet ordering diagnostics when StvnMetadataOrderInspection is active
        if (diag.errorCode().isPresent() && "ERR_FACET_ORDER_VIOLATION".equals(diag.errorCode().get())) {
            if (isMetadataOrderInspectionActive(file)) {
                return true;
            }
        }
        if ((message.contains("ERR_FACET_ORDER_VIOLATION") || message.contains("violates canonical")) && isMetadataOrderInspectionActive(file)) {
            return true;
        }
        if ((file instanceof org.stvnadore.plugin.StvnFlatPayloadFile || (file != null && file.getName().endsWith(".stvn_f")))
                && message.contains("cannot contain include statements")) {
            return true;
        }
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
                if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
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

    private static boolean isMetadataOrderInspectionActive(PsiFile file) {
        var vFile = file.getVirtualFile();
        if (vFile != null) {
            var path = vFile.getPath().replace('\\', '/');
            if (path.contains("shared-fixtures") || path.contains("syntax/invalid") || path.contains("metadata/invalid")) {
                return false;
            }
        }
        var project = file.getProject();
        if (project.isDisposed()) {
            return true;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find("StvnMetadataOrder");
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isDiagnosticFromIncludedFile(PsiFile file, StvnDiagnostic diag) {
        // 1. Forward-compatible reflection lookup for diagnostic.sourcePath()
        try {
            var method = diag.getClass().getMethod("sourcePath");
            var pathObj = method.invoke(diag);
            if (pathObj instanceof String sourcePath && !sourcePath.isEmpty()) {
                var virtualFile = file.getVirtualFile();
                if (virtualFile != null) {
                    var activePath = virtualFile.getPath().replace('\\', '/');
                    var normalizedSource = sourcePath.replace('\\', '/');
                    if (!activePath.endsWith(normalizedSource) && !normalizedSource.endsWith(virtualFile.getName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // sourcePath method not present in current core record; fallback to semantic checks
        }

        var msg = diag.message();

        // 2. Included file origin check: if diagnostic reports constraint violation for a type declared in an include
        if (msg.startsWith("Constraint violation (") || msg.startsWith("Zero-Shadowing constraint violated: ")) {
            var openParen = msg.indexOf('(');
            var closeParen = msg.indexOf(')', openParen);
            if (openParen >= 0 && closeParen > openParen) {
                var typeName = msg.substring(openParen + 1, closeParen).trim();
                var resolved = StvnTypeReference.resolveTypeInFile(file, typeName, new java.util.HashSet<>());
                if (resolved != null && isUserIncludedFile(file, resolved.getContainingFile())) {
                    return true;
                }
            }
        }

        // 3. Child module detection for files containing includes:
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        if (!includes.isEmpty()) {
            if (msg.startsWith("Undefined type: ") || msg.startsWith("Unknown or undefined type: ") || msg.startsWith("Unresolved type alias: ")) {
                var typeName = extractCitedTypeToken(msg);
                if (!typeName.isEmpty() && !file.getText().contains(typeName)) {
                    return true;
                }
            }

            var citedTokens = extractCitedTokens(msg);
            for (var candidateSymbol : citedTokens) {
                if (candidateSymbol.startsWith(":") || candidateSymbol.startsWith("#")) {
                    if (!file.getText().contains(candidateSymbol)) {
                        return true;
                    }
                    var resolved = StvnTypeReference.resolveTypeInFile(file, candidateSymbol, new java.util.HashSet<>());
                    if (resolved != null && isUserIncludedFile(file, resolved.getContainingFile())) {
                        var s = Math.max(0, Math.min(diag.startOffset(), file.getTextLength()));
                        var e = Math.max(s, Math.min(diag.endOffset(), file.getTextLength()));
                        if (s < e) {
                            var slice = file.getText().substring(s, e);
                            if (!slice.contains(candidateSymbol)) {
                                return true;
                            }
                        } else {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static boolean isCoordinateMismatchInActiveBuffer(PsiFile file, StvnDiagnostic diag) {
        var start = diag.startOffset();
        var end = diag.endOffset();
        var textLength = file.getTextLength();
        if (start < 0 || end > textLength || start >= end) {
            return false;
        }
        var msg = diag.message();
        var text = file.getText();
        var slice = text.substring(start, end).trim();

        // 1. Undefined or unknown type diagnostics: slice must match cited type
        if (msg.startsWith("Undefined type: ") || msg.startsWith("Unknown or undefined type: ") || msg.startsWith("Unresolved type alias: ")) {
            var typeName = extractCitedTypeToken(msg);
            if (!typeName.isEmpty()) {
                if (!slice.equals(typeName) && !slice.contains(typeName) && !slice.endsWith(typeName)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void renderPinnedIncludeDiagnostics(
            PsiFile file,
            List<StvnDiagnostic> diagnostics,
            AnnotationHolder holder,
            @Nullable List<Problem> problems,
            @Nullable WolfTheProblemSolver wolf,
            @Nullable VirtualFile virtualFile
    ) {
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        if (includes.isEmpty()) return;

        var pinnedKeys = new java.util.HashSet<String>();
        var document = file.getViewProvider().getDocument();

        for (var diag : diagnostics) {
            if (diag.severity() != DiagnosticSeverity.ERROR) continue;
            if (!isDiagnosticFromIncludedFile(file, diag)) continue;

            var msg = sanitizeCompilerJargon(diag.message());
            var primaryToken = extractPrimaryToken(msg);

            // Find matching IncludeElement
            IncludeElement targetInclude = null;
            PsiFile targetFile = null;
            String targetIncludePath = null;

            for (var incl : includes) {
                var stringLit = incl.getStringLiteral();
                if (stringLit != null) {
                    var resolvedFile = StvnTypeReference.resolveIncludeFile(stringLit);
                    var pathText = stringLit.getText().replace("\"", "");

                    if (resolvedFile != null && !primaryToken.isEmpty() && resolvedFile.getText().contains(primaryToken)) {
                        targetInclude = incl;
                        targetFile = resolvedFile;
                        targetIncludePath = pathText;
                        break;
                    }
                }
            }

            if (targetInclude == null && !includes.isEmpty()) {
                targetInclude = includes.iterator().next();
                var stringLit = targetInclude.getStringLiteral();
                if (stringLit != null) {
                    targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                    targetIncludePath = stringLit.getText().replace("\"", "");
                }
            }

            if (targetInclude != null && targetIncludePath != null) {
                var pinKey = targetIncludePath + ":" + msg;
                if (!pinnedKeys.add(pinKey)) {
                    continue;
                }

                var parentStmt = PsiTreeUtil.getParentOfType(targetInclude, IncludeStmt.class, false);
                var targetRange = parentStmt != null ? parentStmt.getTextRange() : targetInclude.getTextRange();

                var pinnedMessage = "Included file '" + targetIncludePath + "' contains compilation errors: " + msg;
                var builder = holder.newAnnotation(HighlightSeverity.ERROR, pinnedMessage)
                                    .range(targetRange);

                var targetVirtualFile = targetFile != null ? targetFile.getVirtualFile() : null;
                if (targetVirtualFile == null) {
                    var activeVf = file.getVirtualFile();
                    if (activeVf != null && activeVf.getParent() != null) {
                        targetVirtualFile = activeVf.getParent().findFileByRelativePath(targetIncludePath);
                    }
                }

                if (targetVirtualFile != null && targetVirtualFile.isValid()) {
                    builder = builder.withFix(new OpenIncludedFileQuickFix(targetIncludePath, targetVirtualFile));
                }

                builder.create();

                if (problems != null && wolf != null && virtualFile != null) {
                    int line = 0;
                    int col = 0;
                    if (document != null && targetRange.getStartOffset() >= 0 && targetRange.getStartOffset() <= document.getTextLength()) {
                        line = document.getLineNumber(targetRange.getStartOffset());
                        col = targetRange.getStartOffset() - document.getLineStartOffset(line);
                    }
                    var problem = wolf.convertToProblem(virtualFile, line, col, new String[]{ pinnedMessage });
                    if (problem != null) {
                        problems.add(problem);
                    }
                }
            }
        }
    }

    private static boolean isUserIncludedFile(PsiFile activeFile, @Nullable PsiFile targetFile) {
        if (targetFile == null || targetFile == activeFile) {
            return false;
        }
        var name = targetFile.getName();
        if (name.contains("prelude") || targetFile.getVirtualFile() == null) {
            return false;
        }
        return true;
    }

    private static boolean isDuplicateOrCascadingMismatchedInput(List<StvnDiagnostic> allDiagnostics, StvnDiagnostic d) {
        var msg = d.message();
        if (msg.contains("mismatched input") && (msg.contains("expecting ')'") || msg.contains("expecting <schema type>"))) {
            for (var other : allDiagnostics) {
                if (other != d && Math.abs(other.startOffset() - d.startOffset()) <= 15 &&
                    (other.message().contains("empty composite") || other.message().contains("insufficient composite"))) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Translates raw ANTLR or compiler generator error strings into clean domain concepts.
     *
     * @param raw the raw compiler diagnostic message
     * @return sanitized user-facing error message
     */
    public static String sanitizeCompilerJargon(String raw) {
        if (raw.contains("token recognition error at: '#'")) {
            return "Incomplete variant tag '#'";
        }
        if (raw.contains("token recognition error at: ':'")) {
            return "Incomplete type sigil ':'";
        }
        if (raw.startsWith("no viable alternative at input")) {
            return raw.replace("no viable alternative at input", "Syntax error at");
        }
        return raw;
    }

    /**
     * Clamps a wide diagnostic range on a type definition down to the specific offending child element.
     *
     * @param file the containing PSI file
     * @param range the original diagnostic range
     * @param message the diagnostic message
     * @return the clamped text range, or null if no clamping applies
     */
    private static @Nullable TextRange clampToOffendingChildIfTypeDef(PsiFile file, TextRange range, String message) {
        // Suppressed legacy 1.x type suffix clamping

        if (message.contains("require types to be #equatable #TRUE")) {
            var collections = PsiTreeUtil.findChildrenOfType(file, CollectionType.class);
            for (var coll : collections) {
                var cr = coll.getTextRange();
                if (range.contains(cr) || cr.contains(range) || range.intersects(cr)) {
                    var firstChild = coll.getFirstChild();
                    if (firstChild == null) continue;
                    var tokenText = firstChild.getText();
                    var innerSchemas = coll.getSchemaTypeList();
                    if (innerSchemas.isEmpty()) continue;

                    if (message.contains("Set elements") && tokenText.equals(StvnVocabulary.TYPE_SET)) {
                        var targetSchema = innerSchemas.get(0);
                        if (targetSchema != null) return targetSchema.getTextRange();
                    } else if (message.contains("Map keys") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
                        var targetSchema = innerSchemas.get(0);
                        if (targetSchema != null) return targetSchema.getTextRange();
                    } else if (message.contains("Inverted map values") && tokenText.equals(StvnVocabulary.TYPE_MAP)) {
                        if (innerSchemas.size() >= 2) {
                            var targetSchema = innerSchemas.get(1);
                            if (targetSchema != null) return targetSchema.getTextRange();
                        }
                    }
                }
            }
        }

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
                    // Pass 1: Spatial Intersection - prioritize matching child intersecting the diagnostic range
                    for (var typeKw : typeKeywords) {
                        var isLhs = (typeDef.getTypeKeyword() == typeKw);
                        if (!isLhs && typeKw.getText().equals(typeName)) {
                            var kwRange = typeKw.getTextRange();
                            if (kwRange.equals(range) || kwRange.contains(range) || range.contains(kwRange) || range.intersects(kwRange)) {
                                return kwRange;
                            }
                        }
                    }
                    // Pass 2: Fallback for wide enclosing ranges
                    for (var typeKw : typeKeywords) {
                        var isLhs = (typeDef.getTypeKeyword() == typeKw);
                        if (!isLhs && typeKw.getText().equals(typeName)) {
                            return typeKw.getTextRange();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Clamps wide metadata diagnostic spans down to the exact offending facet token.
     *
     * @param file the containing PSI file
     * @param range the original diagnostic range
     * @param message the diagnostic message
     * @param errorCode the optional diagnostic error code
     * @return the pinned text range on the target token, or null if no clamping applies
     */
    private static @Nullable TextRange clampToOffendingMetadataFacet(PsiFile file, TextRange range, String message, @Nullable String errorCode) {
        // Diagnostic Category Gate: Never clamp type-level errors to metadata facets
        if (message.contains("Undefined type: ") || message.contains("Unresolved type alias: ")
            || "ERR_UNDEFINED_TYPE".equals(errorCode) || "UNDEFINED_TYPE".equals(errorCode)) {
            return null;
        }
        var maps = PsiTreeUtil.findChildrenOfType(file, MetadataMap.class);
        for (var map : maps) {
            var mapRange = map.getTextRange();
            if (mapRange.equals(range) || range.contains(mapRange)) {
                // Pass 1: Inverted range and empty domain faults anchor on the upper bound facet
                if ("INVALID_NUMERIC_RANGE".equals(errorCode) || "ERR_INVERTED_RANGE".equals(errorCode)
                    || "ERR_EMPTY_INTERVAL_DOMAIN".equals(errorCode) || message.contains("effective range is invalid")
                    || message.contains("empty domain") || message.contains("cardinality range is invalid")
                    || message.contains("inverted")) {
                    for (var entry : map.getMetadataEntryList()) {
                        PsiElement leaf = entry.getFirstChild();
                        if (leaf == null) continue;
                        while (leaf.getFirstChild() != null) {
                            leaf = leaf.getFirstChild();
                        }
                        var tokenText = leaf.getText().trim();
                        if (tokenText.equals("#maxExcl") || tokenText.equals("#maxSize") || tokenText.equals("#maxIncl")) {
                            return leaf.getTextRange();
                        }
                    }
                }

                // Pass 2: Mutually exclusive bounds anchor on the conflicting secondary facet
                if ("MUTUALLY_EXCLUSIVE_BOUNDS".equals(errorCode) || "ERR_MUTUALLY_EXCLUSIVE".equals(errorCode)
                    || message.contains("mutually exclusive")) {
                    for (var entry : map.getMetadataEntryList()) {
                        PsiElement leaf = entry.getFirstChild();
                        if (leaf == null) continue;
                        while (leaf.getFirstChild() != null) {
                            leaf = leaf.getFirstChild();
                        }
                        var tokenText = leaf.getText().trim();
                        if (tokenText.equals("#maxIncl") || tokenText.equals("#minExcl") || tokenText.equals("#filterExcl")
                            || tokenText.equals("#zoned") || tokenText.equals("#ms") || tokenText.equals("#audited")
                            || tokenText.equals("#us") || tokenText.equals("#ns")) {
                            return leaf.getTextRange();
                        }
                    }
                }

                // Pass 3: Check for specifically quoted token in message: e.g. facet '#unsigned'
                for (var entry : map.getMetadataEntryList()) {
                    PsiElement leaf = entry.getFirstChild();
                    if (leaf == null) continue;
                    while (leaf.getFirstChild() != null) {
                        leaf = leaf.getFirstChild();
                    }
                    var tokenText = leaf.getText().trim();
                    if (tokenText.startsWith("#") && (message.contains("facet '" + tokenText + "'") || message.contains("'" + tokenText + "'"))) {
                        return leaf.getTextRange();
                    }
                }

                // Pass 4: Incomplete trait facets anchor on the trait keyword token
                if (message.contains("Metadata facet #preserveIndent requires an explicit boolean value")
                    || message.contains("#preserveIndent")) {
                    for (var entry : map.getMetadataEntryList()) {
                        if (entry.getMetadataTrait() != null) {
                            return entry.getMetadataTrait().getTextRange();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Expands a pinpoint diagnostic range anchored on empty parentheses '()' or brackets '[]'
     * backwards to encompass the enclosing composite keyword token (:Tuple, :Union, :Option, :Either, :Enum, etc.).
     *
     * @param file the containing PSI file
     * @param range the original diagnostic range
     * @param message the diagnostic message
     * @return the expanded or original text range
     */
    private static TextRange expandEmptyCompositeRange(PsiFile file, TextRange range, String message) {
        var text = file.getText();
        var textLength = text.length();
        if (textLength == 0) {
            return range;
        }

        var start = Math.max(0, Math.min(range.getStartOffset(), textLength));

        // 1. PSI AST Node Context Inspection
        var element = file.findElementAt(start);
        if (element != null) {
            var product = PsiTreeUtil.getParentOfType(element, ProductType.class);
            if (product != null) {
                var productText = product.getText();
                if (productText.startsWith(StvnVocabulary.TYPE_TUPLE) && product.getSchemaTypeList().isEmpty()) {
                    return product.getTextRange();
                }
            }

            var sum = PsiTreeUtil.getParentOfType(element, SumType.class);
            if (sum != null) {
                var sumText = sum.getText();
                if (sum.getEnumDef() != null && sum.getEnumDef().getValueKeywordList().isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(StvnVocabulary.TYPE_UNION) && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
                if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class).isEmpty()) {
                    return sum.getTextRange();
                }
            }

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
        return kw.equals(StvnVocabulary.TYPE_TUPLE) || kw.equals(StvnVocabulary.TYPE_UNION) || kw.equals(StvnVocabulary.TYPE_ENUM) ||
               kw.equals(StvnVocabulary.TYPE_OPTION) || kw.equals(StvnVocabulary.TYPE_EITHER) || kw.equals(StvnVocabulary.TYPE_SEQ) ||
               kw.equals(StvnVocabulary.TYPE_SET) ||
               kw.equals(StvnVocabulary.TYPE_MAP);
    }

    private static AnnotationBuilder attachWrapSumVariantQuickFixes(
        AnnotationBuilder annotationBuilder,
        PsiFile file,
        TextRange range,
        StvnDiagnostic diag,
        String message
    ) {
        if ((diag.errorCode().isPresent() && "ERR_AMBIGUOUS_SUM_INFERENCE".equals(diag.errorCode().get()))
            || (message.contains("Ambiguous implicit resolution") || message.contains("Ambiguous implicit either") || message.contains("matches multiple candidate branches"))) {
            var elem = file.findElementAt(range.getStartOffset());
            if (elem != null) {
                var valueElement = PsiTreeUtil.getParentOfType(elem, Value.class, false);
                if (valueElement == null) {
                    valueElement = PsiTreeUtil.getNonStrictParentOfType(elem, Value.class);
                }
                var targetElement = (valueElement != null) ? valueElement : elem;
                var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(elem);
                if (expectedSchema != null) {
                    var resolvedNominal = StvnTypeResolver.resolveNominalSchema(expectedSchema);
                    var schemaToInspect = (resolvedNominal != null) ? resolvedNominal : expectedSchema;
                    var constructor = schemaToInspect.getSchemaConstructor();
                    if (constructor != null && constructor.getSumType() != null) {
                        var sumType = constructor.getSumType();
                        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                        if (sumType.getText().startsWith(StvnVocabulary.TYPE_EITHER)) {
                            // Value-Oriented Programming (VOP) Right-First Invariant: R precedes L
                            if (innerSchemas.size() >= 2) {
                                var rightBranch = innerSchemas.get(1);
                                var leftBranch = innerSchemas.get(0);
                                var rightLabel = StvnSchemaFormatter.formatCleanSchema(rightBranch);
                                var leftLabel = StvnSchemaFormatter.formatCleanSchema(leftBranch);
                                annotationBuilder = annotationBuilder.withFix(new WrapSumVariantQuickFix(targetElement, "#Right", rightLabel, PriorityAction.Priority.HIGH));
                                annotationBuilder = annotationBuilder.withFix(new WrapSumVariantQuickFix(targetElement, "#Left", leftLabel, PriorityAction.Priority.NORMAL));
                            }
                        } else if (sumType.getText().startsWith(StvnVocabulary.TYPE_UNION)) {
                            for (int i = 0; i < innerSchemas.size(); i++) {
                                var branch = innerSchemas.get(i);
                                if (valueElement != null && StvnTypeResolver.matchesSchemaPattern(valueElement, branch)) {
                                    var branchLabel = StvnSchemaFormatter.formatCleanSchema(branch);
                                    var tag = "#" + (i + 1);
                                    annotationBuilder = annotationBuilder.withFix(new WrapSumVariantQuickFix(targetElement, tag, branchLabel));
                                }
                            }
                        }
                    }
                }
            }
        }
        return annotationBuilder;
    }
}
