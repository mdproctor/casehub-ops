package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ras.api.ActiveSituation;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class StubSituationRecompiler implements SituationRecompiler {

    @Override
    public Optional<CompilationResult> recompile(
            String tenancyId,
            DesiredStateGraph current,
            ActualState actual,
            ActiveSituation situation,
            DesiredStateGraphFactory factory) {
        return Optional.empty();
    }
}
