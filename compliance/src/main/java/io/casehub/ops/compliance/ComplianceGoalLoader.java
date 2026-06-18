package io.casehub.ops.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.ops.api.compliance.ComplianceGoalEntry;
import io.casehub.ops.api.compliance.ComplianceGoals;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.stream.Stream;

@ApplicationScoped
public class ComplianceGoalLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public ComplianceGoals load(String path) {
        try (InputStream stream = resolveStream(path)) {
            return yamlMapper.readValue(stream, ComplianceGoals.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse compliance YAML: " + path, e);
        }
    }

    public ComplianceGoals loadDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + directoryPath);
        }
        var fragments = new ArrayList<ComplianceGoals>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".yaml") || name.endsWith(".yml");
            }).sorted().forEach(p -> {
                try {
                    fragments.add(yamlMapper.readValue(p.toFile(), ComplianceGoals.class));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to parse: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list directory: " + directoryPath, e);
        }
        return merge(fragments.toArray(new ComplianceGoals[0]));
    }

    public ComplianceGoals merge(ComplianceGoals... fragments) {
        var controls = new ArrayList<ComplianceGoalEntry>();
        for (var f : fragments) {
            controls.addAll(f.controls());
        }
        return new ComplianceGoals(controls);
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
        throw new IllegalArgumentException("Compliance YAML not found: " + path);
    }
}
