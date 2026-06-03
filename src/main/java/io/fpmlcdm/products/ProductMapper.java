package io.fpmlcdm.products;

import cdm.event.common.TradeState;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Strategy for converting one FpML {@code <trade>} subtree into a {@link TradeState}.
 *
 * <h2>Implementation contract</h2>
 *
 * <p>Implementations are stateless per call; a new instance is allocated by
 * {@link io.fpmlcdm.detect.ProductDetector#dispatch(Element)} for every trade.
 * Build a fresh {@link io.fpmlcdm.common.MappingContext} in {@code map()},
 * never share state across invocations.
 *
 * <p>{@code map()} may return {@code null} to signal "no salvageable output"
 * for this trade; the caller ({@link io.fpmlcdm.FpmlToCdmConverter}) currently
 * adds whatever is returned to its list, so prefer building a minimal-but-valid
 * {@link TradeState} over returning null when possible.
 *
 * <h2>PARTY_1 / PARTY_2 conventions (IMPORTANT — varies by family)</h2>
 *
 * <p>The reference CDM dataset uses three different conventions for assigning
 * which FpML party becomes {@code PARTY_1}. Pick the one matching your product
 * family; mismatch produces correct CDM that fails reference equality.
 *
 * <ul>
 *   <li><b>Interest-rate products</b> ({@link SwapMapper}, {@link CapFloorMapper}):
 *       {@code PARTY_1} = payer of the FIRST {@code <swapStream>} in document order.
 *       Implemented via a per-mapper {@code assignCounterpartyRoles(streams, ctx)}
 *       that reorders {@code ctx.partyOrder} before payouts are built so
 *       {@link io.fpmlcdm.payouts.InterestRatePayoutMapper} sees the right mapping.</li>
 *
 *   <li><b>Credit products</b> ({@link CreditDefaultSwapMapper}):
 *       {@code PARTY_1} = seller of protection.</li>
 *
 *   <li><b>Options and FRA</b> ({@link FraMapper}, {@link FxOptionMapper},
 *       {@link EquityOptionMapper}, …): {@code PARTY_1} = buyer / option holder.</li>
 * </ul>
 *
 * <p>When adding a new mapper, document at the top of the class which convention
 * it follows and why.
 *
 * <h2>Trade-level metadata: {@code applyPartyTradeInformation}</h2>
 *
 * <p>{@link SwapMapper#applyPartyTradeInformation(java.util.List, Element)}
 * reads {@code <partyTradeInformation>/<relatedPerson>} and {@code <relatedBusinessUnit>}
 * from the trade header and merges them back onto each {@link cdm.base.staticdata.party.Party}.
 * Call it from your mapper if your reference CDM JSONs carry party-level person/business-unit
 * metadata (most do). The current opt-outs ({@link FraMapper}, {@link CreditDefaultSwapMapper},
 * {@link CapFloorMapper}) skip it because their reference data does not surface this metadata.
 *
 * <h2>Counterparty list: reuse {@link SwapMapper#buildCounterparties(io.fpmlcdm.common.MappingContext)}</h2>
 *
 * <p>That helper is the canonical implementation for emitting the {@code Counterparty[]}
 * list from {@code ctx.partyOrder}. 21+ mappers route to it. Do not re-implement it locally.
 *
 * <h2>Adding a mapper checklist</h2>
 *
 * <ol>
 *   <li>Pick a similar existing mapper as your starting template (do <em>not</em> copy SwapMapper
 *       wholesale — its IRS-specific logic is significant).</li>
 *   <li>Choose your {@code PARTY_1} convention (see above) and document it.</li>
 *   <li>Register the FpML element in {@link io.fpmlcdm.detect.ProductDetector#dispatch(Element)}
 *       at a position that respects precedence — wrapping products (e.g. {@code <swaption>}
 *       containing an inner {@code <swap>}) must be detected <em>before</em> their inner type.</li>
 *   <li>Add a pair to {@code data/train/<category>/{fpml,cdm}/} or rely on existing categories
 *       to exercise the new code path via {@code DataDrivenValidationTest}.</li>
 * </ol>
 */
public interface ProductMapper {
    TradeState map(Document doc, Element trade);
}
