package org.stvnadore.plugin.actions;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural scaffolding engine that evaluates a target SchemaType and generates
 * canonical mock data literals and Live Template tab-stop specifications.
 */
@NullMarked
public final class StvnSchemaSkeletonScaffolder {

    private StvnSchemaSkeletonScaffolder() {}

    /**
     * Result of generating a schema skeleton containing source text and placeholder offsets.
     */
    public static final class ScaffoldResult {
        private final String codeText;
        private final List<PlaceholderSpec> placeholders;

        /**
         * Constructs a ScaffoldResult.
         *
         * @param codeText generated mock source code
         * @param placeholders list of interactive tab-stop specifications
         */
        public ScaffoldResult(String codeText, List<PlaceholderSpec> placeholders) {
            this.codeText = codeText;
            this.placeholders = placeholders;
        }

        /**
         * Returns the generated code text.
         *
         * @return code text
         */
        public String getCodeText() {
            return codeText;
        }

        /**
         * Returns the list of placeholder specifications.
         *
         * @return placeholder specifications
         */
        public List<PlaceholderSpec> getPlaceholders() {
            return placeholders;
        }
    }

    /**
     * Interactive Live Template tab-stop specification indicating replacement range and default value.
     */
    public static final class PlaceholderSpec {
        private final int startOffset;
        private final int endOffset;
        private final String defaultValue;

        /**
         * Constructs a PlaceholderSpec.
         *
         * @param startOffset relative start character offset
         * @param endOffset relative end character offset
         * @param defaultValue default literal text value
         */
        public PlaceholderSpec(int startOffset, int endOffset, String defaultValue) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.defaultValue = defaultValue;
        }

        /**
         * Returns the start offset.
         *
         * @return start offset
         */
        public int getStartOffset() {
            return startOffset;
        }

        /**
         * Returns the end offset.
         *
         * @return end offset
         */
        public int getEndOffset() {
            return endOffset;
        }

