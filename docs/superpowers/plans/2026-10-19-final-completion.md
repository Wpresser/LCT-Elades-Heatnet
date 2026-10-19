# Elades final completion implementation plan

> For agentic workers: execute with superpowers:executing-plans in this session; request a fresh whole-branch review before delivery.

**Goal:** Deliver one reproducible, verified final repository and two correctly filled presentation pairs, with truthful claims and a clear submission record.

**Architecture:** Preserve the supplied v3 algorithm unless a reproduced correctness defect requires a targeted fix. Keep original inputs immutable. Source code, generated results, slides and release evidence refer to the same final commit and policies.

**Tech stack:** Java 11, Spring Boot 2.6.3, PostgreSQL, docker-compose 1.29.2; Python; React/TypeScript; existing PPTX template.

**Spec:** Technical appendix and clarifications in task/sources and task/text; organizer messages supplied in the chat; docs/FINAL_SUBMISSION_RULES.md records the latest schedule.

## Global constraints

- Main mode relaxed; literal is explicitly a comparison. Default dataset and its hash stay unchanged.
- New network must obey the supplied routing, diameter, flow, chamber, clearance, special-passage and output-contract rules.
- Code and submitted materials freeze 20.10.2026 23:59 Europe/Moscow; separate pitch slides may be adapted later.
- Preserve format of mandatory template slides 7–11 in submission and moderator PDF/PPTX. Preserve Elades in filenames.
- Do not invent participant roles, education details, document provenance, performance or test evidence.

## Review focus

- Final approaches: exterior priority, own-building exemptions, non-nearest fallback and courtyard handling.
- Tree topology and flow turns after alignment, short-bend merging and diameter-driven rebuilding.
- Negative inputs: broken geometry/IDs, closed exits and special passages at cameras/roads.
- Deployment: real HTTP upload/status/download, persistence and historical versus current platform evidence.
- Submission integrity: same URLs, valid template fields, synchronized maps/metrics, no unsupported guarantees.

## Task 1: reproducible candidate and dependency gate

- [x] Import v3 on an isolated branch rooted at 08ee21f; preserve its commit as the comparison base.
- [x] Apply the reproduced source-map-js update only; run clean install, audit, viewer tests, typecheck and build.
- [x] Audit technical requirements independently and fix reproduced important defects with failing-then-passing regressions.

## Task 2: release claims and regulator-facing materials

- [x] Remove unsupported provenance dates and blanket +10 m guarantees. Record the latest organizer rules.
- [x] Fill mandatory slide fields from confirmed facts or explicit user answers, preserving template geometry and styling.
- [x] Export both PDF/PPTX pairs; visually inspect every page, verify slide ordering and final metrics.

## Task 3: execution and hostile review

- [x] Run Java/Python/scenarios, main/strict/OSM audits and output contract, generated result equality, viewer checks.
- [x] Build and run the service; test HTTP and persistence on available real runtimes. Record exact platform limitations without claiming unrun Docker/Ubuntu checks.
- [x] Obtain fresh review against source rules and final diff; reproduce and resolve material findings.

## Task 4: final delivery

- [ ] Make the final source ZIP and handoff with manifest after remote verification; verify extraction and patch applicability.
- [ ] Complete remote CI and handle any failures on the published branch/PR.
- [ ] Record final delivered commit and package manifest; do not equate repository delivery with moderator messages or festival registration.

Execution record: local implementation and fresh independent review complete. Remote CI and actual delivery status are recorded in the task handoff; checkboxes describe completion of the work steps, not a fabricated external-submission receipt.
