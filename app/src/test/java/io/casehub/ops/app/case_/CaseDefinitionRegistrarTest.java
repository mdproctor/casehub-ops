package io.casehub.ops.app.case_;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.platform.api.path.Path;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CaseDefinitionRegistrarTest {

    @Test
    void registersSevenCaseDefinitions() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry, new io.casehub.ops.app.service.NodeConvergenceTracker((caseId, path, value) -> {}, new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())), null);

        registrar.onStartup(null);

        assertThat(registry.registered).hasSize(7);
    }

    @Test
    void registersApplicationLifecycleDefinition() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry, new io.casehub.ops.app.service.NodeConvergenceTracker((caseId, path, value) -> {}, new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())), null);

        registrar.onStartup(null);

        assertThat(registry.registered).anyMatch(d ->
                "ops".equals(d.getNamespace()) && "application-lifecycle".equals(d.getName()));
    }

    @Test
    void registersDriftRemediationDefinition() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry, new io.casehub.ops.app.service.NodeConvergenceTracker((caseId, path, value) -> {}, new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())), null);

        registrar.onStartup(null);

        assertThat(registry.registered).anyMatch(d ->
                "ops".equals(d.getNamespace()) && "drift-remediation".equals(d.getName()));
    }

    @Test
    void registersFourStubDefinitions() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry, new io.casehub.ops.app.service.NodeConvergenceTracker((caseId, path, value) -> {}, new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())), null);

        registrar.onStartup(null);

        List<String> stubNames = registry.registered.stream()
                .filter(d -> d.getTitle() != null && d.getTitle().contains("stub"))
                .map(CaseDefinition::getName)
                .toList();
        assertThat(stubNames).containsExactlyInAnyOrder(
                "cve-response", "service-upgrade", "incident-response",
                "compliance-remediation");
    }

    private static class RecordingRegistry implements CaseDefinitionRegistry {
        final List<CaseDefinition> registered = new ArrayList<>();

        @Override
        public Uni<CaseMetaModel> registerCaseDefinition(CaseDefinition model) {
            registered.add(model);
            return Uni.createFrom().nullItem();
        }

        @Override
        public CaseDefinition getCaseDefinition(CaseMetaModel definition) { return null; }

        @Override
        public CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition) { return null; }

        @Override
        public Optional<CaseMetaModel> findByIdentity(String namespace, String name, String version) {
            return Optional.empty();
        }

        @Override
        public Optional<CaseDefinition> findByName(String name) { return Optional.empty(); }

        @Override
        public List<CaseDefinition> findByType(Path type) { return List.of(); }

        @Override
        public List<CaseDefinition> findByLabel(Path label) { return List.of(); }
    }
}
