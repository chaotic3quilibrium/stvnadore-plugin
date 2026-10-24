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
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.StvnTypes;

/**
 * Shared utility for generating and inserting canonical root :meta header templates
 * conforming to Rule STR-STEM and document kind specifications.
 */
@NullMarked
public final class StvnMetaTemplateUtil {

  private StvnMetaTemplateUtil() {}

  /**
   * Resolves the canonical #kind value string for a given file name or extension.
   *
   * @param fileName file name or path
   * @return canonical kind keyword literal string, e.g. "#BODY", "#DEFS"
   */
  public static String resolveExpectedKind(@NotNull String fileName) {
    if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd")) {
      return "#DEFS";
    }
    if (fileName.endsWith(".stvn_di")) {
      return "#DEFS_INCLUDE";
    }
    if (fileName.endsWith(".stvn_i")) {
      return "#BODY_INCLUDE";
    }
    return "#BODY";
  }

  /**
   * Checks whether the file extension mandates a root :meta block under STVN Spec § 1.2.
   *
   * @param fileName file name to test
   * @return true if :meta is mandatory, false if optional (such as .stvn)
   */
  public static boolean isMetaMandatory(@NotNull String fileName) {
    return fileName.endsWith(".stvn_i")
        || fileName.endsWith(".stvn_d")
        || fileName.endsWith(".stvn_di")
        || fileName.endsWith(".stvn_b")
        || fileName.endsWith(".stvn_bd");
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
    String slot1 = dotParts.length > 1 && !dotParts[1].isEmpty() ? dotParts[1] : null;
    String kind = resolveExpectedKind(fileName);

    StringBuilder sb = new StringBuilder();
    sb.append("\n  :meta {\n");
    sb.append("    #name \"").append(slot0).append("\"\n");
    if (slot1 != null) {
      sb.append("    #domain \"").append(slot1).append("\"\n");
    }
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
      if (text.startsWith(":meta") && (text.length() == 5 || Character.isWhitespace(text.charAt(5)) || text.charAt(5) == '{')) {
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
