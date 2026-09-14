package io.casehub.ops.app.composition;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine;
import io.casehub.desiredstate.runtime.composition.DomainRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossDomainCompositionTest {

    private static final NodeType INFRA_NS = NodeType.of("k8s_namespace");
    private static final NodeType INFRA_DEPLOY = NodeType.of("k8s_deployment");
    private static final NodeType DEPLOY_AGENT = NodeType.of("agent");
    private static final NodeType DEPLOY_CHANNEL = NodeType.of("channel");
    private static final NodeType COMPLIANCE_CONTROL = NodeType.of("ENCRYPTION_AT_REST");
    private static final NodeType IOT_DEVICE = NodeType.of("physical-device");
    private static final NodeType IOT_CONFIG = NodeType.of("device-config");

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private CrossDomainCompositionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CrossDomainCompositionEngine(factory);
    }

    @Test
    void four_domain_composition_produces_correct_ordering() {
        DesiredStateGraph infraGraph = factory.of(
            List.of(
                node("k8s-ns-prod", INFRA_NS),
                node("k8s-deploy-api", INFRA_DEPLOY)
            ),
            List.of(new Dependency(NodeId.of("k8s-deploy-api"), NodeId.of("k8s-ns-prod")))
        );

        DesiredStateGraph deployGraph = factory.of(
            List.of(
                node("agent-alpha", DEPLOY_AGENT),
                node("channel-main", DEPLOY_CHANNEL)
            ),
            List.of(new Dependency(NodeId.of("channel-main"), NodeId.of("agent-alpha")))
        );

        DesiredStateGraph complianceGraph = factory.of(
            List.of(node("soc2-encryption", COMPLIANCE_CONTROL)),
            List.of()
        );

        DesiredStateGraph iotGraph = factory.of(
            List.of(
                node("sensor-01", IOT_DEVICE),
                node("sensor-01-config", IOT_CONFIG)
            ),
            List.of(new Dependency(NodeId.of("sensor-01-config"), NodeId.of("sensor-01")))
        );

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("infra"),
                CompilationResult.single(infraGraph))
            .provides(Set.of(INFRA_NS, INFRA_DEPLOY))
            .build());

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("deployment"),
                CompilationResult.single(deployGraph))
            .provides(Set.of(DEPLOY_AGENT, DEPLOY_CHANNEL))
            .requires(Set.of(INFRA_NS))
            .build());

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("compliance"),
                CompilationResult.single(complianceGraph))
            .provides(Set.of(COMPLIANCE_CONTROL))
            .requires(Set.of(DEPLOY_AGENT))
            .build());

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("iot"),
                CompilationResult.single(iotGraph))
            .provides(Set.of(IOT_DEVICE, IOT_CONFIG))
            .requires(Set.of(INFRA_NS))
            .build());

        engine.compose();

        // Verify topological order: infra first, then deployment+iot, then compliance
        List<DomainId> order = engine.topologicalOrder();
        assertTrue(order.indexOf(DomainId.of("infra")) < order.indexOf(DomainId.of("deployment")));
        assertTrue(order.indexOf(DomainId.of("infra")) < order.indexOf(DomainId.of("iot")));
        assertTrue(order.indexOf(DomainId.of("deployment")) < order.indexOf(DomainId.of("compliance")));

        // Verify meta-graph has correct domain-level dependencies
        DesiredStateGraph metaGraph = engine.buildMetaGraph();
        assertEquals(4, metaGraph.nodes().size());

        NodeId infraDomain = NodeId.of("domain:infra");
        NodeId deployDomain = NodeId.of("domain:deployment");
        NodeId complianceDomain = NodeId.of("domain:compliance");
        NodeId iotDomain = NodeId.of("domain:iot");

        // deployment depends on infra (requires k8s_namespace)
        assertTrue(metaGraph.dependenciesOf(deployDomain).contains(infraDomain));

        // compliance depends on deployment (requires agent)
        assertTrue(metaGraph.dependenciesOf(complianceDomain).contains(deployDomain));

        // iot depends on infra (requires k8s_namespace)
        assertTrue(metaGraph.dependenciesOf(iotDomain).contains(infraDomain));

        // iot does NOT depend on deployment
        assertFalse(metaGraph.dependenciesOf(iotDomain).contains(deployDomain));

        // infra has no dependencies (root domain)
        assertTrue(metaGraph.dependenciesOf(infraDomain).isEmpty());
    }

    @Test
    void duplicate_node_type_across_domains_throws() {
        DesiredStateGraph graph1 = factory.of(List.of(node("n1", INFRA_NS)), List.of());
        DesiredStateGraph graph2 = factory.of(List.of(node("n2", INFRA_NS)), List.of());

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("infra"), CompilationResult.single(graph1))
            .provides(Set.of(INFRA_NS))
            .build());

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("bad"), CompilationResult.single(graph2))
            .provides(Set.of(INFRA_NS))
            .build());

        assertThrows(IllegalStateException.class, () -> engine.compose());
    }

    @Test
    void unsatisfied_requires_throws() {
        DesiredStateGraph graph = factory.of(List.of(node("agent", DEPLOY_AGENT)), List.of());

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("deployment"), CompilationResult.single(graph))
            .provides(Set.of(DEPLOY_AGENT))
            .requires(Set.of(NodeType.of("nonexistent_type")))
            .build());

        assertThrows(IllegalStateException.class, () -> engine.compose());
    }

    @Test
    void iot_runs_parallel_with_deployment_not_sequenced_after() {
        DesiredStateGraph infraGraph = factory.of(
            List.of(node("k8s-ns-prod", INFRA_NS)), List.of());
        DesiredStateGraph deployGraph = factory.of(
            List.of(node("agent-alpha", DEPLOY_AGENT)), List.of());
        DesiredStateGraph iotGraph = factory.of(
            List.of(node("sensor-01", IOT_DEVICE)), List.of());

        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("infra"), CompilationResult.single(infraGraph))
            .provides(Set.of(INFRA_NS)).build());
        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("deployment"), CompilationResult.single(deployGraph))
            .provides(Set.of(DEPLOY_AGENT)).requires(Set.of(INFRA_NS)).build());
        engine.registerDomain(DomainRegistration.builder(
                DomainId.of("iot"), CompilationResult.single(iotGraph))
            .provides(Set.of(IOT_DEVICE)).requires(Set.of(INFRA_NS)).build());

        engine.compose();

        // Verify via meta-graph: both depend on infra, neither depends on the other
        DesiredStateGraph metaGraph = engine.buildMetaGraph();

        NodeId infraDomain = NodeId.of("domain:infra");
        NodeId deployDomain = NodeId.of("domain:deployment");
        NodeId iotDomain = NodeId.of("domain:iot");

        assertTrue(metaGraph.dependenciesOf(deployDomain).contains(infraDomain));
        assertTrue(metaGraph.dependenciesOf(iotDomain).contains(infraDomain));

        // iot does NOT depend on deployment — they run in parallel after infra
        assertFalse(metaGraph.dependenciesOf(iotDomain).contains(deployDomain));
        assertFalse(metaGraph.dependenciesOf(deployDomain).contains(iotDomain));
    }

    private DesiredNode node(String id, NodeType type) {
        return new DesiredNode(NodeId.of(id), new StubNodeSpec(type), HumanGating.NONE);
    }

    private record StubNodeSpec(NodeType nodeType) implements NodeSpec {}
}
