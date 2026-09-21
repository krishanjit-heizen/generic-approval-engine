package com.approvalengine.domain.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The parsed form of a workflow definition — what gets stored in
 * {@code workflow_definition.definition_json} and executed by the engine.
 *
 * <p><b>Ordered stages, not an arbitrary DAG.</b> A workflow is a list of stages executed
 * in order, where each stage may itself fan out to several approvers in parallel. That
 * expresses everything the requirements ask for, including the fan-out/fan-in case
 * (Finance ∥ Compliance → Final Approval), which becomes one PARALLEL stage followed by
 * one ordinary stage. A general DAG would need join semantics and cycle detection for
 * expressiveness nothing has yet needed; the upgrade path is deliberately narrow — the
 * executor asks only "which stage comes after this one, given this outcome".
 *
 * <p><b>Immutable once published.</b> Editing a workflow publishes a new version; an
 * existing version is never mutated. A request pins the version it started on, which is
 * the entire answer to "someone changed the workflow while a request was in flight".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WorkflowDocument {

    private final String key;
    private final String name;
    private final List<StageDefinition> stages;
    private final DisplaySpec display;

    @JsonCreator
    public WorkflowDocument(@JsonProperty("key") String key,
                            @JsonProperty("name") String name,
                            @JsonProperty("stages") List<StageDefinition> stages,
                            @JsonProperty("display") DisplaySpec display) {
        this.key = key;
        this.name = name;
        this.stages = stages == null
                ? Collections.<StageDefinition>emptyList()
                : Collections.unmodifiableList(new ArrayList<StageDefinition>(stages));
        this.display = display;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public List<StageDefinition> getStages() {
        return stages;
    }

    public DisplaySpec getDisplay() {
        return display;
    }

    @JsonIgnore
    public String displayName() {
        return name == null || name.trim().isEmpty() ? key : name;
    }

    /** Locates a stage by its declared id — used to resolve {@code onRejectGoto} targets. */
    @JsonIgnore
    public int indexOfStage(String stageId) {
        for (int i = 0; i < stages.size(); i++) {
            if (stageId != null && stageId.equals(stages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Full structural validation, run before a version may be published.
     *
     * <p>Publishing is the last moment a definition can be rejected cheaply. After that a
     * broken rule surfaces mid-approval, on a real request, in front of a real approver —
     * so this is deliberately strict about things that would otherwise fail silently:
     * duplicate stage ids, GOTO targets that do not exist, unsatisfiable policies, and
     * timeout actions with no SLA to trigger them.
     *
     * @return every problem found, in document order; empty means publishable
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<String>();

        if (key == null || key.trim().isEmpty()) {
            errors.add("workflow requires a 'key'");
        }
        if (stages.isEmpty()) {
            errors.add("workflow requires at least one stage");
        }

        Set<String> seenIds = new HashSet<String>();
        for (int i = 0; i < stages.size(); i++) {
            StageDefinition stage = stages.get(i);
            String path = "stages[" + i + "]";
            stage.validate(path, errors);

            if (stage.getId() != null && !seenIds.add(stage.getId())) {
                // Stage ids appear in audit records and GOTO targets; duplicates would make
                // both ambiguous.
                errors.add(path + ": duplicate stage id '" + stage.getId() + "'");
            }
        }

        // GOTO targets are resolved here, once every stage id is known.
        for (int i = 0; i < stages.size(); i++) {
            StageDefinition stage = stages.get(i);
            if (stage.getOnReject() == StageDefinition.RejectAction.GOTO
                    && stage.getOnRejectGoto() != null
                    && indexOfStage(stage.getOnRejectGoto()) < 0) {
                errors.add("stages[" + i + "]: onRejectGoto '" + stage.getOnRejectGoto()
                        + "' does not name any stage in this workflow");
            }
        }

        if (display != null) {
            display.validate("display", errors);
        }
        return errors;
    }
}
