# Canonical Deployment Topologies

14 YAML exemplars proving the CaseHub desired-state YAML frontend can express
the full range of real-world deployment architectures -- from a personal blog
on a single node to a multi-region active-passive banking core.

## The Topology Matrix

5 application architectures x 4 infrastructure topologies, with 14 meaningful
intersections. Each cell links to a YAML exemplar using a recognisable
real-world domain.

| | Single Node | LB Cluster | HA Multi-AZ | Multi-Region A/P |
|---|---|---|---|---|
| **Single Service** | [Personal blog](src/test/resources/topologies/single-service/single-node-blog.yaml) | [Company website](src/test/resources/topologies/single-service/lb-cluster-website.yaml) | -- | -- |
| **Multi-Tier** | [Local dev stack](src/test/resources/topologies/multi-tier/single-node-dev.yaml) | [E-commerce storefront](src/test/resources/topologies/multi-tier/lb-cluster-ecommerce.yaml) | [Hospital records](src/test/resources/topologies/multi-tier/ha-multi-az-healthcare.yaml) | [Retail banking core](src/test/resources/topologies/multi-tier/multi-region-banking.yaml) |
| **Microservices** | [Local dev env](src/test/resources/topologies/microservices/single-node-dev.yaml) | [Food delivery platform](src/test/resources/topologies/microservices/lb-cluster-delivery.yaml) | [Trading platform](src/test/resources/topologies/microservices/ha-multi-az-trading.yaml) | [Global payments](src/test/resources/topologies/microservices/multi-region-payments.yaml) |
| **Event-Driven** | [Local dev env](src/test/resources/topologies/event-driven/single-node-dev.yaml) | [IoT telemetry pipeline](src/test/resources/topologies/event-driven/lb-cluster-telemetry.yaml) | -- | -- |
| **Sidecar/Mesh** | -- | [Logistics fleet tracking](src/test/resources/topologies/sidecar-mesh/lb-cluster-logistics.yaml) | [Insurance claims](src/test/resources/topologies/sidecar-mesh/ha-multi-az-insurance.yaml) | -- |

## YAML Primitives by Architecture

Each architecture type exercises different YAML primitives:

| Primitive | Single Service | Multi-Tier | Microservices | Event-Driven | Sidecar/Mesh |
|-----------|:-:|:-:|:-:|:-:|:-:|
| **Nodes + dependencies** | x | x | x | x | x |
| **Variables** | x | x | x | x | x |
| **Lifecycle phases** | | x | | | |
| **Modules** (load-balancer, ha-multi-az, multi-region, service-mesh) | LB cluster | LB, HA, multi-region | LB, HA, multi-region | LB | mesh, LB, HA |
| **forEach** (AZ replication) | | | x | | |
| **Invariants** | | x | | x | |
| **Rules** (auto-wiring) | | | | | mesh sidecar injection |

## Exemplar Details

### Single Service

| Exemplar | Domain | Nodes | Key pattern |
|----------|--------|-------|-------------|
| `single-node-blog.yaml` | Ghost blog | 3 (namespace, deployment, service) | Minimal viable topology -- namespace before deployment before service |
| `lb-cluster-website.yaml` | Nginx company website | 3 + LB module (namespace, deployment, service, load balancer, ingress) | Load-balancer module import with APPLICATION type |

### Multi-Tier

| Exemplar | Domain | Nodes | Key pattern |
|----------|--------|-------|-------------|
| `single-node-dev.yaml` | E-commerce local dev | 3 across lifecycle phases | Lifecycle phases (data, application, web) with multi-tier chain invariant |
| `lb-cluster-ecommerce.yaml` | E-commerce storefront | 3 + LB module across phases | Lifecycle phases + load-balancer module + invariant |
| `ha-multi-az-healthcare.yaml` | Hospital patient records | 4 across phases + HA module | Lifecycle phases + HA multi-AZ module import |
| `multi-region-banking.yaml` | Retail banking core | 4 across phases + multi-region module | Lifecycle phases + multi-region DR (SEMI_SYNC replication, DNS failover) |

### Microservices

| Exemplar | Domain | Nodes | Key pattern |
|----------|--------|-------|-------------|
| `single-node-dev.yaml` | Microservices local dev | 4 (namespace, user-svc, order-svc, payment-svc) | Independent services sharing a namespace |
| `lb-cluster-delivery.yaml` | Food delivery platform | 5 + LB module (gateway, restaurant, rider, order services) | API gateway pattern with multi-dependency order service |
| `ha-multi-az-trading.yaml` | Stock trading platform | 5 + HA module (market-data, order-matching, risk, settlement) | forEach stamps market-data and order-matching across 3 AZs |
| `multi-region-payments.yaml` | Global payments | 4 + multi-region + LB modules | Multi-region DR + load balancer + fraud detection pipeline |

