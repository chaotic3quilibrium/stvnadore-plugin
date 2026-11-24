package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.KeepOnlyTemporalFacetQuickFix;
import org.stvnadore.plugin.validation.RemoveRedundantFacetQuickFix;
import org.stvnadore.plugin.validation.StvnMetadataFacetInsertionUtil;
import org.stvnadore.plugin.validation.StvnMetadataOrderInspection;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.MetadataValue;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;

/**
 * Supplies {@code IntentionPreviewInfo.DIFF}-safe quick-fixes for {@code :DateTime} schema
 * {@link MetadataMap} bound facets ({@code #minIncl}, {@code #maxExcl}, {@code #minExcl}, {@code #maxIncl})
 * across direct {@code :DateTime} constructors and derived nominal {@code :DateTime} aliases.
 */
@NullMarked
public final class StvnDateTimeBoundQuickFixProvider {

    private static final List<DateTimeMode> ALL_MODES = List.of(
        DateTimeMode.OFFSET,
        DateTimeMode.ZONED,
        DateTimeMode.AUDITED
    );

    private StvnDateTimeBoundQuickFixProvider() {
    }

    /**
     * Computes validated {@code :DateTime} bound remediation quick-fixes for {@code entry}.
     *
     * @param entry offending bound {@link MetadataEntry}
     * @param clock clock used for deterministic zone and current-timestamp generation
     * @return ordered list of intention actions (empty when {@code entry} is not a {@code :DateTime} bound)
     */
    public static List<IntentionAction> computeBoundQuickFixes(MetadataEntry entry, Clock clock) {
        String boundKw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
        if (!StvnVocabulary.FACET_KW_MIN_INCL.equals(boundKw)
                && !StvnVocabulary.FACET_KW_MAX_EXCL.equals(boundKw)
                && !StvnVocabulary.FACET_KW_MIN_EXCL.equals(boundKw)
                && !StvnVocabulary.FACET_KW_MAX_INCL.equals(boundKw)) {
            return List.of();
        }
        MetadataMap metaMap = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
        if (metaMap == null) {
            return List.of();
        }
        SchemaType governingSchema = resolveOwnerSchemaType(metaMap);
        if (governingSchema == null || !isDateTimeSchema(governingSchema)) {
            return List.of();
        }

        List<MetadataEntry> localModeEntries = new ArrayList<>(2);
        for (MetadataEntry sib : metaMap.getMetadataEntryList()) {
            if (KeepOnlyTemporalFacetQuickFix.isModeEntry(sib)) {
                localModeEntries.add(sib);
            }
        }
        if (localModeEntries.size() > 1) {
            return List.of();
        }

        DateTimeMode effectiveMode = resolveEffectiveDateTimeMode(metaMap, governingSchema);
        if (effectiveMode == null) {
            return List.of();
        }

        String modeFacetKw = toModeKeyword(effectiveMode);
        MetadataValue metaVal = entry.getMetadataValue();
        String rawSourceLiteral = resolveAuthoredOrConstantLiteralText(metaVal);
        boolean isLowerBound = StvnVocabulary.FACET_KW_MIN_INCL.equals(boundKw)
            || StvnVocabulary.FACET_KW_MIN_EXCL.equals(boundKw);
        Instant validPeerBoundInstant = resolveValidPeerBoundInstant(
            metaMap,
            governingSchema,
            entry,
            isLowerBound,
            effectiveMode,
            clock
        );

        List<IntentionAction> actions = new ArrayList<>(5);
        boolean hasPrimaryRemediation = false;

        // 1. Primary per-bound mode/DST-gap/fenced/epoch conversion (Priority.TOP)
        if (metaVal != null && rawSourceLiteral != null) {
            String converted = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(
                rawSourceLiteral,
                effectiveMode,
                clock
            );
            if (converted != null
                    && !converted.equals(metaVal.getText().trim())
                    && StvnPayloadValueQuickFixProvider.isValidCanonicalDateTimeLiteral(converted, effectiveMode)
                    && isIntervalSound(converted, isLowerBound, validPeerBoundInstant, clock)) {
                actions.add(new RewriteBoundValueQuickFix(
                    entry,
                    "Convert '" + boundKw + "' to " + modeFacetKw + " "
                        + StvnVocabulary.TYPE_DATE_TIME + " " + converted,
                    boundKw,
                    converted,
                    PriorityAction.Priority.TOP
                ));
                hasPrimaryRemediation = true;
            }
        }

        // 2. Multi-bound 1-click conversion when >= 2 bounds in metaMap fail under effectiveMode (Priority.HIGH)
        MultiBoundConversionPlan batchPlan = buildMultiBoundConversionPlan(metaMap, effectiveMode, clock);
        if (batchPlan != null) {
            actions.add(new RewriteAllBoundsQuickFix(
                metaMap,
                modeFacetKw,
                batchPlan.minFacetKw(),
                batchPlan.convertedMin(),
                batchPlan.maxFacetKw(),
                batchPlan.convertedMax()
            ));
            hasPrimaryRemediation = true;
        }

        // 3. Direct :DateTime constructor mode switch (e.g. Change '#zoned' to '#offset') when all bounds match altMode
        if (governingSchema.getSchemaConstructor() != null && localModeEntries.size() == 1) {
            String currentModeKw = KeepOnlyTemporalFacetQuickFix.extractTemporalKeyword(localModeEntries.getFirst());
            for (DateTimeMode altMode : ALL_MODES) {
                if (altMode == effectiveMode) {
                    continue;
                }
                if (allDeclaredBoundsValidUnderMode(metaMap, altMode, clock)) {
                    String altModeKw = toModeKeyword(altMode);
                    actions.add(new SwitchDateTimeSchemaModeQuickFix(metaMap, currentModeKw, altModeKw));
                    hasPrimaryRemediation = true;
                }
            }
        }

        // 4. Remove invalid '#minIncl' / '#maxExcl' facet (Priority.HIGH if unconvertible, else Priority.NORMAL)
        actions.add(RemoveRedundantFacetQuickFix.createRemoveInvalidFacetFix(
            entry,
            boundKw,
            hasPrimaryRemediation ? PriorityAction.Priority.NORMAL : PriorityAction.Priority.HIGH
        ));

        // 5. Interval-sound "Replace '<#bound>' with current #<mode> :DateTime" (Priority.LOW)
        String currentLiteral = formatCurrentDateTimeForMode(effectiveMode, clock);
        if (currentLiteral != null
                && isIntervalSound(currentLiteral, isLowerBound, validPeerBoundInstant, clock)) {
            actions.add(new RewriteBoundValueQuickFix(
                entry,
                "Replace '" + boundKw + "' with current " + modeFacetKw + " " + StvnVocabulary.TYPE_DATE_TIME,
                boundKw,
                currentLiteral,
                PriorityAction.Priority.LOW
            ));
        }

        return actions;
    }

