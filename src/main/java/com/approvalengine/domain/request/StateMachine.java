package com.approvalengine.domain.request;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single place any status transition is permitted.
 *
 * <p>Every status change in the engine goes through {@link #requireRequestTransition},
 * {@link #requireStageTransition} or {@link #requireTaskTransition}. Nothing sets a status
 * field directly. That discipline is what makes the audit log trustworthy rather than
 * merely present: if an audit row says {@code IN_PROGRESS → APPROVED}, that transition was
 * checked against this table, and no code path exists that could have produced a different
 * one without a check.
 *
 * <p>An illegal transition throws rather than being coerced or ignored. A request that has
 * already been rejected must not be quietly re-approved because a retry arrived late; the
 * caller gets a 409 and the state stands.
 */
public final class StateMachine {

    private static final Map<RequestStatus, Set<RequestStatus>> REQUEST_TRANSITIONS;
    private static final Map<StageStatus, Set<StageStatus>> STAGE_TRANSITIONS;
    private static final Map<TaskStatus, Set<TaskStatus>> TASK_TRANSITIONS;

    static {
        Map<RequestStatus, Set<RequestStatus>> request = new EnumMap<RequestStatus, Set<RequestStatus>>(RequestStatus.class);
        request.put(RequestStatus.CREATED, EnumSet.of(
                RequestStatus.IN_PROGRESS,
                // A workflow whose every stage was skipped is approved outright — nothing
                // applied, so there is nothing to ask.
                RequestStatus.APPROVED,
                RequestStatus.NEEDS_ATTENTION,
                RequestStatus.CANCELLED));
        request.put(RequestStatus.IN_PROGRESS, EnumSet.of(
                RequestStatus.APPROVED,
                RequestStatus.REJECTED,
                RequestStatus.CANCELLED,
                RequestStatus.CHANGES_REQUESTED,
                RequestStatus.NEEDS_ATTENTION));
        request.put(RequestStatus.CHANGES_REQUESTED, EnumSet.of(
                RequestStatus.IN_PROGRESS,
                RequestStatus.CANCELLED));
        request.put(RequestStatus.NEEDS_ATTENTION, EnumSet.of(
                RequestStatus.IN_PROGRESS,
                // An operator may conclude the request is unsalvageable and withdraw it.
                RequestStatus.CANCELLED));
        // Terminal states have no outgoing transitions, deliberately and permanently.
        request.put(RequestStatus.APPROVED, Collections.<RequestStatus>emptySet());
        request.put(RequestStatus.REJECTED, Collections.<RequestStatus>emptySet());
        request.put(RequestStatus.CANCELLED, Collections.<RequestStatus>emptySet());
        REQUEST_TRANSITIONS = Collections.unmodifiableMap(request);

        Map<StageStatus, Set<StageStatus>> stage = new EnumMap<StageStatus, Set<StageStatus>>(StageStatus.class);
        stage.put(StageStatus.PENDING, EnumSet.of(
                StageStatus.ACTIVE,
                StageStatus.SKIPPED,
                // A pending stage is abandoned when an earlier stage terminates the request.
                StageStatus.REJECTED));
        stage.put(StageStatus.ACTIVE, EnumSet.of(
                StageStatus.APPROVED,
                StageStatus.REJECTED,
                StageStatus.EXPIRED,
                StageStatus.RETURNED,
                // An SLA policy can resolve an active stage away without a human decision.
                StageStatus.SKIPPED));
        // Re-planning after a resubmission: a changed context can make a stage that was
        // skipped at creation applicable, or vice versa. This is the only edge that leaves
        // SKIPPED, and it exists so that an approver saying "reduce this below the Director
        // threshold" actually removes the Director stage when the requester complies.
        stage.put(StageStatus.SKIPPED, EnumSet.of(StageStatus.PENDING));
        stage.put(StageStatus.APPROVED, Collections.<StageStatus>emptySet());
        stage.put(StageStatus.EXPIRED, Collections.<StageStatus>emptySet());
        // A completed attempt is never reopened. A revisit — via GOTO, or a resubmission —
        // creates a NEW stage_instance attempt instead, leaving this row as the record of
        // what happened the first time.
        stage.put(StageStatus.REJECTED, Collections.<StageStatus>emptySet());
        stage.put(StageStatus.RETURNED, Collections.<StageStatus>emptySet());
        STAGE_TRANSITIONS = Collections.unmodifiableMap(stage);

        Map<TaskStatus, Set<TaskStatus>> task = new EnumMap<TaskStatus, Set<TaskStatus>>(TaskStatus.class);
        // A queued approver's turn arrives, or the stage concludes before it ever does.
        task.put(TaskStatus.WAITING, EnumSet.of(
                TaskStatus.PENDING,
                TaskStatus.CANCELLED));
        task.put(TaskStatus.PENDING, EnumSet.of(
                TaskStatus.APPROVED,
                TaskStatus.REJECTED,
                TaskStatus.CHANGES_REQUESTED,
                TaskStatus.EXPIRED,
                TaskStatus.CANCELLED));
        // Once an approver has acted, that record is final. This is the invariant that makes
        // "two approvers acted at the same instant" safe: the loser cannot overwrite.
        task.put(TaskStatus.APPROVED, Collections.<TaskStatus>emptySet());
        task.put(TaskStatus.REJECTED, Collections.<TaskStatus>emptySet());
        task.put(TaskStatus.CHANGES_REQUESTED, Collections.<TaskStatus>emptySet());
        task.put(TaskStatus.EXPIRED, Collections.<TaskStatus>emptySet());
        task.put(TaskStatus.CANCELLED, Collections.<TaskStatus>emptySet());
        TASK_TRANSITIONS = Collections.unmodifiableMap(task);
    }

    private StateMachine() {
    }

    public static boolean canTransition(RequestStatus from, RequestStatus to) {
        return allowed(REQUEST_TRANSITIONS, from, to);
    }

    public static boolean canTransition(StageStatus from, StageStatus to) {
        return allowed(STAGE_TRANSITIONS, from, to);
    }

    public static boolean canTransition(TaskStatus from, TaskStatus to) {
        return allowed(TASK_TRANSITIONS, from, to);
    }

    public static void requireRequestTransition(String requestId, RequestStatus from, RequestStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException("request", requestId, name(from), name(to));
        }
    }

    public static void requireStageTransition(String stageInstanceId, StageStatus from, StageStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException("stage", stageInstanceId, name(from), name(to));
        }
    }

    public static void requireTaskTransition(String taskId, TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException("task", taskId, name(from), name(to));
        }
    }

    private static <T extends Enum<T>> boolean allowed(Map<T, Set<T>> table, T from, T to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            // Idempotent re-application is not a transition. Callers that need
            // "already in the target state" semantics must check for it explicitly, so a
            // no-op can never be mistaken for a real state change in the audit trail.
            return false;
        }
        Set<T> targets = table.get(from);
        return targets != null && targets.contains(to);
    }

    private static String name(Enum<?> value) {
        return value == null ? "null" : value.name();
    }
}
