package io.casehub.ops.app.goal;

import io.casehub.desiredstate.api.NodeType;

public final class ApplicationNodeTypes {
    public static final NodeType K8S_NAMESPACE = NodeType.of("k8s_namespace");
    public static final NodeType K8S_DEPLOYMENT = NodeType.of("k8s_deployment");
    public static final NodeType K8S_SERVICE = NodeType.of("k8s_service");
    public static final NodeType K8S_INGRESS = NodeType.of("k8s_ingress");
    public static final NodeType K8S_CONFIGMAP = NodeType.of("k8s_configmap");

    private ApplicationNodeTypes() {}
}
