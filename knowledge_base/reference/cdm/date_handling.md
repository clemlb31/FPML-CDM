# CDM Date Type

CDM uses com.rosetta.model.lib.records.Date (NOT java.time.LocalDate).

  Date d = Date.parse("2024-01-15");   // from ISO-8601 string
  Date d = Date.of(2024, 1, 15);       // year, month, day

Wrapping in AdjustableDate:
  AdjustableDate adj = AdjustableDate.builder()
      .setUnadjustedDate(Date.parse(fpmlDateString))
      .build();

Wrapping in AdjustableOrRelativeDate:
  AdjustableOrRelativeDate aord = AdjustableOrRelativeDate.builder()
      .setAdjustableDate(adj)
      .build();
