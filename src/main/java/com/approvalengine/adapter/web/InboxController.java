package com.approvalengine.adapter.web;

import com.approvalengine.application.InboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The approver's surface — the one human-facing part of the engine.
 *
 * <p>Deliberately <b>not</b> scoped to the calling application. An approver's queue spans every
 * consuming application, because a person who approves expenses and purchase orders should have
 * one place to go, not two. The API key still authenticates the caller; it just does not filter
 * the results.
 *
 * <p>Identity comes from {@code X-Actor-Id}, which is not authenticated in this phase. What makes
 * that acceptable rather than negligent: every item is filtered to tasks the actor actually holds,
 * and acting on one re-verifies ownership. So the exposure is reading another approver's queue,
 * never acting as them. Closing it properly needs OIDC in front of these endpoints, and that is
 * recorded as a known gap rather than assumed away.
 */
@RestController
@RequestMapping("/api/v1/inbox")
@Tag(name = "Inbox", description = "An approver's queue, spanning every consuming application")
public class InboxController {

    private final InboxService inbox;

    public InboxController(InboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    @Operation(summary = "Everything awaiting this approver",
            description = "Oldest first. Only tasks that are actually the approver's turn appear — "
                    + "a task queued behind someone else in a sequential stage is hidden until "
                    + "it is promoted.")
    public List<InboxService.InboxItem> pending(CallerContext caller) {
        return inbox.pendingFor(caller.requireActor());
    }

    @GetMapping("/history")
    @Operation(summary = "What this approver has already decided, most recent first")
    public List<InboxService.InboxItem> history(CallerContext caller,
                                                @RequestParam(defaultValue = "50") int limit) {
        return inbox.historyFor(caller.requireActor(), limit);
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "The full approval screen for one task",
            description = "Returns a rendered title and already-resolved label/value pairs, built "
                    + "from the workflow's display block — so the engine can present a domain it "
                    + "knows nothing about, and every client renders it identically.")
    public InboxService.InboxItem open(CallerContext caller, @PathVariable String taskId) {
        return inbox.open(taskId, caller.requireActor());
    }
}
