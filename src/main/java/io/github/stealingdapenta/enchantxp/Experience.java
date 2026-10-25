package io.github.stealingdapenta.enchantxp;

/** Integer XP accounting for the vanilla 1.12 level curves, independent of lifetime score. */
public final class Experience {
    public static final int MAX_TOTAL = Integer.MAX_VALUE;
    private static final int LEVEL_SEARCH_LIMIT = 30000;

    private Experience() {
    }

    public static long atLevel(int level) {
        if (level < 0 || level > LEVEL_SEARCH_LIMIT) {
            throw new IllegalArgumentException("XP level is outside the supported accounting range");
        }
        long value = level;
        if (level <= 16) {
            return value * value + 6 * value;
        }
        if (level <= 31) {
            return (5 * value * value - 81 * value + 720) / 2;
        }
        return (9 * value * value - 325 * value + 4440) / 2;
    }

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

    /** Round float progress to the nearest whole point; reject rather than truncate over-cap balances. */
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

    public static final class Balance {
        private final int total;
        private final int level;
        private final float progress;

        private Balance(int total, int level, float progress) {
            this.total = total;
            this.level = level;
            this.progress = progress;
        }

        public int total() {
            return total;
        }

        public int level() {
            return level;
        }

        public float progress() {
            return progress;
        }
    }
}
