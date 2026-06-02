package io.fpmlcdm.common;

import cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.math.NonNegativeQuantity;
import cdm.base.math.UnitType;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.Cash;
import cdm.base.staticdata.party.PartyReferencePayerReceiver;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.Transfer;
import cdm.event.common.TransferExpression;
import cdm.event.common.TransferState;
import com.rosetta.model.metafields.FieldWithMetaString;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps FpML {@code <additionalPayment>} and {@code <otherPartyPayment>} (inside {@code <swap>})
 * into CDM {@link TransferState} entries for the root {@code TradeState.transferHistory[]}.
 */
public final class TransferMapper {

    private TransferMapper() {}

    public static List<TransferState> map(Element trade, Element productElement) {
        List<TransferState> out = new ArrayList<>();
        if (productElement != null) {
            for (Element p : XmlUtils.children(productElement, "additionalPayment")) {
                TransferState ts = buildTransferState(p);
                if (ts != null) out.add(ts);
            }
            // CDS feeLeg/initialPayment and feeLeg/singlePayment (the latter uses <fixedAmount>)
            Element feeLeg = XmlUtils.child(productElement, "feeLeg");
            if (feeLeg != null) {
                Element initPay = XmlUtils.child(feeLeg, "initialPayment");
                if (initPay != null) {
                    TransferState ts = buildTransferState(initPay);
                    if (ts != null) out.add(ts);
                }
                Element singlePay = XmlUtils.child(feeLeg, "singlePayment");
                if (singlePay != null) {
                    TransferState ts = buildTransferState(singlePay);
                    if (ts != null) out.add(ts);
                }
            }
        }
        if (trade != null) {
            for (Element p : XmlUtils.children(trade, "otherPartyPayment")) {
                TransferState ts = buildTransferState(p);
                if (ts != null) out.add(ts);
            }
        }
        return out;
    }

    private static TransferState buildTransferState(Element fpml) {
        Transfer.TransferBuilder tb = Transfer.builder();

        // <paymentAmount> for initialPayment / additionalPayment; <fixedAmount> for CDS feeLeg/singlePayment;
        // <additionalPaymentAmount>/<paymentAmount> for returnSwap additionalPayment
        Element amtEl = XmlUtils.child(fpml, "paymentAmount");
        if (amtEl == null) {
            Element addAmt = XmlUtils.child(fpml, "additionalPaymentAmount");
            if (addAmt != null) amtEl = XmlUtils.child(addAmt, "paymentAmount");
        }
        if (amtEl == null) amtEl = XmlUtils.child(fpml, "fixedAmount");
        Element ccyEl = amtEl == null ? null : XmlUtils.child(amtEl, "currency");
        String ccy = ccyEl == null ? null : ccyEl.getTextContent().trim();
        String ccyScheme = ccyEl == null ? null : ccyEl.getAttribute("currencyScheme");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            FieldWithMetaString.FieldWithMetaStringBuilder ccyB = FieldWithMetaString.builder().setValue(ccy);
            if (ccyScheme != null && !ccyScheme.isEmpty()) {
                ccyB.setMeta(com.rosetta.model.metafields.MetaFields.builder().setScheme(ccyScheme).build());
            }
            tb.setQuantity(NonNegativeQuantity.builder()
                    .setValue(new BigDecimal(amount))
                    .setUnit(UnitType.builder()
                            .setCurrency(ccyB.build()).build())
                    .build());
            // The reference ingester drops the currency scheme on Cash.identifier
            // (it survives only on quantity.unit.currency.meta).
            tb.setAsset(cdm.base.staticdata.asset.common.Asset.builder()
                    .setCash(Cash.builder()
                            .addIdentifier(AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(AssetIdTypeEnum.CURRENCY_CODE)
                                    .build())
                            .build())
                    .build());
        }

