package com.approvalengine.application.resolver;

import com.approvalengine.domain.workflow.ApproverSpec;
import com.approvalengine.domain.workflow.condition.EvaluationContext;

import java.util.List;

/**
 * Turns one {@link ApproverSpec} into concrete user ids.
 *
 * <p>An SPI rather than a switch statement: adding a new way to name approvers — an
 * on-call rota, a cost-centre owner, a vendor's account manager — means adding one bean,
 * with no change to the executor, the resolution service, or the workflow schema beyond a
 * new enum constant.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>return an <b>empty list rather than throwing</b> when nobody matches. "Nobody
 *       matched" is an ordinary business outcome that the stage's {@code onUnresolved}
 *       policy is there to handle; an exception would instead abort the whole request.</li>
 *   <li>return a <b>stable order</b>, because a SEQUENTIAL stage asks approvers in the
 *       order given.</li>
 *   <li>never return inactive users — that filtering belongs to the directory.</li>
 * </ul>
 */
public interface ApproverResolver {

    /** The spec type this resolver handles. One resolver per type. */
    ApproverSpec.Type supportedType();

    /**
     * @param spec    the declaration to resolve
     * @param context the request's business context, for specs that read from it
     * @return matching approvers, possibly empty; never null
     */
    List<ResolvedApprover> resolve(ApproverSpec spec, EvaluationContext context);
}
