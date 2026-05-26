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

        // dateAdjustments: from generalTerms/dateAdjustments
        Element gtDateAdj = XmlUtils.child(generalTerms, "dateAdjustments");
        if (gtDateAdj != null) {
            cdm.base.datetime.BusinessDayAdjustments bda = DateMapper.businessDayAdjustments(gtDateAdj);
            if (bda != null) econ.setDateAdjustments(bda);
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
            ProtectionTerms pt = buildProtectionTerms(protTerms, buyerRole, sellerRole);
            if (pt != null) cdpBuilder.addProtectionTerms(pt);
        }

        // settlementTerms (from cashSettlementTerms or physicalSettlementTerms inside creditDefaultSwap)
        cdm.product.common.settlement.SettlementTerms st = buildSettlementTerms(cds);
        if (st != null) cdpBuilder.setSettlementTerms(st);

        // transactedPrice from feeLeg/marketFixedRate
        Element feeLegEarly = XmlUtils.child(cds, "feeLeg");
        String marketFixedRate = feeLegEarly == null ? null : XmlUtils.childText(feeLegEarly, "marketFixedRate");
        if (marketFixedRate != null) {
            cdpBuilder.setTransactedPrice(cdm.observable.asset.TransactedPrice.builder()
                    .setMarketFixedRate(new BigDecimal(marketFixedRate))
                    .build());
        }

        Payout payout = Payout.builder().setCreditDefaultPayout(cdpBuilder.build()).build();
        econ.addPayout(payout);

        // calculationAgent / ancillaryParty
        // - FpML <calculationAgentBusinessCenter> goes on economicTerms.calculationAgent.calculationAgentBusinessCenter
        // - FpML <calculationAgent><calculationAgentPartyReference href=".."/></calculationAgent> goes to
        //   ancillaryParty list with role=CalculationAgentIndependent and the calculationAgentParty enum.
        String agentBc = XmlUtils.childText(trade, "calculationAgentBusinessCenter");
        Element calcAgentEl = XmlUtils.child(trade, "calculationAgent");
        Element capRef = calcAgentEl == null ? null : XmlUtils.child(calcAgentEl, "calculationAgentPartyReference");
        cdm.observable.asset.CalculationAgent.CalculationAgentBuilder caBuilder = null;
        if (agentBc != null || capRef != null) {
            caBuilder = cdm.observable.asset.CalculationAgent.builder();
            if (agentBc != null) {
                try {
                    cdm.base.datetime.BusinessCenterEnum bce =
                            cdm.base.datetime.BusinessCenterEnum.valueOf(agentBc);
                    caBuilder.setCalculationAgentBusinessCenter(
                            cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum.builder()
                                    .setValue(bce).build());
                } catch (IllegalArgumentException ignored) {}
            }
            if (capRef != null) {
                caBuilder.setCalculationAgentParty(
                        cdm.base.staticdata.party.AncillaryRoleEnum.CALCULATION_AGENT_INDEPENDENT);
            }
            econ.setCalculationAgent(caBuilder.build());
        }

        // Fee leg → InterestRatePayout (payout[1]) - only when periodicPayment exists.
        // We defer adding it until we know whether dual-quantity mode is needed (so that the
        // priceQuantity reference is wired to quantity-1 vs quantity-2 correctly).
        Element feeLeg = XmlUtils.child(cds, "feeLeg");
        Element periodicPayment = feeLeg == null ? null : XmlUtils.child(feeLeg, "periodicPayment");
        // (added later, see below)

        // TradeLot structure:
        //   When feeLeg/periodicPayment/fixedAmountCalculation has its OWN calculationAmount,
        //   the reference splits into:
        //     [0] price + quantity (the fee-leg calculationAmount), addressed by price-1 + quantity-2
        //     [1] quantity only (the protectionTerms calculationAmount), addressed by quantity-1
        //   The CDP priceQuantity refs quantity-1 (protection notional); the fee-leg IRP refs quantity-2.
        //   When the feeLeg has NO calculationAmount of its own, the simpler split is used:
        //     [0] price only (price-1), [1] quantity only (quantity-1, from protectionTerms)
        Element calcAmount = XmlUtils.path(protTerms, "calculationAmount");
        Element feeLegCalcAmount = null;
        if (periodicPayment != null) {
            Element fac = XmlUtils.child(periodicPayment, "fixedAmountCalculation");
            if (fac != null) feeLegCalcAmount = XmlUtils.child(fac, "calculationAmount");
        }

        List<PriceQuantity> pqs = new ArrayList<>();

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

        boolean dualQuantity = feeLegCalcAmount != null;
        String feeQuantityKey = dualQuantity ? "quantity-2" : "quantity-1";

        // Now that we know dualQuantity, add the fee-leg payout with the correct quantity ref.
        if (periodicPayment != null) {
            Payout feePayout = buildFeeLegPayout(feeLeg, buyerRole, sellerRole, feeQuantityKey);
            if (feePayout != null) econ.addPayout(feePayout);
        }

        // [0] price (+ quantity in dualQuantity mode)
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
            PriceQuantity.PriceQuantityBuilder pqb = PriceQuantity.builder().addPrice(price);
            if (dualQuantity) {
                Element fccyEl = XmlUtils.child(feeLegCalcAmount, "currency");
                String fccy = fccyEl != null ? fccyEl.getTextContent().trim() : ccy;
                String fccyScheme = fccyEl != null ? fccyEl.getAttribute("currencyScheme") : ccyScheme;
                String famt = XmlUtils.childText(feeLegCalcAmount, "amount");
                UnitType fUnit = ccyUnit(fccy, fccyScheme);
                NonNegativeQuantitySchedule fqty = NonNegativeQuantitySchedule.builder()
                        .setValue(new BigDecimal(famt))
                        .setUnit(fUnit)
                        .build();
                pqb.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(fqty)
                        .setMeta(MetaFields.builder().addKey(
                                Key.builder().setScope("DOCUMENT").setKeyValue(feeQuantityKey).build()).build())
                        .build());
            }
            pqs.add(pqb.build());
        }

        // [1] quantity (from protectionTerms.calculationAmount)
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

        // Product (built after fee-leg payout has been added with correct quantity-N ref)
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());
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

        // AncillaryParty for calculationAgent (FpML <calculationAgent><calculationAgentPartyReference/>)
        if (capRef != null) {
            String href = capRef.getAttribute("href");
            if (href != null && !href.isEmpty()) {
                t.addAncillaryParty(cdm.base.staticdata.party.AncillaryParty.builder()
                        .setRole(cdm.base.staticdata.party.AncillaryRoleEnum.CALCULATION_AGENT_INDEPENDENT)
                        .addPartyReference(ReferenceWithMetaParty.builder()
                                .setExternalReference(href).build())
                        .build());
            }
        }

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
                cdm.base.staticdata.party.EntityIdentifierTypeEnum entityIdType =
                        cdm.base.staticdata.party.EntityIdentifierTypeEnum.OTHER;
                // FpML uses the explicit RED scheme path only on older versioned trades
                // (e.g. /spec/2003/entity-id-RED-1-0). The generic external scheme stays OTHER.
                if (scheme != null && scheme.contains("/spec/") && scheme.contains("entity-id-RED")) {
                    entityIdType = cdm.base.staticdata.party.EntityIdentifierTypeEnum.REDID;
                }
                cdm.base.staticdata.party.EntityIdentifier.EntityIdentifierBuilder ei =
                        cdm.base.staticdata.party.EntityIdentifier.builder()
                                .setIdentifier(FieldWithMetaString.builder()
                                        .setValue(entityId.getTextContent().trim())
                                        .setMeta(MetaFields.builder().setScheme(scheme).build())
                                        .build())
                                .setIdentifierType(entityIdType);
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
                    } else if (scheme != null && scheme.contains("instrument-id-RED")) {
                        idType = cdm.base.staticdata.asset.common.AssetIdTypeEnum.REDID;
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

        // referencePrice (cdm.observable.asset.Price: assetPrice with currency unit/perUnitOf)
        String refPrice = XmlUtils.childText(el, "referencePrice");
        if (refPrice != null) {
            // Find a currency from the enclosing CDS — use the protectionTerms/calculationAmount
            // currency on the top-level CDS. Default to USD if not found.
            // We look up via referenceInformation -> generalTerms -> creditDefaultSwap.
            String ccy = findReferencePriceCurrency(el);
            UnitType u = ccy != null ? ccyUnit(ccy, null) : null;
            cdm.observable.asset.Price.PriceBuilder pb = cdm.observable.asset.Price.builder()
                    .setValue(new BigDecimal(refPrice))
                    .setPriceType(PriceTypeEnum.ASSET_PRICE);
            if (u != null) pb.setUnit(u).setPerUnitOf(u);
            b.setReferencePrice(pb.build());
        }

        return b.build();
    }

    /** Find a currency context for the referencePrice — walk up to creditDefaultSwap, look at protectionTerms/calculationAmount/currency. */
    private static String findReferencePriceCurrency(Element refInfo) {
        org.w3c.dom.Node n = refInfo;
        while (n != null && !(n instanceof Element && "creditDefaultSwap".equals(((Element) n).getLocalName()))) {
            n = n.getParentNode();
        }
        if (!(n instanceof Element)) return null;
        Element cds = (Element) n;
        Element protTerms = XmlUtils.child(cds, "protectionTerms");
        if (protTerms != null) {
            Element calcAmount = XmlUtils.child(protTerms, "calculationAmount");
            if (calcAmount != null) {
                return XmlUtils.childText(calcAmount, "currency");
            }
        }
        Element feeLeg = XmlUtils.child(cds, "feeLeg");
        if (feeLeg != null) {
            Element pp = XmlUtils.child(feeLeg, "periodicPayment");
            if (pp != null) {
                Element fac = XmlUtils.child(pp, "fixedAmountCalculation");
                if (fac != null) {
                    Element ca = XmlUtils.child(fac, "calculationAmount");
                    if (ca != null) return XmlUtils.childText(ca, "currency");
                }
            }
        }
        return null;
    }

    /** Build ProtectionTerms.creditEvents from FpML protectionTerms/creditEvents (and other ProtectionTerms fields). */
    private ProtectionTerms buildProtectionTerms(Element protTermsEl,
                                                 CounterpartyRoleEnum buyerRole, CounterpartyRoleEnum sellerRole) {
        ProtectionTerms.ProtectionTermsBuilder ptb = ProtectionTerms.builder();
        boolean any = false;
        Element ceEl = XmlUtils.child(protTermsEl, "creditEvents");
        if (ceEl != null) {
            CreditEvents ce = buildCreditEvents(ceEl, buyerRole, sellerRole);
            if (ce != null) { ptb.setCreditEvents(ce); any = true; }
        }
        Element oblEl = XmlUtils.child(protTermsEl, "obligations");
        if (oblEl != null) {
            cdm.base.staticdata.asset.credit.Obligations obl = buildObligations(oblEl);
            if (obl != null) { ptb.setObligations(obl); any = true; }
        }
        return any ? ptb.build() : null;
    }

    private static cdm.base.staticdata.asset.credit.Obligations buildObligations(Element el) {
        cdm.base.staticdata.asset.credit.Obligations.ObligationsBuilder b =
                cdm.base.staticdata.asset.credit.Obligations.builder();
        boolean any = false;
        String cat = XmlUtils.childText(el, "category");
        if (cat != null) {
            cdm.base.staticdata.asset.credit.ObligationCategoryEnum oce = null;
            try { oce = cdm.base.staticdata.asset.credit.ObligationCategoryEnum.fromDisplayName(cat); }
            catch (Exception ignored) {
                try { oce = cdm.base.staticdata.asset.credit.ObligationCategoryEnum.valueOf(cat.toUpperCase()); }
                catch (Exception ignored2) {}
            }
            if (oce != null) { b.setCategory(oce); any = true; }
        }
        String ns = XmlUtils.childText(el, "notSubordinated");
        if (ns != null) { b.setNotSubordinated(Boolean.parseBoolean(ns)); any = true; }
        String nsl = XmlUtils.childText(el, "notSovereignLender");
        if (nsl != null) { b.setNotSovereignLender(Boolean.parseBoolean(nsl)); any = true; }
        String ndl = XmlUtils.childText(el, "notDomesticLaw");
        if (ndl != null) { b.setNotDomesticLaw(Boolean.parseBoolean(ndl)); any = true; }
        String listed = XmlUtils.childText(el, "listed");
        if (listed != null) { b.setListed(Boolean.parseBoolean(listed)); any = true; }
        String ndi = XmlUtils.childText(el, "notDomesticIssuance");
        if (ndi != null) { b.setNotDomesticIssuance(Boolean.parseBoolean(ndi)); any = true; }
        return any ? b.build() : null;
    }

    private CreditEvents buildCreditEvents(Element el, CounterpartyRoleEnum buyerRole, CounterpartyRoleEnum sellerRole) {
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
            cdm.observable.event.FailureToPay.FailureToPayBuilder fb = cdm.observable.event.FailureToPay.builder();
            String app = XmlUtils.childText(ftp, "applicable");
            if (app != null) fb.setApplicable(Boolean.parseBoolean(app));
            Element pr = XmlUtils.child(ftp, "paymentRequirement");
            if (pr != null) {
                cdm.observable.asset.Money money = buildMoney(pr);
                if (money != null) fb.setPaymentRequirement(money);
            }
            Element gpe = XmlUtils.child(ftp, "gracePeriodExtension");
            if (gpe != null) {
                cdm.observable.event.GracePeriodExtension.GracePeriodExtensionBuilder gpeb =
                        cdm.observable.event.GracePeriodExtension.builder();
                String gpApp = XmlUtils.childText(gpe, "applicable");
                if (gpApp != null) gpeb.setApplicable(Boolean.parseBoolean(gpApp));
                fb.setGracePeriodExtension(gpeb.build());
            }
            b.setFailureToPay(fb.build());
        }

        // defaultRequirement → Money
        Element dr = XmlUtils.child(el, "defaultRequirement");
        if (dr != null) {
            cdm.observable.asset.Money money = buildMoney(dr);
            if (money != null) b.setDefaultRequirement(money);
        }

        // creditEventNotice
        Element cen = XmlUtils.child(el, "creditEventNotice");
        if (cen != null) {
            cdm.observable.event.CreditEventNotice.CreditEventNoticeBuilder cenb =
                    cdm.observable.event.CreditEventNotice.builder();
            Element np = XmlUtils.child(cen, "notifyingParty");
            if (np != null) {
                // FpML 5.x: notifyingParty contains buyerPartyReference and/or sellerPartyReference.
                // Reference output is [buyer-counterparty-role, seller-counterparty-role].
                Element buyer = XmlUtils.child(np, "buyerPartyReference");
                Element seller = XmlUtils.child(np, "sellerPartyReference");
                if (buyer != null) cenb.addNotifyingParty(buyerRole);
                if (seller != null) cenb.addNotifyingParty(sellerRole);
            }
            Element pai = XmlUtils.child(cen, "publiclyAvailableInformation");
            if (pai != null) {
                cdm.observable.event.PubliclyAvailableInformation.PubliclyAvailableInformationBuilder paib =
                        cdm.observable.event.PubliclyAvailableInformation.builder();
                String sps = XmlUtils.childText(pai, "standardPublicSources");
                if (sps != null) paib.setStandardPublicSources(Boolean.parseBoolean(sps));
                String sn = XmlUtils.childText(pai, "specifiedNumber");
                if (sn != null) paib.setSpecifiedNumber(Integer.parseInt(sn));
                cenb.setPubliclyAvailableInformation(paib.build());
            }
            b.setCreditEventNotice(cenb.build());
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
        // settlementCurrency lives on one of the *settlementTerms wrappers in FpML
        Element ccyHost = cst != null ? cst : pst;
        Element ccyEl = ccyHost == null ? null : XmlUtils.child(ccyHost, "settlementCurrency");
        if (ccyEl != null) {
            String ccy = ccyEl.getTextContent().trim();
            String scheme = ccyEl.getAttribute("currencyScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder fb = FieldWithMetaString.builder().setValue(ccy);
            if (scheme != null && !scheme.isEmpty()) {
                fb.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            b.setSettlementCurrency(fb.build());
        }
        if (cst != null) {
            b.setSettlementType(cdm.product.common.settlement.SettlementTypeEnum.CASH);
            cdm.product.common.settlement.CashSettlementTerms.CashSettlementTermsBuilder cb =
                    cdm.product.common.settlement.CashSettlementTerms.builder();
            String rf = XmlUtils.childText(cst, "recoveryFactor");
            boolean any = false;
            if (rf != null) { cb.setRecoveryFactor(new BigDecimal(rf)); any = true; }
            // Only emit a cashSettlementTerms entry when it has content; otherwise leave the list empty.
            if (any) b.addCashSettlementTerms(cb.build());
        }
        if (pst != null) {
            b.setSettlementType(cdm.product.common.settlement.SettlementTypeEnum.PHYSICAL);
            // physicalSettlementPeriod + deliverableObligations
            cdm.product.common.settlement.PhysicalSettlementTerms physTerms = buildPhysicalSettlementTerms(pst);
            if (physTerms != null) b.setPhysicalSettlementTerms(physTerms);
        }
        return b.build();
    }

    private static cdm.product.common.settlement.PhysicalSettlementTerms buildPhysicalSettlementTerms(Element pst) {
        cdm.product.common.settlement.PhysicalSettlementTerms.PhysicalSettlementTermsBuilder b =
                cdm.product.common.settlement.PhysicalSettlementTerms.builder();
        boolean any = false;
        Element period = XmlUtils.child(pst, "physicalSettlementPeriod");
        if (period != null) {
            String bd = XmlUtils.childText(period, "businessDays");
            if (bd != null) {
                b.setPhysicalSettlementPeriod(cdm.product.common.settlement.PhysicalSettlementPeriod.builder()
                        .setBusinessDays(Integer.parseInt(bd)).build());
                any = true;
            }
        }
        Element dlv = XmlUtils.child(pst, "deliverableObligations");
        if (dlv != null) {
            cdm.product.common.settlement.DeliverableObligations dobl = buildDeliverableObligations(dlv);
            if (dobl != null) { b.setDeliverableObligations(dobl); any = true; }
        }
        String esc = XmlUtils.childText(pst, "escrow");
        if (esc != null) { b.setEscrow(Boolean.parseBoolean(esc)); any = true; }
        return any ? b.build() : null;
    }

    private static cdm.product.common.settlement.DeliverableObligations buildDeliverableObligations(Element el) {
        cdm.product.common.settlement.DeliverableObligations.DeliverableObligationsBuilder b =
                cdm.product.common.settlement.DeliverableObligations.builder();
        boolean any = false;
        String ai = XmlUtils.childText(el, "accruedInterest");
        if (ai != null) { b.setAccruedInterest(Boolean.parseBoolean(ai)); any = true; }
        String cat = XmlUtils.childText(el, "category");
        if (cat != null) {
            cdm.base.staticdata.asset.credit.ObligationCategoryEnum dce = null;
            try { dce = cdm.base.staticdata.asset.credit.ObligationCategoryEnum.fromDisplayName(cat); }
            catch (Exception ignored) {
                try { dce = cdm.base.staticdata.asset.credit.ObligationCategoryEnum.valueOf(cat.toUpperCase()); }
                catch (Exception ignored2) {}
            }
            if (dce != null) { b.setCategory(dce); any = true; }
        }
        // Boolean leaf fields commonly present in FpML deliverableObligations:
        String ns = XmlUtils.childText(el, "notSubordinated");
        if (ns != null) { b.setNotSubordinated(Boolean.parseBoolean(ns)); any = true; }
        String nc = XmlUtils.childText(el, "notContingent");
        if (nc != null) { b.setNotContingent(Boolean.parseBoolean(nc)); any = true; }
        String tr = XmlUtils.childText(el, "transferable");
        if (tr != null) { b.setTransferable(Boolean.parseBoolean(tr)); any = true; }
        String nb = XmlUtils.childText(el, "notBearer");
        if (nb != null) { b.setNotBearer(Boolean.parseBoolean(nb)); any = true; }

        // specifiedCurrency (FpML: applicable + currency list); we emit just applicable.
        Element scEl = XmlUtils.child(el, "specifiedCurrency");
        if (scEl != null) {
            String app = XmlUtils.childText(scEl, "applicable");
            if (app != null) {
                b.setSpecifiedCurrency(cdm.base.staticdata.asset.credit.SpecifiedCurrency.builder()
                        .setApplicable(Boolean.parseBoolean(app)).build());
                any = true;
            }
        }
        // assignableLoan / consentRequiredLoan
        Element alEl = XmlUtils.child(el, "assignableLoan");
        if (alEl != null) {
            String app = XmlUtils.childText(alEl, "applicable");
            if (app != null) {
                b.setAssignableLoan(cdm.product.common.settlement.PCDeliverableObligationCharac.builder()
                        .setApplicable(Boolean.parseBoolean(app)).build());
                any = true;
            }
        }
        Element crlEl = XmlUtils.child(el, "consentRequiredLoan");
        if (crlEl != null) {
            String app = XmlUtils.childText(crlEl, "applicable");
            if (app != null) {
                b.setConsentRequiredLoan(cdm.product.common.settlement.PCDeliverableObligationCharac.builder()
                        .setApplicable(Boolean.parseBoolean(app)).build());
                any = true;
            }
        }
        // maximumMaturity (Period)
        Element mmEl = XmlUtils.child(el, "maximumMaturity");
        if (mmEl != null) {
            String pm = XmlUtils.childText(mmEl, "periodMultiplier");
            String p = XmlUtils.childText(mmEl, "period");
            if (pm != null && p != null) {
                cdm.base.datetime.Period.PeriodBuilder pb = cdm.base.datetime.Period.builder()
                        .setPeriodMultiplier(Integer.parseInt(pm));
                try { pb.setPeriod(EnumMappers.period(p)); } catch (Exception ignored) {}
                b.setMaximumMaturity(pb.build());
                any = true;
            }
        }
        return any ? b.build() : null;
    }

    private static void setBoolIfPresent(Element el, String childName, java.util.function.Consumer<Boolean> setter) {
        String v = XmlUtils.childText(el, childName);
        if (v != null) setter.accept(Boolean.parseBoolean(v));
    }

    /** Build cdm.observable.asset.Money from an FpML <currency>+<amount> sub-element pair. */
    private static cdm.observable.asset.Money buildMoney(Element el) {
        Element ccyEl = XmlUtils.child(el, "currency");
        String amt = XmlUtils.childText(el, "amount");
        if (ccyEl == null || amt == null) return null;
        String ccy = ccyEl.getTextContent().trim();
        String scheme = ccyEl.getAttribute("currencyScheme");
        UnitType u = ccyUnit(ccy, (scheme != null && !scheme.isEmpty()) ? scheme : null);
        return cdm.observable.asset.Money.builder()
                .setValue(new BigDecimal(amt))
                .setUnit(u)
                .build();
    }

    /** Build the fee leg as an InterestRatePayout (FixedRateSpecification with rate=price-1, quantity={quantityKey}). */
    private Payout buildFeeLegPayout(Element feeLeg, CounterpartyRoleEnum buyerRole,
                                     CounterpartyRoleEnum sellerRole, String quantityKey) {
        InterestRatePayout.InterestRatePayoutBuilder irp = InterestRatePayout.builder();

        // The fee leg: the protection buyer PAYS the periodic fixed amount to the seller.
        irp.setPayerReceiver(PayerReceiver.builder()
                .setPayer(buyerRole)
                .setReceiver(sellerRole)
                .build());

        // priceQuantity → quantity-N address ref (quantity-1 in single-quantity mode, quantity-2 in dual)
        irp.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(Reference.builder().setScope("DOCUMENT").setReference(quantityKey).build())
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
            String firstPeriodStartDate = XmlUtils.childText(periodicPayment, "firstPeriodStartDate");
            if (roll != null || firstPeriodStartDate != null) {
                cdm.product.common.schedule.CalculationPeriodDates.CalculationPeriodDatesBuilder cpdb =
                        cdm.product.common.schedule.CalculationPeriodDates.builder();
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
                    cpdb.setCalculationPeriodFrequency(cpf.build());
                }
                if (firstPeriodStartDate != null) {
                    cpdb.setFirstPeriodStartDate(cdm.base.datetime.AdjustableOrRelativeDate.builder()
                            .setAdjustableDate(cdm.base.datetime.AdjustableDate.builder()
                                    .setUnadjustedDate(DateMapper.parse(firstPeriodStartDate))
                                    .build())
                            .build());
                }
                irp.setCalculationPeriodDates(cpdb.build());
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
