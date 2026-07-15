package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.SituationRecompiler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class SituationRecompilerListProducer {

    @Inject
    Instance<SituationRecompiler> recompilers;

    @Produces
    @ApplicationScoped
    public List<SituationRecompiler> produceSituationRecompilers() {
        return StreamSupport.stream(recompilers.spliterator(), false).toList();
    }
}
