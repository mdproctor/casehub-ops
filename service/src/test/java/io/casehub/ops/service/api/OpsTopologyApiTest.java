package io.casehub.ops.service.api;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.FaultPolicyEngine;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.ops.api.deployment.AdaptationSnapshotProvider;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsTopologyApiTest {

    private       OpsTopologyApi                  api;
    private       ReconciliationLoop              reconciliationLoop;
    private       StubActualStateAdapterRouter    actualStateRouter;
    private       StubAdaptationSnapshotProvider  adaptationProvider;
    private final DefaultDesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    @BeforeEach
    void setUp() throws Exception {
        actualStateRouter = new StubActualStateAdapterRouter();

        reconciliationLoop = ReconciliationLoop.builder(
                new TransitionPlanner(),
                (plan, tenancyId) -> new TransitionResult(Map.of()),
                actualStateRouter,
                new FaultPolicyEngine(List.of()),
                () -> Multi.createFrom().empty()
                                                       ).build();

        adaptationProvider = new StubAdaptationSnapshotProvider();

        api = new OpsTopologyApi();
        setField(api, "reconciliationLoop", reconciliationLoop);
        setField(api, "actualStateRouter", actualStateRouter);
        setField(api, "adaptationProvider", new StubInstance<>(adaptationProvider));
    }

    @AfterEach
    void tearDown() {
        reconciliationLoop.shutdown();
    }

    @Test
    void returnsEmptySnapshotWhenNoLoopsForTenant() {
        var result = api.getTopology("tenant-1");

        assertNotNull(result);
        assertTrue(result.loops().isEmpty());
        assertTrue(result.adaptation().activeRules().isEmpty());
    }

    @Test
    void returnsDesiredAndActualNodesForMatchingLoops() {
        NodeId agentId = NodeId.of("fraud-detector");
        DesiredStateGraph graph = graphFactory.empty()
                                              .withNode(new DesiredNode(agentId, agentSpec("agent"), HumanGating.NONE));
        reconciliationLoop.start("tenant-1:app-1:cluster-1", graph);

        actualStateRouter.nextResult = new ActualState(Map.of(agentId, NodeStatus.PRESENT));

        var result = api.getTopology("tenant-1");

        assertEquals(1, result.loops().size());
        var loop = result.loops().get(0);
        assertEquals("tenant-1:app-1:cluster-1", loop.loopKey());
        assertEquals(1, loop.desiredNodes().size());
        assertEquals("fraud-detector", loop.desiredNodes().get(0).nodeId());
        assertEquals("agent", loop.desiredNodes().get(0).nodeType());
        assertEquals("ACTIVE", loop.desiredNodes().get(0).targetStatus());
        assertFalse(loop.desiredNodes().get(0).adapted());
        assertEquals(1, loop.actualStatuses().size());
        assertEquals("fraud-detector", loop.actualStatuses().get(0).nodeId());
        assertEquals("PRESENT", loop.actualStatuses().get(0).status());
    }

    @Test
    void filtersLoopsByTenantPrefix() {
        DesiredStateGraph graph = graphFactory.empty()
                                              .withNode(new DesiredNode(NodeId.of("a"), agentSpec("agent"), HumanGating.NONE));
        reconciliationLoop.start("tenant-1:app-1:cluster-1", graph);
        reconciliationLoop.start("tenant-2:app-2:cluster-2", graph);

        actualStateRouter.nextResult = new ActualState(Map.of());

        var result = api.getTopology("tenant-1");

        assertEquals(1, result.loops().size());
        assertTrue(result.loops().get(0).loopKey().startsWith("tenant-1:"));
    }

    @Test
    void marksScaledNodesAsAdapted() {
        DesiredStateGraph graph = graphFactory.empty()
                                              .withNode(new DesiredNode(NodeId.of("fraud-detector"), agentSpec("agent"), HumanGating.NONE))
                                              .withNode(new DesiredNode(NodeId.of("fraud-detector~2"), agentSpec("agent"), HumanGating.NONE));
        reconciliationLoop.start("tenant-1:app-1:cluster-1", graph);

        actualStateRouter.nextResult = new ActualState(Map.of());

        var result = api.getTopology("tenant-1");

        var nodes = result.loops().get(0).desiredNodes();
        assertEquals(2, nodes.size());
        var base   = nodes.stream().filter(n -> n.nodeId().equals("fraud-detector")).findFirst().orElseThrow();
        var scaled = nodes.stream().filter(n -> n.nodeId().equals("fraud-detector~2")).findFirst().orElseThrow();
        assertFalse(base.adapted());
        assertTrue(scaled.adapted());
    }

    private static NodeSpec agentSpec(String type) {
        return new NodeSpec() {
            @Override
            public NodeType nodeType() {
                return NodeType.of(type);
            }
        };
    }

    private static void setField(Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class StubActualStateAdapterRouter implements ActualStateAdapterRouter {
        ActualState nextResult = new ActualState(Map.of());

        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return nextResult;
        }

        @Override
        public Set<NodeType> allHandledTypes() {
            return Set.of();
        }
    }

    private static class StubAdaptationSnapshotProvider implements AdaptationSnapshotProvider {
        @Override
        public AdaptationSnapshot getAdaptationSnapshot(String tenancyId) {
            return new AdaptationSnapshot(List.of(), List.of());
        }
    }

    private static class StubInstance<T> implements jakarta.enterprise.inject.Instance<T> {
        private final T value;

        StubInstance(T value)                                                                                                                                            {this.value = value;}

        @Override
        public T get()                                                                                                                                                   {return value;}

        @Override
        public boolean isResolvable()                                                                                                                                    {return value != null;}

        @Override
        public boolean isUnsatisfied()                                                                                                                                   {return value == null;}

        @Override
        public boolean isAmbiguous()                                                                                                                                     {return false;}

        @Override
        public jakarta.enterprise.inject.Instance<T> select(java.lang.annotation.Annotation... qualifiers)                                                               {return this;}

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers)                               {throw new UnsupportedOperationException();}

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {throw new UnsupportedOperationException();}

        @Override
        public void destroy(T instance)                                                                                                                                  {}

        @Override
        public Handle<T> getHandle()                                                                                                                                     {throw new UnsupportedOperationException();}

        @Override
        public Iterable<Handle<T>> handles()                                                                                                                             {throw new UnsupportedOperationException();}

        @Override
        public java.util.Iterator<T> iterator()                                                                                                                          {return value != null ? java.util.List.of(value).iterator() : java.util.List.<T>of().iterator();}
    }

}
