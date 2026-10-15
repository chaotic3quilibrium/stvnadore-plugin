package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Intelligent code completion contributor for metadata facet blocks ({ ... }).
 * <p>
 * Enforces Core Rule E036 and Minimal Canonical Forms (VOP Invariant 1):
 * 1. Suppresses '#minIncl 0' on unsigned :Int domains (proposes '#minIncl 1').
 * 2. Suppresses '#minSize 0' on strings and collections (proposes '#minSize 1').
 * 3. Suppresses redundant default trait assertions ('#equatable #TRUE', '#comparable #TRUE' on primitives).
 * 4. Filters out duplicate facets already declared in the local metadata block (§ 5.1.4).
 */
@NullMarked
public final class StvnMetadataCompletionContributor extends CompletionContributor {

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

            // Ensure caret is inside a metadata map and not inside a variant list
            var metaMap = PsiTreeUtil.getParentOfType(position, MetadataMap.class);
            if (metaMap == null || PsiTreeUtil.getParentOfType(position, VariantList.class) != null) {
              return;
            }

            var schemaType = PsiTreeUtil.getParentOfType(metaMap, SchemaType.class);
            if (schemaType == null) {
              var typeDef = PsiTreeUtil.getParentOfType(metaMap, TypeDefinition.class);
              if (typeDef != null) {
                schemaType = typeDef.getSchemaType();
              }
            }

            String targetBaseType = resolveTargetBaseType(schemaType);
            boolean isUnsigned = isUnsignedActive(metaMap, schemaType);
            boolean isExact = isExactActive(metaMap, schemaType);

            // Track facets already present to eliminate duplicate facet proposals (§ 5.1.4)
            Set<String> declaredFacets = new HashSet<>();
            for (var entry : metaMap.getMetadataEntryList()) {
              var text = entry.getText().trim();
              if (!text.isEmpty()) {
                var tag = text.split("\\s+")[0];
                declaredFacets.add(tag);
              }
            }

            var prefix = result.getPrefixMatcher().getPrefix();
            var matcher = result.getPrefixMatcher();
            if (!prefix.isEmpty() && !prefix.startsWith(StvnVocabulary.SIGIL_VALUE)) {
              matcher = matcher.cloneWithPrefix(StvnVocabulary.SIGIL_VALUE + prefix);
            }
            var targetResult = result.withPrefixMatcher(matcher);

            // Numeric Primitives (:Int)
            if (StvnVocabulary.TYPE_INT.equals(targetBaseType) || targetBaseType.isEmpty()) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_UNSIGNED)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_UNSIGNED)
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Definition Facet", true)
                    .withTailText(" (bounds domain to [0, \\infty))", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SIZE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_SIZE + " 32")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Storage Width", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_INCL)) {
                if (isUnsigned) {
                  // Rule E036: Suppress '#minIncl 0' on unsigned :Int; propose '#minIncl 1'
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MIN_INCL + " 1")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Lower Bound", true)
                      .withTailText(" (domain: [1, \\infty))", true));
                } else {
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MIN_INCL + " 0")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Lower Bound", true)
                      .withTailText(" (domain: [0, \\infty))", true));
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MIN_INCL + " 1")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Lower Bound", true)
                      .withTailText(" (domain: [1, \\infty))", true));
                }
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MAX_EXCL)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MAX_EXCL + " 65536")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Exclusive Upper Bound", true));
              }
              // Intrinsic trait override opt-outs only (Rule E036 suppresses #TRUE)
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Trait Override", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Trait Override", true));
              }
            }

            // Strings (:String)
            if (StvnVocabulary.TYPE_STRING.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                // Rule E036: Suppress '#minSize 0'; propose non-empty '#minSize 1'
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MIN_SIZE + " 1")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Non-Empty Bound", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MAX_SIZE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MAX_SIZE + " 256")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Max Capacity", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_REGEX)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_REGEX + " \"^[A-Za-z0-9]+$\"")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Regex Constraint", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_PRESERVE_INDENT + " #TRUE")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Formatting Trait", true));
              }
              // Intrinsic trait override opt-outs only (Rule E036 suppresses #TRUE)
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Trait Override", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Trait Override", true));
              }
            }

            // Collections (:Seq, :Set, :Map)
            if (isCollectionType(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                // Rule E036: Suppress '#minSize 0'; propose '#minSize 1'
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MIN_SIZE + " 1")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Container Bound", true));
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MAX_SIZE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MAX_SIZE + " 100")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Container Capacity", true));
              }
              if (StvnVocabulary.TYPE_MAP.equals(targetBaseType) && !declaredFacets.contains(StvnVocabulary.FACET_KW_INVERTIBLE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_INVERTIBLE)
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Bijective Map", true));
              }
            }

            // Floating-Point (:Float)
            if (StvnVocabulary.TYPE_FLOAT.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EXACT) && !isExact) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_EXACT)
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Exact Decimal", true));
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_SIZE)) {
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_SIZE + " 64")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Storage Width", true));
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_SIZE + " 32")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Storage Width", true));
                }
                // Continuous float allows opt-in equality (suppress #FALSE and comparable #TRUE)
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_EQUATABLE + " #TRUE")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Opt-In Equality", true));
                }
              } else {
                // Exact decimal float: suppress #TRUE (intrinsically equatable and comparable)
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Trait Override", true));
                }
                if (!declaredFacets.contains(StvnVocabulary.FACET_KW_COMPARABLE)) {
                  targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_COMPARABLE + " #FALSE")
                      .withIcon(AllIcons.Nodes.Property)
                      .withTypeText("Trait Override", true));
                }
              }
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_MIN_INCL)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_MIN_INCL + " 0.0")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Lower Bound", true));
              }
            }

            // Boolean Domain (:Boolean)
            if (StvnVocabulary.TYPE_BOOLEAN.equals(targetBaseType)) {
              if (!declaredFacets.contains(StvnVocabulary.FACET_KW_EQUATABLE)) {
                targetResult.addElement(LookupElementBuilder.create(StvnVocabulary.FACET_KW_EQUATABLE + " #FALSE")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText("Trait Override", true));
              }
            }
          }
        }
    );
  }

  private static String resolveTargetBaseType(@Nullable SchemaType schemaType) {
    if (schemaType == null) return "";
    var ctor = schemaType.getSchemaConstructor();
    if (ctor != null) {
      var atom = ctor.getAtomicType();
      if (atom != null) return atom.getText().trim();
      var coll = ctor.getCollectionType();
      if (coll != null) {
        var first = coll.getFirstChild();
        return first != null ? first.getText().trim() : "";
      }
      var prod = ctor.getProductType();
      if (prod != null) return StvnVocabulary.TYPE_TUPLE;
      var sum = ctor.getSumType();
      if (sum != null) {
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

  private static boolean isUnsignedActive(MetadataMap metaMap, @Nullable SchemaType schemaType) {
    for (var entry : metaMap.getMetadataEntryList()) {
      var flag = entry.getMetadataBareFlag();
      if (flag != null && flag.getText().startsWith(StvnVocabulary.FACET_KW_UNSIGNED)) {
        return true;
      }
    }
    if (schemaType != null) {
      var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
      if (resolved != null && resolved.getMetadataMap() != null) {
        for (var entry : resolved.getMetadataMap().getMetadataEntryList()) {
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
      var flag = entry.getMetadataBareFlag();
      if (flag != null && flag.getText().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
        return true;
      }
    }
    if (schemaType != null) {
      var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
      if (resolved != null && resolved.getMetadataMap() != null) {
        for (var entry : resolved.getMetadataMap().getMetadataEntryList()) {
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
}
