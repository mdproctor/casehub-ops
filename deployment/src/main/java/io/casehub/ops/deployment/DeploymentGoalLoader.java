package io.casehub.ops.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.ops.api.deployment.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class DeploymentGoalLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public DeploymentGoals load(String path) {
        try (InputStream stream = resolveStream(path)) {
            return yamlMapper.readValue(stream, DeploymentGoals.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse deployment YAML: " + path, e);
        }
    }

    public DeploymentGoals loadDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + directoryPath);
        }
        var fragments = new ArrayList<DeploymentGoals>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".yaml") || name.endsWith(".yml");
            }).sorted().forEach(p -> {
                try {
                    fragments.add(yamlMapper.readValue(p.toFile(), DeploymentGoals.class));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to parse: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list directory: " + directoryPath, e);
        }
        return merge(fragments.toArray(new DeploymentGoals[0]));
    }

    public DeploymentGoals merge(DeploymentGoals... fragments) {
        var agents = new ArrayList<GoalEntry<AgentNodeSpec>>();
        var channels = new ArrayList<GoalEntry<ChannelNodeSpec>>();
        var caseTypes = new ArrayList<GoalEntry<CaseTypeNodeSpec>>();
        var trust = new ArrayList<GoalEntry<TrustPolicyNodeSpec>>();
        var endpoints = new ArrayList<GoalEntry<EndpointNodeSpec>>();
        for (var f : fragments) {
            agents.addAll(f.agents());
            channels.addAll(f.channels());
            caseTypes.addAll(f.caseTypes());
            trust.addAll(f.trust());
            endpoints.addAll(f.endpoints());
        }
        return new DeploymentGoals(agents, channels, caseTypes, trust, endpoints);
    }

    private InputStream resolveStream(String path) {
        InputStream classpath = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path);
        if (classpath != null) return classpath;
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            try {
                return Files.newInputStream(filePath);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read file: " + path, e);
            }
        }
        throw new IllegalArgumentException("Deployment YAML not found: " + path);
    }
}
