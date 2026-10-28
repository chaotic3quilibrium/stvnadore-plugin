package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.StvnStringCapacityInspection;
import org.stvnadore.psi.*;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Intelligent code completion contributor for metadata facet blocks ({ ... })
 * and pre-schema whitespace auto-wrapping completion.
 * <p>
 * Enforces Core Rule W003 and Minimal Canonical Forms (VOP Invariant 1):
 * 1. Suppresses '#minIncl 0' on unsigned :Int domains (proposes '#minIncl 1').
 * 2. Suppresses '#minSize 0' on strings and collections (proposes '#minSize 1').
 * 3. Suppresses redundant default trait assertions ('#equatable #TRUE', '#comparable #TRUE' on primitives).
 * 4. Filters out duplicate facets already declared in the local metadata block (§ 5.1.4).
 * 5. Auto-wraps facets in '{ <facet> }' when completion is invoked in pre-schema whitespace.
 */
@NullMarked
public final class StvnMetadataCompletionContributor extends CompletionContributor {

  private static final Pattern FACET_TAG_PATTERN = Pattern.compile("#[a-zA-Z0-9_]+");

  public StvnMetadataCompletionContributor() {
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

            // Rule E038: Check if caret is inside :meta { #domain "<caret>" }
            var domainFacet = PsiTreeUtil.getParentOfType(position, MetaDomainFacet.class);
            if (domainFacet == null) {
              var prev = PsiTreeUtil.prevVisibleLeaf(position);
              if (prev != null && "#domain".equals(prev.getText())) {
                domainFacet = PsiTreeUtil.getParentOfType(prev, MetaDomainFacet.class);
              }
            }
            if (domainFacet != null || isCaretInDomainFacet(position)) {
              var project = parameters.getOriginalFile().getProject();
              var candidateDomains = collectWorkspaceDomains(project);
              for (var domain : candidateDomains) {
                // Strictly suppress reserved system domains (§ 3.10.1 Rule 6)
                if (!"stvn_core".equals(domain) && !"stvn_ir".equals(domain) && !"stvn_cas".equals(domain)) {
                  result.addElement(LookupElementBuilder.create(domain)
                      .withIcon(AllIcons.Nodes.Artifact)
                      .withTypeText("Document Domain", true));
                }
              }
              return;
            }

            // Suppress inside comments and enum variant lists
            if (position instanceof PsiComment
                || PsiTreeUtil.getParentOfType(position, PsiComment.class) != null
                || PsiTreeUtil.getParentOfType(position, VariantList.class) != null) {
              return;
            }

            var metaMap = PsiTreeUtil.getParentOfType(position, MetadataMap.class);
            boolean isAutoWrap = false;
            String targetBaseType = "";
            boolean isUnsigned = false;
            boolean isExact = false;
            Set<String> declaredFacets = new HashSet<>();
            InsertHandler<LookupElement> insertHandler;
            SchemaType schemaType = null;

            if (metaMap == null) {
              // Branch B: Pre-Schema Whitespace Auto-Wrapping Completion
              var preSchemaContext = resolvePreSchemaContext(position, parameters);
              if (preSchemaContext == null) {
                return;
              }
              isAutoWrap = true;
              targetBaseType = preSchemaContext.targetBaseType();
              insertHandler = StvnMetadataFacetInsertHandler.AUTO_WRAPPING_INSTANCE;
            } else {
              // Branch A: In-Map Facet Completion
              insertHandler = StvnMetadataFacetInsertHandler.INSTANCE;
              schemaType = PsiTreeUtil.getParentOfType(metaMap, SchemaType.class);
              if (schemaType == null) {
                var typeDef = PsiTreeUtil.getParentOfType(metaMap, TypeDefinition.class);
                if (typeDef != null) {
                  schemaType = typeDef.getSchemaType();
                }
              }

              targetBaseType = resolveTargetBaseType(schemaType);
              isUnsigned = isUnsignedActive(metaMap, schemaType);
              isExact = isExactActive(metaMap, schemaType);

              // Infer targetBaseType as :Float when in-flight definition declares #exact
              if (targetBaseType.isEmpty() && isExact) {
                targetBaseType = StvnVocabulary.TYPE_FLOAT;
              }

              // Track facets already present to eliminate duplicate facet proposals (§ 5.1.4)
              for (var entry : metaMap.getMetadataEntryList()) {
                var root = extractRootFacetKeyword(entry);
                if (root != null) {
                  declaredFacets.add(root);
                }
              }

              // Lexical deduplication fallback (§ 5.1.4): scan sanitized text for unparsed or in-flight facet tags
              var sanitizedMetaText = StvnStringCapacityInspection.sanitizeMetadataText(metaMap.getText());
              var tagMatcher = FACET_TAG_PATTERN.matcher(sanitizedMetaText);
              while (tagMatcher.find()) {
                declaredFacets.add(tagMatcher.group());
              }
            }

            var prefix = result.getPrefixMatcher().getPrefix();
            var matcher = result.getPrefixMatcher();
            if (!prefix.isEmpty() && !prefix.startsWith(StvnVocabulary.SIGIL_VALUE)) {
              matcher = matcher.cloneWithPrefix(StvnVocabulary.SIGIL_VALUE + prefix);
            }
            var targetResult = result.withPrefixMatcher(matcher);

            // Numeric Primitives (:Int)
            if (StvnVocabulary.TYPE_INT.equals(targetBaseType) || (!isAutoWrap && targetBaseType.isEmpty())) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_UNSIGNED)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_UNSIGNED,
                    isAutoWrap,
                    "Definition Facet",
                    " (bounds domain to [0, ∞))",
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SIZE)) {
                // Rule W003: Propose non-default integer bit-widths (valid widenings and narrowings)
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SIZE + " 64",
                    isAutoWrap,
                    "Storage Width",
                    null,
                    insertHandler
                ));
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SIZE + " 16",
                    isAutoWrap,
                    "Storage Width",
                    null,
                    insertHandler
                ));
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SIZE + " 8",
                    isAutoWrap,
                    "Storage Width",
                    null,
                    insertHandler
                ));
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SIZE + " 128",
                    isAutoWrap,
                    "Storage Width",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_INCL)) {
                if (isUnsigned) {
                  // Rule W003: Suppress '#minIncl 0' on unsigned :Int; propose '#minIncl 1'
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_MIN_INCL + " 1",
                      isAutoWrap,
                      "Lower Bound",
                      " (domain: [1, ∞))",
                      insertHandler
                  ));
                } else {
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_MIN_INCL + " 0",
                      isAutoWrap,
                      "Lower Bound",
                      " (domain: [0, ∞))",
                      insertHandler
                  ));
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_MIN_INCL + " 1",
                      isAutoWrap,
                      "Lower Bound",
                      " (domain: [1, ∞))",
                      insertHandler
                  ));
                }
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MAX_EXCL)) {
                int bitWidth = resolveActiveBitWidth(metaMap, schemaType);
                if (bitWidth < 1) {
                  bitWidth = 32;
                }
                int exponent = isUnsigned ? bitWidth : bitWidth - 1;
                if (exponent < 0) {
                  exponent = 0;
                }
                BigInteger vMax = BigInteger.ONE.shiftLeft(exponent);
                BigInteger templateVal;
                if (vMax.compareTo(BigInteger.valueOf(100)) > 0) {
                  templateVal = BigInteger.valueOf(100);
                } else if (vMax.compareTo(BigInteger.TEN) > 0) {
                  templateVal = BigInteger.TEN;
                } else {
                  templateVal = vMax.subtract(BigInteger.ONE);
                }
                String maxExclValue = templateVal.toString();
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_MAX_EXCL + " " + maxExclValue,
                    isAutoWrap,
                    "Exclusive Upper Bound",
                    null,
                    insertHandler
                ));
              }
              // Intrinsic trait override opt-outs only (Rule W003 suppresses #TRUE)
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
            }

            // Strings (:String)
            if (StvnVocabulary.TYPE_STRING.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                // Rule W003: Suppress '#minSize 0'; propose non-empty '#minSize 1'
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_MIN_SIZE + " 1",
                    isAutoWrap,
                    "Non-Empty Bound",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MAX_SIZE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_MAX_SIZE + " 256",
                    isAutoWrap,
                    "Max Capacity",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_REGEX)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_REGEX + " \"^[A-Za-z0-9]+$\"",
                    isAutoWrap,
                    "Regex Constraint",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_PRESERVE_INDENT + " #TRUE",
                    isAutoWrap,
                    "Formatting Trait",
                    null,
                    insertHandler
                ));
              }
              // Intrinsic trait override opt-outs only (Rule W003 suppresses #TRUE)
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
            }

            // Collections (:Seq, :Set, :Map)
            if (isCollectionType(targetBaseType)) {
              if (StvnVocabulary.TYPE_MAP.equals(targetBaseType) && !declaredFacets.contains(StvnVocabulary.FACET_KW_INVERTIBLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_INVERTIBLE,
                    isAutoWrap,
                    "Bijective Map",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                // Rule W003: Suppress '#minSize 0'; propose '#minSize 1'
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_MIN_SIZE + " 1",
                    isAutoWrap,
                    "Container Bound",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MAX_SIZE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_MAX_SIZE + " 100",
                    isAutoWrap,
                    "Container Capacity",
                    null,
                    insertHandler
                ));
              }
              addTraitOverrideCompletions(targetResult, declaredFacets, isAutoWrap, insertHandler);
            }

            // Product Types (:Tuple)
            if (StvnVocabulary.TYPE_TUPLE.equals(targetBaseType)) {
              addTraitOverrideCompletions(targetResult, declaredFacets, isAutoWrap, insertHandler);
            }

            // Sum Types (:Union, :Either, :Option)
            if (isSumConstructorType(targetBaseType)) {
              addTraitOverrideCompletions(targetResult, declaredFacets, isAutoWrap, insertHandler);
            }

            // Enum Constructor (:Enum)
            if (StvnVocabulary.TYPE_ENUM.equals(targetBaseType)) {
              addTraitOverrideCompletions(targetResult, declaredFacets, isAutoWrap, insertHandler);
            }

            // Floating-Point (:Float)
            if (StvnVocabulary.TYPE_FLOAT.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EXACT) && !isExact) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_EXACT,
                    isAutoWrap,
                    "Exact Decimal",
                    null,
                    insertHandler
                ));
                // Rule E008: Strictly suppress #maxSize on continuous :Float
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SIZE)) {
                  // Rule W003: Suppress #size 64 on continuous :Float; propose single-precision #size 32
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_SIZE + " 32",
                      isAutoWrap,
                      "Storage Width",
                      null,
                      insertHandler
                  ));
                }
                // Continuous float allows opt-in equality (suppress #FALSE and comparable #TRUE)
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_EQUATABLE + " #TRUE",
                      isAutoWrap,
                      "Opt-In Equality",
                      null,
                      insertHandler
                  ));
                }
              } else {
                // Exact decimal float: propose domain-specific #maxSize narrowings (§ 5.1.3)
                // Rule W003: Strictly suppress #maxSize 16777216 (intrinsic default ceiling tautology)
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MAX_SIZE)) {
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_MAX_SIZE + " 18",
                      isAutoWrap,
                      "Mantissa Precision",
                      " (Significant Digits: Currency / Int64 decimal)",
                      insertHandler
                  ));
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_MAX_SIZE + " 38",
                      isAutoWrap,
                      "Mantissa Precision",
                      " (Significant Digits: Financial / Int128 decimal)",
                      insertHandler
                  ));
                }
                // Exact decimal float: suppress #TRUE (intrinsically equatable and comparable)
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE",
                      isAutoWrap,
                      "Trait Override",
                      null,
                      insertHandler
                  ));
                }
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                  targetResult.addElement(createFacetLookup(
                      StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE",
                      isAutoWrap,
                      "Trait Override",
                      null,
                      insertHandler
                  ));
                }
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_INCL)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_MIN_INCL + " 0.0",
                    isAutoWrap,
                    "Lower Bound",
                    null,
                    insertHandler
                ));
              }
            }

            // Boolean Domain (:Boolean)
            if (StvnVocabulary.TYPE_BOOLEAN.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
            }

            // Date-Time Domain (:DateTime)
            if (StvnVocabulary.TYPE_DATE_TIME.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_ZONED)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_ZONED,
                    isAutoWrap,
                    "Temporal Trait",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_AUDITED)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_AUDITED,
                    isAutoWrap,
                    "Temporal Trait",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_OFFSET)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_OFFSET,
                    isAutoWrap,
                    "Temporal Trait",
                    null,
                    insertHandler
                ));
              }
              // Intrinsic trait override opt-outs only (Rule W003 suppresses #TRUE on primitives)
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
            }

            // Epoch Millis / Duration Domain (:TimeEpoch)
            if (StvnVocabulary.TYPE_TIME_EPOCH.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SCALE_S)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SCALE_S,
                    isAutoWrap,
                    "Temporal Scale",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SCALE_MS)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SCALE_MS,
                    isAutoWrap,
                    "Temporal Scale",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SCALE_US)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SCALE_US,
                    isAutoWrap,
                    "Temporal Scale",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SCALE_NS)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_SCALE_NS,
                    isAutoWrap,
                    "Temporal Scale",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_INCL)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_MIN_INCL + " 0",
                    isAutoWrap,
                    "Lower Bound",
                    " (domain: [0, ∞))",
                    insertHandler
                ));
              }
              // Intrinsic trait override opt-outs only (Rule W003 suppresses #TRUE on primitives)
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                targetResult.addElement(createFacetLookup(
                    StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE",
                    isAutoWrap,
                    "Trait Override",
                    null,
                    insertHandler
                ));
              }
            }
          }
        }
    );
  }

  private static LookupElementBuilder createFacetLookup(
      String lookupString,
      boolean isAutoWrap,
      String defaultTypeText,
      @Nullable String tailText,
      InsertHandler<LookupElement> insertHandler
  ) {
    var builder = LookupElementBuilder.create(lookupString)
        .withIcon(AllIcons.Nodes.Property)
        .withInsertHandler(insertHandler)
        .withTypeText(isAutoWrap ? "Wrap in { ... }" : defaultTypeText, true);
    if (tailText != null) {
      builder = builder.withTailText(tailText, true);
    }
    return builder;
  }

  private record PreSchemaContext(String targetBaseType) {}

  private static @Nullable PreSchemaContext resolvePreSchemaContext(
      PsiElement position,
      CompletionParameters parameters
  ) {
    var prevLeaf = PsiTreeUtil.prevVisibleLeaf(position);
    var nextLeaf = PsiTreeUtil.nextVisibleLeaf(position);

    if (prevLeaf != null && "}".equals(prevLeaf.getText())) {
      return null; // Metadata map already exists immediately before
    }
    if (nextLeaf == null) {
      return null;
    }

    var nextText = nextLeaf.getText().trim();
    if (!nextText.startsWith(":")) {
      return null;
    }

    // Validate that preceding context is a valid schema site
    boolean isValidPreceding = false;
    if (prevLeaf != null) {
      var prevText = prevLeaf.getText().trim();
      if (prevText.startsWith(":") || "(".equals(prevText) || ")".equals(prevText) || StvnVocabulary.KEYWORD_TYPE.equals(prevText)) {
        isValidPreceding = true;
      }
    }
    if (!isValidPreceding) {
      var typeDef = PsiTreeUtil.getParentOfType(position, TypeDefinition.class);
      if (typeDef == null && parameters.getOriginalPosition() != null) {
        typeDef = PsiTreeUtil.getParentOfType(parameters.getOriginalPosition(), TypeDefinition.class);
      }
      if (typeDef != null) {
        isValidPreceding = true;
      }
    }
    if (!isValidPreceding) {
      return null;
    }

    String targetBaseType = resolveTargetBaseTypeFromKeyword(nextText);
    if (targetBaseType.isEmpty()) {
      var baseName = nextText.replaceAll("[^A-Za-z0-9_:]", "");
      var file = position.getContainingFile();
      if (file != null) {
        var resolved = StvnTypeReference.resolveTypeInFile(file, baseName, new HashSet<>());
        if (resolved != null) {
          var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
          if (parentDef != null && parentDef.getSchemaType() != null) {
            targetBaseType = resolveTargetBaseType(parentDef.getSchemaType());
          }
        }
      }
    }
    if (targetBaseType.isEmpty()) {
      return null;
    }
    return new PreSchemaContext(targetBaseType);
  }

  private static String resolveTargetBaseTypeFromKeyword(String keyword) {
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_TUPLE)) return StvnVocabulary.TYPE_TUPLE;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_SEQ)) return StvnVocabulary.TYPE_SEQ;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_SET)) return StvnVocabulary.TYPE_SET;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_MAP)) return StvnVocabulary.TYPE_MAP;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_UNION)) return StvnVocabulary.TYPE_UNION;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_EITHER)) return StvnVocabulary.TYPE_EITHER;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_OPTION)) return StvnVocabulary.TYPE_OPTION;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_ENUM)) return StvnVocabulary.TYPE_ENUM;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_INT)) return StvnVocabulary.TYPE_INT;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_STRING)) return StvnVocabulary.TYPE_STRING;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_FLOAT)) return StvnVocabulary.TYPE_FLOAT;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_BOOLEAN)) return StvnVocabulary.TYPE_BOOLEAN;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_DATE_TIME)) return StvnVocabulary.TYPE_DATE_TIME;
    if (matchesTypeKeyword(keyword, StvnVocabulary.TYPE_TIME_EPOCH)) return StvnVocabulary.TYPE_TIME_EPOCH;
    return "";
  }

  private static boolean matchesTypeKeyword(String tokenText, String typeKeyword) {
    if (tokenText.equals(typeKeyword)) {
      return true;
    }
    if (tokenText.startsWith(typeKeyword)) {
      char nextChar = tokenText.charAt(typeKeyword.length());
      return nextChar == '(' || nextChar == ')' || nextChar == ' ' || nextChar == '\t' || nextChar == '\n' || nextChar == '\r' || nextChar == '{' || nextChar == '}';
    }
    return false;
  }

  private static String resolveTargetBaseType(@Nullable SchemaType schemaType) {
    if (schemaType == null) return "";
    var ctor = schemaType.getSchemaConstructor();
    if (ctor != null) {
      var atom = ctor.getAtomicType();
      if (atom != null) return atom.getText().trim();
      var coll = ctor.getCollectionType();
      if (coll != null) {
        var node = coll.getNode();
        if (node != null) {
          if (node.findChildByType(StvnTypes.COLL_SEQ) != null) return StvnVocabulary.TYPE_SEQ;
          if (node.findChildByType(StvnTypes.COLL_SET) != null) return StvnVocabulary.TYPE_SET;
          if (node.findChildByType(StvnTypes.COLL_MAP) != null) return StvnVocabulary.TYPE_MAP;
        }
        var first = coll.getFirstChild();
        return first != null ? first.getText().trim() : "";
      }
      var prod = ctor.getProductType();
      if (prod != null) return StvnVocabulary.TYPE_TUPLE;
      var sum = ctor.getSumType();
      if (sum != null) {
        var node = sum.getNode();
        if (sum.getEnumDef() != null || (node != null && node.findChildByType(StvnTypes.KW_ENUM) != null)) {
          return StvnVocabulary.TYPE_ENUM;
        }
        if (node != null) {
          if (node.findChildByType(StvnTypes.KW_OPTION) != null) return StvnVocabulary.TYPE_OPTION;
          if (node.findChildByType(StvnTypes.KW_EITHER) != null) return StvnVocabulary.TYPE_EITHER;
          if (node.findChildByType(StvnTypes.KW_UNION) != null) return StvnVocabulary.TYPE_UNION;
        }
        var first = sum.getFirstChild();
        return first != null ? first.getText().trim() : "";
      }
    }
    var kw = schemaType.getTypeKeyword();
    if (kw != null) {
      var text = kw.getText().trim();
      if (text.equals(StvnVocabulary.TYPE_INT)
          || text.equals(StvnVocabulary.TYPE_STRING)
          || text.equals(StvnVocabulary.TYPE_FLOAT)
          || text.equals(StvnVocabulary.TYPE_BOOLEAN)
          || text.equals(StvnVocabulary.TYPE_TIME_EPOCH)
          || text.equals(StvnVocabulary.TYPE_DATE_TIME)) {
        return text;
      }
      var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
      if (resolved != null) {
        return resolveTargetBaseType(resolved);
      }
      return text;
    }
    return "";
  }

  public static @Nullable String extractRootFacetKeyword(@Nullable MetadataEntry entry) {
    if (entry == null) return null;
    var node = entry.getNode();
    if (node != null) {
      if (node.findChildByType(StvnTypes.KW_SIZE) != null) return StvnVocabulary.FACET_KW_SIZE;
      if (node.findChildByType(StvnTypes.KW_MIN_SIZE) != null) return StvnVocabulary.FACET_KW_MIN_SIZE;
      if (node.findChildByType(StvnTypes.KW_MAX_SIZE) != null) return StvnVocabulary.FACET_KW_MAX_SIZE;
      if (node.findChildByType(StvnTypes.KW_MIN_INCL) != null) return StvnVocabulary.FACET_KW_MIN_INCL;
      if (node.findChildByType(StvnTypes.KW_MAX_EXCL) != null) return StvnVocabulary.FACET_KW_MAX_EXCL;
      if (node.findChildByType(StvnTypes.KW_MIN_EXCL) != null) return StvnVocabulary.FACET_KW_MIN_EXCL;
      if (node.findChildByType(StvnTypes.KW_MAX_INCL) != null) return StvnVocabulary.FACET_KW_MAX_INCL;
      if (node.findChildByType(StvnTypes.KW_UNSIGNED) != null) return StvnVocabulary.FACET_KW_UNSIGNED;
      if (node.findChildByType(StvnTypes.KW_EXACT) != null) return StvnVocabulary.FACET_KW_EXACT;
      if (node.findChildByType(StvnTypes.KW_INVERTIBLE) != null) return StvnVocabulary.FACET_KW_INVERTIBLE;
      if (node.findChildByType(StvnTypes.KW_SCALE_S) != null) return StvnVocabulary.FACET_KW_SCALE_S;
      if (node.findChildByType(StvnTypes.KW_SCALE_MS) != null) return StvnVocabulary.FACET_KW_SCALE_MS;
      if (node.findChildByType(StvnTypes.KW_SCALE_US) != null) return StvnVocabulary.FACET_KW_SCALE_US;
      if (node.findChildByType(StvnTypes.KW_SCALE_NS) != null) return StvnVocabulary.FACET_KW_SCALE_NS;
      if (node.findChildByType(StvnTypes.KW_OFFSET) != null) return StvnVocabulary.FACET_KW_OFFSET;
      if (node.findChildByType(StvnTypes.KW_ZONED) != null) return StvnVocabulary.FACET_KW_ZONED;
      if (node.findChildByType(StvnTypes.KW_AUDITED) != null) return StvnVocabulary.FACET_KW_AUDITED;
      if (node.findChildByType(StvnTypes.KW_EQUATABLE) != null) return StvnVocabulary.FACET_KW_EQUATABLE;
      if (node.findChildByType(StvnTypes.KW_COMPARABLE) != null) return StvnVocabulary.FACET_KW_COMPARABLE;
      if (node.findChildByType(StvnTypes.KW_PRESERVE_INDENT) != null) return StvnVocabulary.FACET_KW_PRESERVE_INDENT;
      if (node.findChildByType(StvnTypes.KW_REGEX) != null) return StvnVocabulary.FACET_KW_REGEX;
      if (node.findChildByType(StvnTypes.KW_FILTER_INCL) != null) return StvnVocabulary.FACET_KW_FILTER_INCL;
      if (node.findChildByType(StvnTypes.KW_FILTER_EXCL) != null) return StvnVocabulary.FACET_KW_FILTER_EXCL;
      if (node.findChildByType(StvnTypes.KW_STRIP) != null) return StvnVocabulary.FACET_KW_STRIP;
    }
    var text = entry.getText().trim();
    var matcher = FACET_TAG_PATTERN.matcher(text);
    if (matcher.find()) {
      return matcher.group();
    }
    return null;
  }

  private static int resolveActiveBitWidth(@Nullable MetadataMap metaMap, @Nullable SchemaType schemaType) {
    if (metaMap != null) {
      for (var entry : metaMap.getMetadataEntryList()) {
        var root = extractRootFacetKeyword(entry);
        if (StvnVocabulary.FACET_KW_SIZE.equals(root)) {
          var val = extractIntegerArgument(entry);
          if (val != null) {
            return val;
          }
        }
      }
      var sanitized = StvnStringCapacityInspection.sanitizeMetadataText(metaMap.getText());
      var m = Pattern.compile("#size\\s+(\\d+)").matcher(sanitized);
      if (m.find()) {
        try {
          return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {}
      }
    }
    if (schemaType != null) {
      var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
      if (resolved != null && resolved.getMetadataMap() != null) {
        for (var entry : resolved.getMetadataMap().getMetadataEntryList()) {
          var root = extractRootFacetKeyword(entry);
          if (StvnVocabulary.FACET_KW_SIZE.equals(root)) {
            var val = extractIntegerArgument(entry);
            if (val != null) {
              return val;
            }
          }
        }
      }
    }
    return 32;
  }

  private static @Nullable Integer extractIntegerArgument(MetadataEntry entry) {
    var intLit = PsiTreeUtil.findChildOfType(entry, IntegerLiteral.class);
    if (intLit != null) {
      try {
        return Integer.parseInt(intLit.getText().trim());
      } catch (NumberFormatException ignored) {}
    }
    var text = entry.getText().trim();
    var parts = text.split("\\s+");
    if (parts.length >= 2) {
      try {
        return Integer.parseInt(parts[1]);
      } catch (NumberFormatException ignored) {}
    }
    return null;
  }

  private static boolean isUnsignedActive(MetadataMap metaMap, @Nullable SchemaType schemaType) {
    for (var entry : metaMap.getMetadataEntryList()) {
      var root = extractRootFacetKeyword(entry);
      if (StvnVocabulary.FACET_KW_UNSIGNED.equals(root)) {
        return true;
      }
      var flag = entry.getMetadataBareFlag();
      if (flag != null && flag.getText().startsWith(StvnVocabulary.FACET_KW_UNSIGNED)) {
        return true;
      }
    }
    if (schemaType != null) {
      var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
      if (resolved != null && resolved.getMetadataMap() != null) {
        for (var entry : resolved.getMetadataMap().getMetadataEntryList()) {
          var root = extractRootFacetKeyword(entry);
          if (StvnVocabulary.FACET_KW_UNSIGNED.equals(root)) {
            return true;
          }
          var flag = entry.getMetadataBareFlag();
          if (flag != null && flag.getText().startsWith(StvnVocabulary.FACET_KW_UNSIGNED)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean isExactActive(MetadataMap metaMap, @Nullable SchemaType schemaType) {
    for (var entry : metaMap.getMetadataEntryList()) {
      var root = extractRootFacetKeyword(entry);
      if (StvnVocabulary.FACET_KW_EXACT.equals(root)) {
        return true;
      }
      var flag = entry.getMetadataBareFlag();
      if (flag != null && flag.getText().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
        return true;
      }
    }
    if (schemaType != null) {
      var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
      if (resolved != null && resolved.getMetadataMap() != null) {
        for (var entry : resolved.getMetadataMap().getMetadataEntryList()) {
          var root = extractRootFacetKeyword(entry);
          if (StvnVocabulary.FACET_KW_EXACT.equals(root)) {
            return true;
          }
          var flag = entry.getMetadataBareFlag();
          if (flag != null && flag.getText().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean isCollectionType(String targetBaseType) {
    return StvnVocabulary.TYPE_SEQ.equals(targetBaseType)
        || StvnVocabulary.TYPE_SET.equals(targetBaseType)
        || StvnVocabulary.TYPE_MAP.equals(targetBaseType);
  }

  private static boolean isSumConstructorType(String targetBaseType) {
    return StvnVocabulary.TYPE_UNION.equals(targetBaseType)
        || StvnVocabulary.TYPE_EITHER.equals(targetBaseType)
        || StvnVocabulary.TYPE_OPTION.equals(targetBaseType);
  }

  private static void addTraitOverrideCompletions(
      CompletionResultSet targetResult,
      Set<String> declaredFacets,
      boolean isAutoWrap,
      InsertHandler<LookupElement> insertHandler
  ) {
    if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
      targetResult.addElement(createFacetLookup(
          StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE",
          isAutoWrap,
          "Trait Override",
          null,
          insertHandler
      ));
      targetResult.addElement(createFacetLookup(
          StvnVocabulary.FACET_KW_EQUATABLE + " #TRUE",
          isAutoWrap,
          "Trait Override",
          null,
          insertHandler
      ));
    }
    if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
      targetResult.addElement(createFacetLookup(
          StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE",
          isAutoWrap,
          "Trait Override",
          null,
          insertHandler
      ));
      targetResult.addElement(createFacetLookup(
          StvnVocabulary.FACET_KW_COMPARABLE + " #TRUE",
          isAutoWrap,
          "Trait Override",
          null,
          insertHandler
      ));
    }
  }

  private static boolean isCaretInDomainFacet(PsiElement position) {
    var metaBlock = PsiTreeUtil.getParentOfType(position, MetaBlock.class);
    if (metaBlock != null) {
      var prev = PsiTreeUtil.prevVisibleLeaf(position);
      if (prev != null && "#domain".equals(prev.getText())) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> collectWorkspaceDomains(com.intellij.openapi.project.Project project) {
    var domains = new HashSet<String>();
    var projectName = project.getName().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    if (!projectName.isEmpty()) {
      domains.add(projectName);
    }
    domains.add("telemetry");
    domains.add("catalog");
    domains.add("models");
    domains.add("finance");

    var scope = GlobalSearchScope.projectScope(project);
    var psiManager = com.intellij.psi.PsiManager.getInstance(project);

    var virtualFiles = new java.util.ArrayList<com.intellij.openapi.vfs.VirtualFile>();
    virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Defs.INSTANCE, scope));
    virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.DefsInclude.INSTANCE, scope));
    virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.PayloadInclude.INSTANCE, scope));
    virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Payload.INSTANCE, scope));

    for (var vf : virtualFiles) {
      var psiFile = psiManager.findFile(vf);
      if (psiFile == null) continue;
      for (var facet : PsiTreeUtil.findChildrenOfType(psiFile, MetaDomainFacet.class)) {
        var lit = facet.getStringLiteral();
        if (lit != null) {
          var text = lit.getText();
          if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            domains.add(text.substring(1, text.length() - 1));
          }
        }
      }
    }
    return domains;
  }
}
