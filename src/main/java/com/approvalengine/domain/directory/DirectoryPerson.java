package com.approvalengine.domain.directory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * A person the engine may ask for approval.
 *
 * <p>Deliberately minimal: id, a name to show, a manager to escalate to, a department to
 * group by. The engine has no business knowing anything else about an employee, and a
 * narrow shape keeps a real HRMS adapter cheap to write.
 */
@Getter
@AllArgsConstructor
@Builder
public class DirectoryPerson {

    private final String userId;
    private final String displayName;
    private final String email;
    private final String managerId;
    private final String department;

    /** Fallback for a user id the directory does not know — used to render inbox history. */
    public static DirectoryPerson unknown(String userId) {
        return DirectoryPerson.builder().userId(userId).displayName(userId).build();
    }
}
