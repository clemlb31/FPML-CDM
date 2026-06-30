# MXML → FpML XSLT — state & manifest

_Generated on 2026-06-23. The XSLT under `knowledge_base/mxml-fpml/ird-4-3`, `ird-5-3`, `utils` are
**XSLT 1.0 Murex** modules (MXML→FpML, FpML-5 confirmation, EXSLT + `mx:` namespace)._

## Verdict

**Not executable as-is.** Out of **89 imported modules** (`xsl:import href="mx.…"`), ~60 are
**absent** from the repo — including the layer that reads the MXML and most of the FpML building blocks. Moreover, the
hrefs are **dotted names** (`mx.a.b.c`) resolved by a Murex URIResolver not provided here.
The missing modules are not present anywhere else on the machine either.

## Missing layers (in order of criticality)

| Layer (namespace) | Role | Absent modules |
|---|---|---:|
| `mx.xml2xml.extract.mxml.*` | **Reading/extracting the input MXML** | 5 |
| `mx.xml2xml.transform.any2fpml.*` | FpML building blocks (party, tradeHeader, exercise, floatingRate, tradeNode…) | 37 |
| `mx.generic.exslt.*` | EXSLT shims (date/str/math) | 7 |
| `mx.generic.errorHandling.*` | assert / paramsToString | 2 |
| `mx.xml2xml.transform.mxml2fpml.*` (partial) | common.common, addNamespace, tradePosition, utils calc/stubs | 9 |

## Present in the repo (~29 modules, fine-grained IRD layers)

```
knowledge_base/mxml-fpml/ird-4-3/aswp/copyAllocationItems
knowledge_base/mxml-fpml/ird-4-3/aswp/keepOnlyOldContractFromEvent
knowledge_base/mxml-fpml/ird-4-3/aswp/transform
knowledge_base/mxml-fpml/ird-4-3/aswp/variables
knowledge_base/mxml-fpml/ird-4-3/cf/transform
knowledge_base/mxml-fpml/ird-4-3/cf/variables
knowledge_base/mxml-fpml/ird-4-3/common/documentation
knowledge_base/mxml-fpml/ird-4-3/common/index
knowledge_base/mxml-fpml/ird-4-3/common/stub
knowledge_base/mxml-fpml/ird-4-3/cs/transform
knowledge_base/mxml-fpml/ird-4-3/cs/variables
knowledge_base/mxml-fpml/ird-4-3/fra/transform
knowledge_base/mxml-fpml/ird-4-3/fra/variables
knowledge_base/mxml-fpml/ird-4-3/oswp/swaptionCashSettlementMethod
knowledge_base/mxml-fpml/ird-4-3/oswp/transform
knowledge_base/mxml-fpml/ird-4-3/oswp/variables
knowledge_base/mxml-fpml/ird-4-3/swap/copyAllocationItems
knowledge_base/mxml-fpml/ird-4-3/swap/keepOnlyOldContractFromEvent
knowledge_base/mxml-fpml/ird-4-3/swap/transform
knowledge_base/mxml-fpml/ird-4-3/swap/variables
knowledge_base/mxml-fpml/ird-4-3/utils/businessDayConventionsAndBusinessCenters
knowledge_base/mxml-fpml/ird-4-3/variables
knowledge_base/mxml-fpml/ird-5-3/cf/parameters
knowledge_base/mxml-fpml/ird-5-3/cf/template
knowledge_base/mxml-fpml/ird-5-3/cf/transform
knowledge_base/mxml-fpml/ird-5-3/cf/variables
knowledge_base/mxml-fpml/ird-5-3/fra/parameters
knowledge_base/mxml-fpml/ird-5-3/fra/template
knowledge_base/mxml-fpml/ird-5-3/fra/transform
knowledge_base/mxml-fpml/ird-5-3/fra/variables
knowledge_base/mxml-fpml/ird-5-3/oswp/template
knowledge_base/mxml-fpml/ird-5-3/oswp/transform
knowledge_base/mxml-fpml/ird-5-3/oswp/variables
knowledge_base/mxml-fpml/ird-5-3/swap/parameters
knowledge_base/mxml-fpml/ird-5-3/swap/template
knowledge_base/mxml-fpml/ird-5-3/swap/transform
knowledge_base/mxml-fpml/ird-5-3/swap/variables
knowledge_base/mxml-fpml/ird-5-3/variables
knowledge_base/mxml-fpml/utils/calcPeriodDays
knowledge_base/mxml-fpml/utils/formatDate
knowledge_base/mxml-fpml/utils/formatDateTime
knowledge_base/mxml-fpml/utils/getTimeZone
knowledge_base/mxml-fpml/utils/marketPrice
knowledge_base/mxml-fpml/utils/marketPriceType
knowledge_base/mxml-fpml/utils/schedule
knowledge_base/mxml-fpml/utils/stubDates
knowledge_base/mxml-fpml/utils/toFpMLDate
knowledge_base/mxml-fpml/utils/xsdDateTimeFormatter
knowledge_base/mxml-fpml/utils/xsdDateTimestamp
```

## Complete list of ABSENT imports

