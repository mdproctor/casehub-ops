package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ComplianceGoalCompiler implements GoalCompiler<ComplianceGoals> {

    @Override
    public CompilationResult compile(ComplianceGoals goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        for (var entry : goals.controls()) {
            ComplianceControlSpec spec = entry.spec();
            nodes.add(new DesiredNode(
                    NodeId.of(spec.controlId()),
                    NodeType.of(spec.controlType()),
                    spec, spec.requiresHumanReview() ? HumanGating.ALL : HumanGating.NONE));

            for (String depId : entry.dependsOn()) {
                deps.add(new Dependency(NodeId.of(spec.controlId()), NodeId.of(depId)));
            }
        }
        return CompilationResult.single(factory.of(nodes, deps));
    }
}
