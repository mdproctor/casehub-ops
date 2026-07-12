package io.casehub.ops.infra;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.ops.api.infra.AnsiblePlaybookSpec;
import io.casehub.ops.api.infra.ComputeInstanceSpec;
import io.casehub.ops.api.infra.DatabaseClusterSpec;
import io.casehub.ops.api.infra.GenericResourceSpec;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sIngressSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.api.infra.TerraformWorkspaceSpec;
import io.casehub.ops.api.infra.goal.ImportDeclaration;
import io.casehub.ops.api.infra.goal.InfraGoals;
import io.casehub.ops.api.infra.goal.ResourceDeclaration;
import io.casehub.ops.api.infra.types.AnsibleExtraVars;
import io.casehub.ops.api.infra.types.AnsibleInventory;
import io.casehub.ops.api.infra.types.BackupConfig;
import io.casehub.ops.api.infra.types.CloudProvider;
import io.casehub.ops.api.infra.types.ClusterSize;
import io.casehub.ops.api.infra.types.DatabaseEngine;
import io.casehub.ops.api.infra.types.IngressRule;
import io.casehub.ops.api.infra.types.InstanceType;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.NetworkConfig;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ops.api.infra.types.ServiceType;
import io.casehub.ops.api.infra.types.TerraformBackendConfig;
import io.casehub.ops.api.infra.types.TerraformStateType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Compiles {@link InfraGoals} into a {@link DesiredStateGraph}.
 *
 * <p>For each resource/import declaration, the compiler:
 * <ol>
 *   <li>Maps the type string to a typed {@link InfraNodeSpec} via the YAML config JsonNode</li>
 *   <li>Resolves the backend: per-resource > default > "standalone"</li>
 *   <li>Validates type/backend compatibility</li>
 *   <li>Wraps in {@link InfraDesiredNodeSpec}</li>
 *   <li>Creates dependency edges from {@code dependsOn} lists</li>
 * </ol>
 */
@ApplicationScoped
public class InfraGoalCompiler implements GoalCompiler<InfraGoals> {

    private static final String STANDALONE_BACKEND = "standalone";

    @Override
    public CompilationResult compile(InfraGoals goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        for (ResourceDeclaration decl : goals.resources()) {
            String backendId = resolveBackend(decl.backend(), goals.defaultBackend());
            InfraNodeSpec resourceSpec = parseSpec(decl.type(), decl.config());
            validateBackendCompatibility(decl.type(), backendId);

            var wrapper = new InfraDesiredNodeSpec(resourceSpec, backendId);
            nodes.add(new DesiredNode(NodeId.of(decl.id()), NodeType.of(decl.type()), wrapper, false));

            for (String dep : decl.dependsOn()) {
                // decl.id() depends on dep: Dependency(from=dependent, to=dependency)
                dependencies.add(new Dependency(NodeId.of(decl.id()), NodeId.of(dep)));
            }
        }

        for (ImportDeclaration decl : goals.imports()) {
            String backendId = resolveImportBackend(decl.type(), goals.defaultBackend());
            InfraNodeSpec resourceSpec = parseSpec(decl.type(), decl.config());

            var wrapper = new InfraDesiredNodeSpec(resourceSpec, backendId);
            nodes.add(new DesiredNode(NodeId.of(decl.id()), NodeType.of(decl.type()), wrapper, false));

            for (String dep : decl.dependsOn()) {
                dependencies.add(new Dependency(NodeId.of(decl.id()), NodeId.of(dep)));
            }
        }

        return CompilationResult.single(factory.of(nodes, dependencies));
    }

    private String resolveBackend(String perResource, String defaultBackend) {
        if (perResource != null && !perResource.isBlank()) {
            return perResource;
        }
        if (defaultBackend != null && !defaultBackend.isBlank()) {
            return defaultBackend;
        }
        return STANDALONE_BACKEND;
    }

    private String resolveImportBackend(String type, String defaultBackend) {
        // Wrapping types have hardcoded backend routing
        return switch (type) {
            case "terraform_workspace" -> "terraform";
            case "ansible_playbook" -> "ansible";
            default -> defaultBackend != null && !defaultBackend.isBlank() ? defaultBackend : STANDALONE_BACKEND;
        };
    }

