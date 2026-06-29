package io.fpmlcdm.fpml.cdm.common;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.AdjustedRelativeDateOffset;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.datetime.BusinessCenters;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.DayTypeEnum;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.MetaFields;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import org.w3c.dom.Element;

import java.util.List;

/** Date and business-day mappers for FpML 5.x date constructs. */
public final class DateMapper {

    private DateMapper() {}

    /** Parses an FpML {@code YYYY-MM-DD} string into a Rosetta {@link Date}. */
    public static Date parse(String iso) {
        if (iso == null) return null;
        String clean = iso.trim();
        if (clean.isEmpty()) return null;

        // Some FpML values carry timestamps; keep only the date portion.
        int tIdx = clean.indexOf('T');
        if (tIdx > 0) clean = clean.substring(0, tIdx);
        if (clean.endsWith("Z")) clean = clean.substring(0, clean.length() - 1);

        String[] p = clean.split("-");
        if (p.length != 3) return null;
        try {
            int y = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            int d = Integer.parseInt(p[2]);
            // Guard Murex null-date sentinels (0000-00-00, 0002-11-30, …): no real trade
            // date predates 1900, so treat such years as "no date".
            if (y < 1900 || m < 1 || m > 12 || d < 1 || d > 31) return null;
            return Date.of(y, m, d);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Maps an FpML {@code <dateAdjustments>} element to a CDM {@link BusinessDayAdjustments}.
     * Handles inline {@code <businessCenters>} and {@code <businessCentersReference>} variants.
     */
    public static BusinessDayAdjustments businessDayAdjustments(Element fpml) {
        if (fpml == null) return null;
        BusinessDayAdjustments.BusinessDayAdjustmentsBuilder b = BusinessDayAdjustments.builder();
        String bdc = XmlUtils.childText(fpml, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));

        Element centers = XmlUtils.child(fpml, "businessCenters");
        Element centersRef = XmlUtils.child(fpml, "businessCentersReference");
        if (centers != null) {
            BusinessCenters bc = buildBusinessCenters(centers);
            if (bc != null) b.setBusinessCenters(bc);
        } else if (centersRef != null) {
            String href = centersRef.getAttribute("href");
            BusinessCenters.BusinessCentersBuilder wrap = BusinessCenters.builder();
            wrap.setBusinessCentersReference(ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(href)
                    .build());
            b.setBusinessCenters(wrap.build());
        }
        return b.build();
    }

    public static BusinessCenters buildBusinessCenters(Element fpml) {
        if (fpml == null) return null;
        BusinessCenters.BusinessCentersBuilder b = BusinessCenters.builder();
        List<Element> centerEls = XmlUtils.children(fpml, "businessCenter");
        boolean any = false;
        for (Element c : centerEls) {
            String code = c.getTextContent().trim();
            try {
                BusinessCenterEnum e = BusinessCenterEnum.valueOf(code);
                FieldWithMetaBusinessCenterEnum.FieldWithMetaBusinessCenterEnumBuilder fb =
                        FieldWithMetaBusinessCenterEnum.builder().setValue(e);
                String scheme = c.getAttribute("businessCenterScheme");
                if (scheme != null && !scheme.isEmpty()) {
                    fb.setMeta(MetaFields.builder().setScheme(scheme).build());
                }
                b.addBusinessCenter(fb.build());
                any = true;
            } catch (IllegalArgumentException ignored) {
                // Unknown business center — skip; rare in standard ISO codes.
            }
        }
        String id = fpml.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
            any = true;
        }
        if (!any) return null;
        return b.build();
    }

    /**
     * FpML {@code <effectiveDate>}/{@code <terminationDate>} containing an
     * {@code <unadjustedDate>} + {@code <dateAdjustments>}.
     */
    public static AdjustableOrRelativeDate adjustableOrRelative(Element fpml) {
        if (fpml == null) return null;
        // Check for a nested <adjustableDate> child first (e.g. <commencementDate><adjustableDate>...)
        Element adjustableDateChild = XmlUtils.child(fpml, "adjustableDate");
        AdjustableDate adj = adjustable(adjustableDateChild != null ? adjustableDateChild : fpml);
        return adj == null ? null : AdjustableOrRelativeDate.builder().setAdjustableDate(adj).build();
    }

