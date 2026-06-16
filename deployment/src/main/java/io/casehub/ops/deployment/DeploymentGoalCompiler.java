package io.casehub.ops.deployment;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.api.deployment.DeploymentNodeSpec;
import io.casehub.ops.api.deployment.GoalEntry;

@ApplicationScoped
public class DeploymentGoalCompiler implements GoalCompiler<DeploymentGoals> {

    @Override
    public DesiredStateGraph compile(DeploymentGoals goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        compileEntries(goals.agents(), nodes, dependencies);
        compileEntries(goals.channels(), nodes, dependencies);
        compileEntries(goals.caseTypes(), nodes, dependencies);
        compileEntries(goals.trust(), nodes, dependencies);

        return factory.of(nodes, dependencies);
    }

    private <S extends DeploymentNodeSpec> void compileEntries(
            List<GoalEntry<S>> entries, List<DesiredNode> nodes, List<Dependency> deps) {
        for (var entry : entries) {
            var spec = entry.spec();
            nodes.add(new DesiredNode(
                    NodeId.of(spec.nodeId()), NodeType.of(spec.nodeType()), spec, false));
            for (String dep : entry.dependsOn()) {
                deps.add(new Dependency(NodeId.of(spec.nodeId()), NodeId.of(dep)));
            }
        }
    }
}
