package org.stvnadore.plugin.validation.quickfix;

import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure-IR and PSI-backed constraint evaluator, bound-neighbor calculator, and deterministic
 * inhabitant synthesizer for {@code :String} schemas ({@code B2-05}).
 * <p>
 * Enforces 1:1 parity with {@code StvnPayloadLowerer.validateStringValue}:
 * <ul>
 *   <li>{@code #minSize} and {@code #maxSize} measure Unicode code points ({@link String#codePointCount}).</li>
 *   <li>{@code #minIncl}, {@code #minExcl}, {@code #maxIncl}, and {@code #maxExcl} compare unsigned UTF-8 byte sequences.</li>
 *   <li>Every candidate string is verified against all active constraints prior to emission.</li>
 * </ul>
 */
@NullMarked
public final class StvnStringConstraintUtil {

    private static final int MAX_SYNTHESIS_LENGTH = 4096;
    private static final int MAX_TRUNCATE_LENGTH = 65536;

    private static final List<String> PRELUDE_AND_DOMAIN_CANDIDATES = List.of(
        "550e8400-e29b-41d4-a716-446655440000",
        "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "1.0.0",
        "user@example.com",
        "127.0.0.1",
        "https://example.com",
        "example_id",
        "example",
        "EXAMPLE",
        "12345",
        "AA00"
    );

    private StvnStringConstraintUtil() {
    }

    /**
     * Immutable snapshot of all active constraints governing a {@code :String} schema.
     *
     * @param minSize        minimum Unicode code-point count ({@code >= 0})
     * @param maxSize        maximum Unicode code-point count
     * @param lowerBound     optional unquoted lower lexicographical bound
     * @param lowerExclusive {@code true} for {@code #minExcl}, {@code false} for {@code #minIncl}
     * @param upperBound     optional unquoted upper lexicographical bound
     * @param upperExclusive {@code true} for {@code #maxExcl}, {@code false} for {@code #maxIncl}
     * @param compiledRegex  optional compiled regular expression pattern
     * @param rawRegex       optional raw unquoted regular expression string
     * @param invalidRegex   {@code true} if {@code rawRegex} failed {@link Pattern#compile}
     */
    public record StringConstraintProfile(
        long minSize,
        long maxSize,
        @Nullable String lowerBound,
        boolean lowerExclusive,
        @Nullable String upperBound,
        boolean upperExclusive,
        @Nullable Pattern compiledRegex,
        @Nullable String rawRegex,
        boolean invalidRegex
    ) {}

    /**
     * Constructs a {@link StringConstraintProfile} from resolved constraint parameters.
     *
     * @param minSize        optional minimum code-point length
     * @param maxSize        optional maximum code-point length
     * @param lowerBound     optional unquoted lower bound
     * @param lowerExclusive whether {@code lowerBound} is exclusive ({@code #minExcl})
     * @param upperBound     optional unquoted upper bound
     * @param upperExclusive whether {@code upperBound} is exclusive ({@code #maxExcl})
     * @param regexPattern   optional unquoted regular expression
     * @return immutable constraint profile
     */
    public static StringConstraintProfile createProfile(
            @Nullable Long minSize,
            @Nullable Long maxSize,
            @Nullable String lowerBound,
            boolean lowerExclusive,
            @Nullable String upperBound,
            boolean upperExclusive,
            @Nullable String regexPattern
    ) {
        long min = minSize != null ? Math.max(0L, minSize) : 0L;
        long max = maxSize != null ? Math.max(0L, maxSize) : StvnVocabulary.DEFAULT_MAX_SIZE.longValue();
        Pattern compiled = null;
        boolean badRegex = false;
        if (regexPattern != null && !regexPattern.isEmpty()) {
            try {
                compiled = Pattern.compile(regexPattern);
            } catch (PatternSyntaxException e) {
                badRegex = true;
            }
        }
        return new StringConstraintProfile(
            min,
            max,
            lowerBound,
            lowerExclusive,
            upperBound,
            upperExclusive,
            compiled,
            regexPattern,
            badRegex
        );
    }

    /**
     * Extracts a {@link StringConstraintProfile} directly from a pure-IR {@link StvnSchema.StringSchema}
     * when available, preserving {@link StvnSchema.BoundKind} inclusivity/exclusivity.
     *
     * @param ss pure-IR string schema
     * @return immutable constraint profile
     */
    public static StringConstraintProfile fromIrSchema(StvnSchema.StringSchema ss) {
        String lower = ss.lowerBound().map(StvnSchema.ContinuousBound::value).orElse(null);
        boolean lowerExcl = ss.lowerBound()
            .map(b -> b.kind() == StvnSchema.BoundKind.EXCLUSIVE)
            .orElse(false);
        String upper = ss.upperBound().map(StvnSchema.ContinuousBound::value).orElse(null);
        boolean upperExcl = ss.upperBound()
            .map(b -> b.kind() == StvnSchema.BoundKind.EXCLUSIVE)
            .orElse(false);
        return new StringConstraintProfile(
            ss.minSize().longValue(),
            ss.maxSize().longValue(),
            lower,
            lowerExcl,
            upper,
            upperExcl,
            ss.compiledRegex().orElse(null),
            ss.rawRegexPattern().orElse(null),
            false
        );
    }

    /**
     * Compares two strings in unsigned UTF-8 byte lexicographical order (1:1 with {@code StvnDefsLowerer}).
     *
     * @param a first string
     * @param b second string
     * @return negative, zero, or positive comparison result
     */
    public static int compareUtf8Lexicographical(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return Arrays.compareUnsigned(ba, bb);
    }

    /**
     * Returns the Unicode code-point length of {@code text}.
     *
     * @param text unquoted string
     * @return number of Unicode code points
     */
    public static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * Slices {@code text} by Unicode code-point indices without splitting surrogate pairs.
     *
     * @param text    source string
     * @param startCp inclusive start code-point index
     * @param endCp   exclusive end code-point index
     * @return substring spanning {@code [startCp, endCp)}
     */
    public static String substringByCodePoints(String text, int startCp, int endCp) {
        int startChar = text.offsetByCodePoints(0, startCp);
        int endChar = text.offsetByCodePoints(startChar, endCp - startCp);
        return text.substring(startChar, endChar);
    }

    /**
     * Verifies whether {@code candidate} satisfies 100% of active constraints in {@code profile}.
     *
     * @param candidate candidate unquoted string (or {@code null})
     * @param profile   governing constraint profile
     * @return {@code true} if valid across length, lower/upper bounds, and regex
     */
    public static boolean isValidCandidate(@Nullable String candidate, StringConstraintProfile profile) {
        if (candidate == null || profile.invalidRegex() || profile.minSize() > profile.maxSize()) {
            return false;
        }
        int cpLen = codePointLength(candidate);
        if (cpLen < profile.minSize() || cpLen > profile.maxSize()) {
            return false;
        }
        if (profile.lowerBound() != null) {
            int cmp = compareUtf8Lexicographical(candidate, profile.lowerBound());
            if (profile.lowerExclusive() ? cmp <= 0 : cmp < 0) {
                return false;
            }
        }
        if (profile.upperBound() != null) {
            int cmp = compareUtf8Lexicographical(candidate, profile.upperBound());
            if (profile.upperExclusive() ? cmp >= 0 : cmp > 0) {
                return false;
            }
        }
        if (profile.compiledRegex() != null && !profile.compiledRegex().matcher(candidate).matches()) {
            return false;
        }
        return true;
    }

    /**
     * Returns {@code true} if {@code authored} violates {@code profile}'s lower lexicographical bound.
     *
     * @param authored decoded authored string
     * @param profile  governing constraint profile
     * @return {@code true} when below {@code #minIncl} or {@code <= #minExcl}
     */
    public static boolean violatesLowerBound(String authored, StringConstraintProfile profile) {
        if (profile.lowerBound() == null) {
            return false;
        }
        int cmp = compareUtf8Lexicographical(authored, profile.lowerBound());
        return profile.lowerExclusive() ? cmp <= 0 : cmp < 0;
    }

    /**
     * Returns {@code true} if {@code authored} violates {@code profile}'s upper lexicographical bound.
     *
     * @param authored decoded authored string
     * @param profile  governing constraint profile
     * @return {@code true} when above {@code #maxIncl} or {@code >= #maxExcl}
     */
    public static boolean violatesUpperBound(String authored, StringConstraintProfile profile) {
        if (profile.upperBound() == null) {
            return false;
        }
        int cmp = compareUtf8Lexicographical(authored, profile.upperBound());
        return profile.upperExclusive() ? cmp >= 0 : cmp > 0;
    }

    /**
     * Computes a deterministic minimum-bound clamp candidate validated against {@code profile}.
     *
     * @param profile governing constraint profile
     * @return valid unquoted minimum bound neighbor, or {@code null} if none satisfies {@code profile}
     */
    public static @Nullable String computeMinBoundCandidate(StringConstraintProfile profile) {
        String lb = profile.lowerBound();
        if (lb == null || profile.invalidRegex()) {
            return null;
        }
        Set<String> candidates = new LinkedHashSet<>();
        if (!profile.lowerExclusive()) {
            candidates.add(lb);
            addRightPaddedVariants(candidates, lb, profile.minSize());
        } else {
            String incremented = incrementWithinAsciiClasses(lb);
            if (incremented != null) {
                candidates.add(incremented);
                addRightPaddedVariants(candidates, incremented, profile.minSize());
            }
            for (int pos = lb.length() - 1; pos >= 0; pos--) {
                char ch = lb.charAt(pos);
                if (ch >= 0x21 && ch < 0x7E) {
                    String stepped = lb.substring(0, pos) + (char) (ch + 1) + lb.substring(pos + 1);
                    candidates.add(stepped);
                    addRightPaddedVariants(candidates, stepped, profile.minSize());
                }
            }
            if (codePointLength(lb) < profile.maxSize()) {
                for (char pad : new char[]{'0', 'A', 'a', '1', '_', '!'}) {
                    String appended = lb + pad;
                    candidates.add(appended);
                    addRightPaddedVariants(candidates, appended, profile.minSize());
                }
            }
        }
        for (String c : candidates) {
            if (isValidCandidate(c, profile)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Computes a deterministic maximum-bound clamp candidate validated against {@code profile}.
     *
     * @param profile governing constraint profile
     * @return valid unquoted maximum bound neighbor, or {@code null} if none satisfies {@code profile}
     */
    public static @Nullable String computeMaxBoundCandidate(StringConstraintProfile profile) {
        String ub = profile.upperBound();
        if (ub == null || profile.invalidRegex()) {
            return null;
        }
        Set<String> candidates = new LinkedHashSet<>();
        int ubCp = codePointLength(ub);
        if (!profile.upperExclusive()) {
            candidates.add(ub);
            if (ubCp > profile.maxSize() && profile.maxSize() <= MAX_TRUNCATE_LENGTH) {
                candidates.add(substringByCodePoints(ub, 0, (int) profile.maxSize()));
            }
        } else {
            if (ubCp > profile.maxSize() && profile.maxSize() <= MAX_TRUNCATE_LENGTH) {
                candidates.add(substringByCodePoints(ub, 0, (int) profile.maxSize()));
            }
            String decremented = decrementWithinAsciiClasses(ub);
            if (decremented != null) {
                candidates.add(decremented);
                addMaxFillPaddedVariants(candidates, decremented, profile.minSize());
            }
            for (int pos = ub.length() - 1; pos >= 0; pos--) {
                char ch = ub.charAt(pos);
                if (ch > 0x21 && ch <= 0x7E) {
                    String stepped = ub.substring(0, pos) + (char) (ch - 1) + ub.substring(pos + 1);
                    candidates.add(stepped);
                }
            }
            if (ubCp > profile.minSize() && ubCp > 0) {
                candidates.add(substringByCodePoints(ub, 0, ubCp - 1));
            }
        }
        for (String c : candidates) {
            if (isValidCandidate(c, profile)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Truncates {@code authored} to {@code profile.maxSize()} code points when valid under {@code profile}.
     *
     * @param authored decoded authored string
     * @param profile  governing constraint profile
     * @return validated truncated string, or {@code null}
     */
    public static @Nullable String computeTruncatedCandidate(String authored, StringConstraintProfile profile) {
        int cpLen = codePointLength(authored);
        if (cpLen <= profile.maxSize() || profile.maxSize() > MAX_TRUNCATE_LENGTH || profile.maxSize() < 0) {
            return null;
        }
        String truncated = substringByCodePoints(authored, 0, (int) profile.maxSize());
        return isValidCandidate(truncated, profile) ? truncated : null;
    }

    /**
     * Pads {@code authored} up to {@code profile.minSize()} code points when valid under {@code profile}.
     *
     * @param authored decoded authored string
     * @param profile  governing constraint profile
     * @return validated padded string, or {@code null}
     */
    public static @Nullable String computePaddedCandidate(String authored, StringConstraintProfile profile) {
        int cpLen = codePointLength(authored);
        if (cpLen >= profile.minSize() || profile.minSize() > MAX_SYNTHESIS_LENGTH) {
            return null;
        }
        int needed = (int) profile.minSize() - cpLen;
        List<Character> padChars = new ArrayList<>(List.of('0', 'A', 'a', '_', '1'));
        if (!authored.isEmpty()) {
            char last = authored.charAt(authored.length() - 1);
            if (!padChars.contains(last)) {
                padChars.add(0, last);
            }
        }
        for (char pad : padChars) {
            String candidate = authored + String.valueOf(pad).repeat(needed);
            if (isValidCandidate(candidate, profile)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Synthesizes a valid unquoted exemplar inhabitant for {@code profile}, or {@code null} if no
     * verified inhabitant can be constructed.
     *
     * @param profile governing constraint profile
     * @return verified unquoted exemplar string, or {@code null}
     */
    public static @Nullable String synthesizeExemplarInhabitant(StringConstraintProfile profile) {
        if (profile.invalidRegex() || profile.minSize() > profile.maxSize()) {
            return null;
        }
        if (profile.maxSize() == 0L) {
            return isValidCandidate("", profile) ? "" : null;
        }
        if (profile.compiledRegex() == null && isValidCandidate("example", profile)) {
            return "example";
        }
        String minCand = computeMinBoundCandidate(profile);
        if (minCand != null) {
            return minCand;
        }
        String maxCand = computeMaxBoundCandidate(profile);
        if (maxCand != null) {
            return maxCand;
        }
        for (String preset : PRELUDE_AND_DOMAIN_CANDIDATES) {
            if (isValidCandidate(preset, profile)) {
                return preset;
            }
        }
        if (profile.rawRegex() != null && !profile.rawRegex().isBlank()) {
            for (String regexSample : synthesizeAnchoredRegexSamples(profile.rawRegex(), profile.minSize(), profile.maxSize())) {
                if (isValidCandidate(regexSample, profile)) {
                    return regexSample;
                }
            }
        }
        if (profile.minSize() <= MAX_SYNTHESIS_LENGTH) {
            String base = "example";
            int cp = codePointLength(base);
            if (cp > profile.maxSize() && profile.maxSize() <= MAX_TRUNCATE_LENGTH) {
                String cut = substringByCodePoints(base, 0, (int) profile.maxSize());
                if (isValidCandidate(cut, profile)) {
                    return cut;
                }
            } else if (cp < profile.minSize()) {
                String padded = base + "a".repeat((int) profile.minSize() - cp);
                if (isValidCandidate(padded, profile)) {
                    return padded;
                }
            }
            int targetLen = (int) Math.max(1L, Math.min(profile.maxSize(), Math.max(profile.minSize(), 4L)));
            if (targetLen <= MAX_SYNTHESIS_LENGTH) {
                for (char fill : new char[]{'a', 'A', '0', '1'}) {
                    String repeated = String.valueOf(fill).repeat(targetLen);
                    if (isValidCandidate(repeated, profile)) {
                        return repeated;
                    }
                }
            }
        }
        return null;
    }

    private static void addRightPaddedVariants(Set<String> out, String base, long minSize) {
        int cp = codePointLength(base);
        if (cp < minSize && minSize <= MAX_SYNTHESIS_LENGTH) {
            int padCount = (int) minSize - cp;
            for (char ch : new char[]{'0', 'A', 'a', '_'}) {
                out.add(base + String.valueOf(ch).repeat(padCount));
            }
        }
    }

    private static void addMaxFillPaddedVariants(Set<String> out, String base, long minSize) {
        int cp = codePointLength(base);
        if (cp < minSize && minSize <= MAX_SYNTHESIS_LENGTH) {
            int padCount = (int) minSize - cp;
            for (char ch : new char[]{'9', 'Z', 'z'}) {
                out.add(base + String.valueOf(ch).repeat(padCount));
            }
        }
    }

    private static @Nullable String incrementWithinAsciiClasses(String s) {
        if (s.isEmpty()) return null;
        char[] chars = s.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            char c = chars[i];
            if (c >= '0' && c < '9') {
                chars[i] = (char) (c + 1);
                return new String(chars);
            } else if (c == '9') {
                chars[i] = '0';
            } else if (c >= 'A' && c < 'Z') {
                chars[i] = (char) (c + 1);
                return new String(chars);
            } else if (c == 'Z') {
                chars[i] = 'A';
            } else if (c >= 'a' && c < 'z') {
                chars[i] = (char) (c + 1);
                return new String(chars);
            } else if (c == 'z') {
                chars[i] = 'a';
            } else {
                return null;
            }
        }
        return null;
    }

    private static @Nullable String decrementWithinAsciiClasses(String s) {
        if (s.isEmpty()) return null;
        char[] chars = s.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            char c = chars[i];
            if (c > '0' && c <= '9') {
                chars[i] = (char) (c - 1);
                return new String(chars);
            } else if (c == '0') {
                chars[i] = '9';
            } else if (c > 'A' && c <= 'Z') {
                chars[i] = (char) (c - 1);
                return new String(chars);
            } else if (c == 'A') {
                chars[i] = 'Z';
            } else if (c > 'a' && c <= 'z') {
                chars[i] = (char) (c - 1);
                return new String(chars);
            } else if (c == 'a') {
                chars[i] = 'z';
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * Synthesizes deterministic minimal strings from common anchored STVN regular expressions
     * (character classes, literals, groups, top-level alternations, and quantifiers).
     */
    static List<String> synthesizeAnchoredRegexSamples(String rawRegex, long minSize, long maxSize) {
        String body = rawRegex;
        if (body.startsWith("^")) {
            body = body.substring(1);
        }
        if (body.endsWith("$") && !body.endsWith("\\$")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> branches = splitTopLevelAlternations(body);
        List<String> results = new ArrayList<>();
        long boundedMin = Math.min(minSize, MAX_SYNTHESIS_LENGTH);
        for (boolean preferHighClassChar : new boolean[]{false, true}) {
            for (String branch : branches) {
                String minSample = expandRegexBranch(branch, 0, preferHighClassChar);
                if (minSample != null) {
                    results.add(minSample);
                    int deficit = (int) Math.max(0L, boundedMin - codePointLength(minSample));
                    if (deficit > 0 && codePointLength(minSample) + deficit <= maxSize) {
                        String stretched = expandRegexBranch(branch, deficit, preferHighClassChar);
                        if (stretched != null) {
                            results.add(stretched);
                        }
                    }
                }
            }
        }
        return results;
    }

    private static List<String> splitTopLevelAlternations(String expr) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inClass = false;
        int last = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (!inClass && c == '(') depth++;
            else if (!inClass && c == ')' && depth > 0) depth--;
            else if (!inClass && depth == 0 && c == '|') {
                parts.add(expr.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(expr.substring(last));
        return parts;
    }

    private static @Nullable String expandRegexBranch(String expr, int extraBudget, boolean preferHigh) {
        StringBuilder sb = new StringBuilder();
        int remainingExtra = extraBudget;
        int i = 0;
        while (i < expr.length() && sb.length() <= MAX_SYNTHESIS_LENGTH) {
            char c = expr.charAt(i);
            String atomSample;
            int nextIdx;
            if (c == '(') {
                int close = findMatchingParen(expr, i);
                if (close < 0) return null;
                String inner = expr.substring(i + 1, close);
                if (inner.startsWith("?:")) {
                    inner = inner.substring(2);
                } else if (inner.startsWith("?")) {
                    return null;
                }
                List<String> subBranches = splitTopLevelAlternations(inner);
                int branchIdx = (preferHigh && subBranches.size() > 1) ? subBranches.size() - 1 : 0;
                atomSample = subBranches.isEmpty() ? "" : expandRegexBranch(subBranches.get(branchIdx), 0, preferHigh);
                if (atomSample == null) return null;
                nextIdx = close + 1;
            } else if (c == '[') {
                int close = findClosingClassBracket(expr, i);
                if (close < 0) return null;
                Character ch = pickCharFromClass(expr.substring(i + 1, close), preferHigh);
                if (ch == null) return null;
                atomSample = String.valueOf(ch);
                nextIdx = close + 1;
            } else if (c == '\\') {
                if (i + 1 >= expr.length()) return null;
                char esc = expr.charAt(i + 1);
                atomSample = switch (esc) {
                    case 'd' -> preferHigh ? "9" : "0";
                    case 'w' -> preferHigh ? "z" : "a";
                    case 's' -> " ";
                    case 'D', 'W' -> "-";
                    case 'b', 'B', 'A', 'z', 'Z' -> "";
                    default -> String.valueOf(esc);
                };
                nextIdx = i + 2;
            } else if (c == '.') {
                atomSample = preferHigh ? "z" : "a";
                nextIdx = i + 1;
            } else if (c == '^' || c == '$') {
                atomSample = "";
                nextIdx = i + 1;
            } else {
                atomSample = String.valueOf(c);
                nextIdx = i + 1;
            }

            int minRep = 1;
            int maxRep = 1;
            if (nextIdx < expr.length()) {
                char q = expr.charAt(nextIdx);
                if (q == '?') {
                    minRep = 0;
                    maxRep = 1;
                    nextIdx++;
                } else if (q == '*') {
                    minRep = 0;
                    maxRep = MAX_SYNTHESIS_LENGTH;
                    nextIdx++;
                } else if (q == '+') {
                    minRep = 1;
                    maxRep = MAX_SYNTHESIS_LENGTH;
                    nextIdx++;
                } else if (q == '{') {
                    int closeBrace = expr.indexOf('}', nextIdx + 1);
                    if (closeBrace > nextIdx) {
                        String spec = expr.substring(nextIdx + 1, closeBrace);
                        int comma = spec.indexOf(',');
                        try {
                            if (comma < 0) {
                                minRep = Integer.parseInt(spec.trim());
                                maxRep = minRep;
                            } else {
                                minRep = Integer.parseInt(spec.substring(0, comma).trim());
                                String hi = spec.substring(comma + 1).trim();
                                maxRep = hi.isEmpty() ? MAX_SYNTHESIS_LENGTH : Integer.parseInt(hi);
                            }
                            nextIdx = closeBrace + 1;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (nextIdx < expr.length() && expr.charAt(nextIdx) == '?') {
                    nextIdx++;
                }
            }
            int reps = minRep;
            if (remainingExtra > 0 && !atomSample.isEmpty() && maxRep > minRep) {
                int canAdd = Math.min(maxRep - minRep, (remainingExtra + atomSample.length() - 1) / atomSample.length());
                reps += canAdd;
                remainingExtra = Math.max(0, remainingExtra - canAdd * atomSample.length());
            }
            if ((long) reps * atomSample.length() > MAX_SYNTHESIS_LENGTH) {
                return null;
            }
            sb.append(atomSample.repeat(reps));
            i = nextIdx;
        }
        return sb.toString();
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        boolean inClass = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (!inClass && c == '(') depth++;
            else if (!inClass && c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int findClosingClassBracket(String s, int openIdx) {
        for (int i = openIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == ']' && i > openIdx + 1) {
                return i;
            }
        }
        return -1;
    }

    private static @Nullable Character pickCharFromClass(String cls, boolean preferHigh) {
        if (cls.isEmpty()) return null;
        if (cls.startsWith("^")) {
            char[] probes = preferHigh
                ? new char[]{'z', 'Z', '9', '_', '-'}
                : new char[]{'a', 'A', '0', '_', '-'};
            for (char probe : probes) {
                if (cls.indexOf(probe) < 0) return probe;
            }
            return null;
        }
        List<Character> expanded = new ArrayList<>();
        for (int i = 0; i < cls.length(); i++) {
            char c = cls.charAt(i);
            if (c == '\\' && i + 1 < cls.length()) {
                char esc = cls.charAt(++i);
                char mapped = switch (esc) {
                    case 'd' -> preferHigh ? '9' : '0';
                    case 'w' -> preferHigh ? 'z' : 'a';
                    case 's' -> ' ';
                    default -> esc;
                };
                expanded.add(mapped);
            } else if (i + 2 < cls.length() && cls.charAt(i + 1) == '-' && cls.charAt(i + 2) != '\\') {
                char end = cls.charAt(i + 2);
                if (c >= 0x20 && c <= 0x7E && end >= c && end <= 0x7E) {
                    expanded.add(c);
                    expanded.add(end);
                }
                i += 2;
            } else if (c >= 0x20 && c <= 0x7E) {
                expanded.add(c);
            }
        }
        if (expanded.isEmpty()) return null;
        return preferHigh ? expanded.get(expanded.size() - 1) : expanded.get(0);
    }
}
