package io.fpmlcdm.products;

import cdm.base.datetime.CalculationPeriodFrequency;
import cdm.base.datetime.Frequency;
import cdm.base.datetime.RollConventionEnum;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.CreditIndex;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.observable.asset.metafields.FieldWithMetaPriceSchedule;
import cdm.observable.asset.metafields.ReferenceWithMetaPriceSchedule;
import cdm.observable.event.CreditEvents;
import cdm.observable.event.FailureToPay;
import cdm.observable.event.Restructuring;
import cdm.observable.event.RestructuringEnum;
import cdm.observable.event.metafields.FieldWithMetaRestructuringEnum;
import cdm.product.asset.CreditDefaultPayout;
import cdm.product.asset.CreditSeniorityEnum;
import cdm.product.asset.FixedRateSpecification;
import cdm.product.asset.GeneralTerms;
import cdm.product.asset.IndexAnnexSourceEnum;
import cdm.product.asset.InterestRatePayout;
import cdm.product.asset.ProtectionTerms;
import cdm.product.asset.RateSpecification;
import cdm.product.asset.ReferenceInformation;
import cdm.product.asset.ReferenceObligation;
import cdm.product.asset.SettledEntityMatrix;
import cdm.product.asset.SettledEntityMatrixSourceEnum;
import cdm.product.asset.Tranche;
import cdm.product.asset.metafields.FieldWithMetaIndexAnnexSourceEnum;
import cdm.product.asset.metafields.FieldWithMetaSettledEntityMatrixSourceEnum;
import cdm.product.common.schedule.PaymentDates;
import cdm.product.common.schedule.RateSchedule;
import cdm.product.common.settlement.ResolvablePriceQuantity;
import cdm.product.template.*;
import com.rosetta.model.lib.meta.Key;
import com.rosetta.model.lib.meta.Reference;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import io.fpmlcdm.common.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class CreditDefaultSwapMapper implements ProductMapper {

    @Override
    public TradeState map(Document doc, Element trade) {
        MappingContext ctx = new MappingContext();
        List<Party> parties = PartyMapper.map(doc, ctx);

        Element tradeHeader = XmlUtils.child(trade, "tradeHeader");
        Element cds = XmlUtils.child(trade, "creditDefaultSwap");
        Element generalTerms = XmlUtils.child(cds, "generalTerms");

        // PARTY_1 = seller of protection
        String sellerHref = null;
        Element sellerRef = XmlUtils.child(generalTerms, "sellerPartyReference");
        if (sellerRef != null) sellerHref = sellerRef.getAttribute("href");
        assignRoles(sellerHref, ctx);

        // economicTerms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();

        // terminationDate from scheduledTerminationDate
        Element schedTerm = XmlUtils.child(generalTerms, "scheduledTerminationDate");
        if (schedTerm != null) {
            econ.setTerminationDate(DateMapper.adjustableOrRelative(schedTerm));
        }

        // effectiveDate
        Element effectiveDate = XmlUtils.child(generalTerms, "effectiveDate");
        if (effectiveDate != null) {
            econ.setEffectiveDate(DateMapper.adjustableOrRelative(effectiveDate));
        }

        // CreditDefaultPayout
        CreditDefaultPayout.CreditDefaultPayoutBuilder cdpBuilder = CreditDefaultPayout.builder();

        // payerReceiver: buyer = receiver of protection, seller = payer of contingent
        String buyerHref = null;
        Element buyerRef = XmlUtils.child(generalTerms, "buyerPartyReference");
        if (buyerRef != null) buyerHref = buyerRef.getAttribute("href");
        Integer sellerOrder = sellerHref != null ? ctx.partyOrder.get(sellerHref) : null;
        CounterpartyRoleEnum sellerRole = (sellerOrder != null && sellerOrder == 0)
                ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
        CounterpartyRoleEnum buyerRole = sellerRole == CounterpartyRoleEnum.PARTY_1
                ? CounterpartyRoleEnum.PARTY_2 : CounterpartyRoleEnum.PARTY_1;
        cdpBuilder.setPayerReceiver(PayerReceiver.builder()
                .setPayer(sellerRole).setReceiver(buyerRole).build());

        // priceQuantity address ref into quantity-1 (the quantity-only PriceQuantity at index 1).
        // If the FpML protectionTerms/calculationAmount has an @id, surface it as meta.externalKey.
        ResolvablePriceQuantity.ResolvablePriceQuantityBuilder rpqBuilder = ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                        .build());
        Element calcAmountEarly = XmlUtils.path(cds, "protectionTerms", "calculationAmount");
        if (calcAmountEarly != null) {
            String id = calcAmountEarly.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                rpqBuilder.setMeta(MetaFields.builder().setExternalKey(id).build());
            }
        }
        cdpBuilder.setPriceQuantity(rpqBuilder.build());

        // generalTerms
        GeneralTerms.GeneralTermsBuilder gtBuilder = GeneralTerms.builder();
        Element indexRefInfo = XmlUtils.child(generalTerms, "indexReferenceInformation");
        Element refInfo = XmlUtils.child(generalTerms, "referenceInformation");

        boolean hasTranche = false;
        if (indexRefInfo != null) {
            CreditIndex ci = buildCreditIndex(indexRefInfo);
            hasTranche = ci.getTranche() != null;
            gtBuilder.setIndexReferenceInformation(ci);
        }
        if (refInfo != null) {
            gtBuilder.setReferenceInformation(buildReferenceInformation(refInfo));
        }
        cdpBuilder.setGeneralTerms(gtBuilder.build());

        // protectionTerms
        Element protTerms = XmlUtils.child(cds, "protectionTerms");
        if (protTerms != null) {
            ProtectionTerms pt = buildProtectionTerms(protTerms);
            if (pt != null) cdpBuilder.addProtectionTerms(pt);
        }

        // settlementTerms (from cashSettlementTerms or physicalSettlementTerms inside creditDefaultSwap)
        cdm.product.common.settlement.SettlementTerms st = buildSettlementTerms(cds);
        if (st != null) cdpBuilder.setSettlementTerms(st);

        Payout payout = Payout.builder().setCreditDefaultPayout(cdpBuilder.build()).build();
        econ.addPayout(payout);

        // calculationAgent (FpML <calculationAgentBusinessCenter> sibling of creditDefaultSwap)
        String agentBc = XmlUtils.childText(trade, "calculationAgentBusinessCenter");
        if (agentBc != null) {
            try {
                cdm.base.datetime.BusinessCenterEnum bce =
                        cdm.base.datetime.BusinessCenterEnum.valueOf(agentBc);
                econ.setCalculationAgent(cdm.observable.asset.CalculationAgent.builder()
                        .setCalculationAgentBusinessCenter(
                                cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum.builder()
                                        .setValue(bce).build())
                        .build());
            } catch (IllegalArgumentException ignored) {}
        }

        // Fee leg → InterestRatePayout (payout[1]) - only when periodicPayment exists
        Element feeLeg = XmlUtils.child(cds, "feeLeg");
        Element periodicPayment = feeLeg == null ? null : XmlUtils.child(feeLeg, "periodicPayment");
        if (periodicPayment != null) {
            Payout feePayout = buildFeeLegPayout(feeLeg, buyerRole, sellerRole);
            if (feePayout != null) econ.addPayout(feePayout);
        }

        // Product
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());

        // Taxonomy
        String qualifier;
        if (indexRefInfo != null) {
            qualifier = hasTranche ? "CreditDefaultSwap_IndexTranche" : "CreditDefaultSwap_Index";
        } else {
            qualifier = "CreditDefaultSwap_SingleName";
        }
        ntp.addTaxonomy(cdm.base.staticdata.asset.common.ProductTaxonomy.builder()
                .setSource(cdm.base.staticdata.asset.common.TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());

        // TradeLot — split into two PriceQuantity entries: [0]=price, [1]=quantity
        Element calcAmount = XmlUtils.path(protTerms, "calculationAmount");
        List<PriceQuantity> pqs = new ArrayList<>();

        // [0] price (the periodic fixed rate from feeLeg) - only when there's a periodicPayment with a fixedRate
        Element ccyEl = calcAmount != null ? XmlUtils.child(calcAmount, "currency") : null;
        String ccy = ccyEl != null ? ccyEl.getTextContent().trim() : null;
        String ccyScheme = ccyEl != null ? ccyEl.getAttribute("currencyScheme") : null;
        BigDecimal fixedRate = null;
        if (periodicPayment != null) {
            Element fixedRateEl = XmlUtils.path(periodicPayment, "fixedAmountCalculation", "fixedRate");
            if (fixedRateEl != null) {
                fixedRate = new BigDecimal(fixedRateEl.getTextContent().trim());
            }
        }
        if (fixedRate != null && ccy != null) {
            UnitType ccyUnit = ccyUnit(ccy, ccyScheme);
            PriceSchedule ps = PriceSchedule.builder()
                    .setValue(fixedRate)
                    .setUnit(ccyUnit)
                    .setPerUnitOf(ccyUnit)
                    .setPriceType(PriceTypeEnum.INTEREST_RATE)
                    .build();
            FieldWithMetaPriceSchedule price = FieldWithMetaPriceSchedule.builder()
                    .setValue(ps)
                    .setMeta(MetaFields.builder().addKey(
                            Key.builder().setScope("DOCUMENT").setKeyValue("price-1").build()).build())
                    .build();
            pqs.add(PriceQuantity.builder().addPrice(price).build());
        }

        // [1] quantity
        if (calcAmount != null) {
            String amt = XmlUtils.childText(calcAmount, "amount");
            UnitType qtyUnit = ccyUnit(ccy, ccyScheme);
            NonNegativeQuantitySchedule qty = NonNegativeQuantitySchedule.builder()
                    .setValue(new BigDecimal(amt))
                    .setUnit(qtyUnit)
                    .build();
            pqs.add(PriceQuantity.builder()
                    .addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                            .setValue(qty)
                            .setMeta(MetaFields.builder().addKey(
                                    Key.builder().setScope("DOCUMENT").setKeyValue("quantity-1").build()).build())
                            .build())
                    .build());
        }
        TradeLot tradeLot = TradeLot.builder().setPriceQuantity(pqs).build();

        // Counterparties
        List<Counterparty> counterparties = new ArrayList<>();
        ctx.partyOrder.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue())
                .forEach(e -> counterparties.add(Counterparty.builder()
                        .setRole(e.getValue() == 0 ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2)
                        .setPartyReference(ReferenceWithMetaParty.builder()
                                .setExternalReference(e.getKey()).build())
                        .build()));

        // Trade identifiers
        List<TradeIdentifier> identifiers = new ArrayList<>();
        if (tradeHeader != null) {
            for (Element pti : XmlUtils.children(tradeHeader, "partyTradeIdentifier")) {
                identifiers.addAll(IdentifierMapper.mapWithSplit(pti));
            }
        }

        // Trade date
        FieldWithMetaDate tradeDate = null;
        String tdText = XmlUtils.pathText(tradeHeader, "tradeDate");
        if (tdText != null) {
            tradeDate = FieldWithMetaDate.builder().setValue(DateMapper.parse(tdText)).build();
        }

        // Contract details
        ContractDetails contractDetails = ContractDetailsMapper.map(
                XmlUtils.child(trade, "documentation"), parties, ctx);

        Trade.TradeBuilder t = Trade.builder()
                .setProduct(ntp.build())
                .addTradeLot(tradeLot);
        counterparties.forEach(t::addCounterparty);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        // transferHistory from feeLeg/initialPayment
        TradeState.TradeStateBuilder tsb = TradeState.builder().setTrade(t.build());
        for (TransferState ts : TransferMapper.map(trade, cds)) {
            tsb.addTransferHistory(ts);
        }
        return tsb.build();
    }

    private CreditIndex buildCreditIndex(Element el) {
        CreditIndex.CreditIndexBuilder b = CreditIndex.builder();
        Element nameEl = XmlUtils.child(el, "indexName");
        if (nameEl != null) {
            String name = nameEl.getTextContent().trim();
            FieldWithMetaString.FieldWithMetaStringBuilder nb = FieldWithMetaString.builder().setValue(name);
            String scheme = nameEl.getAttribute("indexNameScheme");
            if (scheme != null && !scheme.isEmpty()) {
                nb.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            b.setName(nb.build());
        }
        // indexId → AssetIdentifier (identifierType=Name)
        for (Element idEl : XmlUtils.children(el, "indexId")) {
            String idScheme = idEl.getAttribute("indexIdScheme");
            cdm.base.staticdata.asset.common.AssetIdentifier.AssetIdentifierBuilder aib =
                    cdm.base.staticdata.asset.common.AssetIdentifier.builder()
                            .setIdentifier(FieldWithMetaString.builder()
                                    .setValue(idEl.getTextContent().trim())
                                    .setMeta(MetaFields.builder().setScheme(idScheme).build())
                                    .build())
                            .setIdentifierType(cdm.base.staticdata.asset.common.AssetIdTypeEnum.NAME);
            b.addIdentifier(aib.build());
        }
        b.setAssetClass(AssetClassEnum.CREDIT);
        String series = XmlUtils.childText(el, "indexSeries");
        if (series != null) b.setIndexSeries(Integer.parseInt(series));
        String version = XmlUtils.childText(el, "indexAnnexVersion");
        if (version != null) b.setIndexAnnexVersion(Integer.parseInt(version));
        String annexDate = XmlUtils.childText(el, "indexAnnexDate");
        if (annexDate != null) b.setIndexAnnexDate(DateMapper.parse(annexDate));
        String annexSource = XmlUtils.childText(el, "indexAnnexSource");
        if (annexSource != null) {
            try {
                IndexAnnexSourceEnum src = IndexAnnexSourceEnum.fromDisplayName(annexSource);
                b.setIndexAnnexSource(FieldWithMetaIndexAnnexSourceEnum.builder().setValue(src).build());
            } catch (Exception ignored) {
                try {
                    IndexAnnexSourceEnum src = IndexAnnexSourceEnum.valueOf(annexSource.toUpperCase());
                    b.setIndexAnnexSource(FieldWithMetaIndexAnnexSourceEnum.builder().setValue(src).build());
                } catch (Exception ignored2) {}
            }
        }

        for (Element excl : XmlUtils.children(el, "excludedReferenceEntity")) {
            b.addExcludedReferenceEntity(buildReferenceInformation(excl));
        }

        String seniority = XmlUtils.childText(el, "seniority");
        if (seniority != null) {
            try { b.setSeniority(CreditSeniorityEnum.fromDisplayName(seniority)); }
            catch (Exception ignored) {}
        }

        // Tranche
        Element tranche = XmlUtils.child(el, "tranche");
        if (tranche != null) {
            Tranche.TrancheBuilder tb = Tranche.builder();
            String ap = XmlUtils.childText(tranche, "attachmentPoint");
            if (ap != null) tb.setAttachmentPoint(new BigDecimal(ap));
            String ep = XmlUtils.childText(tranche, "exhaustionPoint");
            if (ep != null) tb.setExhaustionPoint(new BigDecimal(ep));
            String ira = XmlUtils.childText(tranche, "incurredRecoveryApplicable");
            if (ira != null) tb.setIncurredRecoveryApplicable(Boolean.parseBoolean(ira));
            b.setTranche(tb.build());
        }

        // SettledEntityMatrix
        Element sem = XmlUtils.child(el, "settledEntityMatrix");
        if (sem != null) {
            SettledEntityMatrix.SettledEntityMatrixBuilder smb = SettledEntityMatrix.builder();
            String ms = XmlUtils.childText(sem, "matrixSource");
            if (ms != null) {
                SettledEntityMatrixSourceEnum mse = null;
                try { mse = SettledEntityMatrixSourceEnum.fromDisplayName(ms); }
                catch (Exception ignored) {
                    try { mse = SettledEntityMatrixSourceEnum.valueOf(ms.toUpperCase()); } catch (Exception ig2) {}
                }
                if (mse != null) {
                    smb.setMatrixSource(FieldWithMetaSettledEntityMatrixSourceEnum.builder().setValue(mse).build());
                }
            }
            String pd = XmlUtils.childText(sem, "publicationDate");
            if (pd != null) smb.setPublicationDate(DateMapper.parse(pd));
            b.setSettledEntityMatrix(smb.build());
        }
        return b.build();
    }

    private ReferenceInformation buildReferenceInformation(Element el) {
        ReferenceInformation.ReferenceInformationBuilder b = ReferenceInformation.builder();
        Element refEntity = XmlUtils.child(el, "referenceEntity");
        if (refEntity != null) {
            LegalEntity.LegalEntityBuilder le = LegalEntity.builder();
            String eName = XmlUtils.childText(refEntity, "entityName");
            if (eName != null) le.setName(FieldWithMetaString.builder().setValue(eName).build());
            for (Element entityId : XmlUtils.children(refEntity, "entityId")) {
                String scheme = entityId.getAttribute("entityIdScheme");
                cdm.base.staticdata.party.EntityIdentifier.EntityIdentifierBuilder ei =
                        cdm.base.staticdata.party.EntityIdentifier.builder()
                                .setIdentifier(FieldWithMetaString.builder()
                                        .setValue(entityId.getTextContent().trim())
                                        .setMeta(MetaFields.builder().setScheme(scheme).build())
                                        .build())
                                .setIdentifierType(cdm.base.staticdata.party.EntityIdentifierTypeEnum.OTHER);
                le.addEntityIdentifier(ei.build());
            }
            String entityIdAttr = refEntity.getAttribute("id");
            String externalKey = (entityIdAttr != null && !entityIdAttr.isEmpty()) ? entityIdAttr : "referenceEntity";
            le.setMeta(MetaFields.builder().setExternalKey(externalKey).build());
            b.setReferenceEntity(le.build());
        }
        // simple referenceEntity without nesting (e.g. excludedReferenceEntity has entityName directly)
        String entityName = XmlUtils.childText(el, "entityName");
        if (entityName != null && refEntity == null) {
            b.setReferenceEntity(LegalEntity.builder()
                    .setName(FieldWithMetaString.builder().setValue(entityName).build())
                    .build());
        }

        for (Element ro : XmlUtils.children(el, "referenceObligation")) {
            ReferenceObligation.ReferenceObligationBuilder rob = ReferenceObligation.builder();
            Element bond = XmlUtils.child(ro, "bond");
            if (bond != null) {
                cdm.base.staticdata.asset.common.Security.SecurityBuilder sec =
                        cdm.base.staticdata.asset.common.Security.builder();
                Element instrId = XmlUtils.child(bond, "instrumentId");
                if (instrId != null) {
                    String scheme = instrId.getAttribute("instrumentIdScheme");
                    cdm.base.staticdata.asset.common.AssetIdTypeEnum idType =
                            cdm.base.staticdata.asset.common.AssetIdTypeEnum.OTHER;
                    if (scheme != null && scheme.contains("instrument-id-ISIN")) {
                        idType = cdm.base.staticdata.asset.common.AssetIdTypeEnum.ISIN;
                    } else if (scheme != null && scheme.contains("instrument-id-CUSIP")) {
                        idType = cdm.base.staticdata.asset.common.AssetIdTypeEnum.CUSIP;
                    }
                    sec.addIdentifier(cdm.base.staticdata.asset.common.AssetIdentifier.builder()
                            .setIdentifier(FieldWithMetaString.builder()
                                    .setValue(instrId.getTextContent().trim())
                                    .setMeta(MetaFields.builder().setScheme(scheme).build())
                                    .build())
                            .setIdentifierType(idType)
                            .build());
                }
                rob.setSecurity(sec.build());
            }
            Element por = XmlUtils.child(ro, "primaryObligorReference");
            if (por != null) {
                String href = por.getAttribute("href");
                if (href != null && !href.isEmpty()) {
                    rob.setPrimaryObligorReference(cdm.base.staticdata.party.metafields.ReferenceWithMetaLegalEntity.builder()
                            .setExternalReference(href)
                            .build());
                }
            }
            b.addReferenceObligation(rob.build());
        }

        String noRefObl = XmlUtils.childText(el, "noReferenceObligation");
        if ("true".equals(noRefObl)) b.setNoReferenceObligation(true);

        return b.build();
    }

    /** Build ProtectionTerms.creditEvents from FpML protectionTerms/creditEvents (and other ProtectionTerms fields). */
    private ProtectionTerms buildProtectionTerms(Element protTermsEl) {
        Element ceEl = XmlUtils.child(protTermsEl, "creditEvents");
        if (ceEl == null) return null; // skip empty protectionTerms
        CreditEvents ce = buildCreditEvents(ceEl);
        if (ce == null) return null;
        return ProtectionTerms.builder().setCreditEvents(ce).build();
    }

    private CreditEvents buildCreditEvents(Element el) {
        CreditEvents.CreditEventsBuilder b = CreditEvents.builder();
        // Boolean flags. Note: obligationDefault and governmentalIntervention are intentionally not
        // mapped — the reference CDM ingester drops these from the FpML inputs we target.
        setBoolIfPresent(el, "bankruptcy", b::setBankruptcy);
        setBoolIfPresent(el, "failureToPayPrincipal", b::setFailureToPayPrincipal);
        setBoolIfPresent(el, "failureToPayInterest", b::setFailureToPayInterest);
        setBoolIfPresent(el, "obligationAcceleration", b::setObligationAcceleration);
        setBoolIfPresent(el, "repudiationMoratorium", b::setRepudiationMoratorium);
        setBoolIfPresent(el, "distressedRatingsDowngrade", b::setDistressedRatingsDowngrade);
        setBoolIfPresent(el, "maturityExtension", b::setMaturityExtension);
        setBoolIfPresent(el, "writedown", b::setWritedown);
        setBoolIfPresent(el, "impliedWritedown", b::setImpliedWritedown);

        // failureToPay (complex)
        Element ftp = XmlUtils.child(el, "failureToPay");
        if (ftp != null) {
            FailureToPay.FailureToPayBuilder fb = FailureToPay.builder();
            String app = XmlUtils.childText(ftp, "applicable");
            if (app != null) fb.setApplicable(Boolean.parseBoolean(app));
            b.setFailureToPay(fb.build());
        }

        // restructuring (complex)
        Element rs = XmlUtils.child(el, "restructuring");
        if (rs != null) {
            Restructuring.RestructuringBuilder rb = Restructuring.builder();
            String app = XmlUtils.childText(rs, "applicable");
            if (app != null) rb.setApplicable(Boolean.parseBoolean(app));
            String rt = XmlUtils.childText(rs, "restructuringType");
            if (rt != null) {
                RestructuringEnum re = null;
                try { re = RestructuringEnum.fromDisplayName(rt); }
                catch (Exception ignored) {
                    try { re = RestructuringEnum.valueOf(rt); } catch (Exception ig2) {}
                }
                if (re != null) {
                    rb.setRestructuringType(FieldWithMetaRestructuringEnum.builder().setValue(re).build());
                }
            }
            String mho = XmlUtils.childText(rs, "multipleHolderObligation");
            if (mho != null) rb.setMultipleHolderObligation(Boolean.parseBoolean(mho));
            String mcen = XmlUtils.childText(rs, "multipleCreditEventNotices");
            if (mcen != null) rb.setMultipleCreditEventNotices(Boolean.parseBoolean(mcen));
            b.setRestructuring(rb.build());
        }
        return b.build();
    }

    /** Build SettlementTerms from FpML cashSettlementTerms (and similar) inside creditDefaultSwap. */
    private cdm.product.common.settlement.SettlementTerms buildSettlementTerms(Element cds) {
        Element cst = XmlUtils.child(cds, "cashSettlementTerms");
        Element pst = XmlUtils.child(cds, "physicalSettlementTerms");
        if (cst == null && pst == null) return null;
        cdm.product.common.settlement.SettlementTerms.SettlementTermsBuilder b =
                cdm.product.common.settlement.SettlementTerms.builder();
        if (cst != null) {
            b.setSettlementType(cdm.product.common.settlement.SettlementTypeEnum.CASH);
            cdm.product.common.settlement.CashSettlementTerms.CashSettlementTermsBuilder cb =
                    cdm.product.common.settlement.CashSettlementTerms.builder();
            String rf = XmlUtils.childText(cst, "recoveryFactor");
            if (rf != null) cb.setRecoveryFactor(new BigDecimal(rf));
            b.addCashSettlementTerms(cb.build());
        }
        if (pst != null) {
            b.setSettlementType(cdm.product.common.settlement.SettlementTypeEnum.PHYSICAL);
        }
        return b.build();
    }

    private static void setBoolIfPresent(Element el, String childName, java.util.function.Consumer<Boolean> setter) {
        String v = XmlUtils.childText(el, childName);
        if (v != null) setter.accept(Boolean.parseBoolean(v));
    }

    /** Build the fee leg as an InterestRatePayout (FixedRateSpecification with rate=price-1, quantity=quantity-1). */
    private Payout buildFeeLegPayout(Element feeLeg, CounterpartyRoleEnum buyerRole, CounterpartyRoleEnum sellerRole) {
        InterestRatePayout.InterestRatePayoutBuilder irp = InterestRatePayout.builder();

        // The fee leg: the protection buyer PAYS the periodic fixed amount to the seller.
        irp.setPayerReceiver(PayerReceiver.builder()
                .setPayer(buyerRole)
                .setReceiver(sellerRole)
                .build());

        // priceQuantity → quantity-1 address ref
        irp.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference("quantity-1").build())
                        .build())
                .build());

        // rateSpecification → FixedRateSpecification pointing at price-1
        Element periodicPayment = XmlUtils.child(feeLeg, "periodicPayment");
        Element fac = XmlUtils.child(periodicPayment, "fixedAmountCalculation");
        if (fac != null) {
            FixedRateSpecification fixed = FixedRateSpecification.builder()
                    .setRateSchedule(RateSchedule.builder()
                            .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                    .setReference(Reference.builder().setScope("DOCUMENT").setReference("price-1").build())
                                    .build())
                            .build())
                    .build();
            irp.setRateSpecification(RateSpecification.builder()
                    .setFixedRateSpecification(fixed)
                    .build());
        }

        // dayCountFraction from periodicPayment/fixedAmountCalculation/dayCountFraction (FpML 5.x location)
        if (fac != null) {
            String dcf = XmlUtils.childText(fac, "dayCountFraction");
            if (dcf != null) irp.setDayCountFraction(EnumMappers.dayCount(dcf));
        }

        // calculationPeriodFrequency/rollConvention from periodicPayment
        if (periodicPayment != null) {
            String roll = XmlUtils.childText(periodicPayment, "rollConvention");
            if (roll != null) {
                CalculationPeriodFrequency.CalculationPeriodFrequencyBuilder cpf =
                        CalculationPeriodFrequency.builder();
                RollConventionEnum rc = parseRollConvention(roll);
                if (rc != null) cpf.setRollConvention(rc);
                // Try to inherit period/periodMultiplier from paymentFrequency (the FpML convention)
                Element pf = XmlUtils.child(periodicPayment, "paymentFrequency");
                if (pf != null) {
                    String pm = XmlUtils.childText(pf, "periodMultiplier");
                    String p = XmlUtils.childText(pf, "period");
                    if (pm != null) cpf.setPeriodMultiplier(Integer.parseInt(pm));
                    if (p != null) cpf.setPeriod(EnumMappers.periodExtended(p));
                }
                irp.setCalculationPeriodDates(
                        cdm.product.common.schedule.CalculationPeriodDates.builder()
                                .setCalculationPeriodFrequency(cpf.build()).build());
            }
        }

        // paymentDates: paymentFrequency + firstPaymentDate (+ lastRegularPaymentDate)
        if (periodicPayment != null) {
            PaymentDates pd = buildFeePaymentDates(periodicPayment);
            if (pd != null) irp.setPaymentDates(pd);
        }

        return Payout.builder().setInterestRatePayout(irp.build()).build();
    }

    private PaymentDates buildFeePaymentDates(Element periodicPayment) {
        PaymentDates.PaymentDatesBuilder b = PaymentDates.builder();
        boolean any = false;
        Element pf = XmlUtils.child(periodicPayment, "paymentFrequency");
        if (pf != null) {
            String pm = XmlUtils.childText(pf, "periodMultiplier");
            String p = XmlUtils.childText(pf, "period");
            Frequency.FrequencyBuilder fb = Frequency.builder();
            if (pm != null) fb.setPeriodMultiplier(Integer.parseInt(pm));
            if (p != null) fb.setPeriod(EnumMappers.periodExtended(p));
            b.setPaymentFrequency(fb.build());
            any = true;
        }
        String fpd = XmlUtils.childText(periodicPayment, "firstPaymentDate");
        if (fpd != null) { b.setFirstPaymentDate(DateMapper.parse(fpd)); any = true; }
        String lpd = XmlUtils.childText(periodicPayment, "lastRegularPaymentDate");
        if (lpd != null) { b.setLastRegularPaymentDate(DateMapper.parse(lpd)); any = true; }
        return any ? b.build() : null;
    }

    private static RollConventionEnum parseRollConvention(String text) {
        try {
            int n = Integer.parseInt(text);
            return RollConventionEnum.valueOf("_" + n);
        } catch (NumberFormatException ignored) {}
        try { return RollConventionEnum.valueOf(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static UnitType ccyUnit(String ccy, String scheme) {
        FieldWithMetaString.FieldWithMetaStringBuilder ccyB = FieldWithMetaString.builder().setValue(ccy);
        if (scheme != null && !scheme.isEmpty()) {
            ccyB.setMeta(MetaFields.builder().setScheme(scheme).build());
        }
        return UnitType.builder().setCurrency(ccyB.build()).build();
    }

    private void assignRoles(String sellerHref, MappingContext ctx) {
        ctx.partyOrder.clear();
        for (String id : new ArrayList<>(ctx.parties.keySet())) {
            if (id.equals(sellerHref)) {
                ctx.partyOrder.put(id, 0); // PARTY_1 = seller
            } else {
                ctx.partyOrder.put(id, 1);
            }
        }
    }
}
