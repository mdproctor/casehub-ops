# Channel Drift Checker Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enrich ChannelDriftChecker with full field comparison, connector binding drift, tenancy-aware lookup, and CSV set comparison.

**Architecture:** Replace the ChannelLookup functional interface with direct injection of CrossTenantChannelStore + ChannelBindingStore. Expand mutableFieldsMatch() to 8 fields, add binding drift detection, unify duplicate type matchers, and add debug logging per mismatched field.

**Tech Stack:** Java 21, Quarkus CDI, casehub-qhorus runtime stores, JUnit 5

## Global Constraints

- Issue: casehubio/casehub-ops#14
- Every commit references #14
- TDD: failing test first, then implementation
- `mvn --batch-mode install` must pass after each task

---

### Task 1: Replace ChannelLookup with store injection + tenancy fix

**Files:**
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/drift/ChannelDriftChecker.java`
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/drift/ChannelDriftCheckerTest.java`

**Interfaces:**
- Consumes: `CrossTenantChannelStore.findByNameAndTenancy(String name, String tenancyId)` → `Optional<Channel>`, `ChannelBindingStore.findByChannelId(UUID channelId)` → `Optional<ChannelConnectorBinding>`
- Produces: `ChannelDriftChecker(CrossTenantChannelStore, ChannelBindingStore)` constructor, `check(NodeSpec, String tenancyId)` unchanged return type

- [ ] **Step 1: Write tenancy-scoped lookup test**

Add to `ChannelDriftCheckerTest.java`. Replace the entire test setup and add a tenancy test. The test stubs use anonymous inner classes for `CrossTenantChannelStore` and `ChannelBindingStore`.

