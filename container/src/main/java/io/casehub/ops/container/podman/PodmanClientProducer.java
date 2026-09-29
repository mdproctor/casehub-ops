package io.casehub.ops.container.podman;

import io.vertx.core.Vertx;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PodmanClientProducer {

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "casehub.container.podman.socket",
                     defaultValue = "/var/run/podman/podman.sock")
    String socketPath;

    @Produces
    @ApplicationScoped
    @Podman
    SocketAddress podmanSocketAddress() {
        return SocketAddress.domainSocketAddress(socketPath);
    }

    @Produces
    @ApplicationScoped
    @Podman
    WebClient podmanWebClient() {
        return WebClient.create(vertx);
    }
}