    /**
     * Builds {@code AdjustableOrRelativeDate.relativeDate} from an FpML element such as
     * {@code <relativeEffectiveDate>} / {@code <relativeTerminationDate>}.
     */
    public static AdjustableOrRelativeDate relativeOnly(Element fpml) {
        if (fpml == null) return null;
        AdjustedRelativeDateOffset.AdjustedRelativeDateOffsetBuilder b = AdjustedRelativeDateOffset.builder();
        String pm = XmlUtils.childText(fpml, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(fpml, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(fpml, "dayType");
        if (dt != null) {
            try { b.setDayType(DayTypeEnum.valueOf(dt.toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
        }
        String bdc = XmlUtils.childText(fpml, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));

        Element centers = XmlUtils.child(fpml, "businessCenters");
        Element centersRef = XmlUtils.child(fpml, "businessCentersReference");
        if (centers != null) {
            BusinessCenters bc = buildBusinessCenters(centers);
            if (bc != null) b.setBusinessCenters(bc);
        } else if (centersRef != null) {
            b.setBusinessCentersReference(ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(centersRef.getAttribute("href")).build());
        }
        Element drt = XmlUtils.child(fpml, "dateRelativeTo");
        if (drt != null) {
            b.setDateRelativeTo(ReferenceWithMetaDate.builder()
                    .setExternalReference(drt.getAttribute("href")).build());
        }
        Element relAdj = XmlUtils.child(fpml, "relativeDateAdjustments");
        if (relAdj != null) {
            b.setRelativeDateAdjustments(businessDayAdjustments(relAdj));
        }
        return AdjustableOrRelativeDate.builder().setRelativeDate(b.build()).build();
    }

    /**
     * Builds a RelativeDateOffset from an FpML element containing
     * periodMultiplier, period, dayType, businessDayConvention, businessCenters, dateRelativeTo.
     */
    public static RelativeDateOffset buildRelativeDateOffset(Element fpml) {
        if (fpml == null) return null;
        RelativeDateOffset.RelativeDateOffsetBuilder b = RelativeDateOffset.builder();
        String pm = XmlUtils.childText(fpml, "periodMultiplier");
        if (pm != null) b.setPeriodMultiplier(Integer.parseInt(pm));
        String p = XmlUtils.childText(fpml, "period");
        if (p != null) b.setPeriod(EnumMappers.period(p));
        String dt = XmlUtils.childText(fpml, "dayType");
        if (dt != null) {
            try { b.setDayType(DayTypeEnum.fromDisplayName(dt)); }
            catch (Exception ignored) {
                try { b.setDayType(DayTypeEnum.valueOf(dt.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase())); }
                catch (Exception ig2) {}
            }
        }
        String bdc = XmlUtils.childText(fpml, "businessDayConvention");
        if (bdc != null) b.setBusinessDayConvention(EnumMappers.bdc(bdc));
        Element centers = XmlUtils.child(fpml, "businessCenters");
        Element centersRef = XmlUtils.child(fpml, "businessCentersReference");
        if (centers != null) {
            BusinessCenters bc = buildBusinessCenters(centers);
            if (bc != null) b.setBusinessCenters(bc);
        } else if (centersRef != null) {
            b.setBusinessCentersReference(cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters.builder()
                    .setExternalReference(centersRef.getAttribute("href")).build());
        }
        Element drt = XmlUtils.child(fpml, "dateRelativeTo");
        if (drt != null) {
            b.setDateRelativeTo(com.rosetta.model.metafields.ReferenceWithMetaDate.builder()
                    .setExternalReference(drt.getAttribute("href")).build());
        }
        return b.build();
    }

    public static AdjustableDate adjustable(Element fpml) {
        if (fpml == null) return null;
        String iso = XmlUtils.childText(fpml, "unadjustedDate");
        String adj = XmlUtils.childText(fpml, "adjustedDate");
        if (iso == null && adj == null) return null;
        AdjustableDate.AdjustableDateBuilder b = AdjustableDate.builder();
        Date unadjusted = parse(iso);
        if (unadjusted != null) b.setUnadjustedDate(unadjusted);
        if (adj != null) {
            Date adjusted = parse(adj);
            if (adjusted != null) {
                b.setAdjustedDate(com.rosetta.model.metafields.FieldWithMetaDate.builder()
                        .setValue(adjusted).build());
            }
        }
        BusinessDayAdjustments bda = businessDayAdjustments(XmlUtils.child(fpml, "dateAdjustments"));
        if (bda != null) b.setDateAdjustments(bda);
        return b.build();
    }
}
