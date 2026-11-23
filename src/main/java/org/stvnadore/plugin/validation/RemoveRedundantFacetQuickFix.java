package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
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

import java.util.Objects;

/**
 * Dedicated quick-fix and intention action that cleanly deletes redundant metadata facets.
 * <p>
 * Ensures document invariants:
 * 1. Safely strips preceding or trailing whitespace to avoid orphaned spaces.
 * 2. If removing the facet leaves an empty metadata block ({}), cleanly excises the enclosing
 *    braces to prevent triggering ERR_EMPTY_METADATA_BLOCK (E003).
 */
@NullMarked
public final class RemoveRedundantFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

  @SafeFieldForPreview
  private final String facetSnippet;
  @SafeFieldForPreview
  private final String actionPrefix;
  @SafeFieldForPreview
  private final String actionSuffix;
  @SafeFieldForPreview
  private final Priority priority;

  /**
   * Constructs a quick-fix with default snippet derived from the element.
   *
   * @param element the PSI element representing or enclosed within the offending metadata entry
   */
  public RemoveRedundantFacetQuickFix(@NotNull PsiElement element) {
    this(element, extractFacetKeyword(element), "Remove redundant facet", Priority.TOP);
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
    this(element, facetSnippet, "Remove redundant facet", Priority.TOP);
  }

  /**
   * Constructs a quick-fix with an explicit snippet and custom action prefix.
   *
   * @param element the PSI element representing or enclosed within the offending metadata entry
   * @param facetSnippet the display snippet of the offending facet (e.g., "#ms")
   * @param actionPrefix the action prefix (e.g., "Remove inapplicable facet" or "Remove redundant facet")
   */
  public RemoveRedundantFacetQuickFix(@NotNull PsiElement element, String facetSnippet, String actionPrefix) {
    this(
        element,
        facetSnippet,
        actionPrefix,
        actionPrefix.contains("non-overridable") ? Priority.HIGH : Priority.TOP
    );
  }

  /**
   * Constructs a quick-fix with an explicit snippet, custom action prefix, and intention priority.
   *
   * @param element the PSI element representing or enclosed within the offending metadata entry
   * @param facetSnippet the display snippet of the offending facet (e.g., "#size 16")
   * @param actionPrefix the action prefix (e.g., "Remove non-overridable facet")
   * @param priority the intention priority ordering
   */
  public RemoveRedundantFacetQuickFix(
      @NotNull PsiElement element,
      String facetSnippet,
      String actionPrefix,
      @NotNull Priority priority
  ) {
    this(element, facetSnippet, actionPrefix, "", priority);
  }

  private RemoveRedundantFacetQuickFix(
      @NotNull PsiElement element,
      String facetSnippet,
      String actionPrefix,
      String actionSuffix,
      @NotNull Priority priority
  ) {
    super(element);
    this.facetSnippet = facetSnippet;
    this.actionPrefix = actionPrefix;
    this.actionSuffix = actionSuffix;
    this.priority = priority;
  }

  /**
   * Creates a high-priority quick-fix with exact label {@code "Remove invalid '#regex' facet"}
   * for malformed {@code E013 / INVALID_REGEX_PATTERN} metadata entries.
   *
   * @param element      the offending {@code #regex} metadata entry
   * @param facetKeyword the canonical {@code #regex} keyword constant
   * @return configured {@link RemoveRedundantFacetQuickFix}
   */
  public static RemoveRedundantFacetQuickFix createRemoveInvalidFacetFix(
      @NotNull PsiElement element,
      String facetKeyword
  ) {
    return createRemoveInvalidFacetFix(element, facetKeyword, Priority.HIGH);
  }

  /**
   * Creates a quick-fix with exact label {@code "Remove invalid '<facetKeyword>' facet"}
   * and explicit intention priority.
   *
   * @param element      the offending metadata entry
   * @param facetKeyword the canonical facet keyword constant
   * @param priority     the intention priority ordering
   * @return configured {@link RemoveRedundantFacetQuickFix}
   */
  public static RemoveRedundantFacetQuickFix createRemoveInvalidFacetFix(
      @NotNull PsiElement element,
      String facetKeyword,
      @NotNull Priority priority
  ) {
    return new RemoveRedundantFacetQuickFix(
        element,
        facetKeyword,
        "Remove invalid",
        " facet",
        priority
    );
  }

  /**
   * Creates a quick-fix with exact label {@code "Remove '<facetSnippet>' facet"} that deletes
   * the target {@link MetadataEntry} and excises sole comment-free enclosing {@code {}} braces.
   *
   * @param element      the offending metadata entry or child node
   * @param facetSnippet the normalized facet snippet (for example, {@code "#filterIncl []"})
   * @param priority     the intention priority ordering
   * @return configured {@link RemoveRedundantFacetQuickFix}
   */
  public static RemoveRedundantFacetQuickFix createRemoveFacetFix(
      @NotNull PsiElement element,
      String facetSnippet,
      @NotNull Priority priority
  ) {
    return new RemoveRedundantFacetQuickFix(element, facetSnippet, "Remove", " facet", priority);
  }

  @Override
  public @NotNull Priority getPriority() {
    return priority;
  }

  @Override
  public @Nls @NotNull String getText() {
    return facetSnippet.isEmpty()
        ? actionPrefix + actionSuffix
        : actionPrefix + " '" + facetSnippet + "'" + actionSuffix;
  }

  @Override
  public @Nls @NotNull String getFamilyName() {
    return actionSuffix.isEmpty() ? actionPrefix : actionPrefix + actionSuffix;
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

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RemoveRedundantFacetQuickFix other)) {
      return false;
    }
    return Objects.equals(resolveMetadataEntry(getStartElement()), resolveMetadataEntry(other.getStartElement()))
        && Objects.equals(actionPrefix, other.actionPrefix)
        && Objects.equals(facetSnippet, other.facetSnippet)
        && Objects.equals(actionSuffix, other.actionSuffix);
  }

  @Override
  public int hashCode() {
    return Objects.hash(resolveMetadataEntry(getStartElement()), actionPrefix, facetSnippet, actionSuffix);
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
      if (entries.size() <= 1 && PsiTreeUtil.findChildOfType(map, PsiComment.class) == null) {
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