```java
import io.casehub.qhorus.runtime.store.CrossTenantChannelStore;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;

// New fields:
private CrossTenantChannelStore channelStore;
private ChannelBindingStore bindingStore;
private Map<String, Channel> channels; // keyed by "name:tenancyId"
private Map<UUID, ChannelConnectorBinding> bindings;

@BeforeEach
void setUp() {
    channels = new ConcurrentHashMap<>();
    bindings = new ConcurrentHashMap<>();

    channelStore = new CrossTenantChannelStore() {
        @Override
        public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
            return Optional.ofNullable(channels.get(name + ":" + tenancyId));
        }
        @Override
        public List<Channel> listAll() { throw new UnsupportedOperationException(); }
        @Override
        public Optional<Channel> findById(UUID id) { throw new UnsupportedOperationException(); }
    };

    bindingStore = new ChannelBindingStore() {
        @Override
        public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
            return Optional.ofNullable(bindings.get(channelId));
        }
        @Override
        public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) { throw new UnsupportedOperationException(); }
        @Override
        public void put(ChannelConnectorBinding binding) { throw new UnsupportedOperationException(); }
        @Override
        public void delete(UUID channelId) { throw new UnsupportedOperationException(); }
        @Override
        public Map<UUID, ChannelConnectorBinding> findAll() { throw new UnsupportedOperationException(); }
    };

    checker = new ChannelDriftChecker(channelStore, bindingStore);
}

@Test
void channelPresent_tenancyUsedInLookup() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    channels.put("dev/work:tenant-1", ch);

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, null, null, null, null);

    // Found under tenant-1
    assertEquals(NodeStatus.PRESENT, checker.check(spec, "tenant-1"));
    // Not found under tenant-2
    assertEquals(NodeStatus.ABSENT, checker.check(spec, "tenant-2"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl deployment -Dtest=ChannelDriftCheckerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — ChannelDriftChecker constructor signature doesn't match

- [ ] **Step 3: Rewrite ChannelDriftChecker — replace ChannelLookup, fix tenancy**

Replace the entire `ChannelDriftChecker.java`:

```java
package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.runtime.store.CrossTenantChannelStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChannelDriftChecker implements NodeDriftChecker {

    private static final Logger LOG = Logger.getLogger(ChannelDriftChecker.class);

    private final CrossTenantChannelStore channelStore;
    private final ChannelBindingStore bindingStore;

    @Inject
    public ChannelDriftChecker(CrossTenantChannelStore channelStore, ChannelBindingStore bindingStore) {
        this.channelStore = channelStore;
        this.bindingStore = bindingStore;
    }

    @Override
    public String nodeType() {
        return "channel";
    }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof ChannelNodeSpec channelSpec)) {
            return NodeStatus.UNKNOWN;
        }

        Optional<Channel> actual = channelStore.findByNameAndTenancy(channelSpec.name(), tenancyId);
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }

        boolean drifted = false;
        Channel ch = actual.get();

        // Channel field comparison
        if (!fieldMatch("description", channelSpec.name(), channelSpec.description(), ch.description)) {
            drifted = true;
        }
        if (!typesMatch("allowedTypes", channelSpec.name(), channelSpec.allowedTypes(), ch.allowedTypes)) {
            drifted = true;
        }
        if (!typesMatch("deniedTypes", channelSpec.name(), channelSpec.deniedTypes(), ch.deniedTypes)) {
            drifted = true;
        }
        if (!fieldMatch("rateLimitPerChannel", channelSpec.name(), channelSpec.rateLimitPerChannel(), ch.rateLimitPerChannel)) {
            drifted = true;
        }
        if (!fieldMatch("rateLimitPerInstance", channelSpec.name(), channelSpec.rateLimitPerInstance(), ch.rateLimitPerInstance)) {
            drifted = true;
        }
        if (!csvSetMatch("allowedWriters", channelSpec.name(), channelSpec.allowedWriters(), ch.allowedWriters)) {
            drifted = true;
        }
        if (!csvSetMatch("adminInstances", channelSpec.name(), channelSpec.adminInstances(), ch.adminInstances)) {
            drifted = true;
        }
        if (!csvSetMatch("barrierContributors", channelSpec.name(), channelSpec.barrierContributors(), ch.barrierContributors)) {
            drifted = true;
        }

        // Binding comparison — always check, even if fields already drifted
        if (!bindingMatch(channelSpec, ch.id)) {
            drifted = true;
        }

        return drifted ? NodeStatus.DRIFTED : NodeStatus.PRESENT;
    }

    private boolean fieldMatch(String fieldName, String channelName, Object desired, Object actual) {
        if (Objects.equals(desired, actual)) {
            return true;
        }
        LOG.debugf("channel %s: %s drifted [%s → %s]", channelName, fieldName, desired, actual);
        return false;
    }

    private boolean typesMatch(String fieldName, String channelName, Set<MessageType> desired, String actualCsv) {
        if (desired == null && actualCsv == null) {
            return true;
        }
        if ((desired == null || desired.isEmpty()) && (actualCsv == null || actualCsv.isEmpty())) {
            return true;
        }
        if (desired == null || actualCsv == null) {
            LOG.debugf("channel %s: %s drifted [%s → %s]", channelName, fieldName, desired, actualCsv);
            return false;
        }
        Set<MessageType> actualSet = MessageType.parseTypes(actualCsv);
        if (desired.equals(actualSet)) {
            return true;
        }
        LOG.debugf("channel %s: %s drifted [%s → %s]", channelName, fieldName, desired, actualSet);
        return false;
    }

    private boolean csvSetMatch(String fieldName, String channelName, String desired, String actual) {
        if (desired == null && actual == null) {
            return true;
        }
        if ((desired == null || desired.isBlank()) && (actual == null || actual.isBlank())) {
            return true;
        }
        if (desired == null || actual == null) {
            LOG.debugf("channel %s: %s drifted [%s → %s]", channelName, fieldName, desired, actual);
            return false;
        }
        Set<String> desiredSet = parseCsvSet(desired);
        Set<String> actualSet = parseCsvSet(actual);
        if (desiredSet.equals(actualSet)) {
            return true;
        }
        LOG.debugf("channel %s: %s drifted [%s → %s]", channelName, fieldName, desiredSet, actualSet);
        return false;
    }

    private Set<String> parseCsvSet(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private boolean bindingMatch(ChannelNodeSpec spec, java.util.UUID channelId) {
        boolean specHasBinding = spec.inboundConnectorId() != null
                || spec.externalKey() != null
                || spec.outboundConnectorId() != null
                || spec.outboundDestination() != null;

        Optional<ChannelConnectorBinding> actualBinding = bindingStore.findByChannelId(channelId);

        if (!specHasBinding && actualBinding.isEmpty()) {
            return true;
        }
        if (specHasBinding && actualBinding.isEmpty()) {
            LOG.debugf("channel %s: binding expected but absent", spec.name());
            return false;
        }
        if (!specHasBinding && actualBinding.isPresent()) {
            LOG.debugf("channel %s: binding present but not in spec (reverse asymmetry)", spec.name());
            return false;
        }

        ChannelConnectorBinding binding = actualBinding.get();
        boolean match = true;
        if (!Objects.equals(spec.inboundConnectorId(), binding.inboundConnectorId)) {
            LOG.debugf("channel %s: binding.inboundConnectorId drifted [%s → %s]",
                    spec.name(), spec.inboundConnectorId(), binding.inboundConnectorId);
            match = false;
        }
        if (!Objects.equals(spec.externalKey(), binding.externalKey)) {
            LOG.debugf("channel %s: binding.externalKey drifted [%s → %s]",
                    spec.name(), spec.externalKey(), binding.externalKey);
            match = false;
        }
        if (!Objects.equals(spec.outboundConnectorId(), binding.outboundConnectorId)) {
            LOG.debugf("channel %s: binding.outboundConnectorId drifted [%s → %s]",
                    spec.name(), spec.outboundConnectorId(), binding.outboundConnectorId);
            match = false;
        }
        if (!Objects.equals(spec.outboundDestination(), binding.outboundDestination)) {
            LOG.debugf("channel %s: binding.outboundDestination drifted [%s → %s]",
                    spec.name(), spec.outboundDestination(), binding.outboundDestination);
            match = false;
        }
        return match;
    }
}
```

- [ ] **Step 4: Update existing tests for new constructor + stubs**

Rewrite all existing tests in `ChannelDriftCheckerTest.java` to use the new setUp() from Step 1. Key changes:
- Channel map keyed by `"name:tenancyId"` instead of just `"name"`
- All channel insertions use `channels.put("dev/work:" + TENANCY_ID, ch)` instead of `channels.put("dev/work", ch)`
- `unknownSpecType` test unchanged (returns UNKNOWN before lookup)

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl deployment -Dtest=ChannelDriftCheckerTest`
Expected: all existing tests + tenancy test PASS

