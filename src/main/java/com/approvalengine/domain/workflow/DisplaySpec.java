package com.approvalengine.domain.workflow;

import com.approvalengine.domain.workflow.condition.EvaluationContext;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the engine should present a request it knows nothing about.
 *
 * <p>The approver inbox is hosted by the engine, which means the engine has to render an
 * expense claim, a purchase order and an access request without containing a line of code
 * about any of them. Without help it could only dump raw JSON. So a workflow declares its
 * own presentation alongside its rules:
 *
 * <pre>{@code
 * "display": {
 *   "titleTemplate": "{{context.department}} expense — ₹{{context.amount}}",
 *   "summaryFields": [
 *     { "path": "amount",     "label": "Amount",    "format": "CURRENCY_INR" },
 *     { "path": "vendor.name","label": "Vendor",    "format": "TEXT" },
 *     { "path": "invoiceUrl", "label": "Invoice",   "format": "LINK" }
 *   ]
 * }
 * }</pre>
 *
 * <p>Rendering is pure token substitution over the context snapshot — no expression
 * evaluation, no method calls. A workflow definition is data that a consuming application
 * uploads, so it must never be able to execute anything, and that constraint applies to
 * presentation exactly as it does to rules.
 *
 * <p>This block is optional. Absent it, the inbox falls back to a generic key/value table:
 * display metadata improves the experience but is never required for correctness.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DisplaySpec {

    /** How a value should be presented. The UI decides what each means; the engine just labels it. */
    public enum Format {
        TEXT,
        NUMBER,
        CURRENCY_INR,
        DATE,
        BOOLEAN,
        LINK,
        /** Render the raw sub-object — an escape hatch for nested data worth showing verbatim. */
        JSON
    }

    /** One labelled field to surface on the approval screen. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Field {
        private final String path;
        private final String label;
        private final Format format;

        @JsonCreator
        public Field(@JsonProperty("path") String path,
                     @JsonProperty("label") String label,
                     @JsonProperty("format") Format format) {
            this.path = path;
            this.label = label;
            this.format = format == null ? Format.TEXT : format;
        }

        public String getPath() {
            return path;
        }

        public String getLabel() {
            return label;
        }

        public Format getFormat() {
            return format;
        }

        void validate(String errorPath, List<String> errors) {
            if (path == null || path.trim().isEmpty()) {
                errors.add(errorPath + ": display field requires a 'path'");
            }
            if (label == null || label.trim().isEmpty()) {
                errors.add(errorPath + ": display field requires a 'label'");
            }
        }
    }

    /** Matches {@code {{context.some.path}}} and nothing else. */
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*context\\.([A-Za-z0-9_.\\-]+)\\s*}}");

    private final String titleTemplate;
    private final List<Field> summaryFields;

    @JsonCreator
    public DisplaySpec(@JsonProperty("titleTemplate") String titleTemplate,
                       @JsonProperty("summaryFields") List<Field> summaryFields) {
        this.titleTemplate = titleTemplate;
        this.summaryFields = summaryFields == null
                ? Collections.<Field>emptyList()
                : Collections.unmodifiableList(new ArrayList<Field>(summaryFields));
    }

    public String getTitleTemplate() {
        return titleTemplate;
    }

    public List<Field> getSummaryFields() {
        return summaryFields;
    }

    /**
     * Substitutes {@code {{context.path}}} tokens against the request context.
     *
     * <p>An unresolvable token renders as {@code —} rather than leaving the raw token
     * visible or throwing: a title is cosmetic, and a missing optional field should not
     * make an approval screen unreadable.
     */
    public String renderTitle(EvaluationContext context, String fallback) {
        if (titleTemplate == null || titleTemplate.trim().isEmpty()) {
            return fallback;
        }
        Matcher matcher = TOKEN.matcher(titleTemplate);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Optional<Object> value = context.resolve(matcher.group(1));
            String replacement = value.isPresent() ? String.valueOf(value.get()) : "—";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public void validate(String errorPath, List<String> errors) {
        for (int i = 0; i < summaryFields.size(); i++) {
            summaryFields.get(i).validate(errorPath + ".summaryFields[" + i + "]", errors);
        }
    }
}
