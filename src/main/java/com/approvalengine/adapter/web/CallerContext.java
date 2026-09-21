package com.approvalengine.adapter.web;

import lombok.Getter;

/**
 * Who is making this call: which application, and which human on whose behalf.
 *
 * <p>The two are deliberately separate. {@code appId} comes from an API key and is
 * authenticated — it scopes every query, so one consuming application can never read
 * another's requests. {@code actorId} comes from a header and is <b>not</b> authenticated in
 * this phase; it names the person acting.
 *
 * <p>That asymmetry is safe only because identity is never the authorisation. Before any
 * approval action, the engine re-checks that the actor actually holds a PENDING task on that
 * request. So a wrong or guessed actor id cannot approve anything — the worst it achieves is
 * reading an inbox that is not theirs. Closing that last gap needs real authentication
 * (OIDC) in front of the inbox endpoints, and is called out in the README rather than
 * quietly assumed away.
 */
@Getter
public final class CallerContext {

    /** Request attribute the filter stores this under. */
    static final String ATTRIBUTE = CallerContext.class.getName();

    private final String appId;
    private final String appName;
    private final String actorId;

    CallerContext(String appId, String appName, String actorId) {
        this.appId = appId;
        this.appName = appName;
        this.actorId = actorId;
    }

    /**
     * The acting human, required for endpoints that record an action.
     *
     * <p>Fails loudly rather than defaulting to something like "system": an audit row whose
     * actor is a placeholder is worse than a rejected request, because it looks authoritative.
     */
    public String requireActor() {
        if (actorId == null || actorId.trim().isEmpty()) {
            throw new com.approvalengine.error.ValidationException("actor-required",
                    "this endpoint records an action, so the X-Actor-Id header naming the acting user "
                            + "is required");
        }
        return actorId;
    }
}
