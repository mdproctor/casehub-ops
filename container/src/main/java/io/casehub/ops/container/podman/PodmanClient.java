package io.casehub.ops.container.podman;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.vertx.core.parsetools.JsonEventType;
import io.vertx.core.parsetools.JsonParser;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PodmanClient {

    private static final String API_VERSION = "/v5.0.0/libpod";

    private final WebClient webClient;
    private final SocketAddress socketAddress;

    @Inject
    public PodmanClient(@Podman WebClient webClient, @Podman SocketAddress socketAddress) {
        this.webClient = webClient;
        this.socketAddress = socketAddress;
    }

    private HttpRequest<Buffer> get(String path) {
        return webClient.request(HttpMethod.GET, socketAddress, 80, "localhost", API_VERSION + path);
    }

    private HttpRequest<Buffer> post(String path) {
        return webClient.request(HttpMethod.POST, socketAddress, 80, "localhost", API_VERSION + path);
    }

    private HttpRequest<Buffer> delete(String path) {
        return webClient.request(HttpMethod.DELETE, socketAddress, 80, "localhost", API_VERSION + path);
    }

    public Uni<List<PodmanContainer>> listContainers() {
        return toUni(get("/containers/json")
            .addQueryParam("all", "true")
            .send())
            .map(resp -> {
                var array = new JsonArray(resp.bodyAsString());
                var result = new ArrayList<PodmanContainer>();
                for (int i = 0; i < array.size(); i++) {
                    var obj = array.getJsonObject(i);
                    var names = new ArrayList<String>();
                    var namesArr = obj.getJsonArray("Names");
                    if (namesArr != null) {
                        for (int j = 0; j < namesArr.size(); j++) {
                            names.add(namesArr.getString(j));
                        }
                    }
                    result.add(new PodmanContainer(
                        obj.getString("Id"),
                        List.copyOf(names),
                        obj.getString("State"),
                        obj.getString("Image")));
                }
                return List.copyOf(result);
            });
    }

    public Uni<List<PodmanNetwork>> listNetworks() {
        return toUni(get("/networks/json").send())
            .map(resp -> {
                var array = new JsonArray(resp.bodyAsString());
                var result = new ArrayList<PodmanNetwork>();
                for (int i = 0; i < array.size(); i++) {
                    var obj = array.getJsonObject(i);
                    result.add(new PodmanNetwork(obj.getString("Name"), obj.getString("Id")));
                }
                return List.copyOf(result);
            });
    }

    public Uni<List<PodmanVolume>> listVolumes() {
        return toUni(get("/volumes/json").send())
            .map(resp -> {
                var body = new JsonObject(resp.bodyAsString());
                var volumesArr = body.getJsonArray("Volumes");
                var result = new ArrayList<PodmanVolume>();
                if (volumesArr != null) {
                    for (int i = 0; i < volumesArr.size(); i++) {
                        var obj = volumesArr.getJsonObject(i);
                        result.add(new PodmanVolume(obj.getString("Name"), obj.getString("Driver")));
                    }
                }
                return List.copyOf(result);
            });
    }

    public Uni<String> createContainer(CreateContainerRequest request) {
        var body = new JsonObject()
            .put("name", request.name())
            .put("image", request.image());
        if (request.env() != null && !request.env().isEmpty()) {
            var envArray = new JsonArray();
            request.env().forEach((k, v) -> envArray.add(k + "=" + v));
            body.put("env", envArray);
        }
        if (request.portMappings() != null && !request.portMappings().isEmpty()) {
            var portsArray = new JsonArray();
            request.portMappings().forEach((container, host) ->
                portsArray.add(new JsonObject()
                    .put("container_port", container)
                    .put("host_port", host)));
            body.put("portmappings", portsArray);
        }
        return toUni(post("/containers/create").sendJsonObject(body))
            .map(resp -> new JsonObject(resp.bodyAsString()).getString("Id"));
    }

    public Uni<Void> startContainer(String id) {
        return toUni(post("/containers/" + id + "/start").send()).replaceWithVoid();
    }

    public Uni<Void> stopContainer(String id) {
        return toUni(post("/containers/" + id + "/stop").send()).replaceWithVoid();
    }

    public Uni<Void> removeContainer(String id) {
        return toUni(delete("/containers/" + id).addQueryParam("force", "true").send()).replaceWithVoid();
    }

    public Uni<PodmanNetwork> createNetwork(String name) {
        var body = new JsonObject().put("name", name);
        return toUni(post("/networks/create").sendJsonObject(body))
            .map(resp -> {
                var obj = new JsonObject(resp.bodyAsString());
                return new PodmanNetwork(obj.getString("Name"), obj.getString("Id"));
            });
    }

    public Uni<PodmanVolume> createVolume(String name, String driver) {
        var body = new JsonObject().put("Name", name).put("Driver", driver);
        return toUni(post("/volumes/create").sendJsonObject(body))
            .map(resp -> {
                var obj = new JsonObject(resp.bodyAsString());
                return new PodmanVolume(obj.getString("Name"), obj.getString("Driver"));
            });
    }

    public Uni<Void> pullImage(String image) {
        return toUni(post("/images/pull").addQueryParam("reference", image).send()).replaceWithVoid();
    }

    public Multi<JsonObject> events(String type) {
        return Multi.createFrom().<JsonObject>emitter(emitter -> {
            JsonParser parser = JsonParser.newParser()
                .objectValueMode()
                .handler(event -> {
                    if (event.type() == JsonEventType.VALUE) {
                        emitter.emit(event.objectValue());
                    }
                })
                .exceptionHandler(emitter::fail);

            webClient.request(HttpMethod.GET, socketAddress, 80, "localhost",
                    API_VERSION + "/events")
                .addQueryParam("stream", "true")
                .addQueryParam("type", type)
                .as(BodyCodec.jsonStream(parser))
                .send()
                .onFailure(emitter::fail);
        }, BackPressureStrategy.BUFFER);
    }

    private static <T> Uni<T> toUni(io.vertx.core.Future<T> future) {
        return Uni.createFrom().completionStage(future.toCompletionStage());
    }

    public record CreateContainerRequest(
        String name, String image,
        Map<Integer, Integer> portMappings,
        Map<String, String> env
    ) {}
}
