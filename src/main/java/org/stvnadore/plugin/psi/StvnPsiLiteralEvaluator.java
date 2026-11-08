package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.Value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Pure Java 21 CPU-bias-free PSI literal evaluator for standalone editor inspections,
 * hover cards, and completion contributors.
 */
@NullMarked
public final class StvnPsiLiteralEvaluator {

    /** Maximum bit-width shifted eagerly into decimal range bounds during live editor analysis. */
    public static final int MAX_EAGER_SHIFT_BIT_WIDTH = 16_384;

    private static final int MAX_CONST_RESOLUTION_DEPTH = 16;

    private StvnPsiLiteralEvaluator() {
    }

    /**
     * Parses an STVN integer token (decimal, {@code 0x}, {@code 0b}, or {@code 0o}) into a {@link BigInteger}.
     *
     * @param rawText raw PSI token text
     * @return parsed {@link BigInteger}, or empty if syntactically incomplete or invalid
     */
    public static Optional<BigInteger> parseBigInteger(@Nullable String rawText) {
        if (rawText == null) {
            return Optional.empty();
        }
        String s = rawText.trim().replace("_", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        boolean neg = false;
        if (s.startsWith("-")) {
            neg = true;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            BigInteger mag;
            if (s.startsWith("0x") || s.startsWith("0X")) {
                mag = new BigInteger(s.substring(2), 16);
            } else if (s.startsWith("0b") || s.startsWith("0B")) {
                mag = new BigInteger(s.substring(2), 2);
            } else if (s.startsWith("0o") || s.startsWith("0O")) {
                mag = new BigInteger(s.substring(2), 8);
            } else {
                mag = new BigInteger(s, 10);
            }
            return Optional.of(neg ? mag.negate() : mag);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Parses an STVN floating-point or exact decimal token into a {@link BigDecimal}.
     *
     * @param rawText raw PSI token text
     * @return parsed {@link BigDecimal}, or empty if non-finite or malformed
     */
    public static Optional<BigDecimal> parseBigDecimal(@Nullable String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(rawText.trim().replace("_", "")));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Resolves an STVN integer literal or a {@code #CONST} reference chain to a {@link BigInteger}.
     *
     * @param context PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText raw literal or {@code #CONST} token text
     * @return resolved {@link BigInteger}, or empty if unresolvable or cyclic
     */
    public static Optional<BigInteger> resolveBigIntegerOrConst(@Nullable PsiElement context, @Nullable String rawText) {
        return resolveBigIntegerOrConstInternal(context, rawText, new HashSet<>());
    }

    private static Optional<BigInteger> resolveBigIntegerOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        Optional<BigInteger> direct = parseBigInteger(trimmed);
        if (direct.isPresent()) {
            return direct;
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                return resolveBigIntegerOrConstInternal(val, val.getText(), visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves an STVN decimal/floating-point literal or a {@code #CONST} reference chain to a {@link BigDecimal}.
     *
     * @param context PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText raw literal or {@code #CONST} token text
     * @return resolved {@link BigDecimal}, or empty if unresolvable or cyclic
     */
    public static Optional<BigDecimal> resolveBigDecimalOrConst(@Nullable PsiElement context, @Nullable String rawText) {
        return resolveBigDecimalOrConstInternal(context, rawText, new HashSet<>());
    }

    private static Optional<BigDecimal> resolveBigDecimalOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        Optional<BigDecimal> direct = parseBigDecimal(trimmed);
        if (direct.isPresent()) {
            return direct;
        }
        Optional<BigInteger> intVal = parseBigInteger(trimmed);
        if (intVal.isPresent()) {
            return Optional.of(new BigDecimal(intVal.get()));
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                return resolveBigDecimalOrConstInternal(val, val.getText(), visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves an STVN string literal (simple or fenced) or a {@code #CONST} reference chain to its decoded UTF-8 string.
     *
     * @param context        PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText        raw string literal or {@code #CONST} token text
     * @param preserveIndent {@code true} when {@code #preserveIndent #TRUE} retains common leading spaces
     * @return decoded string value, or empty if unresolvable or cyclic
     */
    public static Optional<String> resolveStringOrConst(
        @Nullable PsiElement context,
        @Nullable String rawText,
        boolean preserveIndent
    ) {
        return resolveStringOrConstInternal(context, rawText, preserveIndent, new HashSet<>());
    }

    private static Optional<String> resolveStringOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        boolean preserveIndent,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        Optional<String> decoded = decodeStringLiteral(trimmed, preserveIndent);
        if (decoded.isPresent()) {
            return decoded;
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                return resolveStringOrConstInternal(val, val.getText(), preserveIndent, visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a {@link ConstantDefinition} across local {@code :defs}, {@code :package}, {@code :use},
     * {@code :include}, and Standard Library Prelude scopes.
     *
     * @param context   originating PSI context element
     * @param constName constant token starting with {@code #}
     * @return resolved {@link ConstantDefinition}, or {@code null} if not found
     */
    public static @Nullable ConstantDefinition resolveConstantDefinition(@Nullable PsiElement context, String constName) {
        if (context == null || !constName.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return null;
        }
        PsiFile file = context.getContainingFile();
        if (file == null) {
            return null;
        }
        PsiElement resolvedElem = StvnConstantReference.resolveConstantInFile(file, constName, new HashSet<>());
        if (resolvedElem != null) {
            if (resolvedElem instanceof ConstantDefinition cd) {
                return cd;
            }
            if (resolvedElem.getParent() instanceof ConstantDefinition cd) {
                return cd;
            }
            if (resolvedElem.getParent() instanceof UseMapAlias alias) {
                var kws = alias.getValueKeywordList();
                if (!kws.isEmpty() && kws.get(0) != null) {
                    var useStmt = PsiTreeUtil.getParentOfType(alias, UseStmt.class);
                    String remoteName = kws.get(0).getText().trim();
                    if (useStmt != null && useStmt.getUseTarget() != null) {
                        String fqni = useStmt.getUseTarget().getText().trim() + "/" + remoteName;
                        PsiElement hopped = StvnConstantReference.resolveConstantInFile(file, fqni, new HashSet<>());
                        if (hopped instanceof ConstantDefinition cd) {
                            return cd;
                        }
                        if (hopped != null && hopped.getParent() instanceof ConstantDefinition cd) {
                            return cd;
                        }
                    }
                    PsiElement hoppedShort = StvnConstantReference.resolveConstantInFile(file, remoteName, new HashSet<>());
                    if (hoppedShort instanceof ConstantDefinition cd) {
                        return cd;
                    }
                    if (hoppedShort != null && hoppedShort.getParent() instanceof ConstantDefinition cd) {
                        return cd;
                    }
                }
            }
        }
        ConstantDefinition local = StvnTypeResolver.findConstantDefinition(file, constName);
        if (local != null) {
            return local;
        }
        var project = file.getProject();
        if (!project.isDisposed()) {
            return StvnPreludeBridge.resolvePreludeConstantDefinition(project, constName);
        }
        return null;
    }

    /**
     * Resolves a 1-based union branch prefix (such as {@code #1} or {@code #2}) to a 0-based branch
     * list index without 32-bit integer truncation.
     *
     * @param tagText     raw union tag token text starting with {@code #}
     * @param branchCount number of branches declared on the governing {@code :Union}
     * @return 0-based branch index when {@code 1 <= index <= branchCount}, or empty otherwise
     */
    public static OptionalInt resolveZeroBasedUnionBranchIndex(@Nullable String tagText, int branchCount) {
        if (tagText == null || !tagText.startsWith(StvnVocabulary.SIGIL_VALUE) || branchCount <= 0) {
            return OptionalInt.empty();
        }
        String digits = tagText.substring(1);
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return OptionalInt.empty();
        }
        BigInteger oneBased = new BigInteger(digits);
        if (oneBased.compareTo(BigInteger.ONE) < 0
            || oneBased.compareTo(BigInteger.valueOf(branchCount)) > 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(oneBased.intValueExact() - 1);
    }

    /**
     * Evaluates the decoded character count of a PSI {@link StringLiteral} using default
     * common-indentation stripping for fenced block strings.
     *
     * @param literal PSI string literal node, or {@code null}
     * @return decoded character length, or {@code 0} when {@code literal} is {@code null}
     */
    public static int evaluateStringContentLength(@Nullable StringLiteral literal) {
        return evaluateStringContentLength(literal, false);
    }

    /**
     * Evaluates the decoded character count of a PSI {@link StringLiteral}.
     *
     * @param literal        PSI string literal node, or {@code null}
     * @param preserveIndent {@code true} when {@code #preserveIndent #TRUE} retains common leading spaces
     * @return decoded character length, or {@code 0} when {@code literal} is {@code null}
     */
    public static int evaluateStringContentLength(@Nullable StringLiteral literal, boolean preserveIndent) {
        if (literal == null) {
            return 0;
        }
        String raw = literal.getText();
        Optional<String> decoded = decodeStringLiteral(raw, preserveIndent);
        if (decoded.isPresent()) {
            return decoded.get().length();
        }
        if (raw.startsWith("\"") && !raw.startsWith("\"\"\"")) {
            int end = (raw.length() >= 2 && raw.endsWith("\"")) ? raw.length() - 1 : raw.length();
            return Math.max(0, end - 1);
        }
        return 0;
    }

    /**
     * Decodes a simple {@code "..."} or fenced {@code """[TAG]...[TAG]"""} string token.
     *
     * @param rawLiteral     raw string literal PSI text
     * @param preserveIndent {@code true} when {@code #preserveIndent #TRUE} retains common leading spaces
     * @return decoded UTF-8 string value, or empty when unclosed or malformed in-flight
     */
    public static Optional<String> decodeStringLiteral(@Nullable String rawLiteral, boolean preserveIndent) {
        if (rawLiteral == null || rawLiteral.length() < 2) {
            return Optional.empty();
        }
        if (rawLiteral.startsWith("\"\"\"[")) {
            int openCloseBracket = rawLiteral.indexOf(']', 4);
            if (openCloseBracket < 0) {
                return Optional.empty();
            }
            String tag = rawLiteral.substring(4, openCloseBracket);
            String closeDelim = "[" + tag + "]\"\"\"";
            if (!rawLiteral.endsWith(closeDelim)) {
                return Optional.empty();
            }
            int firstNewline = rawLiteral.indexOf('\n', openCloseBracket + 1);
            if (firstNewline < 0) {
                return Optional.empty();
            }
            int payloadEnd = rawLiteral.length() - closeDelim.length();
            if (payloadEnd > firstNewline && rawLiteral.charAt(payloadEnd - 1) == '\n') {
                payloadEnd--;
                if (payloadEnd > firstNewline && rawLiteral.charAt(payloadEnd - 1) == '\r') {
                    payloadEnd--;
                }
            }
            String body = rawLiteral.substring(firstNewline + 1, Math.max(firstNewline + 1, payloadEnd))
                .replace("\r\n", "\n");
            return Optional.of(preserveIndent ? body : stripCommonLeadingSpaces(body));
        }
        if (rawLiteral.startsWith("\"") && rawLiteral.endsWith("\"") && !rawLiteral.startsWith("\"\"\"")) {
            String inner = rawLiteral.substring(1, rawLiteral.length() - 1);
            StringBuilder sb = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\') {
                    if (i + 1 >= inner.length()) {
                        return Optional.empty();
                    }
                    char next = inner.charAt(++i);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 < inner.length()) {
                                String hex = inner.substring(i + 1, i + 5);
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                } catch (NumberFormatException ex) {
                                    return Optional.empty();
                                }
                                i += 4;
                            } else {
                                return Optional.empty();
                            }
                        }
                        default -> sb.append(next);
                    }
                } else {
                    sb.append(c);
                }
            }
            return Optional.of(sb.toString());
        }
        return Optional.empty();
    }

    private static String stripCommonLeadingSpaces(String body) {
        String[] lines = body.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') {
                spaces++;
            }
            minIndent = Math.min(minIndent, spaces);
        }
        if (minIndent == 0 || minIndent == Integer.MAX_VALUE) {
            return body;
        }
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            out.append(line.length() >= minIndent ? line.substring(minIndent) : "");
        }
        return out.toString();
    }
}
