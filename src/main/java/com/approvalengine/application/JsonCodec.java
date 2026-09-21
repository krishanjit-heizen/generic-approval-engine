package com.approvalengine.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place JSON crosses the persistence boundary.
 *
 * <p>Workflow definitions, request contexts, condition traces and webhook payloads are all
 * stored as JSON text. Converting them here — in an injected bean — rather than in a JPA
 * {@code AttributeConverter} avoids the usual static-ObjectMapper hack that converters
 * require, and keeps entities dumb holders of {@code String}.
 *
 * <p>Serialization failures are treated as programming errors, not recoverable conditions:
 * if the engine cannot serialize a trace it just produced, silently storing nothing would
 * quietly break the audit trail, which is the one thing that must not degrade.
 */
@Component
public class JsonCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to serialize " + value.getClass().getName() + " to JSON", e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException(
                    "stored JSON is not a valid " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads a request context back out of storage.
     *
     * @return a mutable-safe copy; never null, so callers never guard for it. An absent
     *         context and an empty one mean the same thing to rule evaluation.
     */
    public Map<String, Object> readMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.<String, Object>emptyMap();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json, MAP_TYPE);
            return parsed == null ? Collections.<String, Object>emptyMap()
                    : new LinkedHashMap<String, Object>(parsed);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("stored context is not a JSON object: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * A stable hash of a request body, used to tell an idempotent replay (same key, same
     * body) from key reuse (same key, different body).
     *
     * <p>Hashes the parsed-then-reserialized form rather than the raw bytes, so that
     * insignificant differences — key order, whitespace, a reformatted client — are
     * correctly treated as the same request. A client retrying with a pretty-printer in the
     * way should get its stored response, not a 422.
     */
    public String canonicalHash(String rawBody) {
        String canonical;
        try {
            canonical = rawBody == null || rawBody.trim().isEmpty()
                    ? ""
                    : mapper.writeValueAsString(mapper.readTree(rawBody));
        } catch (JsonProcessingException e) {
            // Unparseable body: hash it verbatim. It will fail validation moments later
            // anyway, and a stable hash keeps the idempotency layer honest until then.
            canonical = rawBody;
        }
        return Hashing.sha256Hex(canonical);
    }

    /** Thrown when JSON already in the database cannot be parsed back. */
    public static class JsonCodecException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
