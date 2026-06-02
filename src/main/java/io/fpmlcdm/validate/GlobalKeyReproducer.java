package io.fpmlcdm.validate;

import cdm.event.common.TradeState;
import com.regnosys.rosetta.common.hashing.GlobalKeyProcessStep;
import com.regnosys.rosetta.common.hashing.NonNullHashCollector;
import com.regnosys.rosetta.common.hashing.ReKeyProcessStep;

/**
 * Reproduces the Regnosys content-hash algorithm to populate {@code globalKey} and
 * {@code globalReference} fields on a {@link TradeState}.
 *
 * Without this step, our converter output has no globalKeys (the builders don't set
 * them) and we must mask {@code globalKey}/{@code globalReference} in {@code SemanticDiff}.
 * After running this reproducer, our output should carry the SAME deterministic
 * content hashes as the reference dataset, enabling byte-level comparison AND
 * automatic verification of cross-reference integrity.
 *
 * The pipeline mirrors what Regnosys runs internally after ingestion:
 *   1. {@link GlobalKeyProcessStep} — hash each {@code GlobalKey}-bearing object's
 *      content and store the result on {@code meta.globalKey}.
 *   2. {@link ReKeyProcessStep} — re-walk and fill {@code globalReference} fields
 *      with the keys produced in step 1.
 */
public final class GlobalKeyReproducer {

    private final GlobalKeyProcessStep keyStep;
    private final ReKeyProcessStep reKeyStep;

    public GlobalKeyReproducer() {
        this.keyStep = new GlobalKeyProcessStep(NonNullHashCollector::new);
        this.reKeyStep = new ReKeyProcessStep(this.keyStep);
    }

    public TradeState apply(TradeState ts) {
        TradeState.TradeStateBuilder builder = ts.toBuilder();
        keyStep.runProcessStep(TradeState.class, (TradeState) builder);
        reKeyStep.runProcessStep(TradeState.class, (TradeState) builder);
        return builder.build();
    }
}
