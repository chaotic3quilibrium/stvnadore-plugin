package org.stvnadore.plugin.completion;

import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.SchemaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes inferable branches and recursive pathways for algebraic sum types (:Option, :Either, :Union)
 * adhering strictly to STVN Language Specification §8.1 (Rules A, B, C, D, E, H).
 */
@NullMarked
public final class StvnSumInferenceHelper {

    /**
     * Represents a single inferable branch of an algebraic sum type.
     *
     * @param schema branch target schema type
     * @param constructorTag constructor literal prefix (e.g. "#Some", "#Right", "#1")
     * @param typeLabel formatted display type label
     */
    public record InferableBranch(
        SchemaType schema,
        String constructorTag,
        String typeLabel
    ) {}

    /**
     * Represents an inferred resolution pathway traversing nested sum types.
     *
     * @param targetSchema destination leaf schema type
     * @param tagPath sequence of required constructor tags
     * @param displayLabel user-facing display label
     * @param isSumType true if target is itself a sum type
     */
    public record InferredPathway(
        SchemaType targetSchema,
        List<String> tagPath,
        String displayLabel,
        boolean isSumType
    ) {}

    private StvnSumInferenceHelper() {}

    /**
     * Recursively traverses all soundly inferable branches starting from rootSchema.
     *
     * @param rootSchema The root schema to evaluate.
     * @return Ordered list of all reachable inferable pathways.
     */
    public static List<InferredPathway> collectInferablePathways(SchemaType rootSchema) {
        var results = new ArrayList<InferredPathway>();
        collectRecursively(rootSchema, new ArrayList<>(), results, 0);
        return List.copyOf(results);
    }

    private static void collectRecursively(
            SchemaType currentSchema,
            List<String> accumulatedTags,
            List<InferredPathway> sink,
            int depth
    ) {
        if (depth > 8) {
            return; // Safety recursion guard
        }

        var immediateBranches = getInferableBranches(currentSchema);
        for (var branch : immediateBranches) {
            var nextPath = new ArrayList<>(accumulatedTags);
            nextPath.add(branch.constructorTag());

            var branchResolved = StvnTypeResolver.resolveNominalSchema(branch.schema());
            var targetSchema = branchResolved != null ? branchResolved : branch.schema();
            var isSum = isSumType(targetSchema);
            var tagChainStr = String.join(" ", nextPath);
            var label = StvnSchemaFormatter.formatCleanSchema(targetSchema) + " (inferred " + tagChainStr + ")";

            sink.add(new InferredPathway(targetSchema, nextPath, label, isSum));

            if (isSum) {
                collectRecursively(targetSchema, nextPath, sink, depth + 1);
            }
        }
    }

    /**
     * Checks if the given schema resolves to an algebraic sum type (:Option, :Either, :Union).
     *
     * @param schema target schema type to inspect
     * @return true if schema is an algebraic sum type
     */
    public static boolean isSumType(SchemaType schema) {
        var resolved = StvnTypeResolver.resolveNominalSchema(schema);
        var toInspect = resolved != null ? resolved : schema;
        var constructor = toInspect.getSchemaConstructor();
        return constructor != null && constructor.getSumType() != null;
    }

    /**
     * Computes the immediate list of branches eligible for untagged implicit inference.
     *
     * @param rootSchema root sum schema type to evaluate
     * @return list of inferable branch descriptors
     */
    public static List<InferableBranch> getInferableBranches(SchemaType rootSchema) {
        var resolved = StvnTypeResolver.resolveNominalSchema(rootSchema);
        var schemaToInspect = resolved != null ? resolved : rootSchema;
        var constructor = schemaToInspect.getSchemaConstructor();
        if (constructor == null || constructor.getSumType() == null) {
            return List.of();
        }

        var sumType = constructor.getSumType();
        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
        var results = new ArrayList<InferableBranch>();

        if (isConstructorMatch(sumType.getText(), StvnVocabulary.TYPE_OPTION)) {
            // Rule A: Option payload is inferable as #Some
            if (!innerSchemas.isEmpty()) {
                var payload = innerSchemas.get(0);
                results.add(new InferableBranch(payload, StvnVocabulary.VAL_SOME, StvnSchemaFormatter.formatCleanSchema(payload)));
            }
        } else if (isConstructorMatch(sumType.getText(), StvnVocabulary.TYPE_EITHER)) {
            // Rule B & Rule E: Right branch is inferable if disjoint; Left is NEVER inferable untagged
            if (innerSchemas.size() > 1) {
                var left = innerSchemas.get(0);
                var right = innerSchemas.get(1);
                if (areDisjoint(left, right)) {
                    results.add(new InferableBranch(right, StvnVocabulary.VAL_RIGHT, StvnSchemaFormatter.formatCleanSchema(right)));
                }
            }
        } else if (isConstructorMatch(sumType.getText(), StvnVocabulary.TYPE_UNION)) {
            // Rule C & Rule D: Branch Tk is inferable if pairwise disjoint from all other branches
            for (int i = 0; i < innerSchemas.size(); i++) {
                var branch = innerSchemas.get(i);
                boolean disjointFromAll = true;
                for (int j = 0; j < innerSchemas.size(); j++) {
                    if (i != j && !areDisjoint(branch, innerSchemas.get(j))) {
                        disjointFromAll = false;
                        break;
                    }
                }
                if (disjointFromAll) {
                    var branchTag = StvnVocabulary.SIGIL_VALUE + (i + 1);
                    results.add(new InferableBranch(branch, branchTag, StvnSchemaFormatter.formatCleanSchema(branch)));
                }
            }
        }

        return List.copyOf(results);
    }

