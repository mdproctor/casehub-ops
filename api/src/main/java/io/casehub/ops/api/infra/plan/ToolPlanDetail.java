package io.casehub.ops.api.infra.plan;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface ToolPlanDetail
        permits ToolPlanDetail.TerraformPlanDetail, ToolPlanDetail.AnsibleCheckDetail, ToolPlanDetail.StandaloneDiffDetail {

    record TerraformPlanDetail(JsonNode planJson) implements ToolPlanDetail {}

    record AnsibleCheckDetail(String checkOutput) implements ToolPlanDetail {}

    record StandaloneDiffDetail(List<FieldDiff> diffs) implements ToolPlanDetail {
        public StandaloneDiffDetail {
            diffs = List.copyOf(diffs);
        }
    }
}