    private static @Nullable String resolveAuthoredOrConstantLiteralText(@Nullable MetadataValue metaVal) {
        if (metaVal == null) {
            return null;
        }
        if (metaVal.getStringLiteral() != null || metaVal.getIntegerLiteral() != null) {
            return metaVal.getText().trim();
        }
        if (metaVal.getValueKeyword() != null) {
            PsiElement resolved = StvnConstantReference.resolveConstantWithContext(metaVal.getValueKeyword());
            ConstantDefinition constDef = org.stvnadore.plugin.psi.StvnPsiUtils.resolveTargetConstantDefinition(resolved);
            if (constDef != null && constDef.getValue() != null) {
                return constDef.getValue().getText().trim();
            }
        }
        return null;
    }

    private static boolean isIntervalSound(
            String candidateLiteral,
            boolean isLowerBound,
            @Nullable Instant validPeerInstant,
            Clock clock
    ) {
        if (validPeerInstant == null) {
            return true;
        }
        Instant candInstant = StvnPayloadValueQuickFixProvider.tryParseDateTimeInstant(candidateLiteral, clock);
        if (candInstant == null) {
            return false;
        }
        return isLowerBound
            ? candInstant.isBefore(validPeerInstant)
            : validPeerInstant.isBefore(candInstant);
    }

