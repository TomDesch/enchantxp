package io.github.stealingdapenta.enchantxp;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceTest {
    @Test
    void curvesMatchBoundaryBalancesAndNextLevelCosts() {
        int[] levels = {0, 1, 15, 16, 17, 30, 31, 32, 100};
        long[] totals = {0, 7, 315, 352, 394, 1395, 1507, 1628, 30970};
        for (int i = 0; i < levels.length; i++) {
            assertEquals(totals[i], Experience.atLevel(levels[i]));
            assertEquals(Experience.atLevel(levels[i] + 1) - totals[i], Experience.toNextLevel(levels[i]));
        }
    }

    @Test
    void totalRoundsFloatProgressToNearestPointIncludingFullProgress() {
        assertEquals(400, Experience.total(17, 6.0f / 47));
        assertEquals(353, Experience.total(16, 0.5f / 42));
        assertEquals(352, Experience.total(16, 0.49f / 42));
        assertEquals(394, Experience.total(16, 1));
    }

    @Test
    void reconstructionRoundTripsEachLowPointAndLargeRandomBalances() {
        for (int total = 0; total < 10000; total++) {
            roundTrip(total);
        }
        Random random = new Random(1122);
        for (int i = 0; i < 20000; i++) {
            roundTrip(random.nextInt(Integer.MAX_VALUE));
        }
        roundTrip(Integer.MAX_VALUE);
        roundTrip(Integer.MAX_VALUE - 1);
    }

    @Test
    void reconstructionRoundTripsLevelBoundariesUpToMaximum() {
        for (int level = 1; Experience.atLevel(level) <= Integer.MAX_VALUE; level++) {
            int total = (int) Experience.atLevel(level);
            roundTrip(total - 1);
            roundTrip(total);
            roundTrip(total + 1);
        }
    }

    @Test
    void totalsRejectInvalidProgressLevelsAndOverflowWithoutClamping() {
        assertThrows(IllegalArgumentException.class, () -> Experience.total(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Experience.total(Integer.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> Experience.total(30000, 0));
        for (float progress : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.01f}) {
            assertThrows(IllegalArgumentException.class, () -> Experience.total(1, progress));
        }
        assertThrows(IllegalArgumentException.class, () -> Experience.balance(-1));
        Experience.Balance maximum = Experience.balance(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> Experience.total(maximum.level(), 1));
    }

    private static void roundTrip(int total) {
        Experience.Balance balance = Experience.balance(total);
        assertEquals(total, Experience.total(balance.level(), balance.progress()));
        assertTrue(balance.progress() >= 0 && balance.progress() < 1);
    }
}
