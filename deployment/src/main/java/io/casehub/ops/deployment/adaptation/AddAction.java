package io.casehub.ops.deployment.adaptation;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.ops.api.deployment.AdaptationActionSpec.AddActionSpec;
import io.casehub.ops.deployment.DeploymentGoalCompiler;

import java.util.Objects;

/**
 * Adds nodes to the graph by compiling inline DeploymentGoals and overlaying them.
 * <p>
 * Delegates to DeploymentGoalCompiler to convert the DeploymentGoals into a sub-graph,
 * then overlays that sub-graph onto the main graph.
 */
final class AddAction {

    private final AddActionSpec spec;
    private final DeploymentGoalCompiler compiler;
    private final DesiredStateGraphFactory factory;

    AddAction(AddActionSpec spec, DeploymentGoalCompiler compiler, DesiredStateGraphFactory factory) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    DesiredStateGraph apply(DesiredStateGraph graph) {
        DesiredStateGraph subGraph = compiler.compile(spec.nodes(), factory);
        return graph.overlay(subGraph);
    }
}