        // CDS feeLeg/initialPayment uses sibling <adjustablePaymentDate> (unadjusted) +
        // <adjustedPaymentDate> (adjusted) instead of a nested <paymentDate>.
        String adjustablePaymentDate = XmlUtils.childText(fpml, "adjustablePaymentDate");
        String adjustedPaymentDate = XmlUtils.childText(fpml, "adjustedPaymentDate");
        if (adjustablePaymentDate != null || adjustedPaymentDate != null) {
            AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    AdjustableOrAdjustedOrRelativeDate.builder();
            if (adjustablePaymentDate != null) sdb.setUnadjustedDate(DateMapper.parse(adjustablePaymentDate));
            if (adjustedPaymentDate != null) {
                sdb.setAdjustedDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(adjustedPaymentDate)).build());
            }
            tb.setSettlementDate(sdb.build());
        }

        Element payDate = XmlUtils.child(fpml, "paymentDate");
        Element relDateForSettlement = null;
        boolean fromAdditionalPaymentDate = false;
        if (payDate == null) {
            // returnSwap additionalPayment uses <additionalPaymentDate>/<adjustableDate or relativeDate>
            Element addDate = XmlUtils.child(fpml, "additionalPaymentDate");
            if (addDate != null) {
                payDate = XmlUtils.child(addDate, "adjustableDate");
                if (payDate == null) relDateForSettlement = XmlUtils.child(addDate, "relativeDate");
                else fromAdditionalPaymentDate = true;
            }
        }
        if (relDateForSettlement != null) {
            AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    AdjustableOrAdjustedOrRelativeDate.builder();
            sdb.setRelativeDate(io.fpmlcdm.common.DateMapper.buildRelativeDateOffset(relDateForSettlement));
            tb.setSettlementDate(sdb.build());
        }
        if (payDate != null) {
            AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    AdjustableOrAdjustedOrRelativeDate.builder();
            // FpML <paymentDate> may have child <unadjustedDate>+<dateAdjustments>, OR child
            // <adjustedDate>, OR be a leaf element holding the adjusted date as text.
            // For <additionalPaymentDate>/<adjustableDate> the reference CDM JSON omits
            // unadjustedDate (only keeps dateAdjustments). Mirror that here.
            String unadj = XmlUtils.childText(payDate, "unadjustedDate");
            if (unadj != null && !fromAdditionalPaymentDate) sdb.setUnadjustedDate(DateMapper.parse(unadj));
            String adj = XmlUtils.childText(payDate, "adjustedDate");
            if (adj != null) {
                sdb.setAdjustedDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(DateMapper.parse(adj)).build());
            } else if (unadj == null) {
                String text = payDate.getTextContent().trim();
                if (!text.isEmpty()) {
                    sdb.setAdjustedDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                            .setValue(DateMapper.parse(text)).build());
                }
            }
            BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(
                    XmlUtils.child(payDate, "dateAdjustments"));
            if (bda != null) sdb.setDateAdjustments(bda);
            tb.setSettlementDate(sdb.build());
        }

        Element payerRef = XmlUtils.child(fpml, "payerPartyReference");
        Element receiverRef = XmlUtils.child(fpml, "receiverPartyReference");
        if (payerRef != null || receiverRef != null) {
            PartyReferencePayerReceiver.PartyReferencePayerReceiverBuilder pr =
                    PartyReferencePayerReceiver.builder();
            if (payerRef != null) {
                pr.setPayerPartyReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(payerRef.getAttribute("href")).build());
            }
            if (receiverRef != null) {
                pr.setReceiverPartyReference(ReferenceWithMetaParty.builder()
                        .setExternalReference(receiverRef.getAttribute("href")).build());
            }
            tb.setPayerReceiver(pr.build());
        }

        tb.setTransferExpression(TransferExpression.builder()
                .setPriceTransfer(cdm.observable.asset.FeeTypeEnum.UPFRONT)
                .build());

        TransferState.TransferStateBuilder tsb = TransferState.builder().setTransfer(tb.build());
        // CDS feeLeg/singlePayment carries an FpML id (e.g. "iey785"); surface it as the
        // TransferState.meta.externalKey to match the reference ingester.
        String fpmlId = fpml.getAttribute("id");
        if (fpmlId != null && !fpmlId.isEmpty()) {
            tsb.setMeta(com.rosetta.model.metafields.MetaFields.builder().setExternalKey(fpmlId).build());
        }
        return tsb.build();
    }
}
