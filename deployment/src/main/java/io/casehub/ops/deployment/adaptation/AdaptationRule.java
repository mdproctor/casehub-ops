package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.deployment.AdaptationActionSpec;
import io.casehub.ops.api.deployment.AdaptationRuleSpec;
import io.casehub.ops.deployment.DeploymentGoalCompiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime wrapper for AdaptationRuleSpec that applies adaptation actions to a graph.
 * <p>
 * Each rule contains one or more actions (scale, add, update) that execute in sequence
 * when the rule is applied. Actions mutate the graph based on the active situation's confidence.
 */
public final class AdaptationRule {

    private final AdaptationRuleSpec spec;
    private final List<Object> actions;
    private final DeploymentGoalCompiler compiler;
    private final ObjectMapper mapper;
    private final DesiredStateGraphFactory factory;

    private AdaptationRule(
            AdaptationRuleSpec spec,
            List<Object> actions,
            DeploymentGoalCompiler compiler,
            ObjectMapper mapper,
            DesiredStateGraphFactory factory) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.actions = List.copyOf(actions);
        this.compiler = compiler;
        this.mapper = mapper;
        this.factory = factory;
    }

    /**
     * Creates AdaptationRule instances from specs.
     */
    public static List<AdaptationRule> fromSpecs(
            List<AdaptationRuleSpec> specs,
            DeploymentGoalCompiler compiler,
            ObjectMapper mapper,
            DesiredStateGraphFactory factory) {
        Objects.requireNonNull(specs, "specs");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(factory, "factory");

        List<AdaptationRule> rules = new ArrayList<>();
        for (var spec : specs) {
            List<Object> actions = new ArrayList<>();
            for (var actionSpec : spec.actions()) {
                Object action = switch (actionSpec) {
                    case AdaptationActionSpec.ScaleActionSpec s -> new ScaleAction(s);
                    case AdaptationActionSpec.UpdateActionSpec u -> new UpdateAction(u);
                    case AdaptationActionSpec.AddActionSpec a -> new AddAction(a, compiler, factory);
                };
                actions.add(action);
            }
            rules.add(new AdaptationRule(spec, actions, compiler, mapper, factory));
        }
        return rules;
    }

    /**
     * Returns the rule name.
     */
    public String name() {
        return spec.name();
    }

    /**
     * Returns the trigger configuration.
     */
    public io.casehub.ops.api.deployment.AdaptationTrigger trigger() {
        return spec.trigger();
    }

    /**
     * Applies all actions in sequence, returning the mutated graph.
     */
    public DesiredStateGraph apply(DesiredStateGraph graph, ActiveSituation situation) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(situation, "situation");

        DesiredStateGraph result = graph;
        double minConfidence = spec.trigger().minConfidence();

        for (var action : actions) {
            result = switch (action) {
                case ScaleAction s -> s.apply(result, situation, minConfidence);
                case UpdateAction u -> u.apply(result, mapper);
                case AddAction a -> a.apply(result);
                default -> throw new IllegalStateException("Unknown action type: " + action.getClass());
            };
        }

        return result;
    }

    /**
     * Returns the set of node IDs that this rule targets.
     * <p>
     * For scale actions: the base target node.
     * For update actions: the target node.
     * For add actions: all nodes in the inline goals.
     */
    public Set<NodeId> targetNodeIds(DesiredStateGraph graph) {
        Objects.requireNonNull(graph, "graph");

        Set<NodeId> targets = new HashSet<>();

        for (var actionSpec : spec.actions()) {
            switch (actionSpec) {
                case AdaptationActionSpec.ScaleActionSpec s -> {
                    NodeId baseId = NodeId.of(s.target());
                    if (graph.nodes().containsKey(baseId)) {
                        targets.add(baseId);
                    }
                }
                case AdaptationActionSpec.UpdateActionSpec u -> {
                    NodeId targetId = NodeId.of(u.target());
                    if (graph.nodes().containsKey(targetId)) {
                        targets.add(targetId);
                    }
                }
                case AdaptationActionSpec.AddActionSpec a -> {
                    DesiredStateGraph subGraph = ((CompilationResult.SingleGraph) compiler.compile(a.nodes(), factory)).graph();
                    targets.addAll(subGraph.nodes().keySet());
                }
            }
        }

        return targets;
    }
}