    /**
     * Evaluates structural and lexical disjointness between two schema types.
     *
     * @param a first schema type
     * @param b second schema type
     * @return true if types are disjoint
     */
    public static boolean areDisjoint(SchemaType a, SchemaType b) {
        var resolvedA = StvnTypeResolver.resolveNominalSchema(a);
        var resolvedB = StvnTypeResolver.resolveNominalSchema(b);
        var typeA = StvnSchemaFormatter.formatCleanSchema(resolvedA != null ? resolvedA : a);
        var typeB = StvnSchemaFormatter.formatCleanSchema(resolvedB != null ? resolvedB : b);

        if (typeA.equals(typeB)) {
            return false;
        }

        boolean isBoolA = isBoolean(typeA);
        boolean isBoolB = isBoolean(typeB);
        if (isBoolA && isBoolB) {
            return false;
        }

        boolean isNumericA = isNumeric(typeA);
        boolean isNumericB = isNumeric(typeB);
        if (isNumericA && isNumericB) {
            return false;
        }

        boolean isStringA = isString(typeA);
        boolean isStringB = isString(typeB);
        if (isStringA && isStringB) {
            return false;
        }

        boolean isListA = isList(typeA);
        boolean isListB = isList(typeB);
        if (isListA && isListB) {
            return false;
        }

        boolean isMapA = isMap(typeA);
        boolean isMapB = isMap(typeB);
        if (isMapA && isMapB) {
            return false;
        }

        return true;
    }

    private static boolean isBoolean(String type) {
        return type.equals(StvnVocabulary.TYPE_BOOLEAN);
    }

    private static boolean isNumeric(String type) {
        if (type.equals(StvnVocabulary.TYPE_INT) || type.equals(StvnVocabulary.TYPE_FLOAT)) {
            return true;
        }
        if (type.startsWith(StvnVocabulary.TYPE_INT)) {
            return type.substring(StvnVocabulary.TYPE_INT.length()).matches("\\d+");
        }
        if (type.startsWith(StvnVocabulary.TYPE_FLOAT)) {
            return type.substring(StvnVocabulary.TYPE_FLOAT.length()).matches("\\d+");
        }
        if (type.startsWith(":Uint")) {
            return type.substring(":Uint".length()).matches("\\d*");
        }
        return false;
    }

    private static boolean isString(String type) {
        if (type.equals(StvnVocabulary.TYPE_STRING)) {
            return true;
        }
        if (type.startsWith(StvnVocabulary.TYPE_STRING) && type.length() > StvnVocabulary.TYPE_STRING.length()) {
            char nextChar = type.charAt(StvnVocabulary.TYPE_STRING.length());
            return Character.isDigit(nextChar) || type.startsWith(":StringFixed") || type.startsWith(":StringNonEmpty");
        }
        return false;
    }

    private static boolean isList(String type) {
        return isConstructorMatch(type, StvnVocabulary.TYPE_SEQ) || isConstructorMatch(type, StvnVocabulary.TYPE_SET);
    }

    private static boolean isMap(String type) {
        return isConstructorMatch(type, StvnVocabulary.TYPE_MAP);
    }

    private static boolean isConstructorMatch(String type, String constructor) {
        if (type.equals(constructor)) {
            return true;
        }
        if (type.startsWith(constructor)) {
            char next = type.charAt(constructor.length());
            return next == '(' || Character.isWhitespace(next);
        }
        return false;
    }
}