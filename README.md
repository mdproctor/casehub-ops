# casehub-ops

[![Build](https://github.com/casehubio/casehub-ops/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/casehub-ops/actions/workflows/publish.yml) [![Open PRs](https://img.shields.io/github/issues-pr/casehubio/casehub-ops)](https://github.com/casehubio/casehub-ops/pulls)

Domain implementations for [casehub-desiredstate](https://github.com/casehubio/casehub-desiredstate) — accountability-native desired-state management across four operational domains.

| Module | Domain | Status |
|--------|--------|--------|
| `deployment` | CaseHub agent topology — declare desired agent fleet, channels, case types, trust config | Research |
| `infra` | Infrastructure provisioning — Terraform/Ansible augmentation with audit trail + governance | Research |
| `compliance` | Compliance posture — continuous SOC2/GDPR/EU-AI-Act/DORA reconciliation | Research |
| `iot` | IoT desired state — physical + logical device provisioning via casehub-iot | Research |

**What this adds over Terraform/Ansible:** continuous reconciliation, tamper-evident audit trail, first-class human governance gates (casehub-work WorkItems), and trust-weighted execution (casehub-ledger trust scores). Augments existing tooling rather than replacing it.

Research project and reference architecture. See the [research doc](https://github.com/casehubio/parent/blob/main/docs/superpowers/research/2026-06-07-desired-state-management-research.md) for design rationale.
