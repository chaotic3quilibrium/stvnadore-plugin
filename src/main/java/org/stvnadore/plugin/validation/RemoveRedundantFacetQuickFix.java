package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;

/**
 * Dedicated quick-fix and intention action that cleanly deletes redundant metadata facets.
 * <p>
 * Ensures document invariants:
 * 1. Safely strips preceding or trailing whitespace to avoid orphaned spaces.
 * 2. If removing the facet leaves an empty metadata block ({}), cleanly excises the enclosing
 *    braces to prevent triggering ERR_EMPTY_METADATA_BLOCK (E003).
 */
@NullMarked
public final class RemoveRedundantFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

  private final String facetSnippet;
  private final String actionPrefix;

  /**
   * Constructs a quick-fix with default snippet derived from the element.
   *
   * @param element the PSI element representing or enclosed within the offending metadata entry
   */
  public RemoveRedundantFacetQuickFix(@NotNull PsiElement element) {
    this(element, extractFacetKeyword(element), "Remove redundant facet");
  }

  /**
   * Extracts the trimmed facet text snippet from a metadata entry PSI element for intention labels.
   *
   * @param element the metadata entry PSI element
   * @return the trimmed facet text (for example, {@code "#zoned"} or {@code "#size 16"})
   */
  public static @NotNull String extractFacetKeyword(@NotNull PsiElement element) {
    return element.getText().trim();
  }

  /**
   * Constructs a quick-fix with an explicit snippet for display in the intention popup.
   *
   * @param element the PSI element representing or enclosed within the offending metadata entry
   * @param facetSnippet the display snippet of the offending facet (e.g., "#minIncl 0")
   */
  public RemoveRedundantFacetQuickFix(@NotNull PsiElement element, String facetSnippet) {
    this(element, facetSnippet, "Remove redundant facet");
  }

  /**
   * Constructs a quick-fix with an explicit snippet and custom action prefix.
   *
   * @param element the PSI element representing or enclosed within the offending metadata entry
   * @param facetSnippet the display snippet of the offending facet (e.g., "#ms")
   * @param actionPrefix the action prefix (e.g., "Remove inapplicable facet" or "Remove redundant facet")
   */
  public RemoveRedundantFacetQuickFix(@NotNull PsiElement element, String facetSnippet, String actionPrefix) {
    super(element);
    this.facetSnippet = facetSnippet;
    this.actionPrefix = actionPrefix;
  }

  @Override
  public @Nls @NotNull String getText() {
    return facetSnippet.isEmpty() ? actionPrefix : actionPrefix + " '" + facetSnippet + "'";
  }

  @Override
  public @Nls @NotNull String getFamilyName() {
    return actionPrefix;
  }

  @Override
  public boolean isAvailable(
      @NotNull Project project,
      @NotNull PsiFile file,
      @NotNull PsiElement startElement,
      @NotNull PsiElement endElement
  ) {
    if (!startElement.isValid()) {
      return false;
    }
    return resolveMetadataEntry(startElement) != null;
  }

  @Override
  public void invoke(
      @NotNull Project project,
      @NotNull PsiFile file,
      @Nullable Editor editor,
      @NotNull PsiElement startElement,
      @NotNull PsiElement endElement
  ) {
    executeRemoval(startElement);
  }

  /**
   * Resolves the target MetadataEntry from a given PSI element or interior leaf token.
   * Supports caret independence across keyword tokens, literal values, and boundary tokens.
   *
   * @param element terminal leaf or composite element
   * @return enclosing MetadataEntry or null if outside metadata block
   */
  public static @Nullable MetadataEntry resolveMetadataEntry(@Nullable PsiElement element) {
    if (element == null || !element.isValid()) {
      return null;
    }
    if (element instanceof MetadataEntry entry) {
      return entry;
    }
    MetadataEntry parent = PsiTreeUtil.getParentOfType(element, MetadataEntry.class, false);
    if (parent != null) {
      return parent;
    }
    // Boundary fallback: if element is RBRACE '}' or trailing whitespace inside MetadataMap, check prevSibling
    PsiElement prev = element.getPrevSibling();
    if (prev instanceof MetadataEntry prevEntry) {
      return prevEntry;
    }
    return null;
  }

  /**
   * Removes the resolved {@link MetadataEntry} from its enclosing {@link MetadataMap},
   * normalizing whitespace and excising the entire block if no entries remain.
   *
   * @param element the target metadata entry or interior leaf element
   */
  public static void executeRemoval(@Nullable PsiElement element) {
    MetadataEntry entry = resolveMetadataEntry(element);
    if (entry == null || !entry.isValid()) {
      return;
    }

    MetadataMap map = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
    if (map != null && map.isValid()) {
      var entries = map.getMetadataEntryList();
      if (entries.size() <= 1) {
        // Excising the entire MetadataMap avoids creating an invalid empty block '{}' (SPEC § 3.5.1, E003)
        var prev = map.getPrevSibling();
        var next = map.getNextSibling();
        if (prev instanceof PsiWhiteSpace) {
          prev.delete();
        } else if (next instanceof PsiWhiteSpace) {
          next.delete();
        }
        map.delete();
        return;
      }
    }

    // Multiple entries remain in the metadata block: excise only this entry.
    // Normalize surrounding whitespace so { #unsigned #size 32 } cleanly becomes { #unsigned }
    var prev = entry.getPrevSibling();
    var next = entry.getNextSibling();
    if (prev instanceof PsiWhiteSpace && prev.getPrevSibling() instanceof MetadataEntry) {
      prev.delete();
    } else if (next instanceof PsiWhiteSpace && next.getNextSibling() instanceof MetadataEntry) {
      next.delete();
    } else if (prev instanceof PsiWhiteSpace) {
      prev.delete();
    } else if (next instanceof PsiWhiteSpace) {
      next.delete();
    }

    entry.delete();
  }
}
