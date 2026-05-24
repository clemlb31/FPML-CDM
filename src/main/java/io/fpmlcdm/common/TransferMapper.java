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
            // CDS feeLeg/initialPayment
            Element feeLeg = XmlUtils.child(productElement, "feeLeg");
            if (feeLeg != null) {
                Element initPay = XmlUtils.child(feeLeg, "initialPayment");
                if (initPay != null) {
                    TransferState ts = buildTransferState(initPay);
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

        Element amtEl = XmlUtils.child(fpml, "paymentAmount");
        String ccy = XmlUtils.childText(amtEl, "currency");
        String amount = XmlUtils.childText(amtEl, "amount");
        if (amount != null && ccy != null) {
            tb.setQuantity(NonNegativeQuantity.builder()
                    .setValue(new BigDecimal(amount))
                    .setUnit(UnitType.builder()
                            .setCurrency(FieldWithMetaString.builder().setValue(ccy).build()).build())
                    .build());
            tb.setAsset(cdm.base.staticdata.asset.common.Asset.builder()
                    .setCash(Cash.builder()
                            .addIdentifier(AssetIdentifier.builder()
                                    .setIdentifier(FieldWithMetaString.builder().setValue(ccy).build())
                                    .setIdentifierType(AssetIdTypeEnum.CURRENCY_CODE)
                                    .build())
                            .build())
                    .build());
        }

        Element payDate = XmlUtils.child(fpml, "paymentDate");
        if (payDate != null) {
            AdjustableOrAdjustedOrRelativeDate.AdjustableOrAdjustedOrRelativeDateBuilder sdb =
                    AdjustableOrAdjustedOrRelativeDate.builder();
            // FpML <paymentDate> may have child <unadjustedDate>+<dateAdjustments>, OR child
            // <adjustedDate>, OR be a leaf element holding the adjusted date as text.
            String unadj = XmlUtils.childText(payDate, "unadjustedDate");
            if (unadj != null) sdb.setUnadjustedDate(DateMapper.parse(unadj));
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

        return TransferState.builder().setTransfer(tb.build()).build();
    }
}
