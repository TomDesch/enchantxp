package io.github.stealingdapenta.enchantxp.enchanting;

/**
 * Integer XP accounting for the vanilla 1.12 level curves, independent of lifetime score.
 */
public final class Experience {

    public static final int MAX_TOTAL = Integer.MAX_VALUE;
    private static final int LEVEL_SEARCH_LIMIT = 30000;

    private Experience() {
    }

    /**
     * Returns cumulative raw XP at the start of a level in `0..30000`.
     *
     * - Levels `0..16`: `level^2 + 6 * level`.
     * - Levels `17..31`: `(5 * level^2 - 81 * level + 720) / 2`.
     * - Levels `32..30000`: `(9 * level^2 - 325 * level + 4440) / 2`.
     *
     * Long arithmetic preserves exact totals beyond the signed-integer balance cap.
     *
     * @throws IllegalArgumentException if the level is outside the accounting range
     */
    public static long atLevel(int level) {
        if (level < 0 || level > LEVEL_SEARCH_LIMIT) {
            throw new IllegalArgumentException("XP level is outside the supported accounting range");
        }
        if (level <= 16) {
            return (long) level * (long) level + 6 * (long) level;
        }
        if (level <= 31) {
            return (5 * (long) level * level - 81 * (long) level + 720) / 2;
        }
        return (9 * (long) level * level - 325 * (long) level + 4440) / 2;
    }

    /**
     * Returns raw XP needed to advance from a level in `0..30000`, using the vanilla boundaries at 15 and 30.
     *
     * @throws IllegalArgumentException if the level is outside the accounting range
     */
    public static int toNextLevel(int level) {
        atLevel(level);
        if (level <= 15) {
            return 2 * level + 7;
        }
        if (level <= 30) {
            return 5 * level - 38;
        }
        return 9 * level - 158;
    }

    /**
     * Converts a level in `0..30000` and finite progress in `0..1` into current raw XP, not lifetime score.
     * Rounds progress to the nearest whole point; rejects rather than truncates over-cap balances.
     *
     * @throws IllegalArgumentException if either input is invalid or the result exceeds `MAX_TOTAL`
     */
    public static int total(int level, float progress) {
        if (Float.isNaN(progress) || Float.isInfinite(progress) || progress < 0 || progress > 1) {
            throw new IllegalArgumentException("Invalid XP progress");
        }
        long total = atLevel(level) + Math.round((double) progress * toNextLevel(level));
        if (total > MAX_TOTAL) {
            throw new IllegalArgumentException("XP exceeds the supported maximum of " + MAX_TOTAL);
        }
        return (int) total;
    }

    /**
     * Decomposes nonnegative raw XP into its highest complete level and fractional progress.
     *
     * @throws IllegalArgumentException if total is negative
     */
    public static Balance balance(int total) {
        if (total < 0) {
            throw new IllegalArgumentException("XP cannot be negative");
        }
        int low = 0;
        int high = LEVEL_SEARCH_LIMIT;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (atLevel(middle) <= total) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        int points = (int) (total - atLevel(low));
        return new Balance(total, low, (float) ((double) points / toNextLevel(low)));
    }

    /**
     * Immutable current balance; lifetime score is deliberately excluded.
     */
    public static final class Balance {

        private final int total;
        private final int level;
        private final float progress;

        private Balance(int total, int level, float progress) {
            this.total = total;
            this.level = level;
            this.progress = progress;
        }

        /** Returns current raw XP in `0..MAX_TOTAL`. */
        public int total() {
            return total;
        }

        /** Returns the highest level fully paid by this balance. */
        public int level() {
            return level;
        }

        /** Returns fractional progress toward the next level, suitable for Bukkit's XP bar. */
        public float progress() {
            return progress;
        }
    }
}
