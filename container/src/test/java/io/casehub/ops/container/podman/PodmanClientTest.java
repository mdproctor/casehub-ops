package io.casehub.ops.container.podman;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PodmanClientTest {

    private static Vertx vertx;
    private static HttpServer server;
    private static int port;
    private static PodmanClient client;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();

        var latch = new CountDownLatch(1);

        server = vertx.createHttpServer();
        server.requestHandler(req -> {
            String path = req.path();

            if (path.endsWith("/containers/json")) {
                var containers = new JsonArray()
                    .add(new JsonObject()
                        .put("Id", "abc123")
                        .put("Names", new JsonArray().add("my-app"))
                        .put("State", "running")
                        .put("Image", "casehub/scaffold:latest")
                        .put("Ports", new JsonArray()));
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(containers.encode());

            } else if (path.endsWith("/networks/json")) {
                var networks = new JsonArray()
                    .add(new JsonObject()
                        .put("Name", "ops-net")
                        .put("Id", "net-456"));
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(networks.encode());

            } else if (path.endsWith("/volumes/json")) {
                var volumes = new JsonObject()
                    .put("Volumes", new JsonArray()
                        .add(new JsonObject()
                            .put("Name", "db-data")
                            .put("Driver", "local")));
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(volumes.encode());

            } else if (path.contains("/containers/create")) {
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("Id", "new-container-id").encode());

            } else if (path.contains("/start") || path.contains("/stop") || path.contains("/remove")) {
                req.response().setStatusCode(204).end();

            } else if (path.contains("/networks/create")) {
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("Name", "ops-net").put("Id", "net-new").encode());

            } else if (path.contains("/volumes/create")) {
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("Name", "new-vol").put("Driver", "local").encode());

            } else if (path.contains("/images/pull")) {
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("id", "img-sha").encode());

            } else {
                req.response().setStatusCode(404).end();
            }
        });

        server.listen(0).onComplete(ar -> {
            port = ar.result().actualPort();
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);

        var webClient = WebClient.create(vertx);
        var address = SocketAddress.inetSocketAddress(port, "localhost");
        client = new PodmanClient(webClient, address);
    }

    @AfterAll
    static void tearDown() throws Exception {
        var latch = new CountDownLatch(1);
        if (server != null) server.close().onComplete(ar -> latch.countDown());
        latch.await(5, TimeUnit.SECONDS);
        if (vertx != null) {
            var latch2 = new CountDownLatch(1);
            vertx.close().onComplete(ar -> latch2.countDown());
            latch2.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void listContainersReturnsParsedResults() {
        var containers = client.listContainers().await().indefinitely();

        assertThat(containers).hasSize(1);
        assertThat(containers.get(0).id()).isEqualTo("abc123");
        assertThat(containers.get(0).names()).containsExactly("my-app");
        assertThat(containers.get(0).state()).isEqualTo("running");
        assertThat(containers.get(0).image()).isEqualTo("casehub/scaffold:latest");
    }

    @Test
    void listNetworksReturnsParsedResults() {
        var networks = client.listNetworks().await().indefinitely();

        assertThat(networks).hasSize(1);
        assertThat(networks.get(0).name()).isEqualTo("ops-net");
        assertThat(networks.get(0).id()).isEqualTo("net-456");
    }

    @Test
    void listVolumesReturnsParsedResults() {
        var volumes = client.listVolumes().await().indefinitely();

        assertThat(volumes).hasSize(1);
        assertThat(volumes.get(0).name()).isEqualTo("db-data");
        assertThat(volumes.get(0).driver()).isEqualTo("local");
    }

    @Test
    void createContainerReturnsId() {
        var request = new PodmanClient.CreateContainerRequest(
            "test-app", "casehub/scaffold:latest",
            java.util.Map.of(8080, 8080), java.util.Map.of("K", "V"));
        var id = client.createContainer(request).await().indefinitely();

        assertThat(id).isEqualTo("new-container-id");
    }

    @Test
    void startContainerSucceeds() {
        client.startContainer("abc123").await().indefinitely();
    }

    @Test
    void stopContainerSucceeds() {
        client.stopContainer("abc123").await().indefinitely();
    }

    @Test
    void createNetworkSucceeds() {
        var network = client.createNetwork("my-net").await().indefinitely();
        assertThat(network.name()).isEqualTo("ops-net");
    }

    @Test
    void createVolumeSucceeds() {
        var volume = client.createVolume("my-vol", "local").await().indefinitely();
        assertThat(volume.name()).isEqualTo("new-vol");
    }

}
