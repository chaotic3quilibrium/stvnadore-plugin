package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier;
import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.AtomicType;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates STVN {@code :String} capacity governance invariants ({@code STVN | Governance | Capacity}).
 * <p>
 * Enforces explicit capacity dimensions on root {@code :String} schemas in {@code :defs} and {@code :type},
 * flags capacities exceeding the configured character threshold, and optionally inspects {@code :body}
 * and {@code #CONST} string literals.
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public class StvnStringCapacityInspection extends AbstractStvnCapacityInspection {

    private static final Pattern CAPACITY_LEXICAL_PATTERN =
        Pattern.compile("#[a-zA-Z0-9_]*size", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCED_STRING_SANITIZE_PATTERN =
        Pattern.compile("(?s)\"\"\"\\[([a-zA-Z0-9_-]{1,256})].*?\\[\\1]\"\"\"");

    /**
     * Authoritative map of Standard Prelude string types to their canonical {@code #maxSize} character capacity.
     */
    public static final Map<String, Integer> PRELUDE_STRING_CAPACITIES = Map.ofEntries(
        Map.entry(":UuidLowerCase", 36),
        Map.entry(":org/stvnadore/prelude/UuidLowerCase", 36),
        Map.entry(":UuidMixedCase", 36),
        Map.entry(":org/stvnadore/prelude/UuidMixedCase", 36),
        Map.entry(":Ulid", 26),
        Map.entry(":org/stvnadore/prelude/Ulid", 26),
        Map.entry(":Sha256LowerCase", 64),
        Map.entry(":org/stvnadore/prelude/Sha256LowerCase", 64),
        Map.entry(":Sha256MixedCase", 64),
        Map.entry(":org/stvnadore/prelude/Sha256MixedCase", 64),
        Map.entry(":SemVer", 128),
        Map.entry(":org/stvnadore/prelude/SemVer", 128),
        Map.entry(":EmailLowerCase", 254),
        Map.entry(":org/stvnadore/prelude/EmailLowerCase", 254),
        Map.entry(":EmailMixedCase", 254),
        Map.entry(":org/stvnadore/prelude/EmailMixedCase", 254),
        Map.entry(":IPv4", 15),
        Map.entry(":org/stvnadore/prelude/IPv4", 15),
        Map.entry(":PosixId", 255),
        Map.entry(":org/stvnadore/prelude/PosixId", 255),
        Map.entry(":PolyglotCodeId", 128),
        Map.entry(":org/stvnadore/prelude/PolyglotCodeId", 128),
        Map.entry(":StvnPosixId64", 64),
        Map.entry(":org/stvnadore/prelude/meta/StvnPosixId64", 64)
    );

    /**
     * Configured capacity threshold in characters (default: 4,096).
     * Preserves legacy XML profile option name {@code thresholdCapacity}.
     */
    public int thresholdCapacity = DEFAULT_INSPECTION_THRESHOLD;

    /**
     * Toggle enabling string literal length verification in {@code :body} payloads and {@code #CONST} values.
     * Preserves legacy XML profile option name {@code inspectPayload}.
     */
    public boolean inspectPayload = false;

    /**
     * Explicit severity override used during automated tests.
     */
    public String configuredSeverity = "WARNING";

    /**
     * Constructs a new {@code StvnStringCapacityInspection} instance.
     */
    public StvnStringCapacityInspection() {
        super(CapacityTargetKind.STRING);
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnStringCapacity";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "String capacity governance inspection";
    }

    @Override
    protected int getConfiguredThreshold() {
        return thresholdCapacity;
    }

    @Override
    protected boolean isInspectLiteralsEnabled() {
        return inspectPayload;
    }

    @Override
    protected String getConfiguredSeverityOverride() {
        return configuredSeverity;
    }

    @Override
    protected String getThresholdFieldName() {
        return "thresholdCapacity";
    }

    @Override
    protected String getInspectLiteralsFieldName() {
        return "inspectPayload";
    }

    @Override
    protected String getThresholdOptionLabel() {
        return "Capacity threshold (characters):";
    }

    @Override
    protected String getInspectLiteralsOptionLabel() {
        return "Inspect string literals in body payloads and constants";
    }

    /**
     * Resolved capacity summary retained for caller compatibility.
     */
    public static final class EffectiveTypeCapacity {
        public final boolean isString;
        public final boolean isCollection;
        public final BigInteger capacity;
        public final boolean hasExplicitCapacity;

        /**
         * Constructs an {@code EffectiveTypeCapacity} summary with a {@link BigInteger} capacity.
         *
         * @param isString            {@code true} when grounded in {@code :String}
         * @param isCollection        {@code true} when grounded in {@code :Seq}, {@code :Set}, or {@code :Map}
         * @param capacity            effective {@code #maxSize} bound
         * @param hasExplicitCapacity {@code true} when an explicit capacity facet is present
         */
        public EffectiveTypeCapacity(boolean isString, boolean isCollection, BigInteger capacity, boolean hasExplicitCapacity) {
            this.isString = isString;
            this.isCollection = isCollection;
            this.capacity = capacity;
            this.hasExplicitCapacity = hasExplicitCapacity;
        }

        /**
         * Constructs an {@code EffectiveTypeCapacity} summary with an {@code int} capacity.
         *
         * @param isString            {@code true} when grounded in {@code :String}
         * @param isCollection        {@code true} when grounded in {@code :Seq}, {@code :Set}, or {@code :Map}
         * @param capacity            effective {@code #maxSize} bound
         * @param hasExplicitCapacity {@code true} when an explicit capacity facet is present
         */
        public EffectiveTypeCapacity(boolean isString, boolean isCollection, int capacity, boolean hasExplicitCapacity) {
            this(isString, isCollection, BigInteger.valueOf(capacity), hasExplicitCapacity);
        }
    }

    /**
     * Sanitizes metadata map text by stripping fenced strings, single-line comments, and simple string literals.
     *
     * @param rawText raw metadata map text
     * @return sanitized text containing only active tokens
     */
    public static String sanitizeMetadataText(@Nullable String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }
        String noFenced = FENCED_STRING_SANITIZE_PATTERN.matcher(rawText).replaceAll("");
        String noComments = noFenced.replaceAll("(?m)//.*$", "");
        return noComments.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "");
    }

    /**
     * Determines whether sanitized metadata text contains a capacity facet keyword.
     *
     * @param sanitizedText text free of comments and string literals
     * @return {@code true} if a capacity facet keyword is detected
     */
    public static boolean containsCapacityFacetLexically(String sanitizedText) {
        return CAPACITY_LEXICAL_PATTERN.matcher(sanitizedText).find()
            || sanitizedText.contains(StvnVocabulary.FACET_KW_SIZE)
            || sanitizedText.contains(StvnVocabulary.FACET_KW_MAX_SIZE)
            || sanitizedText.contains(StvnVocabulary.FACET_KW_MIN_SIZE);
    }

    /**
     * Resolves the effective capacity summary for {@code schemaType}.
     *
     * @param schemaType schema type AST node
     * @param file       containing PSI file
     * @param visited    caller-supplied visited name set (retained for API compatibility)
     * @return resolved capacity summary, or {@code null}
     */
    public static @Nullable EffectiveTypeCapacity resolveEffectiveTypeCapacity(
            SchemaType schemaType,
            PsiFile file,
            Set<String> visited
    ) {
        ResolvedCapacityProfile profile = resolveCapacityProfile(schemaType, file, new HashSet<>());
        if (profile == null) {
            return null;
        }
        return new EffectiveTypeCapacity(
            profile.kind() == CapacityTargetKind.STRING,
            profile.kind().isCollection(),
            profile.capacity(),
            profile.hasExplicitCapacity()
        );
    }

    /**
     * Traces nominal type hierarchy from {@code file} for {@code targetName}.
     *
     * @param file       containing PSI file
     * @param targetName target nominal type identifier
     * @param visited    caller-supplied visited name set (retained for API compatibility)
     * @return resolved capacity summary, or {@code null}
     */
    public static @Nullable EffectiveTypeCapacity traceTypeHierarchy(
            PsiFile file,
            String targetName,
            Set<String> visited
    ) {
        if (!visited.add(targetName)) {
            return null;
        }
        ResolvedCapacityProfile profile = traceNominalCapacityLineage(file, targetName, file, new HashSet<>());
        if (profile == null) {
            return null;
        }
        return new EffectiveTypeCapacity(
            profile.kind() == CapacityTargetKind.STRING,
            profile.kind().isCollection(),
            profile.capacity(),
            profile.hasExplicitCapacity()
        );
    }

    /**
     * Resolves the effective capacity bound for {@code schemaType} as a {@link BigInteger}.
     *
     * @param schemaType schema type AST element
     * @param file       containing PSI file
     * @return resolved capacity bound, or {@code null} if unresolvable
     */
    public static @Nullable BigInteger resolveCapacityBoundBigInteger(SchemaType schemaType, PsiFile file) {
        ResolvedCapacityProfile profile = resolveCapacityProfile(schemaType, file, new HashSet<>());
        return profile != null ? profile.capacity() : null;
    }

    /**
     * Resolves the effective capacity bound for {@code schemaType} clamped to {@code Integer.MAX_VALUE}.
     *
     * @param schemaType schema type AST element
     * @param file       containing PSI file
     * @return resolved capacity bound, or {@code -1} if unresolvable
     */
    public static int resolveCapacityBound(SchemaType schemaType, PsiFile file) {
        var cap = resolveCapacityBoundBigInteger(schemaType, file);
        if (cap == null) {
            return -1;
        }
        if (cap.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return Integer.MAX_VALUE;
        }
        return cap.intValue();
    }

    /**
     * Extracts decoded text content from an STVN string literal token.
     *
     * @param raw raw token text
     * @return decoded string content
     */
    public static String extractLiteralContent(String raw) {
        var decoded = StvnPsiLiteralEvaluator.decodeStringLiteral(raw, false);
        if (decoded.isPresent()) {
            return decoded.get();
        }
        if (raw.startsWith("\"\"\"")) {
            int firstNl = raw.indexOf('\n');
            int lastFence = raw.lastIndexOf("\"\"\"");
            if (firstNl >= 0 && lastFence > firstNl) {
                int closeBracket = raw.lastIndexOf(StvnVocabulary.DELIM_CLOSE_BRACKET.charAt(0));
                if (closeBracket >= 0 && closeBracket < lastFence) {
                    lastFence = raw.lastIndexOf(StvnVocabulary.DELIM_OPEN_BRACKET.charAt(0), closeBracket);
                }
                if (lastFence > firstNl) {
                    return raw.substring(firstNl + 1, lastFence);
                }
            }
        }
        return "";
    }

    /**
     * Truncates raw string literal content to fit within {@code maxCapacity} characters.
     *
     * @param raw         raw token text
     * @param maxCapacity maximum allowed character length
     * @return truncated token text with preserved delimiter wrapping
     */
    public static String truncateLiteral(String raw, int maxCapacity) {
        if (raw.startsWith("\"\"\"")) {
            int firstNl = raw.indexOf('\n');
            int lastFence = raw.lastIndexOf("\"\"\"");
            if (firstNl >= 0 && lastFence > firstNl) {
                int openCloseTag = raw.lastIndexOf(StvnVocabulary.DELIM_OPEN_BRACKET.charAt(0), lastFence);
                int bodyEnd = (openCloseTag > firstNl) ? openCloseTag : lastFence;
                String header = raw.substring(0, firstNl + 1);
                String trailer = raw.substring(bodyEnd);
                String body = raw.substring(firstNl + 1, bodyEnd);
                int cpCount = body.codePointCount(0, body.length());
                String truncatedBody = cpCount > maxCapacity
                    ? body.substring(0, body.offsetByCodePoints(0, maxCapacity))
                    : body;
                return header + truncatedBody + trailer;
            }
        } else if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(raw, false);
            if (decodedOpt.isPresent()) {
                String decoded = decodedOpt.get();
                int cpCount = decoded.codePointCount(0, decoded.length());
                if (cpCount > maxCapacity) {
                    String clamped = decoded.substring(0, decoded.offsetByCodePoints(0, maxCapacity));
                    return StvnPsiLiteralEvaluator.formatCanonicalQuotedString(clamped);
                }
                return raw;
            }
            String content = raw.substring(1, raw.length() - 1);
            String truncated = content.length() > maxCapacity ? content.substring(0, maxCapacity) : content;
            return "\"" + truncated + "\"";
        }
        return raw;
    }

    /**
     * Structural QuickFix injecting explicit capacity facets at canonical 5-tier rank.
     */
    public static class ApplyNominalCapacityQuickFix implements LocalQuickFix, PriorityAction {
        @SafeFieldForPreview
        private final String actionName;
        @SafeFieldForPreview
        private final String facetSnippet;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a new {@code ApplyNominalCapacityQuickFix} with explicit intention priority.
         *
         * @param actionName   user-visible intention action name
         * @param facetSnippet metadata facet snippet
         * @param priority     intention display priority
         */
        public ApplyNominalCapacityQuickFix(String actionName, String facetSnippet, Priority priority) {
            this.actionName = actionName;
            this.facetSnippet = facetSnippet;
            this.priority = priority;
        }

        /**
         * Constructs a new {@code ApplyNominalCapacityQuickFix} with {@link Priority#NORMAL} priority.
         *
         * @param actionName   user-visible intention action name
         * @param facetSnippet metadata facet snippet
         */
        public ApplyNominalCapacityQuickFix(String actionName, String facetSnippet) {
            this(actionName, facetSnippet, Priority.NORMAL);
        }

        private static boolean isCapacityFacet(MetadataEntry entry) {
            String text = entry.getText().trim();
            return text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)
                || text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)
                || text.startsWith(StvnVocabulary.FACET_KW_SIZE);
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @NotNull String getName() {
            return actionName;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Set nominal capacity";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            var file = element.getContainingFile();
            if (file == null) {
                return;
            }
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(file);

            SchemaType targetSchema = element instanceof SchemaType st
                ? st
                : PsiTreeUtil.getParentOfType(element, SchemaType.class, false);
            var typeDef = element instanceof TypeDefinition td
                ? td
                : PsiTreeUtil.getParentOfType(element, TypeDefinition.class, false);
            var constDef = element instanceof ConstantDefinition cd
                ? cd
                : PsiTreeUtil.getParentOfType(element, ConstantDefinition.class, false);
            if (targetSchema == null && typeDef != null) {
                targetSchema = typeDef.getSchemaType();
            }
            if (targetSchema == null && constDef != null) {
                targetSchema = constDef.getSchemaType();
            }
            boolean isTopLevelTypeDefSchema = typeDef != null && targetSchema == typeDef.getSchemaType();
            boolean isTopLevelConstDefSchema = constDef != null && targetSchema == constDef.getSchemaType();
            var metaMap = targetSchema != null && targetSchema.getMetadataMap() != null
                ? targetSchema.getMetadataMap()
                : (isTopLevelTypeDefSchema ? typeDef.getMetadataMap() : (isTopLevelConstDefSchema ? constDef.getMetadataMap() : null));

            if (metaMap != null) {
                var entries = metaMap.getMetadataEntryList();
                String sanitized = sanitizeMetadataText(metaMap.getText());
                boolean hasLexicalCap = containsCapacityFacetLexically(sanitized);
                var capacityEntries = entries.stream()
                    .filter(ApplyNominalCapacityQuickFix::isCapacityFacet)
                    .toList();
                if (hasLexicalCap && capacityEntries.isEmpty()) {
                    return;
                }
                String inner = facetSnippet
                    .replace(StvnVocabulary.DELIM_OPEN_BRACE, "")
                    .replace(StvnVocabulary.DELIM_CLOSE_BRACE, "")
                    .trim();
                String[] tokens = inner.split("\\s+");
                String minSizeVal = null;
                String maxSizeVal = String.valueOf(StvnVocabulary.DEFAULT_MAX_SIZE);
                for (int i = 0; i + 1 < tokens.length; i++) {
                    if (StvnVocabulary.FACET_KW_MIN_SIZE.equals(tokens[i])) {
                        minSizeVal = tokens[i + 1];
                    } else if (StvnVocabulary.FACET_KW_MAX_SIZE.equals(tokens[i])) {
                        maxSizeVal = tokens[i + 1];
                    }
                }
                StvnMetadataFacetInsertionUtil.insertOrReplaceCapacityFacets(
                    doc,
                    metaMap,
                    minSizeVal,
                    maxSizeVal,
                    minSizeVal == null
                );
            } else {
                int startOffset = targetSchema != null
                    ? targetSchema.getTextRange().getStartOffset()
                    : element.getTextRange().getStartOffset();
                String prefix = doc.getText().substring(0, startOffset).trim();
                if (prefix.endsWith(StvnVocabulary.DELIM_CLOSE_BRACE)) {
                    return;
                }
                doc.insertString(startOffset, facetSnippet + " ");
            }
            if (commitPhysical) {
                docManager.commitDocument(doc);
            }
        }
    }

    /**
     * Canonical quick-fix alias conforming to specification naming directives.
     */
    public static class SetNominalCapacityQuickFix extends ApplyNominalCapacityQuickFix {
        /**
         * Constructs a new {@code SetNominalCapacityQuickFix} with explicit priority.
         *
         * @param actionName   user-visible action name
         * @param facetSnippet metadata facet snippet
         * @param priority     intention priority
         */
        public SetNominalCapacityQuickFix(String actionName, String facetSnippet, Priority priority) {
            super(actionName, facetSnippet, priority);
        }

        /**
         * Constructs a new {@code SetNominalCapacityQuickFix} with {@link Priority#NORMAL} priority.
         *
         * @param actionName   user-visible action name
         * @param facetSnippet metadata facet snippet
         */
        public SetNominalCapacityQuickFix(String actionName, String facetSnippet) {
            super(actionName, facetSnippet, Priority.NORMAL);
        }
    }

    /**
     * QuickFix truncating string literal values to the active capacity bound.
     */
    public static final class TruncateStringLiteralQuickFix implements LocalQuickFix {
        @SafeFieldForPreview
        private final int maxCapacity;

        /**
         * Constructs a new {@code TruncateStringLiteralQuickFix}.
         *
         * @param maxCapacity maximum character count to retain
         */
        public TruncateStringLiteralQuickFix(int maxCapacity) {
            this.maxCapacity = maxCapacity;
        }

        @Override
        public @NotNull String getName() {
            return "Truncate string literal to " + maxCapacity + " characters";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Truncate string literal to declared schema capacity";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            var file = element.getContainingFile();
            if (file == null) {
                return;
            }
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(file);

            String rawText = element.getText();
            String truncatedText = truncateLiteral(rawText, maxCapacity);
            var range = element.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), truncatedText);
            if (commitPhysical) {
                docManager.commitDocument(doc);
            }
        }
    }

    /**
     * QuickFix widening the governing {@code :String} root schema {@code #maxSize} to accommodate literal length.
     */
    public static final class WidenSchemaCapacityQuickFix implements LocalQuickFix {
        private final SchemaType schemaType;
        @SafeFieldForPreview
        private final int requiredCapacity;

        /**
         * Constructs a new {@code WidenSchemaCapacityQuickFix}.
         *
         * @param schemaType       governing schema type AST node
         * @param requiredCapacity required minimum character capacity
         */
        public WidenSchemaCapacityQuickFix(SchemaType schemaType, int requiredCapacity) {
            this.schemaType = schemaType;
            this.requiredCapacity = requiredCapacity;
        }

        @Override
        public @NotNull String getName() {
            return "Widen schema string capacity to " + requiredCapacity;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Widen schema string capacity";
        }

        @Override
        public @Nullable FileModifier getFileModifierForPreview(@NotNull PsiFile target) {
            if (!schemaType.isValid() || schemaType.getContainingFile() != target.getOriginalFile()) {
                return null;
            }
            return new WidenSchemaCapacityQuickFix(
                PsiTreeUtil.findSameElementInCopy(schemaType, target),
                requiredCapacity
            );
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            SchemaType rootSchema = resolveEditableStringRootSchema(schemaType);
            if (rootSchema == null || !rootSchema.isValid()) {
                return;
            }
            var file = rootSchema.getContainingFile();
            if (file == null) {
                return;
            }
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(file);

            MetadataMap metaMap = rootSchema.getMetadataMap();
            if (metaMap == null && rootSchema.getParent() instanceof TypeDefinition td && td.getSchemaType() == rootSchema) {
                metaMap = td.getMetadataMap();
            }
            if (metaMap != null) {
                StvnMetadataFacetInsertionUtil.insertOrReplaceCapacityFacets(
                    doc,
                    metaMap,
                    null,
                    String.valueOf(requiredCapacity),
                    true
                );
                if (commitPhysical) {
                    docManager.commitDocument(doc);
                }
                return;
            }
            var atomicType = PsiTreeUtil.findChildOfType(rootSchema, AtomicType.class);
            PsiElement targetElem = atomicType != null ? atomicType : rootSchema.getTypeKeyword();
            if (targetElem != null) {
                String widened = StvnVocabulary.DELIM_OPEN_BRACE + " "
                    + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 "
                    + StvnVocabulary.FACET_KW_MAX_SIZE + " " + requiredCapacity + " "
                    + StvnVocabulary.DELIM_CLOSE_BRACE + " " + StvnVocabulary.TYPE_STRING;
                var range = targetElem.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), widened);
                if (commitPhysical) {
                    docManager.commitDocument(doc);
                }
            }
        }

        private static @Nullable SchemaType resolveEditableStringRootSchema(SchemaType start) {
            SchemaType cur = start;
            Set<PsiElement> visited = new HashSet<>();
            while (cur != null && visited.add(cur)) {
                if (cur.getSchemaConstructor() != null) {
                    return cur;
                }
                TypeKeyword kw = cur.getTypeKeyword();
                if (kw == null) {
                    return null;
                }
                TypeDefinition def = StvnTypeReference.resolveTypeDefinitionAtContext(kw, kw.getText().trim());
                if (def == null || !visited.add(def)) {
                    return null;
                }
                cur = def.getSchemaType();
            }
            return null;
        }
    }

    /**
     * Inspects whether {@code typeName} represents a built-in or Prelude STVN string type.
     *
     * @param typeName type identifier to inspect
     * @return {@code true} if {@code typeName} belongs to the STVN string taxonomy
     */
    public static boolean isNominalStringType(@Nullable String typeName) {
        if (typeName == null) {
            return false;
        }
        return typeName.equals(StvnVocabulary.TYPE_STRING)
            || PRELUDE_STRING_CAPACITIES.containsKey(typeName);
    }
}
