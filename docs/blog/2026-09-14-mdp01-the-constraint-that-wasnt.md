---
layout: post
title: "The Constraint That Wasn't"
date: 2026-09-14
entry_type: note
subtype: diary
projects: [casehubio/casehub-ops]
tags: [desired-state, cross-domain, architecture, design]
---

# The Constraint That Wasn't

Issue #23 asked for cross-domain dependency graphs. The framing: domains like infra, deployment, compliance, and IoT compile and reconcile independently, but in practice they have ordering dependencies. Deployment agents need K8s namespaces to exist first. Compliance evidence collection needs deployment in steady state. The assumption — mine, written into ARC42STORIES.MD since chapter 1 — was that single-domain-per-classpath was a fundamental architectural constraint. Multiple domain modules on the same classpath would create CDI ambiguity across five SPI types, and untangling that would mean qualifier annotations, routing infrastructure, and careful CDI wiring.

Turns out four of those five SPIs already handle multiple implementations. `DefaultActualStateAdapterRouter` dispatches by `NodeType` via `handledTypes()`. `DefaultNodeProvisionerRouter` does the same. `FaultPolicyEngine` accepts a `List<FaultPolicy>` and evaluates all of them. `DefaultMergedEventSource` merges a `Collection<EventSource>`. The routers, the list injection, the collection merging — all of it already there, already tested, already working. The only SPI without routing was `GoalCompiler`, and that was resolved by `@DesiredStateQualifier` in a prior issue.

The constraint was never about CDI. It was about the absence of an orchestration layer — something to discover multiple domain compilers, merge their graphs, and add cross-domain dependency edges. The runtime already knew how to reconcile a mixed-type graph. Nobody had told it to build one.

`DesiredStateGraph` even had `overlay()` and `connect()` sitting there. I'd written them months ago for intra-domain graph composition. `connect()` adds edges from one graph's leaves to another's roots. The cross-domain ordering mechanism was, quite literally, an existing method on an existing interface.

The design that landed in casehub-desiredstate#140 introduces `CrossDomainCompositionEngine`. Domains register with `provides` (what NodeTypes they contribute) and `requires` (what they depend on). The engine computes a topological sort, overlays the per-domain graphs, and injects cross-domain edges connecting dependent roots to provider nodes. Infra provides `k8s_namespace`. Deployment requires `k8s_namespace`. The engine adds edges from deployment roots to infra's namespace nodes. IoT also requires `k8s_namespace` but doesn't require `agent` — so IoT and deployment run in parallel after infra, not sequenced.

The most useful part of the design process was the adversarial decision review. I'd initially proposed hierarchical reconciliation as the primary architecture — a meta-loop with domain-level nodes, inner reconciliation loops per domain, event bridging, lifecycle management. The reviewer pointed out that the flat merge model uses everything the runtime already has. Type-filtered resync gives per-domain resync intervals within a single loop. The hierarchical model adds an engineering surface area — inner-loop lifecycle, fault propagation across layers — for a deployment topology nobody needs yet. The revision: flat merge is primary, hierarchical is a documented extension for when multi-process deployment appears.

The four-domain composition test in ops proves the framework works with real NodeTypes from the codebase. Infra before deployment before compliance, IoT parallel with deployment, correct topological ordering, duplicate detection, unsatisfied-requires validation. The integration is thin — stub registrations, no runtime wiring yet — but the proof is solid.

What this opens up is interesting. The `app/` module can now compose multiple domain graphs at startup. A single operational application could manage infrastructure provisioning, agent topology, compliance posture, and IoT device state in one reconciliation loop, with correct ordering and fault isolation via type-filtered resync. The real runtime wiring — CDI startup beans, goal loading, integration with `StartupRecoveryService` — is follow-up work. But the hard part was never the wiring. The hard part was the design decision, and the design decision was that there wasn't much to decide. The infrastructure was already there.
