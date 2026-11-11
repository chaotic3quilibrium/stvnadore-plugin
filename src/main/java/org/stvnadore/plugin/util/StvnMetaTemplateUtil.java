package org.stvnadore.plugin.util;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeEntry;

/**
 * Shared utility for generating and inserting canonical root :meta header templates
 * conforming to Rule STR-STEM and document kind specifications.
 */
@NullMarked
public final class StvnMetaTemplateUtil {

  private static final String RESERVED_DOMAIN_PREFIX = "stvn_";

  private StvnMetaTemplateUtil() {}

  /**
   * Resolves the canonical #kind value string for a given file name or extension.
   *
   * @param fileName file name or path
   * @return canonical kind keyword literal string, e.g. "#BODY", "#DEFS"
   */
  public static String resolveExpectedKind(@NotNull String fileName) {
    if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd")) {
      return StvnVocabulary.KIND_KW_DEFS;
    }
    if (fileName.endsWith(".stvn_di")) {
      return StvnVocabulary.KIND_KW_DEFS_INCLUDE;
    }
    if (fileName.endsWith(".stvn_i")) {
      return StvnVocabulary.KIND_KW_BODY_INCLUDE;
    }
    return StvnVocabulary.KIND_KW_BODY;
  }

  /**
   * Checks whether the file extension strictly mandates a root {@code :meta} section
   * when {@code :meta} is omitted altogether (STVN Spec § 1.2, {@code E031}).
   *
   * @param fileName file name to test
   * @return {@code true} if extension is {@code .stvn_i}, {@code .stvn_d}, {@code .stvn_di}, {@code .stvn_b}, or {@code .stvn_bd}
   */
  public static boolean isMetaExtensionMandatory(@NotNull String fileName) {
    return fileName.endsWith(".stvn_i")
        || fileName.endsWith(".stvn_d")
        || fileName.endsWith(".stvn_di")
        || fileName.endsWith(".stvn_b")
        || fileName.endsWith(".stvn_bd");
  }

  /**
   * Checks whether a root {@code :meta} section is mandatory for empty-section governance
   * ({@code E039}), matching {@code StvnDefsLowerer.lowerAndValidateDocumentIdentity} 1:1
   * across mandatory extensions and 2-stem {@code <name>.<domain>.stvn} filenames.
   *
   * @param fileName file name to test
   * @return {@code true} if {@code :meta} cannot be omitted to resolve {@code :meta {}}
   */
  public static boolean isMetaMandatory(@NotNull String fileName) {
    if (isMetaExtensionMandatory(fileName)) {
      return true;
    }
    if (!fileName.endsWith(".stvn") || fileName.startsWith(".")) {
      return false;
    }
    int lastDot = fileName.lastIndexOf('.');
    if (lastDot <= 0) {
      return false;
    }
    String stemPart = fileName.substring(0, lastDot);
    String[] dotParts = stemPart.split("\\.", -1);
    return dotParts.length == 2
        && !dotParts[0].isEmpty()
        && !dotParts[1].isEmpty()
        && !dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX);
  }

  /**
   * Resolves the file extension including leading dot (for example {@code .stvn_d}),
   * falling back to {@code fallbackExt} when absent.
   *
   * @param fileName file name to inspect
   * @param fallbackExt fallback extension with leading dot
   * @return resolved extension string
   */
  public static String resolveFileExtensionOrDefault(@NotNull String fileName, @NotNull String fallbackExt) {
    int lastDot = fileName.lastIndexOf('.');
    return lastDot >= 0 ? fileName.substring(lastDot) : fallbackExt;
  }

  /**
   * Checks whether the element resides in (or is) the primary (first) {@code :defs} section of its file.
   *
   * @param element PSI element to test
   * @return {@code true} if no {@code DefsEntry} encloses {@code element}, or the enclosing {@code DefsEntry} is primary
   */
  public static boolean isPrimaryDefsEntry(@Nullable PsiElement element) {
    if (element == null) {
      return false;
    }
    DefsEntry enclosing = element instanceof DefsEntry de
        ? de
        : PsiTreeUtil.getParentOfType(element, DefsEntry.class, false);
    if (enclosing == null) {
      return true;
    }
    PsiFile file = enclosing.getContainingFile();
    return file != null && PsiTreeUtil.findChildOfType(file, DefsEntry.class) == enclosing;
  }

  /**
   * Checks whether the element resides in (or is) the primary (first) {@code :type} section of its file.
   *
   * @param element PSI element to test
   * @return {@code true} if no {@code TypeEntry} encloses {@code element}, or the enclosing {@code TypeEntry} is primary
   */
  public static boolean isPrimaryTypeEntry(@Nullable PsiElement element) {
    if (element == null) {
      return false;
    }
    TypeEntry enclosing = element instanceof TypeEntry te
        ? te
        : PsiTreeUtil.getParentOfType(element, TypeEntry.class, false);
    if (enclosing == null) {
      return true;
    }
    PsiFile file = enclosing.getContainingFile();
    return file != null && PsiTreeUtil.findChildOfType(file, TypeEntry.class) == enclosing;
  }

  /**
   * Checks whether the element resides in (or is) the primary (first) {@code :body} section of its file.
   *
   * @param element PSI element to test
   * @return {@code true} if no {@code BodyEntry} encloses {@code element}, or the enclosing {@code BodyEntry} is primary
   */
  public static boolean isPrimaryBodyEntry(@Nullable PsiElement element) {
    if (element == null) {
      return false;
    }
    BodyEntry enclosing = element instanceof BodyEntry be
        ? be
        : PsiTreeUtil.getParentOfType(element, BodyEntry.class, false);
    if (enclosing == null) {
      return true;
    }
    PsiFile file = enclosing.getContainingFile();
    return file != null && PsiTreeUtil.findChildOfType(file, BodyEntry.class) == enclosing;
  }

  /**
   * Checks whether {@code element} is located inside a secondary ({@code index > 0}) duplicate root
   * {@code MetaEntry}, {@code DefsEntry}, {@code TypeEntry}, or {@code BodyEntry}.
   *
   * @param element PSI element to inspect
   * @return {@code true} whenever {@code element} belongs to a non-primary duplicate root section
   */
  public static boolean isInSecondaryRootSection(@Nullable PsiElement element) {
    if (element == null) {
      return false;
    }
    PsiElement rootSection = findEnclosingRootSection(element);
    if (rootSection == null) {
      return false;
    }
    PsiFile file = rootSection.getContainingFile();
    if (file == null) {
      return false;
    }
    if (rootSection instanceof MetaEntry) {
      return PsiTreeUtil.findChildOfType(file, MetaEntry.class) != rootSection;
    }
    if (rootSection instanceof DefsEntry) {
      return PsiTreeUtil.findChildOfType(file, DefsEntry.class) != rootSection;
    }
    if (rootSection instanceof TypeEntry) {
      return PsiTreeUtil.findChildOfType(file, TypeEntry.class) != rootSection;
    }
    if (rootSection instanceof BodyEntry) {
      return PsiTreeUtil.findChildOfType(file, BodyEntry.class) != rootSection;
    }
    return false;
  }

  /**
   * Finds the enclosing root section PSI node ({@code MetaEntry}, {@code DefsEntry}, {@code TypeEntry}, or {@code BodyEntry}).
   *
   * @param element PSI element at or inside a root section
   * @return enclosing root section PSI element, or {@code null} if outside root sections
   */
  public static @Nullable PsiElement findEnclosingRootSection(@Nullable PsiElement element) {
    for (PsiElement cur = element; cur != null && !(cur instanceof PsiFile); cur = cur.getParent()) {
      if (cur instanceof MetaEntry || cur instanceof DefsEntry || cur instanceof TypeEntry || cur instanceof BodyEntry) {
        return cur;
      }
    }
    return null;
  }

  /**
   * Resolves the canonical keyword ({@code :meta}, {@code :defs}, {@code :type}, {@code :body}) for a root section PSI node.
   *
   * @param rootSection root section PSI node
   * @return canonical keyword constant from {@link StvnVocabulary}, or {@code null} if not a root section
   */
  public static @Nullable String resolveRootSectionKeyword(@NotNull PsiElement rootSection) {
    if (rootSection instanceof MetaEntry) {
      return StvnVocabulary.KEYWORD_META;
    }
    if (rootSection instanceof DefsEntry) {
      return StvnVocabulary.KEYWORD_DEFS;
    }
    if (rootSection instanceof TypeEntry) {
      return StvnVocabulary.KEYWORD_TYPE;
    }
    if (rootSection instanceof BodyEntry) {
      return StvnVocabulary.KEYWORD_BODY;
    }
    return null;
  }

  /**
   * Generates a canonical :meta header block text conforming to Rule STR-STEM.
   *
   * @param fileName name of target file
   * @return canonical 2-space indented :meta block snippet
   */
  public static String generateCanonicalMetaBlock(@NotNull String fileName) {
    int lastDot = fileName.lastIndexOf('.');
    String stemPart = lastDot >= 0 ? fileName.substring(0, lastDot) : fileName;
    String[] dotParts = stemPart.split("\\.", -1);

    String slot0 = dotParts.length > 0 && !dotParts[0].isEmpty() ? dotParts[0] : "document";
    String slot1 = dotParts.length > 1 && !dotParts[1].isEmpty() ? dotParts[1] : "domain";
    String kind = resolveExpectedKind(fileName);

    StringBuilder sb = new StringBuilder();
    sb.append("\n  :meta {\n");
    sb.append("    #name \"").append(slot0).append("\"\n");
    sb.append("    #domain \"").append(slot1).append("\"\n");
    sb.append("    #kind ").append(kind).append("\n");
    sb.append("  }\n");
    return sb.toString();
  }

  /**
   * Checks whether the document buffer contains a root :meta block or :meta keyword header.
   *
   * @param file target STVN PSI file
   * @return true if :meta header is present in file, false otherwise
   */
  public static boolean hasMetaHeader(@NotNull PsiFile file) {
    // Primary check: Grammar-Kit parsed root MetaEntry node
    if (PsiTreeUtil.findChildOfType(file, MetaEntry.class) != null) {
      return true;
    }
    // Fallback for broken AST during in-flight editing:
    // Under SPEC § 1.1 Root Enclosure Rule, :meta strictly precedes :defs, :type, and :body.
    // Verify whether the first non-trivia token immediately following the root opening brace '{' is ':meta'.
    PsiElement rootBrace = findRootOpeningBrace(file);
    if (rootBrace == null) {
      return false;
    }
    PsiElement next = rootBrace.getNextSibling();
    while (next != null && (next instanceof PsiWhiteSpace || next instanceof PsiComment)) {
      next = next.getNextSibling();
    }
    if (next != null) {
      if (next.getNode() != null && next.getNode().getElementType() == StvnTypes.KW_META) {
        return true;
      }
      if (next instanceof MetaEntry) {
        return true;
      }
      String text = next.getText().trim();
      if (text.startsWith(StvnVocabulary.KEYWORD_META) && (text.length() == 5 || Character.isWhitespace(text.charAt(5)) || text.charAt(5) == '{')) {
        return true;
      }
    }
    return false;
  }

  /**
   * Locates the root opening brace element of an STVN document, bypassing leading
   * comments and whitespace tokens.
   *
   * @param file containing PSI file
   * @return root LBRACE element, or first non-comment/non-whitespace leaf element
   */
  public static @Nullable PsiElement findRootOpeningBrace(@NotNull PsiFile file) {
    for (PsiElement child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof PsiWhiteSpace || child instanceof PsiComment) {
        continue;
      }
      if (child.getNode() != null && child.getNode().getElementType() == StvnTypes.LBRACE) {
        return child;
      }
      if ("{".equals(child.getText())) {
        return child;
      }
      PsiElement firstLeaf = findFirstLeaf(child);
      if (firstLeaf != null && "{".equals(firstLeaf.getText())) {
        return firstLeaf;
      }
      return null;
    }
    return null;
  }

  private static @Nullable PsiElement findFirstLeaf(@NotNull PsiElement element) {
    PsiElement current = element;
    while (current.getFirstChild() != null) {
      current = current.getFirstChild();
      if (!(current instanceof PsiWhiteSpace) && !(current instanceof PsiComment)) {
        return current;
      }
    }
    return current;
  }

  /**
   * Determines whether the given PSI element belongs to the primary (first) {@link MetaEntry}
   * in its containing file.
   *
   * @param element the PSI element to inspect
   * @return {@code true} if the enclosing {@link MetaEntry} is the first {@link MetaEntry} in the file
   */
  public static boolean isPrimaryMetaEntry(@Nullable PsiElement element) {
    if (element == null) {
      return false;
    }
    MetaEntry enclosing = element instanceof MetaEntry meta
        ? meta
        : PsiTreeUtil.getParentOfType(element, MetaEntry.class, false);
    if (enclosing == null) {
      return false;
    }
    PsiFile file = enclosing.getContainingFile();
    if (file == null) {
      return false;
    }
    MetaEntry primary = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
    return enclosing.equals(primary);
  }

  /**
   * Inserts the canonical :meta block into the specified file document buffer.
   *
   * @param project current project
   * @param file target STVN PSI file
   * @param editor active editor instance if available
   */
  public static void insertCanonicalMetaBlock(@NotNull Project project, @NotNull PsiFile file, @Nullable Editor editor) {
    Document doc = file.getViewProvider().getDocument();
    if (doc == null && editor != null) {
      doc = editor.getDocument();
    }
    if (doc == null) {
      doc = PsiDocumentManager.getInstance(project).getDocument(file);
    }
    if (doc == null) {
      return;
    }

    PsiElement rootLBrace = findRootOpeningBrace(file);
    if (rootLBrace == null || !"{".equals(rootLBrace.getText())) {
      return;
    }

    String metaBlock = generateCanonicalMetaBlock(file.getName());

    MetaEntry existingMeta = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
    if (existingMeta != null && existingMeta.isValid()) {
      var range = existingMeta.getTextRange();
      doc.replaceString(range.getStartOffset(), range.getEndOffset(), metaBlock.trim());
      PsiDocumentManager.getInstance(project).commitDocument(doc);
      CodeStyleManager.getInstance(project).reformat(file);
      if (editor != null) {
        int targetOffset = Math.min(range.getStartOffset() + metaBlock.trim().length(), doc.getTextLength());
        editor.getCaretModel().moveToOffset(targetOffset);
      }
      return;
    }

    int insertOffset = rootLBrace.getTextRange().getEndOffset();
    doc.insertString(insertOffset, metaBlock);

    PsiDocumentManager.getInstance(project).commitDocument(doc);
    CodeStyleManager.getInstance(project).reformat(file);

    if (editor != null) {
      int targetOffset = Math.min(insertOffset + metaBlock.length(), doc.getTextLength());
      editor.getCaretModel().moveToOffset(targetOffset);
    }
  }
}
