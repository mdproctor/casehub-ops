# CaseHub Ops

## Project Type

type: java

## Repository Role

Integration-tier domain implementations for the casehub-desiredstate generic runtime. Each module
provides SPI implementations (GoalCompiler, ActualStateAdapter, NodeProvisioner, FaultPolicy) for a
specific operational domain.

**Tier:** Integration (alongside claudony, casehub-openclaw, casehub-workers)

**Design philosophy:** Research project and reference architecture. Demonstrates what accountability-native
desired-state management looks like across multiple domains. The infra module augments Terraform/Ansible
rather than replacing them — adding tamper-evident audit trail, continuous reconciliation, human governance
gates, and trust-weighted execution. The compliance module addresses the largest identified market gap:
continuous compliance posture (vs. point-in-time audit prep tools).

**Research doc:** `docs/superpowers/research/2026-06-07-desired-state-management-research.md` (in casehub-parent)

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode -o test -pl app      # app/ tests — offline avoids GitHub Packages metadata hang
mvn --batch-mode deploy -DskipTests   # CI only — requires GITHUB_TOKEN
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-ops-api` | `io.casehub.ops.api` | Shared types across all domain implementations |
| `deployment/` | `casehub-ops-deployment` | `io.casehub.ops.deployment` | CaseHub agent topology domain — PRIMARY target |
| `infra/` | `casehub-ops-infra` | `io.casehub.ops.infra` | Infrastructure provisioning — Terraform/Ansible augmentation |
| `compliance/` | `casehub-ops-compliance` | `io.casehub.ops.compliance` | Compliance posture — SOC2/GDPR/EU-AI-Act/DORA/NIS2 |
| `iot/` | `casehub-ops-iot` | `io.casehub.ops.iot` | IoT desired state — physical + logical node provisioning |
| `testing/` | `casehub-ops-testing` | `io.casehub.ops.testing` | Shared test fixtures. **Test scope only.** |
| `app/` | `casehub-ops-app` | `io.casehub.ops.app` | Operational console — Quarkus application embedding engine + desiredstate. NOT a domain module. |

## Domain Priority

1. **deployment** — primary target, most foundation infrastructure already exists
2. **compliance** — largest market gap, GRC ~$50B, fastest-growing segment
3. **iot** — secondary target, casehub-iot foundation already exists
4. **infra** — demo/proof-of-concept, validates generic runtime against universally understood problem

## Key Design Rules

- Each module is a standalone Jandex library activating by classpath presence — no config required
- `testing` is **never** compile or runtime scope — test only
- Human nodes generate casehub-work WorkItems, never blocking provisioner calls
- All reconciliation events flow through `EventSource.stream()` → ReconciliationLoop in the runtime
- Pruning always before growing — dependency-aware ordering guaranteed by the runtime TransitionPlanner
- tenancyId propagated through all calls — bind in repository/adapter layer only
- `app/` implements the desiredstate SPI quad directly — NOT a domain module. No domain modules on the classpath (ARC42STORIES §2 single-domain CDI constraint)

## Architecture Record

**Primary record:** `ARC42STORIES.MD` (project root)

## Cross-Repo Conventions

Protocols live in `casehub/garden`. Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/superpowers/specs/` |
| adr | `docs/adr/` |
| handover | workspace `HANDOFF.md` |
| write-blog | workspace `blog/` |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/casehub-ops

## Workspace

**Project repo:** `/Users/mdproctor/claude/casehub/ops`
**Workspace:** `/Users/mdproctor/claude/public/casehub-ops`
**Workspace type:** public
