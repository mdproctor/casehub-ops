package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeSpec;

public sealed interface ContainerNodeSpec extends NodeSpec
    permits AppContainerSpec, DatabaseContainerSpec, NetworkSpec, VolumeSpec {
}
