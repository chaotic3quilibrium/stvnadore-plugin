package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.Value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure Java 21 CPU-bias-free PSI literal evaluator for standalone editor inspections,
 * hover cards, and completion contributors.
 */
@NullMarked
public final class StvnPsiLiteralEvaluator {

    /** Maximum bit-width shifted eagerly into decimal range bounds during live editor analysis. */
    public static final int MAX_EAGER_SHIFT_BIT_WIDTH = 16_384;

    private static final int MAX_CONST_RESOLUTION_DEPTH = 16;
    private static final Pattern VALID_FENCE_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");

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
                boolean constPreserveIndent = extractPreserveIndentFromSchema(constDef.getSchemaType());
                return resolveStringOrConstInternal(val, val.getText(), constPreserveIndent, visited);
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the effective {@code #preserveIndent} flag declared on a PSI {@link SchemaType} chain.
     *
     * @param schemaType governing PSI schema type, or {@code null}
     * @return {@code true} when {@code #preserveIndent} is enabled; {@code false} otherwise
     */
    public static boolean extractPreserveIndentFromSchema(@Nullable SchemaType schemaType) {
        return extractPreserveIndentFromSchema(schemaType, new HashSet<>());
    }

    private static boolean extractPreserveIndentFromSchema(@Nullable SchemaType schemaType, Set<String> visited) {
        if (schemaType == null) {
            return false;
        }
        MetadataMap metaMap = schemaType.getMetadataMap();
        if (metaMap == null && schemaType.getParent() instanceof TypeDefinition td) {
            metaMap = td.getMetadataMap();
        }
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                String text = entry.getText().trim();
                if (text.equals(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
                    return true;
                }
                if (text.startsWith(StvnVocabulary.FACET_KW_PRESERVE_INDENT)
                        && Character.isWhitespace(text.charAt(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()))) {
                    String arg = text.substring(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()).trim();
                    return resolveBooleanOrConst(entry, arg).orElse(false);
                }
            }
        }
        if (schemaType.getTypeKeyword() != null && schemaType.getContainingFile() != null) {
            String alias = schemaType.getTypeKeyword().getText().trim();
            if (visited.add(alias)) {
                var target = StvnTypeReference.resolveTypeInFile(
                    schemaType.getContainingFile(), alias, new HashSet<>());
                var parentDef = StvnPsiUtils.getParentTypeDefinition(target);
                if (parentDef != null) {
                    if (parentDef.getMetadataMap() != null) {
                        for (var entry : parentDef.getMetadataMap().getMetadataEntryList()) {
                            String text = entry.getText().trim();
                            if (text.equals(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
                                return true;
                            }
                            if (text.startsWith(StvnVocabulary.FACET_KW_PRESERVE_INDENT)
                                    && Character.isWhitespace(text.charAt(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()))) {
                                String arg = text.substring(StvnVocabulary.FACET_KW_PRESERVE_INDENT.length()).trim();
                                return resolveBooleanOrConst(entry, arg).orElse(false);
                            }
                        }
                    }
                    return extractPreserveIndentFromSchema(parentDef.getSchemaType(), visited);
                }
            }
        }
        return false;
    }

    /**
     * Resolves an STVN boolean literal ({@code #TRUE}, {@code #T}, {@code #FALSE}, {@code #F}) or a
     * {@code #CONST} reference chain to its boolean value.
     *
     * @param context PSI context element for resolving {@code :defs}, {@code :use}, {@code :include}, or Prelude constants
     * @param rawText raw boolean token or {@code #CONST} reference text
     * @return resolved boolean value, or empty if unresolvable or cyclic
     */
    public static Optional<Boolean> resolveBooleanOrConst(@Nullable PsiElement context, @Nullable String rawText) {
        return resolveBooleanOrConstInternal(context, rawText, new HashSet<>());
    }

    private static Optional<Boolean> resolveBooleanOrConstInternal(
        @Nullable PsiElement context,
        @Nullable String rawText,
        Set<String> visited
    ) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        if (StvnVocabulary.VAL_TRUE.equals(trimmed) || StvnVocabulary.VAL_TRUE_SHORT.equals(trimmed)) {
            return Optional.of(true);
        }
        if (StvnVocabulary.VAL_FALSE.equals(trimmed) || StvnVocabulary.VAL_FALSE_SHORT.equals(trimmed)) {
            return Optional.of(false);
        }
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE) && context != null && visited.size() < MAX_CONST_RESOLUTION_DEPTH && visited.add(trimmed)) {
            ConstantDefinition constDef = resolveConstantDefinition(context, trimmed);
            if (constDef != null && constDef.getValue() != null) {
                Value val = constDef.getValue();
                return resolveBooleanOrConstInternal(val, val.getText(), visited);
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
                    var useElem = PsiTreeUtil.getParentOfType(alias, UseElement.class);
                    String remoteName = kws.get(0).getText().trim();
                    if (useElem != null && useElem.getUseTarget() != null) {
                        String fqni = StvnConstantReference.buildPackageConstantFqni(
                            useElem.getUseTarget().getText().trim(), remoteName);
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
     * @return decoded character length, or {@code 0} when {@code literal} is {@code null} or lexically malformed
     */
    public static int evaluateStringContentLength(@Nullable StringLiteral literal, boolean preserveIndent) {
        if (literal == null) {
            return 0;
        }
        return decodeStringLiteral(literal.getText(), preserveIndent)
            .map(String::length)
            .orElse(0);
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
            String normalized = rawLiteral.replace("\r\n", "\n").replace("\r", "\n");
            int openCloseBracket = normalized.indexOf(']', 4);
            if (openCloseBracket <= 4) {
                return Optional.empty();
            }
            String tag = normalized.substring(4, openCloseBracket);
            if (!VALID_FENCE_TAG_PATTERN.matcher(tag).matches()) {
                return Optional.empty();
            }
            String closeDelim = "[" + tag + "]\"\"\"";
            if (!normalized.endsWith(closeDelim)) {
                return Optional.empty();
            }
            int firstNewline = normalized.indexOf('\n', openCloseBracket + 1);
            if (firstNewline < 0) {
                return Optional.empty();
            }
            for (int i = openCloseBracket + 1; i < firstNewline; i++) {
                char ch = normalized.charAt(i);
                if (ch != ' ' && ch != '\t') {
                    return Optional.empty();
                }
            }
            int payloadStart = firstNewline + 1;
            int payloadEnd = normalized.length() - closeDelim.length();
            if (payloadStart > payloadEnd) {
                return Optional.empty();
            }
            String body = normalized.substring(payloadStart, payloadEnd);
            return Optional.of(preserveIndent ? body : trimCommonIndent(body));
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
                                int codeUnit = 0;
                                for (int k = 1; k <= 4; k++) {
                                    int digit = asciiHexValue(inner.charAt(i + k));
                                    if (digit < 0) {
                                        return Optional.empty();
                                    }
                                    codeUnit = (codeUnit << 4) | digit;
                                }
                                sb.append((char) codeUnit);
                                i += 4;
                            } else {
                                return Optional.empty();
                            }
                        }
                        default -> {
                            return Optional.empty();
                        }
                    }
                } else {
                    if (c < 0x20 || c == '"') {
                        return Optional.empty();
                    }
                    sb.append(c);
                }
            }
            return Optional.of(sb.toString());
        }
        return Optional.empty();
    }

    private static int asciiHexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return 10 + (c - 'a');
        }
        if (c >= 'A' && c <= 'F') {
            return 10 + (c - 'A');
        }
        return -1;
    }

    /**
     * Strips common leading ASCII space ({@code 0x20}) indentation across non-empty lines while
     * preserving verbatim horizontal tabs ({@code 0x09}) per STVN 2.0.0 Rule {@code STR-04}.
     *
     * @param text LF-normalized fenced string payload
     * @return dedented string payload matching {@code StvnPayloadLowerer.trimCommonIndent} 1:1
     */
    public static String trimCommonIndent(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (isAsciiSpacesOnly(line)) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (indent < minIndent) {
                minIndent = indent;
            }
        }
        if (minIndent == Integer.MAX_VALUE) {
            minIndent = 0;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if (!isAsciiSpacesOnly(line)) {
                out.append(minIndent <= line.length() ? line.substring(minIndent) : "");
            }
        }
        return out.toString();
    }

    /**
     * Evaluates whether a decoded simple string satisfies the STVN 2.0.0 Fenced-String Bias trigger
     * and 100% lossless round-trip soundness guard ({@code decode(print(s)) == s}).
     *
     * @param decoded        decoded UTF-16 string value
     * @param preserveIndent effective {@code #preserveIndent} flag for the target slot
     * @return {@code true} when fenced string emission is triggered and round-trip lossless
     */
    public static boolean shouldEmitFencedPretty(String decoded, boolean preserveIndent) {
        if (!containsSimpleEscapeTrigger(decoded) || containsForbiddenFencedControlChar(decoded)) {
            return false;
        }
        if (preserveIndent) {
            return true;
        }
        return !isAsciiSpacesOnly(decoded)
            && !hasNonEmptyAsciiSpaceOnlyLine(decoded)
            && trimCommonIndent(decoded).equals(decoded);
    }

    /**
     * Checks whether {@code text} contains any non-empty line consisting exclusively of ASCII spaces ({@code 0x20}).
     *
     * @param text LF-normalized string content
     * @return {@code true} if any non-empty line contains only ASCII spaces
     */
    public static boolean hasNonEmptyAsciiSpaceOnlyLine(String text) {
        int lineStart = 0;
        int len = text.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || text.charAt(i) == '\n') {
                if (i > lineStart) {
                    boolean allSpaces = true;
                    for (int j = lineStart; j < i; j++) {
                        if (text.charAt(j) != ' ') {
                            allSpaces = false;
                            break;
                        }
                    }
                    if (allSpaces) {
                        return true;
                    }
                }
                lineStart = i + 1;
            }
        }
        return false;
    }

    /**
     * Selects a collision-free Rule {@code STR-04} fence tag ({@code baseTag}, {@code baseTag_1}, ...)
     * whose closing delimiter {@code [TAG]"""} does not appear in {@code decoded}.
     *
     * @param decoded decoded string body
     * @param baseTag preferred base tag ({@code RAW} or {@code REGEX})
     * @return collision-free fence tag
     */
    public static String chooseSafeFenceTag(String decoded, String baseTag) {
        String candidate = baseTag;
        int suffix = 0;
        while (decoded.contains("[" + candidate + "]\"\"\"")) {
            suffix++;
            candidate = baseTag + "_" + suffix;
        }
        return candidate;
    }

    /**
     * Determines whether a constant identifier ends with {@code REGEX} or {@code _REGEX}.
     *
     * @param rawConstIdentifier constant identifier text (local or FQNI)
     * @return {@code true} if the constant identifier denotes a regex pattern constant
     */
    public static boolean isRegexConstantIdentifier(String rawConstIdentifier) {
        int slashIdx = rawConstIdentifier.lastIndexOf('/');
        String localName = slashIdx >= 0
            ? rawConstIdentifier.substring(slashIdx + 1)
            : (rawConstIdentifier.startsWith(StvnVocabulary.SIGIL_VALUE)
                ? rawConstIdentifier.substring(StvnVocabulary.SIGIL_VALUE.length())
                : rawConstIdentifier);
        return localName.endsWith("_" + StvnVocabulary.FENCE_TAG_REGEX)
            || localName.endsWith(StvnVocabulary.FENCE_TAG_REGEX);
    }

    private static boolean containsSimpleEscapeTrigger(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"' || c == '\n' || c == '\t') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsForbiddenFencedControlChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\t') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiSpacesOnly(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Formats a decoded UTF-8 string as a canonical single-line STVN simple string literal {@code "..."}.
     *
     * @param decoded decoded string content
     * @return canonically escaped STVN quoted string literal
     */
    public static String formatCanonicalQuotedString(String decoded) {
        StringBuilder sb = new StringBuilder(decoded.length() + 4);
        sb.append('"');
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
