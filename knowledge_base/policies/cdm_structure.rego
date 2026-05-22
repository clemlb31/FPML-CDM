# CDM IRS Structure Policy
# OPA Rego policy for validating generated CDM 6.x TradeState JSON.
#
# Usage (if `opa` binary is installed):
#   opa eval -d policies/cdm_structure.rego -I 'data.cdm.irs.deny' < output.json
#
# The LangGraph test_node calls this optionally via subprocess.
# Violations are added to transform_diffs so the patch loop can fix them.
#
# This policy enforces structural invariants that must hold for any valid
# IRS CDM TradeState, regardless of the specific trade parameters.

package cdm.irs

# ── Required top-level structure ──────────────────────────────────────────────

deny contains msg if {
    not input.trade
    msg := "MISSING: root field 'trade'"
}

deny contains msg if {
    not input.trade.product
    msg := "MISSING: trade.product (must be NonTransferableProduct)"
}

deny contains msg if {
    not input.trade.tradeLot
    msg := "MISSING: trade.tradeLot (must be non-empty list)"
}

deny contains msg if {
    count(input.trade.tradeLot) == 0
    msg := "EMPTY: trade.tradeLot must have at least one TradeLot"
}

deny contains msg if {
    not input.trade.party
    msg := "MISSING: trade.party (at minimum two parties required for IRS)"
}

deny contains msg if {
    count(input.trade.party) < 2
    msg := "INSUFFICIENT: trade.party must have at least 2 parties for a bilateral IRS"
}

deny contains msg if {
    not input.trade.counterparty
    msg := "MISSING: trade.counterparty (PARTY_1 / PARTY_2 roles required)"
}

deny contains msg if {
    count(input.trade.counterparty) < 2
    msg := "INSUFFICIENT: trade.counterparty must define at least 2 roles (PARTY_1, PARTY_2)"
}

# ── Product structure ──────────────────────────────────────────────────────────

deny contains msg if {
    not input.trade.product.economicTerms
    msg := "MISSING: trade.product.economicTerms"
}

deny contains msg if {
    not input.trade.product.economicTerms.payout
    msg := "MISSING: trade.product.economicTerms.payout (must contain at least one InterestRatePayout)"
}

deny contains msg if {
    count(input.trade.product.economicTerms.payout) == 0
    msg := "EMPTY: trade.product.economicTerms.payout must be non-empty"
}

# Each payout must have a payerReceiver
deny contains msg if {
    payout := input.trade.product.economicTerms.payout[_]
    payout.InterestRatePayout
    not payout.InterestRatePayout.payerReceiver
    msg := "MISSING: InterestRatePayout.payerReceiver (required)"
}

# ── Party role validation ──────────────────────────────────────────────────────

# CounterpartyRoleEnum values in CDM 6.x: Party1, Party2
valid_roles := {"Party1", "Party2"}

deny contains msg if {
    cp := input.trade.counterparty[_]
    cp.role
    not valid_roles[cp.role]
    msg := sprintf(
        "INVALID: counterparty.role must be 'Party1' or 'Party2', got '%s'",
        [cp.role]
    )
}

# ── TradeLot / notional ────────────────────────────────────────────────────────

deny contains msg if {
    lot := input.trade.tradeLot[_]
    not lot.priceQuantity
    msg := "MISSING: TradeLot.priceQuantity"
}

# ── Date presence ──────────────────────────────────────────────────────────────

deny contains msg if {
    not input.trade.tradeDate
    msg := "MISSING: trade.tradeDate"
}

# ── Allow passes (no violations = empty deny set) ─────────────────────────────

# Helper: is there at least one InterestRatePayout across all payouts?
has_irs_payout if {
    payout := input.trade.product.economicTerms.payout[_]
    payout.InterestRatePayout
}

deny contains msg if {
    input.trade.product.economicTerms.payout
    not has_irs_payout
    msg := "MISSING: no InterestRatePayout found in any payout element"
}
