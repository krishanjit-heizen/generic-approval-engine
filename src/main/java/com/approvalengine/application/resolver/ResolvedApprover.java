package com.approvalengine.application.resolver;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One person to ask, plus the reason they were chosen.
 *
 * <p>{@code resolvedVia} is persisted onto the task and is not decoration. Months later,
 * "why was Priya asked to approve this?" must be answerable from the record alone — the
 * directory will have changed, so re-running resolution would give a different, misleading
 * answer.
 */
@Getter
@AllArgsConstructor
public class ResolvedApprover {

    private final String approverId;

    /** Human-readable provenance, e.g. {@code MANAGER_OF(user-123)} or {@code ROLE(FINANCE)}. */
    private final String resolvedVia;

    public static ResolvedApprover of(String approverId, String resolvedVia) {
        return new ResolvedApprover(approverId, resolvedVia);
    }
}
