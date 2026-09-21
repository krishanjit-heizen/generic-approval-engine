package com.approvalengine.application;

import com.approvalengine.adapter.persistence.entity.ApprovalRequestEntity;
import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.adapter.persistence.repository.ApprovalRequestRepository;
import com.approvalengine.adapter.persistence.repository.ApprovalTaskRepository;
import com.approvalengine.domain.directory.DirectoryPerson;
import com.approvalengine.domain.directory.DirectoryService;
import com.approvalengine.domain.request.TaskStatus;
import com.approvalengine.domain.workflow.DisplaySpec;
import com.approvalengine.domain.workflow.WorkflowDocument;
import com.approvalengine.domain.workflow.condition.EvaluationContext;
import com.approvalengine.error.ForbiddenException;
import com.approvalengine.error.NotFoundException;
import lombok.Getter;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine-hosted approver inbox.
 *
 * <p><b>Cross-application by design.</b> One approver gets one queue spanning every consuming
 * application, rather than chasing approvals across three separate products. That is the main
 * payoff of hosting the inbox here instead of leaving it to each app — and it is only coherent
 * because tenancy is modelled explicitly, so items can be labelled with which application they
 * came from.
 *
 * <p>Hosting it also creates an obligation: the engine has to render an expense claim, a
 * purchase order and an access request without containing a line of code about any of them.
 * That is what each workflow's {@code display} block is for. Absent one, this falls back to a
 * generic key/value table — display metadata improves the experience but is never required for
 * correctness.
 */
@Service
@Transactional(readOnly = true)
public class InboxService {

    private final ApprovalTaskRepository tasks;
    private final ApprovalRequestRepository requests;
    private final WorkflowService workflows;
    private final DirectoryService directory;
    private final JsonCodec json;

    public InboxService(ApprovalTaskRepository tasks,
                        ApprovalRequestRepository requests,
                        WorkflowService workflows,
                        DirectoryService directory,
                        JsonCodec json) {
        this.tasks = tasks;
        this.requests = requests;
        this.workflows = workflows;
        this.directory = directory;
        this.json = json;
    }

    /**
     * Everything awaiting this approver, oldest first.
     *
     * <p>Only PENDING tasks appear. A WAITING task in a sequential stage is recorded but is not
     * yet this person's turn, and showing it would invite them to act out of order.
     */
    public List<InboxItem> pendingFor(String approverId) {
        List<InboxItem> items = new ArrayList<InboxItem>();
        for (ApprovalTaskEntity task : tasks.findByApproverIdAndStatusOrderByCreatedAtAsc(
                approverId, TaskStatus.PENDING)) {
            requests.findById(task.getRequestId()).ifPresent(
                    request -> items.add(toItem(task, request, false)));
        }
        return items;
    }

    /** What this approver has already decided, most recent first. */
    public List<InboxItem> historyFor(String approverId, int limit) {
        List<InboxItem> items = new ArrayList<InboxItem>();
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        for (ApprovalTaskEntity task : tasks.findByApproverIdAndStatusInOrderByActedAtDesc(
                approverId,
                Arrays.asList(TaskStatus.APPROVED, TaskStatus.REJECTED,
                        TaskStatus.CHANGES_REQUESTED, TaskStatus.EXPIRED),
                page)) {
            requests.findById(task.getRequestId()).ifPresent(
                    request -> items.add(toItem(task, request, false)));
        }
        return items;
    }

    /**
     * The full approval screen for one task.
     *
     * @throws ForbiddenException if the task belongs to someone else — an approver may only
     *         open their own items, which is the same ownership check that guards acting on them
     */
    public InboxItem open(String taskId, String approverId) {
        ApprovalTaskEntity task = tasks.findById(taskId)
                .orElseThrow(() -> new NotFoundException("task-not-found", "no task " + taskId));

        if (!task.getApproverId().equals(approverId)) {
            throw new ForbiddenException("not-your-task",
                    "task " + taskId + " is assigned to another approver");
        }

        ApprovalRequestEntity request = requests.findById(task.getRequestId())
                .orElseThrow(() -> new NotFoundException("request-not-found",
                        "the request behind task " + taskId + " no longer exists"));
        return toItem(task, request, true);
    }

