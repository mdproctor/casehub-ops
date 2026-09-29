package io.casehub.ops.container;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ContainerGoalCompiler implements GoalCompiler<DeploymentDescriptor> {

    @Override
    public CompilationResult compile(DeploymentDescriptor goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        var netId = NodeId.of(goals.appName() + "-net");
        nodes.add(new DesiredNode(netId, new NetworkSpec(goals.appName() + "-net"), HumanGating.NONE));

        var volId = NodeId.of(goals.appName() + "-db-data");
        nodes.add(new DesiredNode(volId, new VolumeSpec(goals.appName() + "-db-data", "local"), HumanGating.NONE));

        var dbId = NodeId.of(goals.appName() + "-db");
        nodes.add(new DesiredNode(dbId, new DatabaseContainerSpec(
            goals.appName() + "-db", null, goals.databaseName(),
            goals.appName(), goals.appName(), goals.appName() + "-db-data"), HumanGating.NONE));
        dependencies.add(new Dependency(dbId, netId));
        dependencies.add(new Dependency(dbId, volId));

        var appId = NodeId.of(goals.appName());
        var appEnv = new HashMap<>(goals.env());
        appEnv.put("QUARKUS_DATASOURCE_JDBC_URL",
            "jdbc:postgresql://" + goals.appName() + "-db:5432/" + goals.databaseName());
        appEnv.put("QUARKUS_DATASOURCE_USERNAME", goals.appName());
        appEnv.put("QUARKUS_DATASOURCE_PASSWORD", goals.appName());
        nodes.add(new DesiredNode(appId, new AppContainerSpec(
            goals.appName(), goals.image(), goals.ports(), appEnv, java.util.Map.of()), HumanGating.NONE));
        dependencies.add(new Dependency(appId, dbId));
        dependencies.add(new Dependency(appId, netId));

        return CompilationResult.single(factory.of(nodes, dependencies));
    }
}
