package io.casehub.ops.app.spi;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationSource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class StubSituationSource implements SituationSource {

    @Override
    public List<ActiveSituation> activeSituations(String tenancyId) {
        return List.of();
    }
}
