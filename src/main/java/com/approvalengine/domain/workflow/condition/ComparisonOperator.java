package com.approvalengine.domain.workflow.condition;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * The closed set of comparisons a workflow condition may express.
 *
 * <p>Closed is the point. A workflow definition is authored data, so the alternative —
 * SpEL or any expression language — would mean evaluating a rule could execute arbitrary
 * code, and could not be explained node-by-node in an audit trail. Every operator here is
 * total: it returns true or false for any pair of inputs and never throws.
 *
 * <p>Comparison semantics, chosen for predictability over cleverness:
 * <ul>
 *   <li>If both sides look numeric, compare numerically ({@code 25000} == {@code "25000"}).
 *       This matters because JSON gives us Integer, Long, Double and String
 *       inconsistently for the same business value.</li>
 *   <li>Otherwise compare as strings, case-sensitively.</li>
 *   <li>An absent path is never equal to anything and never satisfies an ordering
 *       comparison — it yields false with a note, rather than an error.</li>
 * </ul>
 */
public enum ComparisonOperator {

    EQ("==") {
        @Override
        boolean test(Object actual, Object expected) {
            // Handle null explicitly rather than falling through to String.valueOf, which
            // would make an absent path equal to the literal string "null".
            if (actual == null || expected == null) {
                return actual == expected;
            }
            Integer c = compareOrNull(actual, expected);
            return c != null ? c == 0 : String.valueOf(actual).equals(String.valueOf(expected));
        }
    },

    NEQ("!=") {
        @Override
        boolean test(Object actual, Object expected) {
            return !EQ.test(actual, expected);
        }
    },

    GT(">") {
        @Override
        boolean test(Object actual, Object expected) {
            Integer c = compareOrNull(actual, expected);
            return c != null && c > 0;
        }
    },

    GTE(">=") {
        @Override
        boolean test(Object actual, Object expected) {
            Integer c = compareOrNull(actual, expected);
            return c != null && c >= 0;
        }
    },

    LT("<") {
        @Override
        boolean test(Object actual, Object expected) {
            Integer c = compareOrNull(actual, expected);
            return c != null && c < 0;
        }
    },

    LTE("<=") {
        @Override
        boolean test(Object actual, Object expected) {
            Integer c = compareOrNull(actual, expected);
            return c != null && c <= 0;
        }
    },

    /** actual ∈ expected. {@code expected} must be a list. */
    IN("in") {
        @Override
        boolean test(Object actual, Object expected) {
            if (!(expected instanceof Collection)) {
                return false;
            }
            for (Object candidate : (Collection<?>) expected) {
                if (EQ.test(actual, candidate)) {
                    return true;
                }
            }
            return false;
        }
    },

    /**
     * Membership in the other direction: the context value is the container.
     * Works for lists (element present), strings (substring) and maps (key present),
     * so a workflow can test {@code tags CONTAINS "urgent"} without caring which shape
     * the consuming app used.
     */
    CONTAINS("contains") {
        @Override
        boolean test(Object actual, Object expected) {
            if (actual instanceof Collection) {
                for (Object element : (Collection<?>) actual) {
                    if (EQ.test(element, expected)) {
                        return true;
                    }
                }
                return false;
            }
            if (actual instanceof Map) {
                for (Object key : ((Map<?, ?>) actual).keySet()) {
                    if (EQ.test(key, expected)) {
                        return true;
                    }
                }
                return false;
            }
            return String.valueOf(actual).contains(String.valueOf(expected));
        }
    },

    /** True when the path resolved to a non-null value. Ignores the operand. */
    EXISTS("exists") {
        @Override
        boolean test(Object actual, Object expected) {
            return actual != null;
        }
    };

    private final String symbol;

    ComparisonOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** Whether this operator is meaningful when the path is absent. Only EXISTS is. */
    public boolean toleratesMissingValue() {
        return this == EXISTS || this == NEQ;
    }

    abstract boolean test(Object actual, Object expected);

    /**
     * Evaluates the operator. {@code actual} may be null, meaning the path was absent —
     * every operator handles that without throwing.
     */
    public boolean apply(Object actual, Object expected) {
        if (actual == null && !toleratesMissingValue()) {
            return false;
        }
        return test(actual, expected);
    }

    /**
     * Three-way compare, or null when the two values are not ordered-comparable.
     * Returning null rather than throwing is what keeps every operator total.
     */
    private static Integer compareOrNull(Object a, Object b) {
        BigDecimal left = toDecimal(a);
        BigDecimal right = toDecimal(b);
        if (left != null && right != null) {
            return Integer.valueOf(left.compareTo(right));
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            // Booleans are equal-or-not; ordering them is meaningless.
            return null;
        }
        if (a == null || b == null) {
            return null;
        }
        if (a instanceof Collection || b instanceof Collection
                || a instanceof Map || b instanceof Map) {
            return null;
        }
        return Integer.valueOf(String.valueOf(a).compareTo(String.valueOf(b)));
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof CharSequence) {
            try {
                return new BigDecimal(value.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