- [ ] **Step 6: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/drift/ChannelDriftChecker.java deployment/src/test/java/io/casehub/ops/deployment/drift/ChannelDriftCheckerTest.java
git commit -m "refactor(#14): replace ChannelLookup with store injection, fix tenancy bug"
```

---

### Task 2: Full field comparison + CSV set tests

**Files:**
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/drift/ChannelDriftCheckerTest.java`

**Interfaces:**
- Consumes: `ChannelDriftChecker.check(NodeSpec, String)` from Task 1
- Produces: test coverage for all 8 mutable fields + CSV order insensitivity

- [ ] **Step 1: Add field comparison tests**

```java
@Test
void channelPresent_allFieldsMatch() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.description = "Work channel";
    ch.semantic = ChannelSemantic.APPEND;
    ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
    ch.deniedTypes = null;
    ch.allowedWriters = "agent-1,agent-2";
    ch.adminInstances = "admin-1";
    ch.barrierContributors = "contrib-1,contrib-2";
    ch.rateLimitPerChannel = 100;
    ch.rateLimitPerInstance = 10;
    channels.put("dev/work:" + TENANCY_ID, ch);

    var spec = new ChannelNodeSpec("dev/work", "Work channel", ChannelSemantic.APPEND,
            Set.of(MessageType.COMMAND), null, "agent-1,agent-2", "admin-1", "contrib-1,contrib-2",
            100, 10, null, null, null, null);

    assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_descriptionMismatch() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.description = "Old description";
    ch.semantic = ChannelSemantic.APPEND;
    channels.put("dev/work:" + TENANCY_ID, ch);

    var spec = new ChannelNodeSpec("dev/work", "New description", ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, null, null, null, null);

    assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_allowedWritersOrderInsensitive() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    ch.allowedWriters = "bob,alice";
    channels.put("dev/work:" + TENANCY_ID, ch);

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, "alice,bob", null, null, null, null, null, null, null, null);

    assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_allowedWritersMismatch() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    ch.allowedWriters = "alice,charlie";
    channels.put("dev/work:" + TENANCY_ID, ch);

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, "alice,bob", null, null, null, null, null, null, null, null);

    assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_adminInstancesMismatch() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    ch.adminInstances = "admin-old";
    channels.put("dev/work:" + TENANCY_ID, ch);

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, "admin-new", null, null, null, null, null, null, null);

    assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_barrierContributorsMismatch() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    ch.barrierContributors = "contrib-old";
    channels.put("dev/work:" + TENANCY_ID, ch);

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, null, "contrib-new", null, null, null, null, null, null);

    assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
}
```

