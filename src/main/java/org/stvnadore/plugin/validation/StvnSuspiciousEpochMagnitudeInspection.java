package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.documentation.StvnTimeEpochFormatter;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.IntegerLiteral;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.Visitor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates epoch tick integer literals against the magnitude band of their declared {@code :TimeEpoch} scale.
 * <p>
 * Emits a warning when a literal digit length deviates by &ge; 3 orders of magnitude from declared scale.
 * Offers quick-fixes to rescale the literal or update the local schema scale flag.
 */
@NullMarked
public final class StvnSuspiciousEpochMagnitudeInspection extends LocalInspectionTool {

    /**
     * Constructs a new {@link StvnSuspiciousEpochMagnitudeInspection}.
     */
    public StvnSuspiciousEpochMagnitudeInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnSuspiciousEpochMagnitude";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Suspicious epoch magnitude inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "STVN";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitIntegerLiteral(@NotNull IntegerLiteral literal) {
                super.visitIntegerLiteral(literal);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(literal)) {
                    return;
                }

                var bodyEntry = PsiTreeUtil.getParentOfType(literal, BodyEntry.class);
                if (bodyEntry == null) return;

                var value = PsiTreeUtil.getParentOfType(literal, Value.class);
                if (value == null) return;

                var info = StvnTypeResolver.resolveBaseTypeInfo(value);
                if (info == null) return;

                var baseType = resolveBaseTypeString(info.getSchema(), new HashSet<>());
                if (!StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)) return;

                var scaleFlag = extractScaleFlag(info.getSchema());
                var text = literal.getText().trim();
                var digits = (text.startsWith("-") ? text.substring(1) : text).replace("_", "");
                int digitCount = digits.length();

                if (digitCount == 0 || text.equals("0")) return;

                var detectedScale = StvnTimeEpochFormatter.detectScaleBand(digitCount);
                if (detectedScale == null || detectedScale.equals(scaleFlag)) return;

                int declaredOrder = scaleToOrder(scaleFlag);
                int detectedOrder = scaleToOrder(detectedScale);
                int orderDifference = Math.abs(detectedOrder - declaredOrder);

                if (orderDifference >= 3) {
                    String msg = String.format(
                        "Suspicious epoch magnitude: literal '%s' has %d digits (suggesting '%s'), "
                            + "but declared scale is '%s'.",
                        text, digitCount, detectedScale, scaleFlag
                    );
                    var formatted = StvnDiagnosticFormatter.formatPlugin(
                        msg,
                        StvnDiagnosticCodes.WARN_SUSPICIOUS_EPOCH_MAGNITUDE
                    );

                    boolean outOfSchemaBounds = isLiteralOutOfTimeEpochBounds(info.getSchema(), literal, text);
                    List<LocalQuickFix> quickFixes = new ArrayList<>(2);
                    if (!outOfSchemaBounds) {
                        quickFixes.add(createRescaleFix(text, declaredOrder, detectedOrder, scaleFlag));
                    }
                    LocalQuickFix updateSchemaFix = createUpdateSchemaFix(info.getSchema(), detectedScale);
                    if (updateSchemaFix != null) {
                        quickFixes.add(updateSchemaFix);
                    }

                    holder.registerProblem(
                        literal,
                        formatted,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        quickFixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                }
            }
        };
    }

    private static boolean isLiteralOutOfTimeEpochBounds(
            SchemaType schemaType,
            PsiElement context,
            String rawText
    ) {
        BigInteger actual = StvnPsiLiteralEvaluator.parseBigInteger(rawText).orElse(null);
        if (actual == null) {
            return false;
        }
        Map<String, String> meta = StvnExemplarValueGenerator.extractLineageMetadata(schemaType);
        TemporalScale scale = StvnExemplarValueGenerator.resolveEpochScaleFromMeta(meta);
        boolean unsigned = meta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
        int sizeBits = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                context,
                meta.get(StvnVocabulary.FACET_KW_SIZE)
            )
            .map(BigInteger::intValue)
            .orElse(scale.bitWidth().intValue());
        BigInteger minIncl = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                context,
                meta.get(StvnVocabulary.FACET_KW_MIN_INCL)
            )
            .orElse(null);
        BigInteger maxExcl = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                context,
                meta.get(StvnVocabulary.FACET_KW_MAX_EXCL)
            )
            .orElse(null);
        BigInteger[] bounds = StvnExemplarValueGenerator.computeEffectiveIntegerBounds(
            unsigned,
            sizeBits,
            minIncl,
            maxExcl
        );
        return actual.compareTo(bounds[0]) < 0 || actual.compareTo(bounds[1]) > 0;
    }

    /**
     * Converts a canonical temporal scale facet flag to its decimal exponent order relative to seconds.
     *
     * @param scale scale facet flag
     * @return decimal order (0, 3, 6, or 9)
     */
    public static int scaleToOrder(String scale) {
        return switch (scale) {
            case StvnVocabulary.FACET_KW_SCALE_NS -> 9;
            case StvnVocabulary.FACET_KW_SCALE_US -> 6;
            case StvnVocabulary.FACET_KW_SCALE_MS -> 3;
            default -> 0;
        };
    }

    private static LocalQuickFix createRescaleFix(
            String rawText,
            int declaredOrder,
            int detectedOrder,
            String targetScale
    ) {
        BigInteger original = new BigInteger(rawText.replace("_", ""));
        int diff = detectedOrder - declaredOrder;
        BigInteger factor = BigInteger.TEN.pow(Math.abs(diff));
        BigInteger rescaled = diff > 0 ? original.divide(factor) : original.multiply(factor);
        String label = "Rescale literal to '" + rescaled + "' (match declared '" + targetScale + "' scale)";

        return new RescaleEpochLiteralQuickFix(rescaled.toString(), label);
    }

    private static final class RescaleEpochLiteralQuickFix implements LocalQuickFix, IntentionAction, PriorityAction {
        private final String rescaledText;
        private final String label;

        RescaleEpochLiteralQuickFix(String rescaledText, String label) {
            this.rescaledText = rescaledText;
            this.label = label;
        }

        @Override
        public @Nls @NotNull String getName() {
            return label;
        }

        @Override
        public @Nls @NotNull String getText() {
            return label;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Rescale TimeEpoch literal";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            return file != null;
        }

        @Override
        public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            if (editor == null || file == null) return;
            var elem = file.findElementAt(editor.getCaretModel().getOffset());
            if (elem == null) return;
            var intLit = elem instanceof IntegerLiteral il
                ? il
                : PsiTreeUtil.getParentOfType(elem, IntegerLiteral.class, false);
            if (intLit == null) return;
            var doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc == null) return;
            doc.replaceString(intLit.getTextRange().getStartOffset(), intLit.getTextRange().getEndOffset(), rescaledText);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var elem = descriptor.getPsiElement();
            if (elem == null) return;
            var doc = PsiDocumentManager.getInstance(project).getDocument(elem.getContainingFile());
            if (doc == null) return;
            doc.replaceString(elem.getTextRange().getStartOffset(), elem.getTextRange().getEndOffset(), rescaledText);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }

    private static @Nullable LocalQuickFix createUpdateSchemaFix(SchemaType schemaType, String detectedScale) {
        if (!schemaType.isWritable()) return null;
        var metaMap = schemaType.getMetadataMap();
        PsiElement targetScaleElem = null;

        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var t = entry.getText().trim();
                if (t.equals(StvnVocabulary.FACET_KW_SCALE_S) || t.equals(StvnVocabulary.FACET_KW_SCALE_MS)
                    || t.equals(StvnVocabulary.FACET_KW_SCALE_US) || t.equals(StvnVocabulary.FACET_KW_SCALE_NS)) {
                    targetScaleElem = entry;
                    break;
                }
            }
        }

        if (targetScaleElem == null) {
            var kw = schemaType.getTypeKeyword();
            if (kw != null) {
                var file = schemaType.getContainingFile();
                if (file != null) {
                    var resolved = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
                    var td = StvnPsiUtils.getParentTypeDefinition(resolved);
                    if (td != null && td.isWritable() && td.getSchemaType() != null) {
                        return createUpdateSchemaFix(td.getSchemaType(), detectedScale);
                    }
                }
            }
            return null;
        }

        final PsiElement scaleNode = targetScaleElem;
        return new LocalQuickFix() {
            @Override
            public @Nls @NotNull String getName() {
                return "Update schema scale flag to '" + detectedScale + "'";
            }

            @Override
            public @Nls @NotNull String getFamilyName() {
                return "Update TimeEpoch scale flag";
            }

            @Override
            public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
                var doc = PsiDocumentManager.getInstance(project).getDocument(scaleNode.getContainingFile());
                if (doc == null) return;
                doc.replaceString(scaleNode.getTextRange().getStartOffset(), scaleNode.getTextRange().getEndOffset(), detectedScale);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        };
    }

    private static String extractScaleFlag(SchemaType schemaType) {
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_SCALE_S) || text.equals(StvnVocabulary.FACET_KW_SCALE_MS)
                    || text.equals(StvnVocabulary.FACET_KW_SCALE_US) || text.equals(StvnVocabulary.FACET_KW_SCALE_NS)) {
                    return text;
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
                var td = StvnPsiUtils.getParentTypeDefinition(resolved);
                if (td != null && td.getSchemaType() != null) {
                    return extractScaleFlag(td.getSchemaType());
                }
            }
        }
        return StvnVocabulary.FACET_KW_SCALE_MS;
    }

    private static @Nullable String resolveBaseTypeString(SchemaType schemaType, Set<String> visited) {
        var ctor = schemaType.getSchemaConstructor();
        if (ctor != null && ctor.getAtomicType() != null) {
            return ctor.getAtomicType().getText().trim();
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (StvnVocabulary.TYPE_TIME_EPOCH.equals(text)) return text;
            if (!visited.add(text)) return null;
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                var td = StvnPsiUtils.getParentTypeDefinition(resolved);
                if (td != null && td.getSchemaType() != null) {
                    return resolveBaseTypeString(td.getSchemaType(), visited);
                }
            }
        }
        return null;
    }
}
