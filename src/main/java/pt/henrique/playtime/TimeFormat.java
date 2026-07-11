package pt.henrique.playtime;

import java.util.Locale;

/**
 * Pure tick-to-text formatting (20 ticks = 1 second). Decimal hours honor the
 * receiver's locale (comma for pt_PT, point for en_US); stateless and
 * thread-safe.
 */
public final class TimeFormat {

    private static final long TICKS_PER_MINUTE = 20L * 60L;
    private static final double TICKS_PER_HOUR = 20.0 * 3600.0;

    private TimeFormat() {
    }

    /** Full form: {@code 2d 4h 32m}, {@code 4h 32m}, {@code 32m} (never empty). */
    public static String dhm(long ticks) {
        long totalMinutes = Math.max(0, ticks) / TICKS_PER_MINUTE;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (days > 0 || hours > 0) {
            out.append(hours).append("h ");
        }
        out.append(minutes).append('m');
        return out.toString();
    }

    /** Compact form for lists — the two most significant units: {@code 2d 4h}, {@code 4h 32m}, {@code 32m}. */
    public static String compact(long ticks) {
        long totalMinutes = Math.max(0, ticks) / TICKS_PER_MINUTE;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    /** Decimal hours in the given locale: {@code 27,5} (pt_PT) / {@code 27.5} (en_US). */
    public static String decimalHours(long ticks, Locale locale) {
        return String.format(locale, "%.1f", Math.max(0, ticks) / TICKS_PER_HOUR);
    }
}