- [ ] **Step 2: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=ChannelDriftCheckerTest`
Expected: all PASS (implementation already in place from Task 1)

- [ ] **Step 3: Commit**

```bash
git add deployment/src/test/java/io/casehub/ops/deployment/drift/ChannelDriftCheckerTest.java
git commit -m "test(#14): full field comparison + CSV set tests"
```

---

### Task 3: Connector binding drift tests

**Files:**
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/drift/ChannelDriftCheckerTest.java`

**Interfaces:**
- Consumes: `ChannelDriftChecker.check(NodeSpec, String)` from Task 1, `bindings` map from setUp
- Produces: test coverage for all 4 binding drift cases

- [ ] **Step 1: Add binding drift tests**

```java
@Test
void channelPresent_withMatchingBinding() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    channels.put("dev/work:" + TENANCY_ID, ch);

    ChannelConnectorBinding binding = new ChannelConnectorBinding();
    binding.channelId = ch.id;
    binding.inboundConnectorId = "slack";
    binding.externalKey = "C12345";
    binding.outboundConnectorId = "slack";
    binding.outboundDestination = "#general";
    bindings.put(ch.id, binding);

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, "slack", "C12345", "slack", "#general");

    assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_bindingFieldMismatch() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    channels.put("dev/work:" + TENANCY_ID, ch);

    ChannelConnectorBinding binding = new ChannelConnectorBinding();
    binding.channelId = ch.id;
    binding.inboundConnectorId = "slack";
    binding.externalKey = "C12345";
    binding.outboundConnectorId = "slack";
    binding.outboundDestination = "#old-channel";
    bindings.put(ch.id, binding);

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, "slack", "C12345", "slack", "#new-channel");

    assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_bindingExpectedButAbsent() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    channels.put("dev/work:" + TENANCY_ID, ch);
    // No binding in bindings map

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, "slack", "C12345", "slack", "#general");

    assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
}

@Test
void channelDrifted_bindingPresentButNotInSpec() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    channels.put("dev/work:" + TENANCY_ID, ch);

    ChannelConnectorBinding binding = new ChannelConnectorBinding();
    binding.channelId = ch.id;
    binding.inboundConnectorId = "slack";
    binding.externalKey = "C12345";
    binding.outboundConnectorId = "slack";
    binding.outboundDestination = "#general";
    bindings.put(ch.id, binding);

    // Spec has no binding fields (all null)
    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, null, null, null, null);

    assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
}

@Test
void channelPresent_noBindingEitherSide() {
    Channel ch = new Channel();
    ch.id = UUID.randomUUID();
    ch.name = "dev/work";
    ch.semantic = ChannelSemantic.APPEND;
    channels.put("dev/work:" + TENANCY_ID, ch);
    // No binding in bindings map, no binding in spec

    var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, null, null, null, null);

    assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
}
```

