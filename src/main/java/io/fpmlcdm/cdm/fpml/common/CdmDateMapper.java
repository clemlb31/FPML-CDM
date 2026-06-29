package io.fpmlcdm.cdm.fpml.common;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.BusinessDayAdjustments;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.FieldWithMetaDate;
import io.fpmlcdm.cdm.fpml.FpmlConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Utility for mapping CDM Date constructs to FpML XML elements.
 */
public class CdmDateMapper {

    /**
     * Maps a CDM {@link AdjustableDate} to an FpML {@code <adjustableDate>} structure.
     */
    public static Element mapAdjustableDate(Document doc, AdjustableDate adjustableDate) {
        if (adjustableDate == null) return null;

        Element adjustableDateElement = doc.createElementNS(FpmlConstants.FPML_NS, "adjustableDate");

        // Unadjusted Date — returns FieldWithMetaDate in CDM 6.x
        try {
            Object unadjustedField = getUnadjustedDate(adjustableDate);
            if (unadjustedField != null) {
                Element unadjustedDate = doc.createElementNS(FpmlConstants.FPML_NS, "unadjustedDate");
                if (unadjustedField instanceof Date) {
                    unadjustedDate.setTextContent(formatDate((Date) unadjustedField));
                } else if (unadjustedField.getClass().getMethod("get") != null) {
                    Object dateVal = unadjustedField.getClass().getMethod("get").invoke(unadjustedField);
                    if (dateVal instanceof Date) {
                        unadjustedDate.setTextContent(formatDate((Date) dateVal));
                    }
                }
                adjustableDateElement.appendChild(unadjustedDate);
            }
        } catch (Exception e) {
            // Unadjusted date missing — acceptable for some CDM constructs
        }

        // Adjusted Date — returns FieldWithMetaDate in CDM 6.x
        try {
            Object adjustedField = getAdjustedDate(adjustableDate);
            if (adjustedField != null) {
                Element adjustedDate = doc.createElementNS(FpmlConstants.FPML_NS, "adjustedDate");
                if (adjustedField instanceof Date) {
                    adjustedDate.setTextContent(formatDate((Date) adjustedField));
                } else if (adjustedField.getClass().getMethod("get") != null) {
                    Object dateVal = adjustedField.getClass().getMethod("get").invoke(adjustedField);
                    if (dateVal instanceof Date) {
                        adjustedDate.setTextContent(formatDate((Date) dateVal));
                    }
                }
                adjustableDateElement.appendChild(adjustedDate);
            }
        } catch (Exception e) {
            // Adjusted date missing — acceptable for some CDM constructs
        }

        // Date Adjustments
        BusinessDayAdjustments bda = adjustableDate.getDateAdjustments();
        if (bda != null) {
            Element adjustmentsElement = mapBusinessDayAdjustments(doc, bda);
            if (adjustmentsElement != null) {
                adjustableDateElement.appendChild(adjustmentsElement);
            }
        }

        return adjustableDateElement;
    }

    private static Object getUnadjustedDate(AdjustableDate adj) throws Exception {
        try {
            java.lang.reflect.Method m = adj.getClass().getMethod("getUnadjustedDate");
            return m.invoke(adj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object getAdjustedDate(AdjustableDate adj) throws Exception {
        try {
            java.lang.reflect.Method m = adj.getClass().getMethod("getAdjustedDate");
            return m.invoke(adj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Maps FpML {@code <dateAdjustments>} to a CDM {@link BusinessDayAdjustments}.
     */
    private static Element mapBusinessDayAdjustments(Document doc, BusinessDayAdjustments bda) {
        if (bda == null) return null;

        Element adjustmentsElement = doc.createElementNS(FpmlConstants.FPML_NS, "dateAdjustments");

        try {
            Object qualifier = bda.getClass().getMethod("getQualifier").invoke(bda);
            if (qualifier != null) {
                Element qualElem = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayAdjustmentConvention");
                qualElem.setTextContent(String.valueOf(qualifier));
                adjustmentsElement.appendChild(qualElem);
            }

            Object modifier = bda.getClass().getMethod("getModifier").invoke(bda);
            if (modifier != null) {
                Element modElem = doc.createElementNS(FpmlConstants.FPML_NS, "businessDayAdjustmentTerm");
                modElem.setTextContent(String.valueOf(modifier));
                adjustmentsElement.appendChild(modElem);
            }

            Object dates = bda.getClass().getMethod("getDates").invoke(bda);
            if (dates instanceof java.util.List) {
                java.util.List<?> dateList = (java.util.List<?>) dates;
                for (Object d : dateList) {
                    if (d instanceof Date) {
                        Element dateElem = doc.createElementNS(FpmlConstants.FPML_NS, "datePeriodAdjustments");
                        dateElem.appendChild(doc.createElementNS(FpmlConstants.FPML_NS, "modifiedBusinessDay"));
                        dateElem.getElementsByTagName("modifiedBusinessDay").item(0).setTextContent(formatDate((Date) d));
                        adjustmentsElement.appendChild(dateElem);
                    }
                }
            }
        } catch (Exception e) {
            // BDA fields missing — fallback to empty element
        }

        return adjustmentsElement;
    }

    private static String formatDate(Date date) {
        if (date == null) return "";
        try {
            int year = date.getYear();
            int month = date.getMonth();
            int day = date.getDay();
            return String.format("%04d-%02d-%02d", year, month, day);
        } catch (Exception e) {
            return "";
        }
    }
}
