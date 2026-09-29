package io.casehub.ops.api.container;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerReviewSpecTest {

    @Test
    void nodeType_returnsContainerReview() {
        var spec = new ContainerReviewSpec(NodeId.of("app-1"), "OOM killed");
        assertThat(spec.nodeType()).isEqualTo(NodeType.of("container-review"));
    }

    @Test
    void rejectsNullFaultedNode() {
        assertThatThrownBy(() -> new ContainerReviewSpec(null, "reason"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullReason() {
        assertThatThrownBy(() -> new ContainerReviewSpec(NodeId.of("app-1"), null))
            .isInstanceOf(NullPointerException.class);
    }
}
