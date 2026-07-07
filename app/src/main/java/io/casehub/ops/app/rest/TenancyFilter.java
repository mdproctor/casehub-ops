package io.casehub.ops.app.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

@Provider
@PreMatching
public class TenancyFilter implements ContainerRequestFilter {

    public static final String TENANCY_HEADER = "X-Tenancy-ID";
    public static final String TENANCY_PROPERTY = "casehub.tenancyId";
    public static final String DEFAULT_TENANCY = "default";

    @Override
    public void filter(ContainerRequestContext ctx) {
        String tenancyId = ctx.getHeaderString(TENANCY_HEADER);
        if (tenancyId == null || tenancyId.isBlank()) {
            tenancyId = DEFAULT_TENANCY;
        }
        ctx.setProperty(TENANCY_PROPERTY, tenancyId);
    }
}