    private void validateBackendCompatibility(String type, String backendId) {
        if ("terraform_workspace".equals(type) && !"terraform".equals(backendId)) {
            throw new IllegalArgumentException(
                    "terraform_workspace requires 'terraform' backend, got '" + backendId + "'");
        }
        if ("ansible_playbook".equals(type) && !"ansible".equals(backendId)) {
            throw new IllegalArgumentException(
                    "ansible_playbook requires 'ansible' backend, got '" + backendId + "'");
        }
    }

    // --- spec parsing ---

    private InfraNodeSpec parseSpec(String type, JsonNode config) {
        return switch (type) {
            case "k8s_namespace" -> parseK8sNamespace(config);
            case "k8s_deployment" -> parseK8sDeployment(config);
            case "k8s_service" -> parseK8sService(config);
            case "k8s_ingress" -> parseK8sIngress(config);
            case "compute_instance" -> parseComputeInstance(config);
            case "database_cluster" -> parseDatabaseCluster(config);
            case "terraform_workspace" -> parseTerraformWorkspace(config);
            case "ansible_playbook" -> parseAnsiblePlaybook(config);
            default -> new GenericResourceSpec(type, config);
        };
    }

    private K8sNamespaceSpec parseK8sNamespace(JsonNode config) {
        String name = requireText(config, "name", "k8s_namespace");
        Labels labels = parseLabels(config.get("labels"));
        return new K8sNamespaceSpec(name, labels);
    }

    private K8sDeploymentSpec parseK8sDeployment(JsonNode config) {
        String namespace = requireText(config, "namespace", "k8s_deployment");
        String name = requireText(config, "name", "k8s_deployment");
        String image = requireText(config, "image", "k8s_deployment");
        int replicas = config.has("replicas") ? config.get("replicas").asInt() : 1;
        ResourceRequirements resources = parseResourceRequirements(config.get("resources"));
        Labels labels = parseLabels(config.get("labels"));
        return new K8sDeploymentSpec(namespace, name, image, replicas, resources, labels);
    }

    private K8sServiceSpec parseK8sService(JsonNode config) {
        String namespace  = requireText(config, "namespace", "k8s_service");
        String name       = requireText(config, "name", "k8s_service");
        int    port       = config.has("port") ? config.get("port").asInt() : 80;
        int    targetPort = config.has("targetPort") ? config.get("targetPort").asInt() : port;
        ServiceType serviceType = config.has("serviceType")
                                  ? ServiceType.valueOf(config.get("serviceType").asText())
                                  : ServiceType.CLUSTER_IP;
        Labels labels   = parseLabels(config.get("labels"));
        Labels selector = config.has("selector") ? parseLabels(config.get("selector")) : labels;
        return new K8sServiceSpec(namespace, name, port, targetPort, serviceType, labels, selector);}

    private K8sIngressSpec parseK8sIngress(JsonNode config) {
        String namespace = requireText(config, "namespace", "k8s_ingress");
        String name = requireText(config, "name", "k8s_ingress");
        String host = requireText(config, "host", "k8s_ingress");
        List<IngressRule> rules = parseIngressRules(config.get("rules"));
        Labels labels = parseLabels(config.get("labels"));
        return new K8sIngressSpec(namespace, name, host, rules, labels);
    }

    private ComputeInstanceSpec parseComputeInstance(JsonNode config) {
        CloudProvider provider = CloudProvider.valueOf(requireText(config, "provider", "compute_instance"));
        String region = requireText(config, "region", "compute_instance");
        JsonNode instanceTypeNode = config.get("instanceType");
        if (instanceTypeNode == null) {
            throw new IllegalArgumentException("compute_instance requires 'instanceType'");
        }
        InstanceType instanceType = new InstanceType(
                requireText(instanceTypeNode, "family", "compute_instance.instanceType"),
                requireText(instanceTypeNode, "size", "compute_instance.instanceType"));
        String imageId = requireText(config, "imageId", "compute_instance");
        JsonNode networkNode = config.get("network");
        if (networkNode == null) {
            throw new IllegalArgumentException("compute_instance requires 'network'");
        }
        NetworkConfig network = new NetworkConfig(
                requireText(networkNode, "vpcId", "compute_instance.network"),
                requireText(networkNode, "subnetId", "compute_instance.network"),
                parseStringList(networkNode.get("securityGroups")));
        return new ComputeInstanceSpec(provider, region, instanceType, imageId, network);
    }

