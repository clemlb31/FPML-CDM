package io.fpmlcdm.validate;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import io.fpmlcdm.FpmlToCdmConverter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Runs our converter on one FpML, applies GlobalKeyReproducer, prints the resulting JSON. */
public final class GlobalKeyDemoCli {
    public static void main(String[] args) throws Exception {
        Path xml = Paths.get(args[0]);
        ObjectMapper json = RosettaObjectMapper.getNewRosettaObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);

        FpmlToCdmConverter converter = new FpmlToCdmConverter();
        List<TradeState> states;
        try (InputStream in = Files.newInputStream(xml)) {
            states = converter.convert(in);
        }
        TradeState ts = states.get(0);

        TradeState rekeyed = new GlobalKeyReproducer().apply(ts);
        String out = json.writeValueAsString(rekeyed);
        if (args.length > 1) Files.writeString(Paths.get(args[1]), out);
        else System.out.println(out);
    }
}
