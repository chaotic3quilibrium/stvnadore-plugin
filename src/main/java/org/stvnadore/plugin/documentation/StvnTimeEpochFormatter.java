package org.stvnadore.plugin.documentation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.settings.StvnSettings;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Utility for BigInteger temporal tick de-scaling, 12h/24h UTC formatting,
 * and magnitude discrepancy analysis for {@code :TimeEpoch} literals.
 */
@NullMarked
public final class StvnTimeEpochFormatter {

    private static final BigInteger THOUSAND = BigInteger.valueOf(1000L);
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000L);
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);

    private static final BigInteger INSTANT_MAX_SECONDS = BigInteger.valueOf(31556889864403199L);
    private static final BigInteger INSTANT_MIN_SECONDS = BigInteger.valueOf(-31557014167219200L);

    private static final DateTimeFormatter DATE_PART_FORMATTER =
        DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, 'at' ", Locale.US);

    private StvnTimeEpochFormatter() {}

    /**
     * Temporal epoch de-scaling and formatting result.
     *
     * @param formattedUtc human-readable UTC timestamp
     * @param year computed calendar year, or null if astronomical
     * @param isCivilWindowViolation true if year &lt; 1900 or year &gt; 2100
     * @param isMagnitudeMismatch true if digit length deviates from declared scale band
     * @param expectedBandDescription expected digit band (e.g. "9..10 digits for #s")
     * @param detectedBandDescription detected digit band (e.g. "13 digits (suggesting #ms)")
     * @param recommendedScale recommended scale flag if mismatch detected
     */
    public record EpochFormatResult(
        String formattedUtc,
        @Nullable Integer year,
        boolean isCivilWindowViolation,
        boolean isMagnitudeMismatch,
        String expectedBandDescription,
        @Nullable String detectedBandDescription,
        @Nullable String recommendedScale
    ) {}

    /**
     * De-scales raw ticks via BigInteger and formats UTC representation according to active settings.
     *
     * @param rawTicks text representation of integer tick literal
     * @param scaleFlag declared scale flag (#s, #ms, #us, #ns)
     * @param hourFormat 12-hour vs 24-hour presentation format
     * @return structured formatting and magnitude analysis result
     */
    public static EpochFormatResult format(
        String rawTicks,
        String scaleFlag,
        StvnSettings.TimeEpochHourFormat hourFormat
    ) {
        String cleanTicks = rawTicks.trim();
        BigInteger ticks;
        try {
            ticks = new BigInteger(cleanTicks);
        } catch (Exception e) {
            return new EpochFormatResult(
                "Invalid Integer Literal: " + rawTicks,
                null,
                true,
                true,
                "Valid Integer",
                null,
                null
            );
        }

        BigInteger divisor;
        long nanoMultiplier;
        int fractionDigits;
        int minExpectedDigits;
        int maxExpectedDigits;

        switch (scaleFlag) {
            case StvnVocabulary.FACET_KW_SCALE_NS -> {
                divisor = BILLION;
                nanoMultiplier = 1L;
                fractionDigits = 9;
                minExpectedDigits = 18;
                maxExpectedDigits = 19;
            }
            case StvnVocabulary.FACET_KW_SCALE_US -> {
                divisor = MILLION;
                nanoMultiplier = 1_000L;
                fractionDigits = 6;
                minExpectedDigits = 15;
                maxExpectedDigits = 16;
            }
            case StvnVocabulary.FACET_KW_SCALE_MS -> {
                divisor = THOUSAND;
                nanoMultiplier = 1_000_000L;
                fractionDigits = 3;
                minExpectedDigits = 12;
                maxExpectedDigits = 13;
            }
            default -> { // #s
                divisor = BigInteger.ONE;
                nanoMultiplier = 1_000_000_000L;
                fractionDigits = 0;
                minExpectedDigits = 9;
                maxExpectedDigits = 10;
            }
        }

        BigInteger[] divRem = ticks.divideAndRemainder(divisor);
        BigInteger sec = divRem[0];
        BigInteger rem = divRem[1];

        // Euclidean division normalization for negative epoch ticks
        if (rem.signum() < 0) {
            sec = sec.subtract(BigInteger.ONE);
            rem = rem.add(divisor);
        }

        long nanos = rem.multiply(BigInteger.valueOf(nanoMultiplier)).longValue();

        boolean astronomical = sec.compareTo(INSTANT_MAX_SECONDS) > 0 || sec.compareTo(INSTANT_MIN_SECONDS) < 0;
        Integer year = null;
        boolean civilWindowViolation = true;
        String formattedTime;

        if (astronomical) {
            formattedTime = "Astronomical (> 1 billion years)";
        } else {
            try {
                Instant instant = Instant.ofEpochSecond(sec.longValue(), nanos);
                ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
                year = zdt.getYear();
                civilWindowViolation = (year < 1900 || year > 2100);
                formattedTime = formatZdt(zdt, fractionDigits, hourFormat);
            } catch (DateTimeException e) {
                astronomical = true;
                formattedTime = "Astronomical (> 1 billion years)";
            }
        }

        // Magnitude band evaluation
        String digitStr = cleanTicks.startsWith("-") ? cleanTicks.substring(1) : cleanTicks;
        int digitCount = digitStr.length();

        String detectedScale = detectScaleBand(digitCount);
        boolean magnitudeMismatch = false;
        if (digitCount > 0 && !cleanTicks.equals("0")) {
            if (digitCount < minExpectedDigits || digitCount > maxExpectedDigits) {
                if (detectedScale != null && !detectedScale.equals(scaleFlag)) {
                    magnitudeMismatch = true;
                } else if (Math.abs(digitCount - minExpectedDigits) >= 3) {
                    magnitudeMismatch = true;
                }
            }
        }

        String expectedDesc = minExpectedDigits + ".." + maxExpectedDigits + " digits for " + scaleFlag;
        String detectedDesc = detectedScale != null
            ? digitCount + " digits (suggesting " + detectedScale + ")"
            : digitCount + " digits";

        return new EpochFormatResult(
            formattedTime,
            year,
            civilWindowViolation,
            magnitudeMismatch,
            expectedDesc,
            detectedDesc,
            detectedScale
        );
    }

    /**
     * Classifies digit length into canonical magnitude bands.
     *
     * @param digitCount count of decimal digits (excluding negative sign)
     * @return detected scale flag (#s, #ms, #us, #ns), or null if unclassified
     */
    public static @Nullable String detectScaleBand(int digitCount) {
        if (digitCount >= 9 && digitCount <= 10) return StvnVocabulary.FACET_KW_SCALE_S;
        if (digitCount >= 12 && digitCount <= 13) return StvnVocabulary.FACET_KW_SCALE_MS;
        if (digitCount >= 15 && digitCount <= 16) return StvnVocabulary.FACET_KW_SCALE_US;
        if (digitCount >= 18 && digitCount <= 19) return StvnVocabulary.FACET_KW_SCALE_NS;
        return null;
    }

    private static String formatZdt(
        ZonedDateTime zdt,
        int fractionDigits,
        StvnSettings.TimeEpochHourFormat hourFormat
    ) {
        var sb = new StringBuilder();
        sb.append(DATE_PART_FORMATTER.format(zdt));

        int hour = zdt.getHour();
        int minute = zdt.getMinute();
        int second = zdt.getSecond();
        int nano = zdt.getNano();

        if (hourFormat == StvnSettings.TimeEpochHourFormat.HOUR_12) {
            int h12 = hour % 12;
            if (h12 == 0) h12 = 12;
            String amPm = hour >= 12 ? "PM" : "AM";
            sb.append(String.format(Locale.US, "%d:%02d:%02d", h12, minute, second));
            appendFraction(sb, nano, fractionDigits);
            sb.append(" ").append(amPm).append(" UTC");
        } else {
            sb.append(String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second));
            appendFraction(sb, nano, fractionDigits);
            sb.append(" UTC");
        }

        return sb.toString();
    }

    private static void appendFraction(StringBuilder sb, int nano, int fractionDigits) {
        if (fractionDigits <= 0) return;
        String nanoPadded = String.format(Locale.US, "%09d", nano);
        sb.append(".").append(nanoPadded, 0, fractionDigits);
    }
}
