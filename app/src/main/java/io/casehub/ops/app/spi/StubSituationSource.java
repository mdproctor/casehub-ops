package io.casehub.ops.app.spi;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationSource;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class StubSituationSource implements SituationSource {

    @Override
    public Uni<List<ActiveSituation>> activeSituations(String tenancyId) {
        return Uni.createFrom().item(List.of());
    }
}
