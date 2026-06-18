package io.casehub.ops.api.compliance;

public sealed interface EvidenceResult {
    String detail();
    record Pass(String detail) implements EvidenceResult {}
    record Fail(String detail) implements EvidenceResult {}
    record Unavailable(String detail) implements EvidenceResult {}
}
