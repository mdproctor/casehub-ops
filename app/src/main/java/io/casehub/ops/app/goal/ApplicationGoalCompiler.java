package io.casehub.ops.app.goal;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ServiceType;
import io.casehub.ops.app.model.ServiceDefinition;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ApplicationGoalCompiler {

    public DesiredStateGraph compileForCluster(List<ServiceDefinition> services,
                                               String clusterId,
                                               String namespace,
                                               DesiredStateGraphFactory factory) {
        String backendId = "kubernetes:" + clusterId;
        Labels appLabels = Labels.of(Map.of("managed-by", "casehub-ops"));

        List<DesiredNode> nodes        = new ArrayList<>();
        List<Dependency>  dependencies = new ArrayList<>();

        NodeId nsNodeId = NodeId.of(clusterId + ":namespace");
        nodes.add(new DesiredNode(nsNodeId, ApplicationNodeTypes.K8S_NAMESPACE,
                                  new InfraDesiredNodeSpec(new K8sNamespaceSpec(namespace, appLabels), backendId),
                                  false));

        List<ServiceDefinition> clusterServices = services.stream()
                                                          .filter(sd -> sd.targetClusters().isEmpty() || sd.targetClusters().contains(clusterId))
                                                          .toList();

        Map<String, NodeId> deploymentNodeIds = clusterServices.stream()
                                                               .collect(Collectors.toMap(
                                                                       ServiceDefinition::serviceId,
                                                                       sd -> NodeId.of(clusterId + ":" + sd.serviceId() + ":deployment")));

        for (ServiceDefinition sd : clusterServices) {
            Labels svcLabels      = Labels.of(Map.of("app", sd.serviceId(), "managed-by", "casehub-ops"));
            Labels selectorLabels = Labels.of(Map.of("app", sd.serviceId()));

            NodeId deployId = deploymentNodeIds.get(sd.serviceId());
            var deploySpec = new K8sDeploymentSpec(
                    namespace, sd.serviceId(), sd.image(), sd.replicas(),
                    sd.resources(), svcLabels, sd.ports(), sd.env(), sd.healthCheck());
            nodes.add(new DesiredNode(deployId, ApplicationNodeTypes.K8S_DEPLOYMENT,
                                      new InfraDesiredNodeSpec(deploySpec, backendId), false));
            dependencies.add(new Dependency(deployId, nsNodeId));

            if (!sd.ports().isEmpty()) {
                var    firstPort = sd.ports().get(0);
                NodeId svcId     = NodeId.of(clusterId + ":" + sd.serviceId() + ":service");
                var svcSpec = new K8sServiceSpec(
                        namespace, sd.serviceId(),
                        firstPort.servicePort(), firstPort.containerPort(),
                        ServiceType.CLUSTER_IP, svcLabels, selectorLabels);
                nodes.add(new DesiredNode(svcId, ApplicationNodeTypes.K8S_SERVICE,
                                          new InfraDesiredNodeSpec(svcSpec, backendId), false));
                dependencies.add(new Dependency(svcId, deployId));
            }

            for (String depServiceId : sd.dependsOn()) {
                NodeId depDeployId = deploymentNodeIds.get(depServiceId);
                if (depDeployId != null) {
                    dependencies.add(new Dependency(deployId, depDeployId));
                }
            }
        }

        return factory.of(nodes, dependencies);}
}
