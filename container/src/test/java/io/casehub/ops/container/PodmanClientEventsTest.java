package io.casehub.ops.container;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PodmanClientEventsTest {

    @Test
    void events_returnsMultiOfJsonObjects() {
        var sim = new ContainerReconciliationIntegrationTest.SimulatingPodmanClient();
        var event = new JsonObject()
            .put("Type", "container")
            .put("Action", "start")
            .put("Actor", new JsonObject()
                .put("Attributes", new JsonObject().put("name", "myapp")));

        var future = sim.events("container")
            .select().first(1)
            .collect().asList()
            .subscribeAsCompletionStage();

        sim.emitEvent(event);

        assertThat(future).succeedsWithin(Duration.ofSeconds(2))
            .asList().hasSize(1);
        assertThat(future.join().get(0).getString("Action")).isEqualTo("start");
    }
}