        /**
         * Returns the default value.
         *
         * @return default value
         */
        public String getDefaultValue() {
            return defaultValue;
        }
    }

    /**
     * Generates a structural mock skeleton literal for the given schema type definition.
     *
     * @param schemaType target schema PSI element to scaffold
     * @param baseIndent base indentation string for nested lines
     * @return ScaffoldResult containing formatted code and tab stops, or null if unresolvable
     */
    public static @Nullable ScaffoldResult generateSkeleton(@Nullable SchemaType schemaType, String baseIndent) {
        if (schemaType == null) {
            return null;
        }

        var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
        if (resolved == null) {
            return null;
        }

        var sb = new StringBuilder();
        var placeholders = new ArrayList<PlaceholderSpec>();
        var visited = new HashSet<String>();
        scaffoldSchema(resolved, baseIndent, sb, placeholders, visited);

        return new ScaffoldResult(sb.toString(), placeholders);
    }

    private static void scaffoldSchema(
        SchemaType schema,
        String currentIndent,
        StringBuilder sb,
        List<PlaceholderSpec> placeholders,
        Set<String> visited
    ) {
        var resolved = StvnTypeResolver.resolveNominalSchema(schema);
        if (resolved == null) {
            appendPlaceholder("0", sb, placeholders);
            return;
        }

        var keyword = resolved.getTypeKeyword();
        if (keyword != null) {
            var typeName = keyword.getText().trim();
            if (visited.contains(typeName)) {
                appendPlaceholder("0", sb, placeholders);
                return;
            }
        }

        var ctor = resolved.getSchemaConstructor();
        if (ctor == null) {
            if (keyword != null) {
                scaffoldFromKeywordText(keyword.getText().trim(), sb, placeholders);
            } else {
                appendPlaceholder("0", sb, placeholders);
            }
            return;
        }

        // 1. Atomic Primitive Types
        var atomic = ctor.getAtomicType();
        if (atomic != null) {
            scaffoldFromKeywordText(atomic.getText().trim(), sb, placeholders);
            return;
        }

        // 2. Product Types (Tuples)
        var product = ctor.getProductType();
        if (product != null) {
            var children = product.getSchemaTypeList();
            if (children.isEmpty()) {
                sb.append("()");
                return;
            }
            sb.append("(\n");
            var innerIndent = currentIndent + "  ";
            for (var child : children) {
                sb.append(innerIndent);
                scaffoldSchema(child, innerIndent, sb, placeholders, visited);
                sb.append("\n");
            }
            sb.append(currentIndent).append(")");
            return;
        }

        // 3. Collection Types (Map, Seq, Set)
        var collection = ctor.getCollectionType();
        if (collection != null) {
            var firstChild = collection.getFirstChild();
            var collName = firstChild != null ? firstChild.getText().trim() : ":Seq";
            var children = collection.getSchemaTypeList();

            if (collName.startsWith(":Map") || collName.startsWith(":MapInv")) {
                sb.append("{\n");
                var innerIndent = currentIndent + "  ";
                sb.append(innerIndent).append("[ ");
                var keySchema = !children.isEmpty() ? children.get(0) : null;
                var valSchema = children.size() > 1 ? children.get(1) : null;

                if (keySchema != null) {
                    scaffoldSchema(keySchema, innerIndent, sb, placeholders, visited);
                } else {
                    appendPlaceholder("\"placeholder\"", sb, placeholders);
                }
                sb.append(" ");
                if (valSchema != null) {
                    scaffoldSchema(valSchema, innerIndent, sb, placeholders, visited);
                } else {
                    appendPlaceholder("0", sb, placeholders);
                }
                sb.append(" ]\n");
                sb.append(currentIndent).append("}");
            } else {
                // Seq or Set
                sb.append("[\n");
                var innerIndent = currentIndent + "  ";
                sb.append(innerIndent);
                if (!children.isEmpty()) {
                    scaffoldSchema(children.get(0), innerIndent, sb, placeholders, visited);
                } else {
                    appendPlaceholder("0", sb, placeholders);
                }
                sb.append("\n");
                sb.append(currentIndent).append("]");
            }
            return;
        }

        // 4. Sum Types (Option, Either, Union, Enum)
        var sum = ctor.getSumType();
        if (sum != null) {
            if (sum.getNode().findChildByType(StvnTypes.KW_OPTION) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                sb.append("#Some ");
                if (!children.isEmpty()) {
                    scaffoldSchema(children.get(0), currentIndent, sb, placeholders, visited);
                } else {
                    appendPlaceholder("0", sb, placeholders);
                }
                return;
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_EITHER) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                sb.append("#Right ");
                if (children.size() > 1) {
                    scaffoldSchema(children.get(1), currentIndent, sb, placeholders, visited);
                } else if (!children.isEmpty()) {
                    scaffoldSchema(children.get(0), currentIndent, sb, placeholders, visited);
                } else {
                    appendPlaceholder("0", sb, placeholders);
                }
                return;
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_UNION) != null) {
                var children = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                sb.append("#1 ");
                if (!children.isEmpty()) {
                    scaffoldSchema(children.get(0), currentIndent, sb, placeholders, visited);
                } else {
                    appendPlaceholder("0", sb, placeholders);
                }
                return;
            }
            if (sum.getNode().findChildByType(StvnTypes.KW_ENUM) != null) {
                var enumDef = sum.getEnumDef();
                if (enumDef != null && !enumDef.getValueKeywordList().isEmpty()) {
                    var firstKeyword = enumDef.getValueKeywordList().get(0).getText().trim();
                    appendPlaceholder(firstKeyword, sb, placeholders);
                } else {
                    appendPlaceholder("#DEFAULT", sb, placeholders);
                }
                return;
            }
        }

        appendPlaceholder("0", sb, placeholders);
    }

    private static void scaffoldFromKeywordText(String text, StringBuilder sb, List<PlaceholderSpec> placeholders) {
        if (text.startsWith(":Int") || text.startsWith(":Uint") || text.startsWith(":TimeEpoch")) {
            appendPlaceholder("0", sb, placeholders);
        } else if (text.startsWith(":Float")) {
            appendPlaceholder("0.0", sb, placeholders);
        } else if (text.startsWith(":String")) {
            appendPlaceholder("\"placeholder\"", sb, placeholders);
        } else if (text.equals(":Boolean")) {
            appendPlaceholder("#FALSE", sb, placeholders);
        } else if (text.equals(":DateTimeOffset")) {
            appendPlaceholder("\"2026-08-18T18:00:00-05:00\"", sb, placeholders);
        } else if (text.equals(":DateTimeZoned")) {
            appendPlaceholder("\"2026-08-18T18:00:00[America/Chicago]\"", sb, placeholders);
        } else if (text.equals(":DateTimeAudited")) {
            appendPlaceholder("\"2026-08-18T18:00:00-05:00[America/Chicago]\"", sb, placeholders);
        } else {
            appendPlaceholder("0", sb, placeholders);
        }
    }

    private static void appendPlaceholder(String value, StringBuilder sb, List<PlaceholderSpec> placeholders) {
        var start = sb.length();
        sb.append(value);
        var end = sb.length();
        placeholders.add(new PlaceholderSpec(start, end, value));
    }
}