    private DatabaseClusterSpec parseDatabaseCluster(JsonNode config) {
        DatabaseEngine engine = DatabaseEngine.valueOf(requireText(config, "engine", "database_cluster"));
        String version = requireText(config, "version", "database_cluster");
        JsonNode sizeNode = config.get("size");
        if (sizeNode == null) {
            throw new IllegalArgumentException("database_cluster requires 'size'");
        }
        ClusterSize size = new ClusterSize(
                sizeNode.has("nodes") ? sizeNode.get("nodes").asInt() : 1,
                requireText(sizeNode, "storageClass", "database_cluster.size"));
        String region = requireText(config, "region", "database_cluster");
        JsonNode backupNode = config.get("backup");
        if (backupNode == null) {
            throw new IllegalArgumentException("database_cluster requires 'backup'");
        }
        BackupConfig backup = new BackupConfig(
                backupNode.has("enabled") && backupNode.get("enabled").asBoolean(),
                backupNode.has("retentionDays") ? backupNode.get("retentionDays").asInt() : 7,
                requireText(backupNode, "schedule", "database_cluster.backup"));
        return new DatabaseClusterSpec(engine, version, size, region, backup);
    }

    private TerraformWorkspaceSpec parseTerraformWorkspace(JsonNode config) {
        String workspacePath = requireText(config, "workspacePath", "terraform_workspace");
        JsonNode stateNode = config.get("state");
        if (stateNode == null) {
            throw new IllegalArgumentException("terraform_workspace requires 'state'");
        }
        TerraformStateType stateType = TerraformStateType.valueOf(
                requireText(stateNode, "type", "terraform_workspace.state"));
        String bucket = stateNode.has("bucket") ? stateNode.get("bucket").asText() : null;
        String key = stateNode.has("key") ? stateNode.get("key").asText() : null;
        TerraformBackendConfig state = new TerraformBackendConfig(stateType, bucket, key);
        return new TerraformWorkspaceSpec(workspacePath, state);
    }

    private AnsiblePlaybookSpec parseAnsiblePlaybook(JsonNode config) {
        String playbookPath = requireText(config, "playbookPath", "ansible_playbook");
        JsonNode inventoryNode = config.get("inventory");
        if (inventoryNode == null) {
            throw new IllegalArgumentException("ansible_playbook requires 'inventory'");
        }
        AnsibleInventory inventory = new AnsibleInventory(
                requireText(inventoryNode, "path", "ansible_playbook.inventory"),
                requireText(inventoryNode, "hostGroup", "ansible_playbook.inventory"));
        AnsibleExtraVars extraVars = parseExtraVars(config.get("extraVars"));
        return new AnsiblePlaybookSpec(playbookPath, inventory, extraVars);
    }

    // --- parsing helpers ---

    private String requireText(JsonNode node, String field, String context) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            throw new IllegalArgumentException(context + " requires '" + field + "'");
        }
        return node.get(field).asText();
    }

    private Labels parseLabels(JsonNode labelsNode) {
        if (labelsNode == null || labelsNode.isNull() || labelsNode.isEmpty()) {
            return Labels.empty();
        }
        Map<String, String> map = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = labelsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return Labels.of(map);
    }

    private ResourceRequirements parseResourceRequirements(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("k8s_deployment requires 'resources'");
        }
        return new ResourceRequirements(
                requireText(node, "cpuRequest", "resources"),
                requireText(node, "cpuLimit", "resources"),
                requireText(node, "memoryRequest", "resources"),
                requireText(node, "memoryLimit", "resources"));
    }

    private List<IngressRule> parseIngressRules(JsonNode rulesNode) {
        if (rulesNode == null || !rulesNode.isArray()) {
            return List.of();
        }
        List<IngressRule> rules = new ArrayList<>();
        for (JsonNode ruleNode : rulesNode) {
            rules.add(new IngressRule(
                    requireText(ruleNode, "path", "ingress rule"),
                    requireText(ruleNode, "serviceName", "ingress rule"),
                    ruleNode.has("servicePort") ? ruleNode.get("servicePort").asInt() : 80));
        }
        return rules;
    }

    private List<String> parseStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            result.add(element.asText());
        }
        return result;
    }

    private AnsibleExtraVars parseExtraVars(JsonNode node) {
        if (node == null || node.isNull() || node.isEmpty()) {
            return AnsibleExtraVars.empty();
        }
        Map<String, String> map = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return new AnsibleExtraVars(map);
    }
}
