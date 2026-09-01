package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.SchemaType;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Schema-directed code completion contributor pro-offering contextual values,
 * enumeration tags, boolean literals, sum variant constructors, matching constants,
 * and live dynamic temporal/prelude generators inside :body and :defs.
 */
@NullMarked
public final class StvnValueCompletionContributor extends CompletionContributor {

    public StvnValueCompletionContributor() {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(StvnLanguage.INSTANCE),
            new CompletionProvider<>() {
                @Override
                protected void addCompletions(
                    @NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result
                ) {
                    var position = parameters.getPosition();
                    var file = parameters.getOriginalFile();

                    // 1. Resolve expected schema at caret offset
                    var expectedSchema = StvnTypeResolver.resolveExpectedSchemaAtCaret(position);
                    if (expectedSchema == null) {
                        return;
                    }

                    var precedingTags = StvnPsiUtils.findPrecedingTagsInExpression(position);
                    var isUntaggedSite = precedingTags.isEmpty();

                    // Apply branch narrowing based on preceding constructor tags
                    var targetSchema = StvnCompletionTypeResolver.resolveExpectedTypeAtCaret(position, expectedSchema);
                    if (targetSchema == null) {
                        return;
                    }

                    var resolvedNominal = StvnTypeResolver.resolveNominalSchema(targetSchema);
                    var schemaToInspect = resolvedNominal != null ? resolvedNominal : targetSchema;
                    var schemaText = StvnSchemaFormatter.formatCleanSchema(schemaToInspect);
                    var typeLabel = StvnSchemaFormatter.formatCleanSchema(targetSchema);

                    // 2. Enum Variant Suggestions (:Enum [ #A #B ... ])
                    populateEnumVariants(schemaToInspect, typeLabel, result);

                    // 3. Boolean Literal Suggestions (:Boolean)
                    populateBooleanLiterals(schemaToInspect, typeLabel, result);

                    // 4. Sum Variant Constructor Suggestions (:Option, :Either, :Union) for target level
                    populateSumConstructors(schemaToInspect, typeLabel, result);

                    // 5. Recursive Inferred Sum Branch Payloads & Intermediate Constructors
                    if (isUntaggedSite) {
                        var pathways = StvnSumInferenceHelper.collectInferablePathways(schemaToInspect);
                        for (var pathway : pathways) {
                            var pathTargetSchema = pathway.targetSchema();
                            var pathResolved = StvnTypeResolver.resolveNominalSchema(pathTargetSchema);
                            var schemaToPopulate = pathResolved != null ? pathResolved : pathTargetSchema;
                            var cleanSchemaText = StvnSchemaFormatter.formatCleanSchema(schemaToPopulate);
                            var label = pathway.displayLabel();

                            if (pathway.isSumType()) {
                                // Intermediate sum constructor pro-offering
                                populateSumConstructors(schemaToPopulate, label, result);
                            } else {
                                // Terminal inferable leaf payload emission
                                populateEnumVariants(schemaToPopulate, label, result);
                                populateBooleanLiterals(schemaToPopulate, label, result);
                                populateMatchingConstants(file, schemaToPopulate, label, result);
                                populateDynamicGenerators(cleanSchemaText, label, result);
                            }
                        }
                    }

                    // 6. Matching Defined Constants (#CONST in :defs / :include)
                    populateMatchingConstants(file, targetSchema, typeLabel, result);

                    // 7. Dynamic Temporal & Prelude Generators
                    populateDynamicGenerators(schemaText, typeLabel, result);
                }
            }
        );
    }

    private static void populateEnumVariants(
        SchemaType schema,
        String typeLabel,
        CompletionResultSet result
    ) {
        var constructor = schema.getSchemaConstructor();
        if (constructor == null || constructor.getSumType() == null) {
            return;
        }
        var enumDef = constructor.getSumType().getEnumDef();
        if (enumDef == null) {
            return;
        }

        var variants = enumDef.getValueKeywordList();
        int total = variants.size();
        for (int i = 0; i < total; i++) {
            var kw = variants.get(i);
            var tagText = kw.getText().trim();
            var variantInfo = " (" + (i + 1) + "/" + total + ")";
            var element = LookupElementBuilder.create(tagText)
                .withIcon(AllIcons.Nodes.Enum)
                .withTailText(variantInfo, true)
                .withTypeText(typeLabel, true)
                .withBoldness(true);

            result.addElement(PrioritizedLookupElement.withPriority(element, 100.0 - i));
        }
    }

    private static void populateBooleanLiterals(
        SchemaType schema,
        String typeLabel,
        CompletionResultSet result
    ) {
        var schemaText = StvnSchemaFormatter.formatCleanSchema(schema);
        if (!schemaText.equals(":Boolean") && !schemaText.equals(":Bool")) {
            return;
        }

        result.addElement(PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create("#TRUE")
                .withIcon(AllIcons.Nodes.Variable)
                .withTailText(" (boolean true)", true)
                .withTypeText(typeLabel, true)
                .withBoldness(true),
            95.0
        ));

        result.addElement(PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create("#FALSE")
                .withIcon(AllIcons.Nodes.Variable)
                .withTailText(" (boolean false)", true)
                .withTypeText(typeLabel, true)
                .withBoldness(true),
            94.0
        ));

        result.addElement(PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create("#T")
                .withIcon(AllIcons.Nodes.Variable)
                .withTailText(" (short-form true)", true)
                .withTypeText(typeLabel, true),
            85.0
        ));

        result.addElement(PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create("#F")
                .withIcon(AllIcons.Nodes.Variable)
                .withTailText(" (short-form false)", true)
                .withTypeText(typeLabel, true),
            84.0
        ));
    }

    private static void populateSumConstructors(
        SchemaType schema,
        String typeLabel,
        CompletionResultSet result
    ) {
        var constructor = schema.getSchemaConstructor();
        if (constructor == null || constructor.getSumType() == null) {
            return;
        }
        var sumType = constructor.getSumType();
        var innerSchemas = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);

        if (sumType.getText().startsWith(":Option")) {
            var innerType = !innerSchemas.isEmpty() ? StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(0)) : ":Value";
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#Some ")
                    .withPresentableText("#Some")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (-> " + innerType + ")", true)
                    .withTypeText(typeLabel, true)
                    .withBoldness(true)
                    .withInsertHandler(StvnSumConstructorInsertionHandler.INSTANCE),
                90.0
            ));
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#S ")
                    .withPresentableText("#S")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (-> " + innerType + ")", true)
                    .withTypeText(typeLabel, true)
                    .withInsertHandler(StvnSumConstructorInsertionHandler.INSTANCE),
                80.0
            ));
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#None")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (empty option)", true)
                    .withTypeText(typeLabel, true)
                    .withBoldness(true),
                89.0
            ));
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#N")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (short empty option)", true)
                    .withTypeText(typeLabel, true),
                79.0
            ));
        } else if (sumType.getText().startsWith(":Either")) {
            var leftType = !innerSchemas.isEmpty() ? StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(0)) : ":Left";
            var rightType = innerSchemas.size() > 1 ? StvnSchemaFormatter.formatCleanSchema(innerSchemas.get(1)) : ":Right";

            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#Right ")
                    .withPresentableText("#Right")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (-> " + rightType + ")", true)
                    .withTypeText(typeLabel, true)
                    .withBoldness(true)
                    .withInsertHandler(StvnSumConstructorInsertionHandler.INSTANCE),
                90.0
            ));
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#R ")
                    .withPresentableText("#R")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (-> " + rightType + ")", true)
                    .withTypeText(typeLabel, true)
                    .withInsertHandler(StvnSumConstructorInsertionHandler.INSTANCE),
                80.0
            ));
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#Left ")
                    .withPresentableText("#Left")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (-> " + leftType + ")", true)
                    .withTypeText(typeLabel, true)
                    .withBoldness(true)
                    .withInsertHandler(StvnSumConstructorInsertionHandler.INSTANCE),
                89.0
            ));
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("#L ")
                    .withPresentableText("#L")
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (-> " + leftType + ")", true)
                    .withTypeText(typeLabel, true)
                    .withInsertHandler(StvnSumConstructorInsertionHandler.INSTANCE),
                79.0
            ));
        } else if (sumType.getText().startsWith(":Union")) {
            for (int i = 0; i < innerSchemas.size(); i++) {
                var branchSchema = innerSchemas.get(i);
                var branchType = StvnSchemaFormatter.formatCleanSchema(branchSchema);
                var tag = "#" + (i + 1);
                result.addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(tag + " ")
                        .withPresentableText(tag)
                        .withIcon(AllIcons.Nodes.Tag)
                        .withTailText(" (-> " + branchType + ")", true)
                        .withTypeText(typeLabel, true)
                        .withBoldness(true)
                        .withInsertHandler(StvnSumConstructorInsertionHandler.INSTANCE),
                    90.0 - i
                ));
            }
        }
    }

    private static void populateMatchingConstants(
        PsiFile file,
        SchemaType expectedSchema,
        String typeLabel,
        CompletionResultSet result
    ) {
        var constants = StvnTypeResolver.findAssignableConstants(file, expectedSchema);
        for (var constDef : constants) {
            var kw = constDef.getValueKeyword();
            if (kw == null) continue;
            var constName = kw.getText().trim();
            var constSchema = constDef.getSchemaType();
            var constSchemaText = constSchema != null ? StvnSchemaFormatter.formatCleanSchema(constSchema) : "";
            var constValue = constDef.getValue();
            var constValText = constValue != null ? constValue.getText().trim() : "";

            var tail = (!constSchemaText.isEmpty() ? " :" + constSchemaText : "") + (!constValText.isEmpty() ? " = " + constValText : "");
            var element = LookupElementBuilder.create(constName)
                .withIcon(AllIcons.Nodes.Constant)
                .withTailText(tail, true)
                .withTypeText(typeLabel, true)
                .withBoldness(true);

            result.addElement(PrioritizedLookupElement.withPriority(element, 75.0));
        }
    }

    private static void populateDynamicGenerators(
        String schemaText,
        String typeLabel,
        CompletionResultSet result
    ) {
        var zone = ZoneId.systemDefault();
        var nowZoned = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
        var nowOffset = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        var nowLocal = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        var zoneId = zone.getId();

        if (schemaText.equals(":DateTimeAudited") || typeLabel.startsWith(":DateTimeAudited")) {
            var offsetStr = nowZoned.getOffset().getId();
            if (offsetStr.equals("Z")) offsetStr = "+00:00";
            var timestamp = nowLocal.toString() + offsetStr + "[" + zoneId + "]";
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("\"" + timestamp + "\"")
                    .withPresentableText("\"" + timestamp + "\"")
                    .withIcon(AllIcons.Nodes.Function)
                    .withTailText(" (current audited timestamp)", true)
                    .withTypeText(typeLabel, true)
                    .withInsertHandler(StvnQuoteInsertionHandler.INSTANCE),
                98.0
            ));
        } else if (schemaText.equals(":DateTimeOffset") || typeLabel.startsWith(":DateTimeOffset")) {
            var timestamp = nowOffset.toString();
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("\"" + timestamp + "\"")
                    .withPresentableText("\"" + timestamp + "\"")
                    .withIcon(AllIcons.Nodes.Function)
                    .withTailText(" (current physical instant)", true)
                    .withTypeText(typeLabel, true)
                    .withInsertHandler(StvnQuoteInsertionHandler.INSTANCE),
                98.0
            ));
        } else if (schemaText.equals(":DateTimeZoned") || typeLabel.startsWith(":DateTimeZoned")) {
            var timestamp = nowLocal.toString() + "[" + zoneId + "]";
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("\"" + timestamp + "\"")
                    .withPresentableText("\"" + timestamp + "\"")
                    .withIcon(AllIcons.Nodes.Function)
                    .withTailText(" (current civil schedule)", true)
                    .withTypeText(typeLabel, true)
                    .withInsertHandler(StvnQuoteInsertionHandler.INSTANCE),
                98.0
            ));
        } else if (schemaText.equals(":TimeEpochMs") || typeLabel.startsWith(":TimeEpochMs")) {
            var epochMs = String.valueOf(System.currentTimeMillis());
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(epochMs)
                    .withIcon(AllIcons.Nodes.Function)
                    .withTailText(" (current epoch ms)", true)
                    .withTypeText(typeLabel, true),
                95.0
            ));
        } else if (schemaText.equals(":TimeEpochS") || typeLabel.startsWith(":TimeEpochS")) {
            var epochS = String.valueOf(System.currentTimeMillis() / 1000L);
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(epochS)
                    .withIcon(AllIcons.Nodes.Function)
                    .withTailText(" (current epoch s)", true)
                    .withTypeText(typeLabel, true),
                95.0
            ));
        } else if (schemaText.equals(":Uuid") || schemaText.equals(":UUID") || typeLabel.startsWith(":Uuid") || typeLabel.toLowerCase().startsWith(":uuid")) {
            var uuidStr = UUID.randomUUID().toString();
            result.addElement(PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create("\"" + uuidStr + "\"")
                    .withPresentableText("\"" + uuidStr + "\"")
                    .withIcon(AllIcons.Nodes.Function)
                    .withTailText(" (random canonical UUID)", true)
                    .withTypeText(typeLabel, true)
                    .withInsertHandler(StvnQuoteInsertionHandler.INSTANCE),
                96.0
            ));
        }
    }
}