package io.github.stealingdapenta.enchantxp.configuration.values;

/**
 * Immutable shared numeric contracts. YAML remains strictly typed; chat input supplies digits.
 * Bounds, exceptional sentinels and display units belong to the setting, independent of its UI.
 */
public final class NumericRule {

    private static final int MAX_CONFIG_NUMBER = 1000000;
    private static final int MAX_ENCHANTMENT_LEVEL = 255;
    public static final NumericRule PRICE = integer(1, MAX_CONFIG_NUMBER, null, "");
    public static final NumericRule MAX_COST = integer(1, MAX_CONFIG_NUMBER, -1, "");
    public static final NumericRule LEVEL_CAP = integer(0, MAX_ENCHANTMENT_LEVEL, null, "");
    public static final NumericRule LEVEL = integer(1, MAX_ENCHANTMENT_LEVEL, null, "");
    public static final NumericRule ROWS = integer(3, 6, null, "");
    public static final NumericRule PERCENT = integer(0, MAX_CONFIG_NUMBER, null, "%");
    public static final NumericRule SHELVES = integer(1, 15, null, "");
    public static final NumericRule ITEM_DATA = integer(0, Short.MAX_VALUE, null, "");
    public static final NumericRule VOLUME = new NumericRule(false, 0, 4, null, "");
    public static final NumericRule PITCH = new NumericRule(false, 0, 2, null, "");

    private final boolean whole;
    private final double minimum;
    private final double maximum;
    private final Integer sentinel;
    private final String unit;

    private NumericRule(boolean whole, double minimum, double maximum, Integer sentinel, String unit) {
        this.whole = whole;
        this.minimum = minimum;
        this.maximum = maximum;
        this.sentinel = sentinel;
        this.unit = unit;
    }

    private static NumericRule integer(int minimum, int maximum, Integer sentinel, String unit) {
        return new NumericRule(true, minimum, maximum, sentinel, unit);
    }

    public boolean whole() {
        return whole;
    }

    public int maximumInteger() {
        return (int) maximum;
    }

    /** Reads YAML integers without accepting decimal, string or boolean coercion. */
    public int readInteger(Object value, String path) {
        if (!whole || (!(value instanceof Integer) && !(value instanceof Long))) {
            throw new IllegalArgumentException(path + " must be a whole integer");
        }
        long number = ((Number) value).longValue();
        if (!accepts(number)) {
            throw new IllegalArgumentException(path + " must be " + range());
        }
        return (int) number;
    }

    /** Reads a finite YAML number; numeric strings remain invalid. */
    public double readDecimal(Object value, String path) {
        if (whole || !(value instanceof Number)) {
            throw new IllegalArgumentException(path + " must be numeric");
        }
        double number = ((Number) value).doubleValue();
        if (!accepts(number)) {
            throw new IllegalArgumentException(path + " must be finite and " + range());
        }
        return number;
    }

    /** Parses chat with the same bounds as YAML, retaining integer overflow and finite-number checks. */
    public Number parse(String input) {
        if (whole) {
            int number = Integer.parseInt(input);
            return readInteger(number, "Value");
        }
        return readDecimal(Double.parseDouble(input), "Value");
    }

    private boolean accepts(double number) {
        return Double.isFinite(number) &&
                ((number >= minimum && number <= maximum) || (sentinel != null && number == sentinel));
    }

    public String display(Object value) {
        return String.valueOf(value) + unit;
    }

    public String instruction() {
        String bounds = "Enter " + (whole ? "a whole number" : "a number") + " from " +
                display(bound(minimum)) + " to " + display(bound(maximum)) + " inclusive.";
        String exception = sentinel == null ? "" : " Or " + sentinel + " to disable the configured ceiling.";
        String units = unit.isEmpty() ? "" : " Enter digits only; the " + unit + " unit is shown automatically.";
        return bounds + exception + units;
    }

    private String range() {
        return "between " + bound(minimum) + " and " + bound(maximum) +
                (sentinel == null ? "" : ", or " + sentinel + " (no configured ceiling)");
    }

    private String bound(double value) {
        return whole ? Integer.toString((int) value) : Double.toString(value);
    }
}
