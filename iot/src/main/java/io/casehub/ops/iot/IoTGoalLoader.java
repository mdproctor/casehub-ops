package io.casehub.ops.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class IoTGoalLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public IoTGoals load(String path) {
        try (InputStream is = resolveStream(path)) {
            return yamlMapper.readValue(is, IoTGoals.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load IoT topology from " + path, e);
        }
    }

    public IoTGoals loadDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        List<IoTGoals> fragments = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".yaml") || name.endsWith(".yml");
                })
                .sorted()
                .forEach(p -> fragments.add(load(p.toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list directory " + directoryPath, e);
        }
        if (fragments.isEmpty()) {
            throw new IllegalArgumentException("No YAML files found in " + directoryPath);
        }
        return merge(fragments.toArray(IoTGoals[]::new));
    }

    public static IoTGoals merge(IoTGoals... fragments) {
        if (fragments.length == 0) {
            throw new IllegalArgumentException("Cannot merge zero fragments");
        }
        var seen = new HashSet<String>();
        var merged = new ArrayList<IoTDeviceGoal>();
        String tenancyId = fragments[0].tenancyId();
        for (IoTGoals fragment : fragments) {
            if (!fragment.tenancyId().equals(tenancyId)) {
                throw new IllegalArgumentException(
                    "Inconsistent tenancyId in merge: expected " + tenancyId + ", found " + fragment.tenancyId());
            }
            for (IoTDeviceGoal device : fragment.devices()) {
                if (!seen.add(device.deviceId())) {
                    throw new IllegalArgumentException(
                        "Duplicate deviceId in merge: " + device.deviceId());
                }
                merged.add(device);
            }
        }
        return new IoTGoals(tenancyId, merged);
    }

    private InputStream resolveStream(String path) throws IOException {
        InputStream classpath = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(path);
        if (classpath != null) return classpath;
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) return Files.newInputStream(filePath);
        throw new IOException("Resource not found: " + path);
    }
}
