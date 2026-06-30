package io.casehub.ops.deployment;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentProvisionerConfigRegistryCdiTest {

    @Test
    void hasApplicationScopedAnnotation() {
        assertThat(DeploymentProvisionerConfigRegistry.class)
            .hasAnnotation(ApplicationScoped.class);
    }

    @Test
    void implementsProvisionerConfigRegistry() {
        assertThat(ProvisionerConfigRegistry.class)
            .isAssignableFrom(DeploymentProvisionerConfigRegistry.class);
    }

    @Test
    void hasInjectableConstructor() {
        var constructors = DeploymentProvisionerConfigRegistry.class.getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes())
            .containsExactly(DeploymentProviderConfigStore.class);
    }
}
