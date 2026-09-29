package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerNodeSpecTest {

    @Test
    void appContainerSpecHasCorrectNodeType() {
        var spec = new AppContainerSpec("my-app", "casehub/scaffold:latest",
            Map.of(8080, 8080), Map.of("QUARKUS_PROFILE", "prod"), Map.of());
        assertThat(spec.nodeType()).isEqualTo(ContainerNodeTypes.APP_CONTAINER);
        assertThat(spec).isInstanceOf(ContainerNodeSpec.class);
        assertThat(spec).isInstanceOf(NodeSpec.class);
    }

    @Test
    void appContainerSpecDefensiveCopies() {
        var ports = new java.util.HashMap<>(Map.of(8080, 8080));
        var env = new java.util.HashMap<>(Map.of("K", "V"));
        var mounts = new java.util.HashMap<>(Map.of("/host", "/container"));
        var spec = new AppContainerSpec("app", "img:1", ports, env, mounts);

        ports.put(9090, 9090);
        env.put("K2", "V2");
        mounts.put("/other", "/path");

        assertThat(spec.ports()).hasSize(1);
        assertThat(spec.env()).hasSize(1);
        assertThat(spec.volumeMounts()).hasSize(1);
    }

    @Test
    void databaseContainerSpecDefaultsImage() {
        var spec = new DatabaseContainerSpec("my-db", null, "testdb", "admin", "secret", "db-data");
        assertThat(spec.nodeType()).isEqualTo(ContainerNodeTypes.DATABASE);
        assertThat(spec.image()).isEqualTo("postgres:16");
    }

    @Test
    void databaseContainerSpecPreservesExplicitImage() {
        var spec = new DatabaseContainerSpec("my-db", "postgres:15", "testdb", "admin", "secret", "db-data");
        assertThat(spec.image()).isEqualTo("postgres:15");
    }

    @Test
    void networkSpecHasCorrectType() {
        var spec = new NetworkSpec("ops-net");
        assertThat(spec.nodeType()).isEqualTo(ContainerNodeTypes.NETWORK);
        assertThat(spec).isInstanceOf(ContainerNodeSpec.class);
    }

    @Test
    void volumeSpecHasCorrectType() {
        var spec = new VolumeSpec("data-vol", "local");
        assertThat(spec.nodeType()).isEqualTo(ContainerNodeTypes.VOLUME);
        assertThat(spec).isInstanceOf(ContainerNodeSpec.class);
    }

    @Test
    void volumeSpecDefaultsDriver() {
        var spec = new VolumeSpec("data-vol", null);
        assertThat(spec.driver()).isEqualTo("local");
    }

    @Test
    void nodeTypesAreDistinct() {
        assertThat(ContainerNodeTypes.APP_CONTAINER)
            .isNotEqualTo(ContainerNodeTypes.DATABASE)
            .isNotEqualTo(ContainerNodeTypes.NETWORK)
            .isNotEqualTo(ContainerNodeTypes.VOLUME);
    }

    @Test
    void sealedInterfacePermitsOnlyExpectedTypes() {
        var permitted = ContainerNodeSpec.class.getPermittedSubclasses();
        assertThat(permitted).hasSize(4);
        assertThat(permitted).containsExactlyInAnyOrder(
            AppContainerSpec.class, DatabaseContainerSpec.class,
            NetworkSpec.class, VolumeSpec.class);
    }
}