### Event-Driven

| Exemplar | Domain | Nodes | Key pattern |
|----------|--------|-------|-------------|
| `single-node-dev.yaml` | RabbitMQ event dev | 4 (namespace, broker, producer, consumer) | Broker as architectural centre with invariant enforcing broker dependency |
| `lb-cluster-telemetry.yaml` | IoT telemetry pipeline | 5 + LB module (Kafka, ingest, processor, TimescaleDB) | Broker-centric with load-balanced ingestion and time-series storage |

### Sidecar/Mesh

| Exemplar | Domain | Nodes | Key pattern |
|----------|--------|-------|-------------|
| `lb-cluster-logistics.yaml` | Logistics fleet tracking | 4 + mesh + LB modules | Service mesh (sidecar injection rule) + load balancer for fleet API |
| `ha-multi-az-insurance.yaml` | Insurance claims processing | 4 + mesh + HA modules | Service mesh + HA multi-AZ for claims API, underwriting, document store |

## Topology Modules

Four reusable YAML modules ship in `casehub-ops-infra` at
`META-INF/desiredstate/modules/`:

| Module | Nodes added | Parameters | Used by |
|--------|------------|------------|---------|
| `load-balancer` | LB node + ingress | target_service, namespace, health_check_path, lb_type | Website, e-commerce, delivery, telemetry, logistics, payments |
| `ha-multi-az` | HA control plane | namespace, region, zones | Healthcare, trading, insurance |
| `multi-region` | DNS failover + data replication | primary_cluster, dr_cluster, domain, replication_mode | Banking, payments |
| `service-mesh` | Mesh control plane + sidecar injection rule | namespace, control_plane_image, control_plane_replicas | Logistics, insurance |

## Test Pyramid

Tests verify each exemplar at three levels, gated by Maven profiles:

### Level 1: Compilation (default profile)

Verifies YAML compiles to a correct `DesiredStateGraph`. Runs with `mvn test`.

- Correct node count after module expansion and forEach stamping
- Correct node types for all nodes
- Correct dependency edges (roots before leaves)
- Invariant enforcement (remove a required tier and compilation fails)
- forEach stamps correct number of copies with correct IDs
- Lifecycle phases in correct order with correct completion conditions
- Module nodes present with correct aliased IDs

5 test classes, 27 tests total.

### Level 2: Reconciliation (`-Preconciliation` profile)

Verifies the full reconciliation loop: compile, plan, execute, fault
handling. Uses `FailableNodeProvisioner` for deterministic failure injection.

- Provision ordering (namespace before deployments, data before app)
- Drift detection (modify spec, re-reconcile, verify re-provision)
- Fault escalation (inject N failures, verify review node added)
- forEach stamp ordering preserved through reconciliation
- Lifecycle phase transitions

5 test classes + FailableNodeProvisioner test, 36 tests total.

### Level 3: Live K8s (`-Pinfra-live` profile)

Verifies real K8s deployment. Gated by `@EnabledIf` checking K8s cluster
availability via `KubernetesAvailable` condition.

- Namespace creation
- Deployment reaches Ready state
- Service resolves

Scoped to K8s-native types only (not load_balancer, dns_failover, etc.).

## Running

```bash
# Compilation tests (default)
mvn test -pl topology-tests

# A specific architecture
mvn test -pl topology-tests -Dtest=MultiTierCompilationTest

# Reconciliation tests
mvn test -pl topology-tests -Preconciliation

# Live K8s tests (requires cluster)
mvn test -pl topology-tests -Pinfra-live

# All levels
mvn test -pl topology-tests -Preconciliation -Pinfra-live
```

## Key Insight

No new compiler was written for any of these topologies. Every exemplar uses
the same YAML primitives that ship with `casehub-desiredstate-yaml`: nodes,
dependencies, variables, lifecycle phases, modules, forEach, invariants, rules,
and conditions. The `InfraNodeSpecFactoryProvider` bridges `InfraNodeSpec`
records to the YAML frontend via `NodeSpecFactory` -- the YAML processor
discovers types via `@NodeTypeId` and delegates construction to the factory.
