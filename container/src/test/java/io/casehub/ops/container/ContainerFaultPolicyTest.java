package io.casehub.ops.container;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.container.ContainerReviewSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerFaultPolicyTest {

    private static final String TENANCY = "tenant-1";

    private ContainerFaultPolicy policy;
    private DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        policy = new ContainerFaultPolicy();
        graphFactory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void provision_failed_no_escalation_below_threshold() {
        var appNode = appContainer("myapp");
        var graph = graphFactory.of(List.of(appNode), List.of());
        var actual = new ActualState(Map.of(NodeId.of("myapp"), NodeStatus.ABSENT));
        var fault = new FaultEvent(NodeId.of("myapp"), FaultType.PROVISION_FAILED, "connection refused");

        var mutations = policy.onFault(TENANCY, fault, graph, actual);
        assertThat(mutations).isEmpty();
    }

    @Test
    void provision_failed_escalates_at_threshold_three() {
        var appNode = appContainer("myapp");
        var graph = graphFactory.of(List.of(appNode), List.of());
        var actual = new ActualState(Map.of(NodeId.of("myapp"), NodeStatus.ABSENT));
        var fault = new FaultEvent(NodeId.of("myapp"), FaultType.PROVISION_FAILED, "image pull failed");

        policy.onFault(TENANCY, fault, graph, actual);
        policy.onFault(TENANCY, fault, graph, actual);
        var mutations = policy.onFault(TENANCY, fault, graph, actual);

        assertThat(mutations).isNotEmpty();
        assertThat(mutations).anySatisfy(m -> {
            assertThat(m).isInstanceOf(GraphMutation.AddNode.class);
            var addNode = (GraphMutation.AddNode<DesiredNode>) m;
            assertThat(addNode.node().spec()).isInstanceOf(ContainerReviewSpec.class);
            var spec = (ContainerReviewSpec) addNode.node().spec();
            assertThat(spec.faultedNode()).isEqualTo(NodeId.of("myapp"));
            assertThat(spec.reason()).isEqualTo("image pull failed");
        });
    }

    @Test
    void database_container_faults_are_handled() {
        var dbNode = new DesiredNode(NodeId.of("mydb"),
            new DatabaseContainerSpec("mydb", "postgres:16", "testdb", "user", "pass", "mydb-data"),
            HumanGating.NONE);
        var graph = graphFactory.of(List.of(dbNode), List.of());
        var actual = new ActualState(Map.of(NodeId.of("mydb"), NodeStatus.ABSENT));
        var fault = new FaultEvent(NodeId.of("mydb"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault(TENANCY, fault, graph, actual);
        policy.onFault(TENANCY, fault, graph, actual);
        var mutations = policy.onFault(TENANCY, fault, graph, actual);

        assertThat(mutations).isNotEmpty();
    }

    @Test
    void ignores_review_node_faults() {
        var reviewNode = new DesiredNode(NodeId.of("container-review-myapp"),
            new ContainerReviewSpec(NodeId.of("myapp"), "test"),
            HumanGating.ALL);
        var graph = graphFactory.of(List.of(reviewNode), List.of());
        var actual = new ActualState(Map.of());
        var fault = new FaultEvent(NodeId.of("container-review-myapp"),
            FaultType.PROVISION_FAILED, "fail");

        var mutations = policy.onFault(TENANCY, fault, graph, actual);
        assertThat(mutations).isEmpty();
    }

    @Test
    void unhandled_fault_type_returns_empty() {
        var appNode = appContainer("myapp");
        var graph = graphFactory.of(List.of(appNode), List.of());
        var actual = new ActualState(Map.of());
        var fault = new FaultEvent(NodeId.of("myapp"), FaultType.DEPROVISION_FAILED, "fail");

        var mutations = policy.onFault(TENANCY, fault, graph, actual);
        assertThat(mutations).isEmpty();
    }

    private static DesiredNode appContainer(String name) {
        return new DesiredNode(NodeId.of(name),
            new AppContainerSpec(name, "img:latest", Map.of(), Map.of(), Map.of()),
            HumanGating.NONE);
    }
}