    private static @Nullable Instant resolveValidPeerBoundInstant(
            MetadataMap metaMap,
            SchemaType governingSchema,
            MetadataEntry currentEntry,
            boolean currentIsLower,
            DateTimeMode mode,
            Clock clock
    ) {
        for (MetadataEntry sib : metaMap.getMetadataEntryList()) {
            if (sib == currentEntry || sib.getMetadataValue() == null) {
                continue;
            }
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(sib);
            boolean sibIsUpper = StvnVocabulary.FACET_KW_MAX_EXCL.equals(kw)
                || StvnVocabulary.FACET_KW_MAX_INCL.equals(kw);
            boolean sibIsLower = StvnVocabulary.FACET_KW_MIN_INCL.equals(kw)
                || StvnVocabulary.FACET_KW_MIN_EXCL.equals(kw);
            if ((currentIsLower && sibIsUpper) || (!currentIsLower && sibIsLower)) {
                String sibText = resolveAuthoredOrConstantLiteralText(sib.getMetadataValue());
                if (sibText != null && StvnCompiler.validateDateTimeLiteral(sibText, mode).isEmpty()) {
                    return StvnPayloadValueQuickFixProvider.tryParseDateTimeInstant(sibText, clock);
                }
            }
        }
        if (governingSchema.getTypeKeyword() != null) {
            PsiElement target = new StvnTypeReference(governingSchema.getTypeKeyword()).resolve();
            TypeDefinition parentDef = StvnPsiUtils.resolveTargetTypeDefinition(target);
            if (parentDef != null) {
                MetadataMap parentMap = parentDef.getMetadataMap() != null
                    ? parentDef.getMetadataMap()
                    : (parentDef.getSchemaType() != null ? parentDef.getSchemaType().getMetadataMap() : null);
                if (parentMap != null && parentDef.getSchemaType() != null) {
                    return resolveValidPeerBoundInstant(
                        parentMap,
                        parentDef.getSchemaType(),
                        currentEntry,
                        currentIsLower,
                        mode,
                        clock
                    );
                }
            }
        }
        return null;
    }

    private record MultiBoundConversionPlan(
            String minFacetKw,
            String convertedMin,
            String maxFacetKw,
            String convertedMax
    ) {
    }

