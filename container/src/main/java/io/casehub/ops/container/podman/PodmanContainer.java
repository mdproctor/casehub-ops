package io.casehub.ops.container.podman;

import java.util.List;

public record PodmanContainer(String id, List<String> names, String state, String image) {}
