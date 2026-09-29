package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeSpecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerNodeSpecFactoryProviderTest {

    private Map<String, NodeSpecFactory> factories;

    @BeforeEach
    void setUp() {
        factories = new ContainerNodeSpecFactoryProvider().provide();
    }

    @Test
    void registersAllFourContainerTypes() {
        assertThat(factories).containsOnlyKeys(
            "container:app", "container:database", "container:network", "container:volume");
    }

    @Test
    void appContainerFactoryHydratesFromMap() {
        var spec = factories.get("container:app").create(Map.of(
            "name", "my-app",
            "image", "img:1",
            "ports", Map.of(8080, 18080),
            "env", Map.of("K", "V")));

        assertThat(spec).isInstanceOf(AppContainerSpec.class);
        var app = (AppContainerSpec) spec;
        assertThat(app.name()).isEqualTo("my-app");
        assertThat(app.image()).isEqualTo("img:1");
        assertThat(app.ports()).containsEntry(8080, 18080);
        assertThat(app.env()).containsEntry("K", "V");
    }

    @Test
    void databaseFactoryHydratesFromMap() {
        var spec = factories.get("container:database").create(Map.of(
            "name", "my-db",
            "databaseName", "testdb",
            "username", "admin",
            "password", "secret",
            "dataVolume", "db-data"));

        assertThat(spec).isInstanceOf(DatabaseContainerSpec.class);
        var db = (DatabaseContainerSpec) spec;
        assertThat(db.name()).isEqualTo("my-db");
        assertThat(db.databaseName()).isEqualTo("testdb");
        assertThat(db.image()).isEqualTo("postgres:16");
    }

    @Test
    void networkFactoryHydratesFromMap() {
        var spec = factories.get("container:network").create(Map.of("name", "my-net"));

        assertThat(spec).isInstanceOf(NetworkSpec.class);
        assertThat(((NetworkSpec) spec).name()).isEqualTo("my-net");
    }

    @Test
    void volumeFactoryHydratesFromMap() {
        var spec = factories.get("container:volume").create(Map.of(
            "name", "my-vol", "driver", "local"));

        assertThat(spec).isInstanceOf(VolumeSpec.class);
        var vol = (VolumeSpec) spec;
        assertThat(vol.name()).isEqualTo("my-vol");
        assertThat(vol.driver()).isEqualTo("local");
    }
}
