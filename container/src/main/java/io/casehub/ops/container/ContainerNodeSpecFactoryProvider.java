package io.casehub.ops.container;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.NodeSpecFactory;
import io.casehub.desiredstate.api.NodeSpecFactoryProvider;
import io.casehub.desiredstate.api.NodeTypeId;

import java.util.HashMap;
import java.util.Map;

public class ContainerNodeSpecFactoryProvider implements NodeSpecFactoryProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Map<String, NodeSpecFactory> provide() {
        Map<String, NodeSpecFactory> factories = new HashMap<>();
        for (Class<?> permit : ContainerNodeSpec.class.getPermittedSubclasses()) {
            NodeTypeId ann = permit.getAnnotation(NodeTypeId.class);
            if (ann != null) {
                @SuppressWarnings("unchecked")
                var specClass = (Class<? extends ContainerNodeSpec>) permit;
                factories.put(ann.value(), specMap -> MAPPER.convertValue(specMap, specClass));
            }
        }
        return factories;
    }
}