    private static @Nullable MultiBoundConversionPlan buildMultiBoundConversionPlan(
            MetadataMap metaMap,
            DateTimeMode mode,
            Clock clock
    ) {
        MetadataEntry lowerEntry = null;
        MetadataEntry upperEntry = null;
        int invalidCount = 0;
        for (MetadataEntry e : metaMap.getMetadataEntryList()) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(e);
            if (StvnVocabulary.FACET_KW_MIN_INCL.equals(kw)) {
                lowerEntry = e;
            } else if (StvnVocabulary.FACET_KW_MAX_EXCL.equals(kw)) {
                upperEntry = e;
            } else {
                continue;
            }
            String raw = resolveAuthoredOrConstantLiteralText(e.getMetadataValue());
            if (raw == null || StvnCompiler.validateDateTimeLiteral(raw, mode).isPresent()) {
                invalidCount++;
            }
        }
        if (lowerEntry == null || upperEntry == null || invalidCount < 2) {
            return null;
        }
        String lowerRaw = resolveAuthoredOrConstantLiteralText(lowerEntry.getMetadataValue());
        String upperRaw = resolveAuthoredOrConstantLiteralText(upperEntry.getMetadataValue());
        if (lowerRaw == null || upperRaw == null) {
            return null;
        }
        String convLower = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(lowerRaw, mode, clock);
        String convUpper = StvnPayloadValueQuickFixProvider.tryConvertDateTimeLiteral(upperRaw, mode, clock);
        if (convLower == null || convUpper == null) {
            return null;
        }
        Instant minInst = StvnPayloadValueQuickFixProvider.tryParseDateTimeInstant(convLower, clock);
        Instant maxInst = StvnPayloadValueQuickFixProvider.tryParseDateTimeInstant(convUpper, clock);
        if (minInst == null || maxInst == null || !minInst.isBefore(maxInst)) {
            return null;
        }
        return new MultiBoundConversionPlan(
            StvnVocabulary.FACET_KW_MIN_INCL,
            convLower,
            StvnVocabulary.FACET_KW_MAX_EXCL,
            convUpper
        );
    }

    private static boolean allDeclaredBoundsValidUnderMode(
            MetadataMap metaMap,
            DateTimeMode candidateMode,
            Clock clock
    ) {
        Instant minInst = null;
        Instant maxInst = null;
        int boundCount = 0;
        for (MetadataEntry e : metaMap.getMetadataEntryList()) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(e);
            boolean isLower = StvnVocabulary.FACET_KW_MIN_INCL.equals(kw);
            boolean isUpper = StvnVocabulary.FACET_KW_MAX_EXCL.equals(kw);
            if (!isLower && !isUpper) {
                continue;
            }
            boundCount++;
            MetadataValue mv = e.getMetadataValue();
            if (mv == null || mv.getStringLiteral() == null) {
                return false;
            }
            String raw = mv.getText().trim();
            if (StvnCompiler.validateDateTimeLiteral(raw, candidateMode).isPresent()) {
                return false;
            }
            Instant parsed = StvnPayloadValueQuickFixProvider.tryParseDateTimeInstant(raw, clock);
            if (parsed == null) {
                return false;
            }
            if (isLower) {
                minInst = parsed;
            } else {
                maxInst = parsed;
            }
        }
        if (boundCount == 0) {
            return false;
        }
        return minInst == null || maxInst == null || minInst.isBefore(maxInst);
    }

    private static @Nullable String formatCurrentDateTimeForMode(DateTimeMode mode, Clock clock) {
        ZoneId zone = StvnExemplarValueGenerator.resolveExemplarZoneId(clock.getZone());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone)).truncatedTo(ChronoUnit.SECONDS);
        String local = StvnPayloadValueQuickFixProvider.formatCanonicalLocalDateTime(now.toLocalDateTime());
        String offset = now.getOffset().getTotalSeconds() == 0 ? "Z" : now.getOffset().getId();
        String candidate = switch (mode) {
            case OFFSET -> "\"" + local + offset + "\"";
            case ZONED -> "\"" + local + "[" + zone.getId() + "]\"";
            case AUDITED -> "\"" + local + offset + "[" + zone.getId() + "]\"";
        };
        return StvnPayloadValueQuickFixProvider.isValidCanonicalDateTimeLiteral(candidate, mode) ? candidate : null;
    }

    private static @Nullable SchemaType resolveOwnerSchemaType(MetadataMap metaMap) {
        PsiElement parent = metaMap.getParent();
        return switch (parent) {
            case SchemaType st -> st;
            case TypeDefinition td -> td.getSchemaType();
            case ConstantDefinition cd -> cd.getSchemaType();
            case TypeEntry te -> te.getSchemaType();
            case null, default -> null;
        };
    }

    private static boolean isDateTimeSchema(SchemaType schemaType) {
        SchemaType resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
        SchemaType inspect = resolved != null ? resolved : schemaType;
        return inspect.getSchemaConstructor() != null
            && inspect.getSchemaConstructor().getAtomicType() != null
            && StvnVocabulary.TYPE_DATE_TIME.equals(inspect.getSchemaConstructor().getAtomicType().getText().trim());
    }

    private static @Nullable DateTimeMode resolveEffectiveDateTimeMode(MetadataMap metaMap, SchemaType governingSchema) {
        for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
            DateTimeMode m = fromModeKeyword(kw);
            if (m != null) {
                return m;
            }
        }
        String flag = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(governingSchema);
        if (flag == null) {
            SchemaType resolved = StvnTypeResolver.resolveNominalSchema(governingSchema);
            if (resolved != null) {
                flag = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(resolved);
            }
        }
        return fromModeKeyword(flag);
    }

    private static @Nullable DateTimeMode fromModeKeyword(@Nullable String kw) {
        if (StvnVocabulary.FACET_KW_OFFSET.equals(kw)) {
            return DateTimeMode.OFFSET;
        }
        if (StvnVocabulary.FACET_KW_ZONED.equals(kw)) {
            return DateTimeMode.ZONED;
        }
        if (StvnVocabulary.FACET_KW_AUDITED.equals(kw)) {
            return DateTimeMode.AUDITED;
        }
        return null;
    }

    private static String toModeKeyword(DateTimeMode mode) {
        return switch (mode) {
            case OFFSET -> StvnVocabulary.FACET_KW_OFFSET;
            case ZONED -> StvnVocabulary.FACET_KW_ZONED;
            case AUDITED -> StvnVocabulary.FACET_KW_AUDITED;
        };
    }

    private static final class RewriteBoundValueQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        @SafeFieldForPreview
        private final String actionText;
        @SafeFieldForPreview
        private final String boundKw;
        @SafeFieldForPreview
        private final String replacementLiteral;
        @SafeFieldForPreview
        private final Priority priority;

        RewriteBoundValueQuickFix(
                MetadataEntry entry,
                String actionText,
                String boundKw,
                String replacementLiteral,
                Priority priority
        ) {
            super(entry);
            this.actionText = actionText;
            this.boundKw = boundKw;
            this.replacementLiteral = replacementLiteral;
            this.priority = priority;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            return actionText;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Convert " + StvnVocabulary.TYPE_DATE_TIME + " bound literal";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataEntry targetEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(startElement);
            if (targetEntry == null || !targetEntry.isValid()) {
                return;
            }
            MetadataEntry newEntry = StvnElementFactory.createMetadataEntry(
                project,
                boundKw + " " + replacementLiteral
            );
            targetEntry.replace(newEntry);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RewriteBoundValueQuickFix other)) {
                return false;
            }
            return Objects.equals(
                    RemoveRedundantFacetQuickFix.resolveMetadataEntry(getStartElement()),
                    RemoveRedundantFacetQuickFix.resolveMetadataEntry(other.getStartElement())
                )
                && Objects.equals(actionText, other.actionText)
                && Objects.equals(replacementLiteral, other.replacementLiteral);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                RemoveRedundantFacetQuickFix.resolveMetadataEntry(getStartElement()),
                actionText,
                replacementLiteral
            );
        }
    }

    private static final class RewriteAllBoundsQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        @SafeFieldForPreview
        private final String modeFacetKw;
        @SafeFieldForPreview
        private final String minFacetKw;
        @SafeFieldForPreview
        private final String convertedMin;
        @SafeFieldForPreview
        private final String maxFacetKw;
        @SafeFieldForPreview
        private final String convertedMax;

        RewriteAllBoundsQuickFix(
                MetadataMap metaMap,
                String modeFacetKw,
                String minFacetKw,
                String convertedMin,
                String maxFacetKw,
                String convertedMax
        ) {
            super(metaMap);
            this.modeFacetKw = modeFacetKw;
            this.minFacetKw = minFacetKw;
            this.convertedMin = convertedMin;
            this.maxFacetKw = maxFacetKw;
            this.convertedMax = convertedMax;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Convert all bounds to " + modeFacetKw + " " + StvnVocabulary.TYPE_DATE_TIME;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Convert all " + StvnVocabulary.TYPE_DATE_TIME + " bounds";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap map = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            if (map == null || !map.isValid()) {
                return;
            }
            for (MetadataEntry entry : List.copyOf(map.getMetadataEntryList())) {
                String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
                if (minFacetKw.equals(kw)) {
                    entry.replace(StvnElementFactory.createMetadataEntry(project, minFacetKw + " " + convertedMin));
                } else if (maxFacetKw.equals(kw)) {
                    entry.replace(StvnElementFactory.createMetadataEntry(project, maxFacetKw + " " + convertedMax));
                }
            }
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RewriteAllBoundsQuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(modeFacetKw, other.modeFacetKw);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), modeFacetKw);
        }
    }

    private static final class SwitchDateTimeSchemaModeQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        @SafeFieldForPreview
        private final String currentModeKw;
        @SafeFieldForPreview
        private final String targetModeKw;

        SwitchDateTimeSchemaModeQuickFix(MetadataMap metaMap, String currentModeKw, String targetModeKw) {
            super(metaMap);
            this.currentModeKw = currentModeKw;
            this.targetModeKw = targetModeKw;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @Nls @NotNull String getText() {
            return "Change '" + currentModeKw + "' to '" + targetModeKw + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Change " + StvnVocabulary.TYPE_DATE_TIME + " temporal mode";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap map = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            if (map == null || !map.isValid()) {
                return;
            }
            Document doc = file.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            StvnMetadataFacetInsertionUtil.insertOrReplaceSingleFacetRemoving(
                doc,
                map,
                targetModeKw,
                targetModeKw,
                Set.of(
                    StvnVocabulary.FACET_KW_OFFSET,
                    StvnVocabulary.FACET_KW_ZONED,
                    StvnVocabulary.FACET_KW_AUDITED
                )
            );
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SwitchDateTimeSchemaModeQuickFix other)) {
                return false;
            }
            return Objects.equals(getStartElement(), other.getStartElement())
                && Objects.equals(targetModeKw, other.targetModeKw);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getStartElement(), targetModeKw);
        }
    }
}
