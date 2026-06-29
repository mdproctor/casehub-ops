package io.casehub.ops.api.deployment;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AdaptationRuleSpecTest {

    @Test
    void rejectsNullName() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        var action = new AdaptationActionSpec.ScaleActionSpec("target", 1, 5);
        assertThrows(NullPointerException.class, () ->
            new AdaptationRuleSpec(null, trigger, List.of(action)));
    }

    @Test
    void rejectsEmptyActions() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationRuleSpec("rule", trigger, List.of()));
    }

    @Test
    void triggerDeactivateBelowDefaultsToMinConfidence() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        assertEquals(0.7, trigger.effectiveDeactivateBelow());
    }

    @Test
    void triggerDeactivateBelowUsesExplicitValue() {
        var trigger = new AdaptationTrigger("sit", 0.7, 0.5, null);
        assertEquals(0.5, trigger.effectiveDeactivateBelow());
    }

    @Test
    void triggerRejectsMinConfidenceOfOne() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationTrigger("sit", 1.0, null, null));
    }

    @Test
    void triggerCooldownDefaultsToZero() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        assertEquals(Duration.ZERO, trigger.effectiveCooldown());
    }

    @Test
    void scaleActionRejectsMinGreaterThanMax() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.ScaleActionSpec("target", 5, 2));
    }

    @Test
    void scaleActionRejectsMinLessThanOne() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.ScaleActionSpec("target", 0, 5));
    }

    @Test
    void scaleActionRejectsTildeInTarget() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.ScaleActionSpec("risk~agent", 1, 5));
    }

    @Test
    void updateActionRejectsEmptyFields() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.UpdateActionSpec("target", "agent", Map.of()));
    }

    @Test
    void updateActionRejectsBlankNodeType() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.UpdateActionSpec("target", "", Map.of("key", "value")));
    }

    @Test
    void updateActionAcceptsNullNodeType() {
        var action = new AdaptationActionSpec.UpdateActionSpec("target", null, Map.of("key", "value"));
        assertNull(action.nodeType());
    }

    @Test
    void addActionRejectsNullNodes() {
        assertThrows(NullPointerException.class, () ->
            new AdaptationActionSpec.AddActionSpec(null));
    }
}
