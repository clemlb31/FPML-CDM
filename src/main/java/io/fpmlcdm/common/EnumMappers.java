package io.fpmlcdm.common;

import cdm.base.datetime.BusinessDayConventionEnum;
import cdm.base.datetime.PeriodEnum;
import cdm.base.datetime.PeriodExtendedEnum;
import cdm.base.datetime.daycount.DayCountFractionEnum;
import cdm.base.datetime.daycount.metafields.FieldWithMetaDayCountFractionEnum;
import cdm.base.staticdata.party.PartyIdentifierTypeEnum;
import cdm.base.staticdata.asset.rates.FloatingRateIndexEnum;
import cdm.base.staticdata.asset.rates.InflationRateIndexEnum;
import cdm.base.staticdata.asset.rates.metafields.FieldWithMetaFloatingRateIndexEnum;
import cdm.base.staticdata.asset.rates.metafields.FieldWithMetaInflationRateIndexEnum;

import java.util.HashMap;
import java.util.Map;

/** Conversions between FpML string values and CDM enums. */
public final class EnumMappers {

    private static final Map<String, DayCountFractionEnum> DAY_COUNT = new HashMap<>();
    static {
        DAY_COUNT.put("ACT/360", DayCountFractionEnum.ACT_360);
        DAY_COUNT.put("ACT/365.FIXED", DayCountFractionEnum.ACT_365_FIXED);
        DAY_COUNT.put("ACT/365L", DayCountFractionEnum.ACT_365L);
        DAY_COUNT.put("ACT/ACT.AFB", DayCountFractionEnum.ACT_ACT_AFB);
        DAY_COUNT.put("ACT/ACT.ICMA", DayCountFractionEnum.ACT_ACT_ICMA);
        DAY_COUNT.put("ACT/ACT.ISDA", DayCountFractionEnum.ACT_ACT_ISDA);
        DAY_COUNT.put("ACT/ACT.ISMA", DayCountFractionEnum.ACT_ACT_ISMA);
        DAY_COUNT.put("30/360", DayCountFractionEnum._30_360);
        DAY_COUNT.put("30E/360", DayCountFractionEnum._30E_360);
        DAY_COUNT.put("30E/360.ISDA", DayCountFractionEnum._30E_360_ISDA);
        DAY_COUNT.put("BUS/252", DayCountFractionEnum.CAL_252);
        DAY_COUNT.put("1/1", DayCountFractionEnum._1_1);
    }

    private EnumMappers() {}

    public static FieldWithMetaDayCountFractionEnum dayCount(String text) {
        if (text == null) return null;
        DayCountFractionEnum e = DAY_COUNT.get(text);
        if (e == null) {
            // Fall back to raw value; serialiser will keep the text but unit tests may detect it.
            return null;
        }
        return FieldWithMetaDayCountFractionEnum.builder().setValue(e).build();
    }

    public static PeriodEnum period(String text) {
        if (text == null) return null;
        return PeriodEnum.valueOf(text);
    }

    public static PeriodExtendedEnum periodExtended(String text) {
        if (text == null) return null;
        return PeriodExtendedEnum.valueOf(text);
    }

    public static BusinessDayConventionEnum bdc(String text) {
        if (text == null) return null;
        return switch (text) {
            case "FOLLOWING"       -> BusinessDayConventionEnum.FOLLOWING;
            case "MODFOLLOWING"    -> BusinessDayConventionEnum.MODFOLLOWING;
            case "PRECEDING"       -> BusinessDayConventionEnum.PRECEDING;
            case "MODPRECEDING"    -> BusinessDayConventionEnum.MODPRECEDING;
            case "NEAREST"         -> BusinessDayConventionEnum.NEAREST;
            case "FRN"             -> BusinessDayConventionEnum.FRN;
            case "NotApplicable",
                 "NotEnumerated"   -> BusinessDayConventionEnum.NOT_APPLICABLE;
            case "NONE"            -> BusinessDayConventionEnum.NONE;
            default -> {
                try { yield BusinessDayConventionEnum.valueOf(text); }
                catch (IllegalArgumentException ex) { yield null; }
            }
        };
    }

    /** Maps a partyId scheme URI to the matching CDM identifierType. */
    public static PartyIdentifierTypeEnum partyIdentifierType(String scheme) {
        if (scheme == null) return null;
        if (scheme.contains("iso17442")) return PartyIdentifierTypeEnum.LEI;
        if (scheme.contains("iso9362"))  return PartyIdentifierTypeEnum.BIC;
        if (scheme.contains("mic"))      return PartyIdentifierTypeEnum.MIC;
        return null;
    }

    public static FieldWithMetaFloatingRateIndexEnum floatingRateIndex(String text) {
        if (text == null) return null;
        FloatingRateIndexEnum e = null;
        // FloatingRateIndexEnum.fromDisplayName throws IllegalArgumentException when not found,
        // rather than returning null — wrap to make this null-safe.
        try { e = FloatingRateIndexEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        if (e == null) {
            for (FloatingRateIndexEnum candidate : FloatingRateIndexEnum.values()) {
                if (candidate.name().equalsIgnoreCase(text.replace('-', '_').replace('.', '_'))) {
                    e = candidate;
                    break;
                }
            }
        }
        if (e == null) return null;
        return FieldWithMetaFloatingRateIndexEnum.builder().setValue(e).build();
    }

    public static FieldWithMetaInflationRateIndexEnum inflationRateIndex(String text) {
        if (text == null) return null;
        InflationRateIndexEnum e = null;
        try { e = InflationRateIndexEnum.fromDisplayName(text); }
        catch (Exception ignored) {}
        if (e == null) {
            String normalized = text.replace('-', '_').replace('.', '_').toUpperCase();
            for (InflationRateIndexEnum candidate : InflationRateIndexEnum.values()) {
                if (candidate.name().equalsIgnoreCase(normalized)) {
                    e = candidate;
                    break;
                }
            }
        }
        if (e == null) return null;
        return FieldWithMetaInflationRateIndexEnum.builder().setValue(e).build();
    }
}