    private InboxItem toItem(ApprovalTaskEntity task, ApprovalRequestEntity request, boolean detailed) {
        WorkflowDocument document = workflows.parse(workflows.requireById(request.getWorkflowDefId()));
        Map<String, Object> context = json.readMap(request.getContextJson());
        EvaluationContext evaluation = EvaluationContext.of(context, request.getRequestedBy());

        DisplaySpec display = document.getDisplay();
        String fallbackTitle = document.displayName() + " — "
                + request.getEntityType() + "/" + request.getEntityId();
        String title = display == null
                ? fallbackTitle
                : display.renderTitle(evaluation, fallbackTitle);

        Optional<DirectoryPerson> requester = directory.findActivePerson(request.getRequestedBy());

        return new InboxItem(
                task.getId(),
                request.getId(),
                request.getAppId(),
                title,
                request.getEntityType(),
                request.getEntityId(),
                request.getRequestedBy(),
                requester.map(DirectoryPerson::getDisplayName).orElse(request.getRequestedBy()),
                task.getResolvedVia(),
                task.getStatus(),
                task.getDueAt(),
                task.getActedAt(),
                task.getComment(),
                request.getCreatedAt(),
                renderFields(display, evaluation, context),
                // The raw context is only sent on the detail view. A list of fifty items does
                // not need fifty full business payloads.
                detailed ? context : null);
    }

    /**
     * Resolves the workflow's declared display fields into label/value pairs.
     *
     * <p>Resolved here rather than in a UI so that every future client — a web inbox, a Slack
     * message, a CLI — renders the same thing without re-implementing path lookup or knowing
     * anything about the consuming domain.
     */
    private List<Field> renderFields(DisplaySpec display, EvaluationContext evaluation,
                                     Map<String, Object> context) {
        List<Field> fields = new ArrayList<Field>();

        if (display == null || display.getSummaryFields().isEmpty()) {
            // Fallback: a generic table of whatever the consumer sent. Shallow on purpose —
            // nested objects are rendered as JSON rather than flattened, since the engine has
            // no basis for guessing which nested values matter.
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                fields.add(new Field(entry.getKey(), humanise(entry.getKey()),
                        entry.getValue(), DisplaySpec.Format.TEXT));
            }
            return fields;
        }

        for (DisplaySpec.Field declared : display.getSummaryFields()) {
            Object value = evaluation.resolve(declared.getPath()).orElse(null);
            // Declared-but-absent fields are still listed, with a null value. Dropping them
            // would make an incomplete submission look like a complete one.
            fields.add(new Field(declared.getPath(), declared.getLabel(), value, declared.getFormat()));
        }
        return fields;
    }

    /** Turns {@code invoiceUrl} into {@code Invoice Url} for the no-display-block fallback. */
    private static String humanise(String path) {
        String spaced = path.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('.', ' ').replace('_', ' ');
        return spaced.isEmpty() ? path : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** One row in an approver's queue. */
    @Getter
    public static final class InboxItem {
        private final String taskId;
        private final String requestId;

        /** Which consuming application raised it — the queue spans all of them. */
        private final String appId;

        /** Rendered from the workflow's display template. */
        private final String title;

        private final String entityType;
        private final String entityId;
        private final String requestedBy;
        private final String requestedByName;

        /** Why this approver was asked, e.g. {@code ROLE(FINANCE)}. */
        private final String assignedVia;

        private final TaskStatus status;
        private final Instant dueAt;
        private final Instant actedAt;
        private final String comment;
        private final Instant requestedAt;

        /** Label/value pairs, already resolved and formatted. */
        private final List<Field> fields;

        /** The full submitted context. Only populated on the detail view. */
        private final Map<String, Object> context;

        InboxItem(String taskId, String requestId, String appId, String title, String entityType,
                  String entityId, String requestedBy, String requestedByName, String assignedVia,
                  TaskStatus status, Instant dueAt, Instant actedAt, String comment,
                  Instant requestedAt, List<Field> fields, Map<String, Object> context) {
            this.taskId = taskId;
            this.requestId = requestId;
            this.appId = appId;
            this.title = title;
            this.entityType = entityType;
            this.entityId = entityId;
            this.requestedBy = requestedBy;
            this.requestedByName = requestedByName;
            this.assignedVia = assignedVia;
            this.status = status;
            this.dueAt = dueAt;
            this.actedAt = actedAt;
            this.comment = comment;
            this.requestedAt = requestedAt;
            this.fields = fields;
            this.context = context;
        }

        /** Whether the SLA has already elapsed — surfaced so a UI can flag it without maths. */
        public boolean isOverdue() {
            return dueAt != null && status == TaskStatus.PENDING && dueAt.isBefore(Instant.now());
        }
    }

    /** A labelled, formatted value ready to display. */
    @Getter
    public static final class Field {
        private final String path;
        private final String label;
        private final Object value;
        private final DisplaySpec.Format format;

        Field(String path, String label, Object value, DisplaySpec.Format format) {
            this.path = path;
            this.label = label;
            this.value = value;
            this.format = format;
        }
    }

    /** Convenience for building an empty grouped view. */
    static Map<String, Object> emptyGroups() {
        return new LinkedHashMap<String, Object>();
    }
}
