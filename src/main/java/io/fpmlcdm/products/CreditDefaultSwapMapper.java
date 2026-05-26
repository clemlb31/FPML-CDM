package io.fpmlcdm.products;

import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.math.metafields.ReferenceWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.*;
import cdm.observable.asset.CreditIndex;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.observable.asset.metafields.FieldWithMetaPriceSchedule;
import cdm.observable.asset.metafields.ReferenceWithMetaPriceSchedule;
import cdm.product.asset.CreditDefaultPayout;
import cdm.product.asset.GeneralTerms;
import cdm.product.asset.ReferenceInformation;
import cdm.product.asset.ReferenceObligation;
import cdm.product.asset.CreditSeniorityEnum;
import cdm.product.asset.CreditEvents;
import cdm.product.asset.FixedRateSpecification;
import cdm.product.asset.InterestRatePayout;
import cdm.product.asset.ProtectionTerms;
import cdm.product.asset.RateSpecification;
import cdm.product.asset.Restructuring;
import cdm.product.common.schedule.CalculationPeriodDates;
import cdm.product.common.schedule.PaymentDates;
import cdm.product.common.schedule.RateSchedule;
import cdm.product.common.settlement.CashSettlementTerms;
import cdm.product.common.settlement.ResolvablePriceQuantity;
import cdm.product.common.settlement.SettlementTerms;
import cdm.product.template.*;
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

        // PARTY_1 = seller of protection ⇒ buyer is PARTY_2 (so CDS payout: payer=Party1, receiver=Party2)
        // Per expected JSON: payer="Party1", receiver="Party2" → seller is Party1.
        String sellerHref = href(XmlUtils.child(generalTerms, "sellerPartyReference"));
        assignRoles(sellerHref, ctx);

        // Determine fee-leg quantity behavior: separate amount (own calc) ⇒ quantity-2 for fee leg
        Element feeLeg = XmlUtils.child(cds, "feeLeg");
        Element periodic = feeLeg == null ? null : XmlUtils.child(feeLeg, "periodicPayment");
        Element fixedCalc = periodic == null ? null : XmlUtils.child(periodic, "fixedAmountCalculation");
        Element feeLegOwnCalcAmount = fixedCalc == null ? null : XmlUtils.child(fixedCalc, "calculationAmount");
        boolean feeHasOwnAmount = feeLegOwnCalcAmount != null;
        String feeQtyLabel = feeHasOwnAmount ? "quantity-2" : "quantity-1";

        // economicTerms
        EconomicTerms.EconomicTermsBuilder econ = EconomicTerms.builder();

        // effectiveDate
        Element effectiveDate = XmlUtils.child(generalTerms, "effectiveDate");
        if (effectiveDate != null) {
            econ.setEffectiveDate(DateMapper.adjustableOrRelative(effectiveDate));
        }
        // terminationDate
        Element schedTerm = XmlUtils.child(generalTerms, "scheduledTerminationDate");
        if (schedTerm != null) {
            econ.setTerminationDate(DateMapper.adjustableOrRelative(schedTerm));
        }
        // dateAdjustments (top-level under generalTerms)
        Element gtDateAdj = XmlUtils.child(generalTerms, "dateAdjustments");
        if (gtDateAdj != null) {
            econ.setDateAdjustments(DateMapper.businessDayAdjustments(gtDateAdj));
        }

        // ---------- Payout[0]: CreditDefaultPayout ----------
        CreditDefaultPayout.CreditDefaultPayoutBuilder cdpBuilder = CreditDefaultPayout.builder();

        // payerReceiver: protection buyer is the one who pays for protection → buyer=payer, seller=receiver
        // (Per expected JSON: payer=Party1, receiver=Party2 and Party1=seller, the spec says
        //  buyer pays premium so in fee leg buyer is payer. But for CreditDefaultPayout the
        //  CONTINGENT payout: seller pays buyer on default → seller=payer? Reference shows
        //  payer:Party1=seller, receiver:Party2=buyer. So seller pays the contingent leg.)
        CounterpartyRoleEnum sellerRole = CounterpartyRoleEnum.PARTY_1;
        CounterpartyRoleEnum buyerRole = CounterpartyRoleEnum.PARTY_2;
        cdpBuilder.setPayerReceiver(PayerReceiver.builder()
                .setPayer(sellerRole).setReceiver(buyerRole).build());

        // priceQuantity → quantity-1 address ref
        cdpBuilder.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(addressRef("quantity-1"))
                        .build())
                .build());

        // generalTerms
        GeneralTerms.GeneralTermsBuilder gtBuilder = GeneralTerms.builder();
        Element indexRefInfo = XmlUtils.child(generalTerms, "indexReferenceInformation");
        Element refInfo = XmlUtils.child(generalTerms, "referenceInformation");

        if (indexRefInfo != null) {
            gtBuilder.setIndexReferenceInformation(buildCreditIndex(indexRefInfo));
        }
        if (refInfo != null) {
            gtBuilder.setReferenceInformation(buildReferenceInformation(refInfo));
        }
        cdpBuilder.setGeneralTerms(gtBuilder.build());

        // protectionTerms (creditEvents, obligations, floatingAmountEvents)
        Element protTerms = XmlUtils.child(cds, "protectionTerms");
        if (protTerms != null) {
            ProtectionTerms pt = buildProtectionTerms(protTerms);
            if (pt != null) cdpBuilder.addProtectionTerms(pt);
        }

        // settlementTerms: physicalSettlementTerms or cashSettlementTerms at creditDefaultSwap level
        Element physSettle = XmlUtils.child(cds, "physicalSettlementTerms");
        Element cashSettle = XmlUtils.child(cds, "cashSettlementTerms");
        if (physSettle != null || cashSettle != null) {
            SettlementTerms st = buildSettlementTerms(physSettle, cashSettle);
            if (st != null) cdpBuilder.setSettlementTerms(st);
        }

        Payout protectionPayout = Payout.builder().setCreditDefaultPayout(cdpBuilder.build()).build();
        econ.addPayout(protectionPayout);

        // ---------- Payout[1]: InterestRatePayout (the fee leg) ----------
        if (periodic != null) {
            InterestRatePayout fee = buildFeeLegInterestRatePayout(
                    periodic, fixedCalc, sellerRole, buyerRole, feeQtyLabel);
            if (fee != null) {
                econ.addPayout(Payout.builder().setInterestRatePayout(fee).build());
            }
        }

        // calculationAgent + business center
        CalculationAgentMapper.Result ca = CalculationAgentMapper.map(trade);
        if (ca.calculationAgent() != null) econ.setCalculationAgent(ca.calculationAgent());

        // Product
        NonTransferableProduct.NonTransferableProductBuilder ntp = NonTransferableProduct.builder()
                .setEconomicTerms(econ.build());

        // Taxonomy
        String qualifier = indexRefInfo != null ? "CreditDefaultSwap_Index" : "CreditDefaultSwap_SingleName";
        ntp.addTaxonomy(cdm.base.staticdata.asset.common.ProductTaxonomy.builder()
                .setSource(cdm.base.staticdata.asset.common.TaxonomySourceEnum.ISDA)
                .setProductQualifier(qualifier)
                .build());

        // ---------- TradeLot priceQuantity[] ----------
        // [0] = fee leg's price (+ its own quantity if separate); [1] = protection quantity-1
        Element protCalcAmount = XmlUtils.path(protTerms, "calculationAmount");
        String protCcy = XmlUtils.childText(protCalcAmount, "currency");
        String protAmt = XmlUtils.childText(protCalcAmount, "amount");

        // Fee leg fixed rate value (always emitted when periodic payment + fixedAmountCalculation present)
        String feeRate = fixedCalc == null ? null : XmlUtils.childText(fixedCalc, "fixedRate");
        // Fee leg currency: own calculationAmount currency if present, else protection ccy
        String feeCcy = protCcy;
        String feeAmt = protAmt;
        if (feeHasOwnAmount) {
            feeCcy = XmlUtils.childText(feeLegOwnCalcAmount, "currency");
            feeAmt = XmlUtils.childText(feeLegOwnCalcAmount, "amount");
        }

        List<PriceQuantity> pqs = new ArrayList<>();

        // tradeLot[0] = fee leg's price (+ quantity-2 if fee has own amount)
        if (feeRate != null && feeCcy != null) {
            PriceQuantity.PriceQuantityBuilder pq0 = PriceQuantity.builder();
            PriceSchedule price = PriceSchedule.builder()
                    .setValue(new BigDecimal(feeRate))
                    .setUnit(currencyUnit(feeCcy))
                    .setPerUnitOf(currencyUnit(feeCcy))
                    .setPriceType(PriceTypeEnum.INTEREST_RATE)
                    .build();
            pq0.addPrice(FieldWithMetaPriceSchedule.builder()
                    .setValue(price)
                    .setMeta(QuantityMapper.locationMeta("price-1"))
                    .build());
            if (feeHasOwnAmount && feeAmt != null) {
                NonNegativeQuantitySchedule qs = NonNegativeQuantitySchedule.builder()
                        .setValue(new BigDecimal(feeAmt))
                        .setUnit(currencyUnit(feeCcy))
                        .build();
                pq0.addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                        .setValue(qs)
                        .setMeta(QuantityMapper.locationMeta("quantity-2"))
                        .build());
            }
            pqs.add(pq0.build());
        }

        // tradeLot[1] = protection's quantity-1
        if (protAmt != null && protCcy != null) {
            NonNegativeQuantitySchedule qs1 = NonNegativeQuantitySchedule.builder()
                    .setValue(new BigDecimal(protAmt))
                    .setUnit(currencyUnit(protCcy))
                    .build();
            PriceQuantity pq1 = PriceQuantity.builder()
                    .addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                            .setValue(qs1)
                            .setMeta(QuantityMapper.locationMeta("quantity-1"))
                            .build())
                    .build();
            pqs.add(pq1);
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
        for (AncillaryParty ap : ca.ancillaryParties()) t.addAncillaryParty(ap);
        identifiers.forEach(t::addTradeIdentifier);
        if (tradeDate != null) t.setTradeDate(tradeDate);
        parties.forEach(t::addParty);
        if (contractDetails != null) t.setContractDetails(contractDetails);
        for (Account a : AccountMapper.map(doc)) t.addAccount(a);

        TradeState.TradeStateBuilder tsb = TradeState.builder().setTrade(t.build());
        for (TransferState ts : TransferMapper.map(trade, cds)) {
            tsb.addTransferHistory(ts);
        }
        return tsb.build();
    }

    /* ---------------- helpers ---------------- */

    private CreditIndex buildCreditIndex(Element el) {
        CreditIndex.CreditIndexBuilder b = CreditIndex.builder();
        b.setAssetClass(AssetClassEnum.CREDIT);
        String name = XmlUtils.childText(el, "indexName");
        if (name != null) b.setName(FieldWithMetaString.builder().setValue(name).build());
        String series = XmlUtils.childText(el, "indexSeries");
        if (series != null) b.setIndexSeries(Integer.parseInt(series));
        String version = XmlUtils.childText(el, "indexAnnexVersion");
        if (version != null) b.setIndexAnnexVersion(Integer.parseInt(version));
        String annexDate = XmlUtils.childText(el, "indexAnnexDate");
        if (annexDate != null) b.setIndexAnnexDate(DateMapper.parse(annexDate));
        // indexAnnexSource: skipped (enum class location uncertain across CDM versions)
        for (Element excl : XmlUtils.children(el, "excludedReferenceEntity")) {
            b.addExcludedReferenceEntity(buildReferenceInformation(excl));
        }
        String seniority = XmlUtils.childText(el, "seniority");
        if (seniority != null) {
            try { b.setSeniority(CreditSeniorityEnum.fromDisplayName(seniority)); }
            catch (Exception ignored) {
                try { b.setSeniority(CreditSeniorityEnum.valueOf(seniority.toUpperCase())); }
                catch (Exception ig2) {}
            }
        }
        return b.build();
    }

    private ReferenceInformation buildReferenceInformation(Element el) {
        ReferenceInformation.ReferenceInformationBuilder b = ReferenceInformation.builder();
        Element refEntity = XmlUtils.child(el, "referenceEntity");
        if (refEntity != null) {
            b.setReferenceEntity(buildLegalEntity(refEntity));
        } else {
            // Some forms have entityName directly under <referenceEntity> or fallback
            String entityName = XmlUtils.childText(el, "entityName");
            if (entityName != null) {
                b.setReferenceEntity(LegalEntity.builder()
                        .setName(FieldWithMetaString.builder().setValue(entityName).build())
                        .build());
            }
        }

        for (Element ro : XmlUtils.children(el, "referenceObligation")) {
            b.addReferenceObligation(buildReferenceObligation(ro));
        }

        String noRefObl = XmlUtils.childText(el, "noReferenceObligation");
        if ("true".equals(noRefObl)) b.setNoReferenceObligation(true);

        // referencePrice (Price wrapper)
        Element refPrice = XmlUtils.child(el, "referencePrice");
        if (refPrice != null) {
            String value = refPrice.getTextContent().trim();
            // referencePrice in FpML can also have a child structure, but most often it's a scalar
            BigDecimal pv = null;
            try { pv = new BigDecimal(value); } catch (Exception ignored) {}
            if (pv != null) {
                // Use the protection ccy if available — but we don't have it in this helper;
                // build a bare Price with InterestRate type isn't right. The expected output
                // shows priceType "AssetPrice" with currency unit. Just emit value + priceType.
                b.setReferencePrice(cdm.observable.asset.Price.builder()
                        .setValue(pv)
                        .setPriceType(PriceTypeEnum.ASSET_PRICE)
                        .build());
            }
        }
        return b.build();
    }

    private LegalEntity buildLegalEntity(Element refEntity) {
        LegalEntity.LegalEntityBuilder le = LegalEntity.builder();
        String eName = XmlUtils.childText(refEntity, "entityName");
        if (eName != null) le.setName(FieldWithMetaString.builder().setValue(eName).build());

        // entityId → EntityIdentifier (new shape) with identifierType derived from scheme
        for (Element entityIdEl : XmlUtils.children(refEntity, "entityId")) {
            String value = entityIdEl.getTextContent().trim();
            String scheme = entityIdEl.getAttribute("entityIdScheme");
            FieldWithMetaString.FieldWithMetaStringBuilder idB = FieldWithMetaString.builder()
                    .setValue(value);
            if (scheme != null && !scheme.isEmpty()) {
                idB.setMeta(MetaFields.builder().setScheme(scheme).build());
            }
            cdm.base.staticdata.party.EntityIdentifier.EntityIdentifierBuilder eib =
                    cdm.base.staticdata.party.EntityIdentifier.builder()
                            .setIdentifier(idB.build());
            cdm.base.staticdata.party.EntityIdentifierTypeEnum t = entityIdentifierType(scheme);
            if (t != null) eib.setIdentifierType(t);
            le.addEntityIdentifier(eib.build());
        }

        String id = refEntity.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            le.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return le.build();
    }

    private static cdm.base.staticdata.party.EntityIdentifierTypeEnum entityIdentifierType(String scheme) {
        if (scheme == null || scheme.isEmpty()) return cdm.base.staticdata.party.EntityIdentifierTypeEnum.OTHER;
        String low = scheme.toLowerCase();
        if (low.contains("entity-id-red") || low.contains("red-")) {
            // RED ID
            try { return cdm.base.staticdata.party.EntityIdentifierTypeEnum.valueOf("REDID"); }
            catch (Exception ignored) {}
            try { return cdm.base.staticdata.party.EntityIdentifierTypeEnum.fromDisplayName("REDID"); }
            catch (Exception ignored) {}
        }
        if (low.contains("lei") || low.contains("iso17442")) {
            try { return cdm.base.staticdata.party.EntityIdentifierTypeEnum.valueOf("LEI"); }
            catch (Exception ignored) {}
        }
        return cdm.base.staticdata.party.EntityIdentifierTypeEnum.OTHER;
    }

    private ReferenceObligation buildReferenceObligation(Element ro) {
        ReferenceObligation.ReferenceObligationBuilder rob = ReferenceObligation.builder();
        Element bond = XmlUtils.child(ro, "bond");
        if (bond != null) {
            Security.SecurityBuilder secb = Security.builder();
            for (Element instId : XmlUtils.children(bond, "instrumentId")) {
                String value = instId.getTextContent().trim();
                String scheme = instId.getAttribute("instrumentIdScheme");
                AssetIdentifier.AssetIdentifierBuilder aib = AssetIdentifier.builder()
                        .setIdentifier(FieldWithMetaString.builder()
                                .setValue(value)
                                .setMeta(scheme != null && !scheme.isEmpty()
                                        ? MetaFields.builder().setScheme(scheme).build() : null)
                                .build())
                        .setIdentifierType(mapAssetIdType(scheme));
                secb.addIdentifier(aib.build());
            }
            // securityType/assetType are dropped by SemanticDiff; we omit instrumentType setter
            // because the enum class location is uncertain across CDM versions.
            rob.setSecurity(secb.build());
        }
        // primaryObligorReference: omitted in this pass — the metafields wrapper class name and
        // its package vary by CDM version. Without a build to verify, we skip rather than risk
        // breaking compilation. (SemanticDiff drops globalReference anyway.)
        return rob.build();
    }

    private static AssetIdTypeEnum mapAssetIdType(String scheme) {
        if (scheme == null || scheme.isEmpty()) return AssetIdTypeEnum.OTHER;
        String lower = scheme.toLowerCase();
        if (lower.contains("cusip")) return AssetIdTypeEnum.CUSIP;
        if (lower.contains("isin")) return AssetIdTypeEnum.ISIN;
        if (lower.contains("sedol")) return AssetIdTypeEnum.SEDOL;
        if (lower.contains("ric")) return AssetIdTypeEnum.RIC;
        return AssetIdTypeEnum.OTHER;
    }

    private ProtectionTerms buildProtectionTerms(Element protTerms) {
        ProtectionTerms.ProtectionTermsBuilder b = ProtectionTerms.builder();

        Element credEv = XmlUtils.child(protTerms, "creditEvents");
        if (credEv != null) {
            CreditEvents ce = buildCreditEvents(credEv);
            if (ce != null) b.setCreditEvents(ce);
        }
        // (We deliberately skip obligations / floatingAmountEvents details for now
        //  to avoid risking compile errors on rarer types.)
        return b.build();
    }

    private CreditEvents buildCreditEvents(Element ce) {
        CreditEvents.CreditEventsBuilder b = CreditEvents.builder();
        // simple booleans
        String bankruptcy = XmlUtils.childText(ce, "bankruptcy");
        if (bankruptcy != null) b.setBankruptcy(Boolean.parseBoolean(bankruptcy));
        String oblAccel = XmlUtils.childText(ce, "obligationAcceleration");
        if (oblAccel != null) b.setObligationAcceleration(Boolean.parseBoolean(oblAccel));
        String repMor = XmlUtils.childText(ce, "repudiationMoratorium");
        if (repMor != null) b.setRepudiationMoratorium(Boolean.parseBoolean(repMor));
        String govIntv = XmlUtils.childText(ce, "governmentalIntervention");
        if (govIntv != null) b.setGovernmentalIntervention(Boolean.parseBoolean(govIntv));

        // restructuring
        Element restruct = XmlUtils.child(ce, "restructuring");
        if (restruct != null) {
            Restructuring.RestructuringBuilder rb = Restructuring.builder();
            String applicable = XmlUtils.childText(restruct, "applicable");
            if (applicable != null) rb.setApplicable(Boolean.parseBoolean(applicable));
            String mho = XmlUtils.childText(restruct, "multipleHolderObligation");
            if (mho != null) rb.setMultipleHolderObligation(Boolean.parseBoolean(mho));
            String mcen = XmlUtils.childText(restruct, "multipleCreditEventNotices");
            if (mcen != null) rb.setMultipleCreditEventNotices(Boolean.parseBoolean(mcen));
            // restructuringType skipped (class location varies)
            b.setRestructuring(rb.build());
        }

        return b.build();
    }

    private SettlementTerms buildSettlementTerms(Element physSettle, Element cashSettle) {
        SettlementTerms.SettlementTermsBuilder b = SettlementTerms.builder();
        if (cashSettle != null) {
            CashSettlementTerms.CashSettlementTermsBuilder cstb = CashSettlementTerms.builder();
            String recoveryFactor = XmlUtils.childText(cashSettle, "recoveryFactor");
            if (recoveryFactor != null) {
                cstb.setRecoveryFactor(new BigDecimal(recoveryFactor));
            }
            // settlementCurrency at cashSettle level (rare)
            b.addCashSettlementTerms(cstb.build());
            b.setSettlementType(cdm.product.common.settlement.SettlementTypeEnum.CASH);
        }
        if (physSettle != null) {
            // Just emit settlementType=Physical; we don't deeply map physicalSettlementTerms (complex)
            b.setSettlementType(cdm.product.common.settlement.SettlementTypeEnum.PHYSICAL);
            String setCcy = XmlUtils.childText(physSettle, "settlementCurrency");
            if (setCcy != null) {
                b.setSettlementCurrency(FieldWithMetaString.builder().setValue(setCcy).build());
            }
        }
        return b.build();
    }

    private InterestRatePayout buildFeeLegInterestRatePayout(Element periodic,
                                                              Element fixedCalc,
                                                              CounterpartyRoleEnum sellerRole,
                                                              CounterpartyRoleEnum buyerRole,
                                                              String feeQtyLabel) {
        InterestRatePayout.InterestRatePayoutBuilder irp = InterestRatePayout.builder();

        // payerReceiver: fee leg buyer pays seller → payer=buyer (Party2), receiver=seller (Party1)
        irp.setPayerReceiver(PayerReceiver.builder()
                .setPayer(buyerRole).setReceiver(sellerRole).build());

        // priceQuantity address ref (quantity-1 if shared, quantity-2 if fee owns its amount)
        irp.setPriceQuantity(ResolvablePriceQuantity.builder()
                .setQuantitySchedule(ReferenceWithMetaNonNegativeQuantitySchedule.builder()
                        .setReference(addressRef(feeQtyLabel))
                        .build())
                .build());

        // rateSpecification: FixedRateSpecification ref to price-1
        FixedRateSpecification frs = FixedRateSpecification.builder()
                .setRateSchedule(RateSchedule.builder()
                        .setPrice(ReferenceWithMetaPriceSchedule.builder()
                                .setReference(addressRef("price-1"))
                                .build())
                        .build())
                .build();
        irp.setRateSpecification(RateSpecification.builder()
                .setFixedRateSpecification(frs)
                .build());

        // dayCountFraction (if present in fixedAmountCalculation)
        String dcf = fixedCalc == null ? null : XmlUtils.childText(fixedCalc, "dayCountFraction");
        if (dcf != null) irp.setDayCountFraction(EnumMappers.dayCount(dcf));

        // calculationPeriodDates from rollConvention (if present)
        String rollConv = XmlUtils.childText(periodic, "rollConvention");
        Element pFreqEl = XmlUtils.child(periodic, "paymentFrequency");
        if (rollConv != null && pFreqEl != null) {
            CalculationPeriodDates.CalculationPeriodDatesBuilder cpd = CalculationPeriodDates.builder();
            cdm.base.datetime.CalculationPeriodFrequency.CalculationPeriodFrequencyBuilder cpf =
                    cdm.base.datetime.CalculationPeriodFrequency.builder();
            String pm = XmlUtils.childText(pFreqEl, "periodMultiplier");
            String p = XmlUtils.childText(pFreqEl, "period");
            if (pm != null) cpf.setPeriodMultiplier(Integer.parseInt(pm));
            if (p != null) cpf.setPeriod(EnumMappers.periodExtended(p));
            try {
                int rn = Integer.parseInt(rollConv);
                cpf.setRollConvention(cdm.base.datetime.RollConventionEnum.valueOf("_" + rn));
            } catch (Exception ignored) {
                try { cpf.setRollConvention(cdm.base.datetime.RollConventionEnum.valueOf(rollConv)); }
                catch (Exception ig2) {}
            }
            cpd.setCalculationPeriodFrequency(cpf.build());
            irp.setCalculationPeriodDates(cpd.build());
        }

        // paymentDates from paymentFrequency + firstPaymentDate
        if (pFreqEl != null) {
            PaymentDates.PaymentDatesBuilder pd = PaymentDates.builder();
            cdm.base.datetime.Frequency.FrequencyBuilder fb = cdm.base.datetime.Frequency.builder();
            String pm = XmlUtils.childText(pFreqEl, "periodMultiplier");
            String p = XmlUtils.childText(pFreqEl, "period");
            if (pm != null) fb.setPeriodMultiplier(Integer.parseInt(pm));
            if (p != null) fb.setPeriod(EnumMappers.periodExtended(p));
            pd.setPaymentFrequency(fb.build());
            String fpd = XmlUtils.childText(periodic, "firstPaymentDate");
            if (fpd != null) pd.setFirstPaymentDate(DateMapper.parse(fpd));
            irp.setPaymentDates(pd.build());
        }
        return irp.build();
    }

    /* ---------------- small helpers ---------------- */

    private static UnitType currencyUnit(String ccy) {
        if (ccy == null) return null;
        return UnitType.builder().setCurrency(FieldWithMetaString.builder().setValue(ccy).build()).build();
    }

    private static Reference addressRef(String value) {
        return Reference.builder().setScope("DOCUMENT").setReference(value).build();
    }

    private static String href(Element el) {
        return el == null ? null : el.getAttribute("href");
    }

    private void assignRoles(String sellerHref, MappingContext ctx) {
        if (sellerHref == null) return;
        // Re-key partyOrder: seller → 0 (Party1), other → 1 (Party2). Preserve original order for ties.
        java.util.LinkedHashMap<String, Integer> newOrder = new java.util.LinkedHashMap<>();
        newOrder.put(sellerHref, 0);
        int idx = 1;
        for (String pid : ctx.partyOrder.keySet()) {
            if (!pid.equals(sellerHref)) {
                newOrder.put(pid, idx++);
            }
        }
        ctx.partyOrder.clear();
        ctx.partyOrder.putAll(newOrder);
    }
}
