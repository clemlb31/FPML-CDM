package io.fpmlcdm.common;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.AdjustableOrRelativeDate;
import cdm.base.datetime.BusinessCenters;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.metafields.FieldWithMetaBusinessCenterEnum;
import cdm.base.datetime.BusinessCenterEnum;
import cdm.base.datetime.metafields.ReferenceWithMetaBusinessCenters;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.MetaFields;
import org.w3c.dom.Element;

import java.util.List;

/** Date and business-day mappers for FpML 5.x date constructs. */
public final class DateMapper {

    private DateMapper() {}

    /** Parses an FpML {@code YYYY-MM-DD} string into a Rosetta {@link Date}. */
    public static Date parse(String iso) {
        if (iso == null) return null;
        String[] p = iso.split("-");
        return Date.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
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
            b.setBusinessCenters(buildBusinessCenters(centers));
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
        for (Element c : centerEls) {
            String code = c.getTextContent().trim();
            try {
                BusinessCenterEnum e = BusinessCenterEnum.valueOf(code);
                b.addBusinessCenter(FieldWithMetaBusinessCenterEnum.builder().setValue(e).build());
            } catch (IllegalArgumentException ignored) {
                // Unknown business center — skip; rare in standard ISO codes.
            }
        }
        String id = fpml.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            b.setMeta(MetaFields.builder().setExternalKey(id).build());
        }
        return b.build();
    }

    /**
     * FpML {@code <effectiveDate>}/{@code <terminationDate>} containing an
     * {@code <unadjustedDate>} + {@code <dateAdjustments>}.
     */
    public static AdjustableOrRelativeDate adjustableOrRelative(Element fpml) {
        if (fpml == null) return null;
        AdjustableDate adj = adjustable(fpml);
        return adj == null ? null : AdjustableOrRelativeDate.builder().setAdjustableDate(adj).build();
    }

    public static AdjustableDate adjustable(Element fpml) {
        if (fpml == null) return null;
        String iso = XmlUtils.childText(fpml, "unadjustedDate");
        if (iso == null) return null;
        AdjustableDate.AdjustableDateBuilder b = AdjustableDate.builder();
        b.setUnadjustedDate(parse(iso));
        BusinessDayAdjustments bda = businessDayAdjustments(XmlUtils.child(fpml, "dateAdjustments"));
        if (bda != null) b.setDateAdjustments(bda);
        return b.build();
    }
}
