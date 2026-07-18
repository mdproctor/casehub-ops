package io.casehub.ops.app.service;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ops.app.model.ServiceDefinition;

public final class ServiceDefinitionParser {

    private static final TypeReference<List<ServiceDefinition>> TYPE_REF = new TypeReference<>() {};

    private ServiceDefinitionParser() {}

    public static List<ServiceDefinition> parse(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid services JSON", e);
        }
    }
}