- [ ] **Step 2: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=ChannelDriftCheckerTest`
Expected: all PASS

- [ ] **Step 3: Commit**

```bash
git add deployment/src/test/java/io/casehub/ops/deployment/drift/ChannelDriftCheckerTest.java
git commit -m "test(#14): connector binding drift tests — all 4 cases + reverse asymmetry"
```

---

### Task 4: Update integration test + full build + PLATFORM.md cleanup

**Files:**
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentLifecycleIntegrationTest.java`
- Modify: `~/claude/casehub/parent/docs/PLATFORM.md` (separate repo, separate commit)

**Interfaces:**
- Consumes: `ChannelDriftChecker(CrossTenantChannelStore, ChannelBindingStore)` from Task 1
- Produces: passing integration test, clean full build

- [ ] **Step 1: Update DeploymentLifecycleIntegrationTest**

Add a `StubCrossTenantChannelStore` and `StubChannelBindingStore` to the test. Add fields and update setUp():

```java
// New fields alongside existing ones:
private StubCrossTenantChannelStore crossTenantChannelStore;
private StubChannelBindingStore channelBindingStore;

// In setUp(), add after channelOps creation:
crossTenantChannelStore = new StubCrossTenantChannelStore(channelOps);
channelBindingStore = new StubChannelBindingStore();

// Replace line 58:
var channelChecker = new ChannelDriftChecker(crossTenantChannelStore, channelBindingStore);
```

Add stub classes inside `DeploymentLifecycleIntegrationTest`:

```java
static class StubCrossTenantChannelStore implements CrossTenantChannelStore {
    private final StubChannelOperations channelOps;

    StubCrossTenantChannelStore(StubChannelOperations channelOps) {
        this.channelOps = channelOps;
    }

    @Override
    public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
        return channelOps.findByName(name);
    }

    @Override
    public List<Channel> listAll() { throw new UnsupportedOperationException(); }

    @Override
    public Optional<Channel> findById(UUID id) { throw new UnsupportedOperationException(); }
}

static class StubChannelBindingStore implements ChannelBindingStore {
    private final Map<UUID, ChannelConnectorBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
        return Optional.ofNullable(bindings.get(channelId));
    }

    @Override
    public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) { throw new UnsupportedOperationException(); }

    @Override
    public void put(ChannelConnectorBinding binding) { throw new UnsupportedOperationException(); }

    @Override
    public void delete(UUID channelId) { throw new UnsupportedOperationException(); }

    @Override
    public Map<UUID, ChannelConnectorBinding> findAll() { throw new UnsupportedOperationException(); }
}
```

Add required imports:

```java
import io.casehub.qhorus.runtime.store.CrossTenantChannelStore;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
```

- [ ] **Step 2: Run full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules compile and all tests pass

- [ ] **Step 3: Commit integration test fix**

```bash
git add deployment/src/test/java/io/casehub/ops/deployment/DeploymentLifecycleIntegrationTest.java
git commit -m "test(#14): update integration test for new ChannelDriftChecker constructor"
```

- [ ] **Step 4: Clean up PLATFORM.md in casehub-parent**

In `~/claude/casehub/parent/docs/PLATFORM.md` line 415, remove the `qhorus#287` substring from the deployment row. The text `Foundation bridge modules (eidos#60, qhorus#287) override drift checkers via @Alternative @Priority(1).` becomes `Foundation bridge modules (eidos#60) override drift checkers via @Alternative @Priority(1).`

- [ ] **Step 5: Commit PLATFORM.md cleanup (separate repo)**

```bash
git -C ~/claude/casehub/parent add docs/PLATFORM.md
git -C ~/claude/casehub/parent commit -m "docs(casehub-ops#14): remove stale qhorus#287 reference from deployment row"
```
