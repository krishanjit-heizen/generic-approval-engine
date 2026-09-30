package com.approvalengine;

import com.approvalengine.adapter.persistence.entity.ApprovalTaskEntity;
import com.approvalengine.adapter.persistence.repository.ApprovalTaskRepository;
import com.approvalengine.domain.request.TaskStatus;
import com.approvalengine.ops.SlaSweeper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regressions, driven through the HTTP API against an in-memory database.
 *
 * <p>The schedulers are disabled so nothing moves unless a test moves it: SLA expiry is
 * produced by back-dating a task's deadline and running the sweeper by hand, which keeps these
 * tests free of sleeps.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:regression;DB_CLOSE_DELAY=-1",
                "approval.outbox.enabled=false",
                "approval.sla.enabled=false",
                "approval.recovery.enabled=false"
        })
class EngineRegressionTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApprovalTaskRepository tasks;

    @Autowired
    private SlaSweeper sweeper;

    private String apiKey;
    private String appId;

    @BeforeEach
    void registerApplication() throws Exception {
        JsonNode registration = register();
        apiKey = registration.get("apiKey").asText();
        appId = registration.get("appId").asText();
    }

    // --- onReject: GOTO ---------------------------------------------------

    @Test
    void rejectionWithGotoReopensTheTargetStage() throws Exception {
        String workflow = publish("{\"key\":\"%s\",\"name\":\"Goto\",\"stages\":["
                + "{\"id\":\"a\",\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-410\"}]},"
                + "{\"id\":\"b\",\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-411\"}],"
                + "\"onReject\":\"GOTO\",\"onRejectGoto\":\"a\"}]}");
        String requestId = submit(workflow);

        assertThat(act(requestId, "user-410", "APPROVE", null).getStatusCodeValue()).isEqualTo(200);

        ResponseEntity<String> rejection = act(requestId, "user-411", "REJECT", "redo this");
        assertThat(rejection.getStatusCodeValue()).isEqualTo(200);

        JsonNode detail = detail(requestId);
        assertThat(detail.get("request").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(stageStatus(detail, "a", 1)).isEqualTo("APPROVED");
        assertThat(stageStatus(detail, "a", 2)).isEqualTo("ACTIVE");
        assertThat(stageStatus(detail, "b", 1)).isEqualTo("REJECTED");

        // The second pass runs to completion like the first.
        assertThat(act(requestId, "user-410", "APPROVE", null).getStatusCodeValue()).isEqualTo(200);
        assertThat(act(requestId, "user-411", "APPROVE", null).getStatusCodeValue()).isEqualTo(200);
        assertThat(detail(requestId).get("request").get("status").asText()).isEqualTo("APPROVED");
    }

    // --- ALL policy + ESCALATE --------------------------------------------

    @Test
    void escalatedTaskDoesNotCountAgainstAnAllPolicy() throws Exception {
        String workflow = publish("{\"key\":\"%s\",\"name\":\"Escalate\",\"stages\":["
                + "{\"id\":\"s\",\"mode\":\"PARALLEL\",\"policy\":{\"type\":\"ALL\"},"
                + "\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-410\"},"
                + "{\"type\":\"USER\",\"userId\":\"user-100\"}],"
                + "\"slaSeconds\":3600,\"onTimeout\":\"ESCALATE\"}]}");
        String requestId = submit(workflow);

        assertThat(act(requestId, "user-410", "APPROVE", null).getStatusCodeValue()).isEqualTo(200);

        // user-100 never answers; their task goes to their manager, user-900.
        expireOutstandingTasks(requestId);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(taskStatus(detail(requestId), "user-100")).isEqualTo("EXPIRED");
        assertThat(taskStatus(detail(requestId), "user-900")).isEqualTo("PENDING");

        assertThat(act(requestId, "user-900", "APPROVE", null).getStatusCodeValue()).isEqualTo(200);

        JsonNode detail = detail(requestId);
        assertThat(detail.get("request").get("status").asText()).isEqualTo("APPROVED");
        assertThat(stageStatus(detail, "s", 1)).isEqualTo("APPROVED");
    }

    // --- reassign after a failed escalation -------------------------------

    @Test
    void reassignReopensAStageWhoseEscalationFailed() throws Exception {
        // user-950 has no manager, so there is nobody to escalate to.
        String workflow = publish("{\"key\":\"%s\",\"name\":\"Reassign\",\"stages\":["
                + "{\"id\":\"s\",\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-950\"}],"
                + "\"slaSeconds\":3600,\"onTimeout\":\"ESCALATE\"},"
                + "{\"id\":\"s2\",\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-410\"}]}]}");
        String requestId = submit(workflow);

        expireOutstandingTasks(requestId);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(detail(requestId).get("request").get("status").asText()).isEqualTo("NEEDS_ATTENTION");

        ResponseEntity<String> reassigned = rest.exchange(
                "/api/v1/admin/requests/" + requestId + "/reassign", HttpMethod.POST,
                new HttpEntity<String>("{\"reason\":\"approver is back\"}", headers("operator")),
                String.class);
        assertThat(reassigned.getStatusCodeValue()).isEqualTo(200);

        JsonNode detail = detail(requestId);
        assertThat(detail.get("request").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(stageStatus(detail, "s", 1)).isEqualTo("EXPIRED");
        assertThat(stageStatus(detail, "s", 2)).isEqualTo("ACTIVE");
        assertThat(stageStatus(detail, "s2", 1)).isEqualTo("PENDING");

        assertThat(act(requestId, "user-950", "APPROVE", null).getStatusCodeValue()).isEqualTo(200);
        assertThat(act(requestId, "user-410", "APPROVE", null).getStatusCodeValue()).isEqualTo(200);
        assertThat(detail(requestId).get("request").get("status").asText()).isEqualTo("APPROVED");
    }

    // --- cancellation ------------------------------------------------------

    @Test
    void cancellingRecordsOpenStagesAsCancelledNotRejected() throws Exception {
        String workflow = publish("{\"key\":\"%s\",\"name\":\"Cancel\",\"stages\":["
                + "{\"id\":\"a\",\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-410\"}]},"
                + "{\"id\":\"b\",\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-411\"}]}]}");
        String requestId = submit(workflow);

        ResponseEntity<String> cancelled = rest.exchange("/api/v1/requests/" + requestId + "/cancel",
                HttpMethod.POST, new HttpEntity<String>("{\"reason\":\"withdrawn\"}", headers(null)),
                String.class);
        assertThat(cancelled.getStatusCodeValue()).isEqualTo(200);

        JsonNode detail = detail(requestId);
        assertThat(detail.get("request").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(stageStatus(detail, "a", 1)).isEqualTo("CANCELLED");
        assertThat(stageStatus(detail, "b", 1)).isEqualTo("CANCELLED");
        assertThat(taskStatus(detail, "user-410")).isEqualTo("CANCELLED");
    }

    // --- idempotency ------------------------------------------------------

    @Test
    void idempotencyKeyReusedByAnotherActorIsNotReplayed() throws Exception {
        String workflow = publish("{\"key\":\"%s\",\"name\":\"Idem\",\"stages\":["
                + "{\"id\":\"s\",\"mode\":\"SEQUENTIAL\","
                + "\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-410\"},"
                + "{\"type\":\"USER\",\"userId\":\"user-411\"}]}]}");
        String requestId = submit(workflow);
        String key = unique("idem");

        ResponseEntity<String> first = actWithKey(requestId, "user-410", key);
        assertThat(first.getStatusCodeValue()).isEqualTo(200);

        // A genuine retry replays the stored response.
        ResponseEntity<String> retry = actWithKey(requestId, "user-410", key);
        assertThat(retry.getStatusCodeValue()).isEqualTo(200);
        assertThat(objectMapper.readTree(retry.getBody())).isEqualTo(objectMapper.readTree(first.getBody()));

        // The same key from a different approver is a different intent.
        ResponseEntity<String> reuse = actWithKey(requestId, "user-411", key);
        assertThat(reuse.getStatusCodeValue()).isEqualTo(422);
        assertThat(objectMapper.readTree(reuse.getBody()).get("type").asText())
                .isEqualTo("idempotency-key-reuse");
        assertThat(taskStatus(detail(requestId), "user-411")).isEqualTo("PENDING");
    }

    // --- tenancy ----------------------------------------------------------

    @Test
    void outboxIsScopedToTheCallingApplication() throws Exception {
        String workflow = publish("{\"key\":\"%s\",\"name\":\"Outbox\",\"stages\":["
                + "{\"id\":\"s\",\"approvers\":[{\"type\":\"USER\",\"userId\":\"user-410\"}]}]}");
        submit(workflow);

        JsonNode mine = objectMapper.readTree(rest.exchange("/api/v1/admin/outbox?status=PENDING",
                HttpMethod.GET, new HttpEntity<String>(headers(null)), String.class).getBody());
        assertThat(mine.size()).isGreaterThan(0);
        String eventId = mine.get(0).get("eventId").asText();

        HttpHeaders other = headers(null);
        other.set("X-Api-Key", register().get("apiKey").asText());

        JsonNode theirs = objectMapper.readTree(rest.exchange("/api/v1/admin/outbox?status=PENDING",
                HttpMethod.GET, new HttpEntity<String>(other), String.class).getBody());
        assertThat(theirs.size()).isEqualTo(0);

        JsonNode summary = objectMapper.readTree(rest.exchange("/api/v1/admin/outbox/summary",
                HttpMethod.GET, new HttpEntity<String>(other), String.class).getBody());
        assertThat(summary.get("PENDING").asLong()).isEqualTo(0L);

        ResponseEntity<String> replay = rest.exchange("/api/v1/admin/outbox/" + eventId + "/replay",
                HttpMethod.POST, new HttpEntity<String>(other), String.class);
        assertThat(replay.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void webhookSecretCanOnlyBeRotatedByItsOwnApplication() throws Exception {
        String path = "/api/v1/admin/applications/" + appId + "/rotate-secret";

        // Probed with GET: the filter rejects an unauthenticated call before the method is even
        // considered, and the JDK HTTP client cannot read a 401 returned to a POST.
        assertThat(rest.exchange(path, HttpMethod.GET, new HttpEntity<String>(new HttpHeaders()),
                String.class).getStatusCodeValue()).isEqualTo(401);

        HttpHeaders other = headers(null);
        other.set("X-Api-Key", register().get("apiKey").asText());
        assertThat(rest.exchange(path, HttpMethod.POST, new HttpEntity<String>(other), String.class)
                .getStatusCodeValue()).isEqualTo(403);

        ResponseEntity<String> own = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<String>(headers(null)), String.class);
        assertThat(own.getStatusCodeValue()).isEqualTo(200);
        assertThat(objectMapper.readTree(own.getBody()).get("webhookSecret").asText()).isNotEmpty();
    }

    // --- error contract ---------------------------------------------------

    @Test
    void callerMistakesAreNotReportedAsServerErrors() throws Exception {
        ResponseEntity<String> badEnum = rest.exchange("/api/v1/requests?status=BOGUS",
                HttpMethod.GET, new HttpEntity<String>(headers(null)), String.class);
        assertThat(badEnum.getStatusCodeValue()).isEqualTo(400);
        assertThat(objectMapper.readTree(badEnum.getBody()).get("type").asText())
                .isEqualTo("parameter-invalid");

        ResponseEntity<String> badMethod = rest.exchange("/api/v1/requests/req_missing",
                HttpMethod.DELETE, new HttpEntity<String>(headers(null)), String.class);
        assertThat(badMethod.getStatusCodeValue()).isEqualTo(405);
        assertThat(objectMapper.readTree(badMethod.getBody()).get("type").asText())
                .isEqualTo("method-not-allowed");

        HttpHeaders plainText = headers(null);
        plainText.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> badMediaType = rest.exchange("/api/v1/requests",
                HttpMethod.POST, new HttpEntity<String>("hello", plainText), String.class);
        assertThat(badMediaType.getStatusCodeValue()).isEqualTo(415);
        assertThat(objectMapper.readTree(badMediaType.getBody()).get("type").asText())
                .isEqualTo("unsupported-media-type");
    }

    // --- helpers ----------------------------------------------------------

    /** Registration needs no key — it is where one comes from. */
    private JsonNode register() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/applications", HttpMethod.POST,
                new HttpEntity<String>("{\"name\":\"" + unique("app")
                        + "\",\"webhookUrl\":\"http://localhost:9/hooks\"}", headers), String.class);
        assertThat(response.getStatusCodeValue()).as(response.getBody()).isEqualTo(201);
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> actWithKey(String requestId, String actor, String idempotencyKey) {
        HttpHeaders headers = headers(actor);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/api/v1/requests/" + requestId + "/actions", HttpMethod.POST,
                new HttpEntity<String>("{\"action\":\"APPROVE\"}", headers), String.class);
    }

    /** Creates and publishes a workflow; {@code %s} in the template receives a unique key. */
    private String publish(String template) {
        String key = unique("wf");
        ResponseEntity<String> created = rest.exchange("/api/v1/workflows", HttpMethod.POST,
                new HttpEntity<String>(String.format(template, key), headers(null)), String.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as(created.getBody()).isTrue();

        ResponseEntity<String> published = rest.exchange(
                "/api/v1/workflows/" + key + "/versions/1/publish", HttpMethod.POST,
                new HttpEntity<String>(headers(null)), String.class);
        assertThat(published.getStatusCode().is2xxSuccessful()).as(published.getBody()).isTrue();
        return key;
    }

    private String submit(String workflow) throws Exception {
        String body = "{\"workflow\":\"" + workflow + "\",\"entityType\":\"test\",\"entityId\":\""
                + unique("ent") + "\",\"requestedBy\":\"user-101\",\"context\":{}}";
        ResponseEntity<String> response = rest.exchange("/api/v1/requests", HttpMethod.POST,
                new HttpEntity<String>(body, headers(null)), String.class);
        assertThat(response.getStatusCodeValue()).as(response.getBody()).isEqualTo(201);
        return objectMapper.readTree(response.getBody()).get("request").get("requestId").asText();
    }

    private ResponseEntity<String> act(String requestId, String actor, String action, String comment) {
        String body = comment == null
                ? "{\"action\":\"" + action + "\"}"
                : "{\"action\":\"" + action + "\",\"comment\":\"" + comment + "\"}";
        return rest.exchange("/api/v1/requests/" + requestId + "/actions", HttpMethod.POST,
                new HttpEntity<String>(body, headers(actor)), String.class);
    }

    private JsonNode detail(String requestId) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/requests/" + requestId,
                HttpMethod.GET, new HttpEntity<String>(headers(null)), String.class);
        assertThat(response.getStatusCodeValue()).as(response.getBody()).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }

    /** Back-dates the deadline of every PENDING task, so the next sweep treats it as overdue. */
    private void expireOutstandingTasks(String requestId) {
        List<ApprovalTaskEntity> overdue = new ArrayList<ApprovalTaskEntity>();
        for (ApprovalTaskEntity task : tasks.findByRequestIdOrderByCreatedAtAsc(requestId)) {
            if (task.getStatus() == TaskStatus.PENDING) {
                task.setDueAt(Instant.now().minusSeconds(60));
                overdue.add(task);
            }
        }
        tasks.saveAll(overdue);
    }

    private static String stageStatus(JsonNode detail, String stageId, int attempt) {
        for (JsonNode stage : detail.get("stages")) {
            if (stageId.equals(stage.get("stageId").asText()) && stage.get("attempt").asInt() == attempt) {
                return stage.get("status").asText();
            }
        }
        return null;
    }

    private static String taskStatus(JsonNode detail, String approverId) {
        for (JsonNode task : detail.get("tasks")) {
            if (approverId.equals(task.get("approverId").asText())) {
                return task.get("status").asText();
            }
        }
        return null;
    }

    private HttpHeaders headers(String actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", apiKey);
        if (actor != null) {
            headers.set("X-Actor-Id", actor);
        }
        return headers;
    }

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
