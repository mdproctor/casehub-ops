package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.k8s.K8sReviewSpec;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesFaultPolicyTest {

    private static final NodeType K8S_REVIEW = NodeType.of("k8s-review");
    private static final ActualState EMPTY_ACTUAL = new ActualState(Map.of());

    private final DefaultDesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();
    private KubernetesFaultPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new KubernetesFaultPolicy();
    }

    @Test
    void provisionFailed_belowThreshold_returnsEmpty() {
        var graph = graphWithK8sNode("deploy-1", ApplicationNodeTypes.K8S_DEPLOYMENT);
        var event = new FaultEvent(NodeId.of("deploy-1"), FaultType.PROVISION_FAILED, "image pull failed");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void provisionFailed_atThreshold_addsReviewNode() {
        var graph = graphWithK8sNode("deploy-1", ApplicationNodeTypes.K8S_DEPLOYMENT);
        var event = new FaultEvent(NodeId.of("deploy-1"), FaultType.PROVISION_FAILED, "image pull failed");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        var mutations = policy.onFault("t1", event, graph, EMPTY_ACTUAL);

        assertThat(mutations).hasSize(2);
        assertThat(mutations.getFirst()).isInstanceOf(GraphMutation.AddNode.class);

        @SuppressWarnings("unchecked")
        var addNode = (GraphMutation.AddNode<DesiredNode>) mutations.getFirst();
        assertThat(addNode.node().id()).isEqualTo(NodeId.of("k8s-review-deploy-1"));
        assertThat(addNode.node().type()).isEqualTo(K8S_REVIEW);
        assertThat(addNode.node().humanGating()).isEqualTo(HumanGating.ALL);
        assertThat(addNode.node().spec()).isInstanceOf(K8sReviewSpec.class);

        var spec = (K8sReviewSpec) addNode.node().spec();
        assertThat(spec.faultedNode()).isEqualTo(NodeId.of("deploy-1"));
        assertThat(spec.reason()).isEqualTo("image pull failed");

        assertThat(mutations.get(1)).isInstanceOf(GraphMutation.AddEdge.class);
        @SuppressWarnings("unchecked")
        var addDep = (GraphMutation.AddEdge<DesiredNode>) mutations.get(1);
        assertThat(addDep.from()).isEqualTo("k8s-review-deploy-1");
        assertThat(addDep.to()).isEqualTo("deploy-1");
    }

    @Test
    void provisionFailed_reviewAlreadyExists_returnsEmpty() {
        var deployNode = new DesiredNode(NodeId.of("deploy-1"),
                testSpec(ApplicationNodeTypes.K8S_DEPLOYMENT), HumanGating.NONE);
        var reviewNode = new DesiredNode(NodeId.of("k8s-review-deploy-1"),
                new K8sReviewSpec(NodeId.of("deploy-1"), "prior"), HumanGating.ALL);
        var graph = graphFactory.of(List.of(deployNode, reviewNode), List.of());
        var event = new FaultEvent(NodeId.of("deploy-1"), FaultType.PROVISION_FAILED, "still failing");

        for (int i = 0; i < 10; i++) {
            assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
        }
    }

    @Test
    void provisionFailed_onReviewNode_returnsEmpty() {
        var reviewNode = new DesiredNode(NodeId.of("review-deploy-1"),
                new K8sReviewSpec(NodeId.of("deploy-1"), "test"), HumanGating.ALL);
        var graph = graphFactory.of(List.of(reviewNode), List.of());
        var event = new FaultEvent(NodeId.of("review-deploy-1"), FaultType.PROVISION_FAILED, "failed");

        for (int i = 0; i < 10; i++) {
            assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
        }
    }

    @Test
    void nonProvisionFaultType_returnsEmpty() {
        var graph = graphWithK8sNode("deploy-1", ApplicationNodeTypes.K8S_DEPLOYMENT);
        for (FaultType type : List.of(FaultType.NODE_DEGRADED, FaultType.NODE_DESTROYED,
                FaultType.DEPROVISION_FAILED, FaultType.DEPENDENCY_UNAVAILABLE,
                FaultType.HUMAN_NODE_TIMEOUT, FaultType.APPROVAL_REJECTED)) {
            var event = new FaultEvent(NodeId.of("deploy-1"), type, "test");
            assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL))
                    .as("FaultType %s should return empty", type)
                    .isEmpty();
        }
    }

    @Test
    void nonK8sNodeType_returnsEmpty() {
        var otherNode = new DesiredNode(NodeId.of("other-1"),
                testSpec(NodeType.of("something-else")), HumanGating.NONE);
        var graph = graphFactory.of(List.of(otherNode), List.of());
        var event = new FaultEvent(NodeId.of("other-1"), FaultType.PROVISION_FAILED, "failed");

        for (int i = 0; i < 10; i++) {
            assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
        }
    }

    @Test
    void allK8sNodeTypes_escalate() {
        for (NodeType k8sType : List.of(ApplicationNodeTypes.K8S_NAMESPACE,
                ApplicationNodeTypes.K8S_DEPLOYMENT,
                ApplicationNodeTypes.K8S_SERVICE,
                ApplicationNodeTypes.K8S_INGRESS,
                ApplicationNodeTypes.K8S_CONFIGMAP)) {
            var freshPolicy = new KubernetesFaultPolicy();
            var node = new DesiredNode(NodeId.of("res-1"), testSpec(k8sType), HumanGating.NONE);
            var graph = graphFactory.of(List.of(node), List.of());
            var event = new FaultEvent(NodeId.of("res-1"), FaultType.PROVISION_FAILED, "fail");

            freshPolicy.onFault("t1", event, graph, EMPTY_ACTUAL);
            freshPolicy.onFault("t1", event, graph, EMPTY_ACTUAL);
            assertThat(freshPolicy.onFault("t1", event, graph, EMPTY_ACTUAL))
                    .as("K8s node type %s should escalate at threshold", k8sType)
                    .hasSize(2);
        }
    }

    private DesiredStateGraph graphWithK8sNode(String nodeId, NodeType type) {
        var node = new DesiredNode(NodeId.of(nodeId), testSpec(type), HumanGating.NONE);
        return graphFactory.of(List.of(node), List.of());
    }

    private NodeSpec testSpec(NodeType type) {
        return new NodeSpec() {
            public NodeType nodeType() { return type; }
        };
    }
}
