---
title: "The container module gets its SPI quad — and the YAML system catches up"
author: mdp
date: 2026-09-29
entry_type: note
subtype: diary
projects: [casehubio/casehub-ops]
series: issue-52-ops-container
tags: [container, podman, desiredstate, yaml]
---

# The container module gets its SPI quad — and the YAML system catches up

The ops container module has had its four SPI implementations since the initial
scaffolding: `ContainerGoalCompiler`, `PodmanActualStateAdapter`,
`PodmanNodeProvisioner`, and the sealed `ContainerNodeSpec` hierarchy. What it
didn't have was proof they worked together, a way to declare topologies in YAML,
or any wiring into the actual app. This session fixed all three.

## SimulatingPodmanClient — testing without a socket

The unit tests for each SPI implementation are fine individually, but they can't
tell you whether the reconciliation loop actually converges. A container that the
provisioner creates needs to show up when the adapter reads actual state. A
container that gets destroyed needs to reappear after the planner generates a
PROVISION step.

The `SimulatingPodmanClient` solves this by maintaining in-memory state. Create a
container — it appears in the ConcurrentHashMap with state "created". Start it —
state becomes "running". Stop it — "exited". Remove it — gone. The adapter sees
it. The provisioner changes it. Five tests exercise the full cycle: green-field
provisioning with loop closure, self-healing after an external kill, drift
detection when a container stops, full deprovision, and — the one I actually
cared about — dependency ordering verification.

That last test checks that the `TransitionPlanner` respects the graph edges:
networks and volumes provision before databases, databases before app containers.
The deployment module's existing tests validated this for agents and channels, but
containers have a much more concrete dependency chain. If the database isn't
running when the app container starts, the JDBC connection fails at boot. The
graph edges encode that, and the planner honours them.

## YAML graph integration via @NodeTypeId

The platform's desiredstate YAML system compiles graph YAML into
`DesiredStateGraph` objects automatically — you declare nodes with types and
specs, the `NodeSpecRegistry` maps type strings to Java record classes, and
Jackson hydrates the specs. The deployment module already uses this.

The container module needed two things to plug in: `@NodeTypeId` annotations on
the four spec records (`"container:app"`, `"container:database"`,
`"container:network"`, `"container:volume"`) and a `ContainerNodeSpecFactoryProvider`
that discovers them via `ContainerNodeSpec.class.getPermittedSubclasses()`. Java
sealed classes as a type registry — the permits list IS the registry. Add a new
spec variant, the factory provider picks it up automatically.

A reference YAML topology for fsitrading exercises the format: variables for
image and ports, dependency edges between network → database → app, and the
spec fields that Jackson maps directly to the record constructors.

## CDI wiring — the @Podman qualifier

Adding the container module to `app/pom.xml` is one line. Making CDI happy is
the interesting part. `PodmanClient` injects a Vert.x `WebClient` and a
`SocketAddress` for the Podman REST socket. In an app that might have other
WebClients (HTTP APIs, external services), these need disambiguation.

A `@Podman` qualifier scopes both injections. `PodmanClientProducer` reads the
socket path from `casehub.container.podman.socket` (defaulting to
`/var/run/podman/podman.sock`) and produces a `SocketAddress.domainSocketAddress()`
— Unix domain socket, not TCP. The qualifier keeps Podman's wiring invisible to
everything else on the classpath.

## What's next

One issue remains: `ContainerEventSource` for real-time Podman events and
container-aware fault classification. The current setup relies on the
reconciliation loop's periodic `ActualStateAdapter` poll — a container that dies
between cycles stays dead until the next 5-minute resync. A Podman event stream
would detect the failure instantly and trigger immediate reconciliation. The
interesting design question is fault classification: an OOM restart is transient
(wait and re-check), but an image pull failure is permanent (no amount of
retrying fixes a missing image). That distinction matters for how aggressively
the system re-provisions.