```
mx.generic.errorHandling.assert
mx.generic.errorHandling.paramsToString
mx.generic.exslt.date.add
mx.generic.exslt.date.day-name
mx.generic.exslt.date.difference
mx.generic.exslt.date.format-date
mx.generic.exslt.math.abs
mx.generic.exslt.str.split
mx.generic.exslt.str.tokenize
mx.xml2xml.extract.mxml.products.common.templates
mx.xml2xml.extract.mxml.products.ird.aswp.templates
mx.xml2xml.extract.mxml.products.ird.fra.templates
mx.xml2xml.extract.mxml.products.ird.swap.templates
mx.xml2xml.extract.mxml.products.ird.templates
mx.xml2xml.transform.any2fpml.4-3.products.common.americanExercise
mx.xml2xml.transform.any2fpml.4-3.products.common.bermudaExercise
mx.xml2xml.transform.any2fpml.4-3.products.common.europeanExercise
mx.xml2xml.transform.any2fpml.4-3.products.common.exercise
mx.xml2xml.transform.any2fpml.4-3.products.common.floatingRate
mx.xml2xml.transform.any2fpml.4-3.products.common.notificationMessageHeader
mx.xml2xml.transform.any2fpml.4-3.products.common.party
mx.xml2xml.transform.any2fpml.4-3.products.common.partyTradeIdentifier
mx.xml2xml.transform.any2fpml.4-3.products.common.period
mx.xml2xml.transform.any2fpml.4-3.products.common.spreadSchedule
mx.xml2xml.transform.any2fpml.4-3.products.common.strikeSchedule
mx.xml2xml.transform.any2fpml.4-3.products.common.tradeHeader
mx.xml2xml.transform.any2fpml.4-3.products.ird.aswp.tradeNode
mx.xml2xml.transform.any2fpml.4-3.products.ird.cf.tradeNode
mx.xml2xml.transform.any2fpml.4-3.products.ird.common.earlyTerminationProvision
mx.xml2xml.transform.any2fpml.4-3.products.ird.fra.tradeNode
mx.xml2xml.transform.any2fpml.4-3.products.ird.oswp.tradeNode
mx.xml2xml.transform.any2fpml.4-3.products.ird.swap.tradeNode
mx.xml2xml.transform.any2fpml.5-3.events.tradeAmendmentContent
mx.xml2xml.transform.any2fpml.5-3.events.tradeNotionalChange
mx.xml2xml.transform.any2fpml.5-3.events.tradeNovationContent
mx.xml2xml.transform.any2fpml.5-3.msg.requestMessageHeader
mx.xml2xml.transform.any2fpml.5-3.products.common.americanExercise
mx.xml2xml.transform.any2fpml.5-3.products.common.bermudaExercise
mx.xml2xml.transform.any2fpml.5-3.products.common.europeanExercise
mx.xml2xml.transform.any2fpml.5-3.products.common.notificationMessageHeader
mx.xml2xml.transform.any2fpml.5-3.products.common.party
mx.xml2xml.transform.any2fpml.5-3.products.common.partyTradeIdentifier
mx.xml2xml.transform.any2fpml.5-3.products.common.payment
mx.xml2xml.transform.any2fpml.5-3.products.common.spreadSchedule
mx.xml2xml.transform.any2fpml.5-3.products.common.strikeSchedule
mx.xml2xml.transform.any2fpml.5-3.products.ird.cf.tradeNode
mx.xml2xml.transform.any2fpml.5-3.products.ird.common.fxLinkedNotionalAmount
mx.xml2xml.transform.any2fpml.5-3.products.ird.common.principalExchange
mx.xml2xml.transform.any2fpml.5-3.products.ird.fra.tradeNode
mx.xml2xml.transform.any2fpml.5-3.products.ird.oswp.tradeNode
mx.xml2xml.transform.any2fpml.5-3.products.ird.swap.tradeNode
mx.xml2xml.transform.mxml2fpml.4-3.products.common.common
mx.xml2xml.transform.mxml2fpml.4-3.products.tradePosition
mx.xml2xml.transform.mxml2fpml.5-3.products.common.addNamespace
mx.xml2xml.transform.mxml2fpml.5-3.products.common.common
mx.xml2xml.transform.mxml2fpml.5-3.products.common.correctableRequestMessage
mx.xml2xml.transform.mxml2fpml.5-3.products.ird.utils.calculateDayCountYearFraction
mx.xml2xml.transform.mxml2fpml.5-3.products.ird.utils.numberOfDaysByObservation
mx.xml2xml.transform.mxml2fpml.5-3.products.tradePosition
mx.xml2xml.transform.mxml2fpml.utils.extract.stubs.dates
```

## To make them executable (if you export the missing modules from Murex)

1. Export from Murex the ~60 `mx.*` modules listed above, into a folder (1 file/module).
2. Processor: **Xalan-J** (XSLT 1.0 + EXSLT `func:`/`dyn:` — the native JDK is not enough).
3. A custom **URIResolver** that maps `mx.a.b.c` → file (a name→path catalog).
4. Input: an IRD MXML (e.g. `data/mxml/OUT_IRD_ASWP_5-3_INS_01/*.xml`); output compared to `_expected.xml`.
