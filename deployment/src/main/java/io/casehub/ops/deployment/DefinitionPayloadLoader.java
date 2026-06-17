package io.casehub.ops.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@ApplicationScoped
public class DefinitionPayloadLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public Map<String, Object> load(String definitionFile) {
        try (InputStream stream = resolveStream(definitionFile)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = yamlMapper.readValue(stream, Map.class);
            return deepFreeze(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse definition file: " + definitionFile, e);
        }
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
        throw new IllegalArgumentException("Definition file not found on classpath or filesystem: " + path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepFreeze(Map<String, Object> map) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), freezeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private Object freezeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) return deepFreeze((Map<String, Object>) value);
        if (value instanceof List) return deepFreezeList((List<Object>) value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> deepFreezeList(List<Object> list) {
        var result = new ArrayList<Object>();
        for (var item : list) {
            result.add(freezeValue(item));
        }
        return Collections.unmodifiableList(result);
    }
}
