package io.casehub.ops.app.case_;

import java.util.List;
import java.util.logging.Logger;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class CaseDefinitionRegistrar {

    private static final Logger LOG = Logger.getLogger(CaseDefinitionRegistrar.class.getName());

    private final CaseDefinitionRegistry registry;

    @Inject
    public CaseDefinitionRegistrar(CaseDefinitionRegistry registry) {
        this.registry = registry;
    }

    void onStartup(@Observes @Priority(10) StartupEvent event) {
        List<CaseDefinition> definitions = List.of(
                ApplicationCaseDescriptor.build(),
                DriftRemediationCaseDescriptor.build(),
                StubChildCaseDescriptor.build("ops", "cve-response", "1.0"),
                StubChildCaseDescriptor.build("ops", "service-upgrade", "1.0"),
                StubChildCaseDescriptor.build("ops", "incident-response", "1.0"),
                StubChildCaseDescriptor.build("ops", "scaling-event", "1.0"),
                StubChildCaseDescriptor.build("ops", "compliance-remediation", "1.0"));

        for (CaseDefinition def : definitions) {
            registry.registerCaseDefinition(def)
                    .subscribe().with(
                            meta -> LOG.fine("Registered case definition: " + def.getNamespace() + ":" + def.getName()),
                            err -> LOG.warning("Failed to register " + def.getName() + ": " + err.getMessage()));
        }
        LOG.info("Registered " + definitions.size() + " case definitions");
    }
}
