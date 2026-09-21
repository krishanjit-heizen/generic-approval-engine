package com.approvalengine.adapter.persistence.entity;

import com.approvalengine.domain.workflow.WorkflowStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.Instant;

/**
 * One immutable version of one workflow.
 *
 * <p>Publishing never mutates an existing row: an edit creates {@code version + 1} and
 * archives the previous ACTIVE version. Combined with each request pinning the version it
 * started on, that is the complete answer to "the workflow changed while a request was
 * already running" — the in-flight request keeps reading this exact row.
 *
 * <p>The definition itself stays as JSON rather than being exploded into stage/condition
 * tables. It is only ever read as a whole, it is never queried inside, and keeping it whole
 * means a stored definition is byte-for-byte what the author published.
 */
@Entity
@Table(name = "workflow_definition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowDefinitionEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    /** Author-chosen key, unique per application, e.g. {@code expense_approval}. */
    @Column(name = "workflow_key", nullable = false, length = 128)
    private String workflowKey;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WorkflowStatus status;

    /** Serialized {@link com.approvalengine.domain.workflow.WorkflowDocument}. */
    @Lob
    @Column(name = "definition_json", nullable = false)
    private String definitionJson;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
