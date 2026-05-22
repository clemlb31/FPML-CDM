# Rosetta Global Key & Cross-Reference Pattern (CDM Java 6.x)

## Problem
In Rosetta CDM, the same object (e.g. Party) can be referenced from multiple places:
  Trade.party[0]           — the Party itself
  Counterparty.partyReference  — a reference back to that Party
  
Without a globalKey, serialisation produces duplicated objects and cross-references break.

## Pattern

### Step 1 — Assign a globalKey when building the object

```java
import com.rosetta.model.metafields.MetaAndTemplateFields;
import java.util.UUID;

private String newKey() {
    return UUID.randomUUID().toString().substring(0, 8);
}

// Build the Party WITH a key:
String party1Key = newKey();
Party party1 = Party.builder()
    .setMeta(MetaAndTemplateFields.builder()
        .setGlobalKey(party1Key)
        .build())
    .setName(FieldWithMetaString.builder().setValue("Bank ABC").build())
    .addPartyId(PartyIdentifier.builder()
        .setIdentifier(FieldWithMetaString.builder().setValue("549300...").build())
        .setIdentifierType(PartyIdentifierTypeEnum.LEI)
        .build())
    .build();
```

### Step 2 — Reference it via ReferenceWithMetaParty

```java
import cdm.base.staticdata.party.ReferenceWithMetaParty;

// In buildParties() — return both Party and its key, e.g. as a Map:
Map<String, Party> partyMap = new LinkedHashMap<>();
partyMap.put("party1", party1);   // key name from FpML @id attribute
partyMap.put("party2", party2);

// In Counterparty:
Counterparty cp1 = Counterparty.builder()
    .setPartyReference(
        ReferenceWithMetaParty.builder()
            .setGlobalReference(party1Key)   // same key as above
            .setValue(party1)                // inline value (helps serialisation)
            .build())
    .setRole(CounterpartyRoleEnum.PARTY_1)
    .build();
```

### Step 3 — Map FpML party hrefs to PARTY_1 / PARTY_2

```java
// FpML: payerPartyReference/@href = "party1" or "party2"
// First party in document order = PARTY_1

private CounterpartyRoleEnum resolvePartyRole(Element context, String hrefAttr, String firstPartyId) {
    String href = context.getAttribute(hrefAttr);
    return href.equals(firstPartyId) ? CounterpartyRoleEnum.PARTY_1 : CounterpartyRoleEnum.PARTY_2;
}
```

## Key rules
- Only objects that are referenced from MULTIPLE places need a globalKey.
- For IRS: ONLY Party objects need globalKeys.
- InterestRatePayout uses CounterpartyRoleEnum (not direct party refs) — no globalKey needed there.
- The key value is arbitrary — use a short UUID substring for readability.
- Always set both .setGlobalReference(key) AND .setValue(party) on ReferenceWithMetaParty.
