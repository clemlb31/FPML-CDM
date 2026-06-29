package io.fpmlcdm.fpml.cdm.common;

import cdm.base.staticdata.party.Party;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds per-document state shared across mappers within one trade:
 *   - the {@code <party id="…">} → CDM Party mapping (with globalKey),
 *   - mapping from FpML id (party1/party2) to {@code CounterpartyRoleEnum}.
 */
public class MappingContext {

    /** FpML id (e.g. "party1") → CDM Party with externalKey set. */
    public final Map<String, Party> parties = new LinkedHashMap<>();

    /**
     * Order parties were declared in the FpML document. Index 0 → PARTY_1, 1 → PARTY_2.
     * The dataset confirms parties' insertion order is the CounterpartyRoleEnum mapping.
     */
    public final Map<String, Integer> partyOrder = new LinkedHashMap<>();

    /**
     * When set, mappers MUST NOT re-assign PARTY_1 via their normal heuristics — the caller has
     * already chosen the ordering (e.g. outer trade is a swaption, so PARTY_1 = buyer).
     */
    public boolean partyOrderLocked = false;
}
