package com.approvalengine.domain.workflow.condition;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only view over an approval request's business context, addressed by dotted path.
 *
 * <p>The context is whatever JSON object the consuming application submitted
 * ({@code {"amount": 25000, "vendor": {"category": "SAAS"}}}). The engine never interprets
 * it — it only reads the paths a workflow's conditions name.
 *
 * <p>Resolution is intentionally limited to map traversal and list indexing. There is no
 * expression language, no function calls, no wildcards: a workflow definition is data, and
 * evaluating it must never be able to execute anything.
 */
public final class EvaluationContext {

    private final Map<String, Object> context;
    private final String requestedBy;

    private EvaluationContext(Map<String, Object> context, String requestedBy) {
        this.context = context == null ? Collections.<String, Object>emptyMap() : context;
        this.requestedBy = requestedBy;
    }

    public static EvaluationContext of(Map<String, Object> context, String requestedBy) {
        return new EvaluationContext(context, requestedBy);
    }

    public static EvaluationContext of(Map<String, Object> context) {
        return new EvaluationContext(context, null);
    }

    /** The submitting user, addressable in approver specs as {@code $requestedBy}. */
    public String requestedBy() {
        return requestedBy;
    }

    public Map<String, Object> raw() {
        return Collections.unmodifiableMap(context);
    }

    /**
     * Resolves a dotted path such as {@code "amount"}, {@code "vendor.category"} or
     * {@code "lineItems.0.sku"}.
     *
     * @return empty if any segment is absent, or if the resolved value is JSON null. A
     *         missing path and an explicit null are deliberately indistinguishable here:
     *         both mean "the consuming application did not give us this value", and
     *         collapsing them keeps rule behaviour predictable for workflow authors.
     */
    public Optional<Object> resolve(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        Object current = context;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(segment);
            } else if (current instanceof List) {
                Integer index = parseIndex(segment);
                List<?> list = (List<?>) current;
                if (index == null || index < 0 || index >= list.size()) {
                    return Optional.empty();
                }
                current = list.get(index);
            } else {
                // Path continues but the value is a scalar — the path does not exist.
                return Optional.empty();
            }
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    private static Integer parseIndex(String segment) {
        try {
            return Integer.valueOf(segment);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
