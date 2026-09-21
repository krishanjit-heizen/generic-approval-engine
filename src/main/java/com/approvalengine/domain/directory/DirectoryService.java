package com.approvalengine.domain.directory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The engine's view of an organisation's people.
 *
 * <p>This interface is the seam that keeps the engine domain-agnostic. Workflows talk about
 * "the requester's manager" and "whoever holds the FINANCE role"; this is what turns that
 * into user ids. A real deployment replaces the local implementation with an adapter over
 * an HRMS or IAM directory, and nothing else changes.
 *
 * <p>Two contractual points that the rest of the engine relies on:
 * <ul>
 *   <li><b>Only active people are ever returned.</b> Filtering happens here, not in callers,
 *       so "the role exists but everyone in it has left" surfaces as an empty result — which
 *       is exactly what triggers a stage's {@code onUnresolved} policy.</li>
 *   <li><b>Results are stably ordered.</b> Resolution feeds task creation, and a
 *       SEQUENTIAL stage asks approvers in order; an unstable order would make the same
 *       workflow behave differently between runs.</li>
 * </ul>
 */
public interface DirectoryService {

    /** @return the person, or empty if unknown or inactive */
    Optional<DirectoryPerson> findActivePerson(String userId);

    /**
     * @return the user's manager, or empty if they have none recorded or that manager is
     *         inactive. Empty is a normal outcome — the CEO has no manager, and a stage that
     *         escalates to one must fall back rather than fault.
     */
    Optional<String> findManagerOf(String userId);

    List<String> findActiveByRole(String role);

    List<String> findActiveByTeam(String team);

    List<String> findActiveByDepartment(String department);

    // ------------------------------------------------------------------
    // The reverse direction: given a user id, what do they hold?
    //
    // The client tells the engine *who* is calling and nothing more. Roles are never
    // accepted from the caller — a client that could assert its own roles could assert
    // its way into any approval. So the engine takes the user id and looks the
    // entitlements up here, then checks.
    // ------------------------------------------------------------------

    /** Roles this user currently holds. Empty if the user is unknown or inactive. */
    Set<String> rolesOf(String userId);

    /** Teams this user currently belongs to. */
    Set<String> teamsOf(String userId);

    /** The user's department, or empty if they have none. */
    Optional<String> departmentOf(String userId);

    // ------------------------------------------------------------------
    // Existence checks, used at publish time.
    //
    // A workflow naming a role nobody defined should be rejected while its author is
    // still looking at it — not discovered later, when a live request activates that
    // stage and parks in NEEDS_ATTENTION because of a typo.
    // ------------------------------------------------------------------

    boolean roleExists(String role);

    boolean teamExists(String team);

    boolean departmentExists(String department);

    boolean userExists(String userId);
}
