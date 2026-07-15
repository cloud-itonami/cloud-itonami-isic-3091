# cloud-itonami-isic-3091: Manufacture of motorcycles

Open Business Blueprint for **ISIC 3091**: manufacture of motorcycles — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **motorcycle plant operations**: production-batch data logging (product-category/engine-displacement/quantity/weld-defect-rate), welding/assembly/test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound shipment coordination.

This repository designs a forkable OSS business for motorcycle plant
operations: run by a qualified operator so a plant keeps its own
operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not welding/assembly-line control

ISIC 3091 covers the **manufacturing plant** that welds frames,
assembles engines and final drivetrains, and inspects the resulting
motorcycles on structural/brake/emissions-dyno test benches. This
actor coordinates the back-office record keeping around that plant —
it never touches the welding/assembly/test-bench equipment directly,
and it is never the ECE R78 (motorcycle brake system) / ECE R40
(motorcycle emissions) type-approval authority.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — frame-weld/engine-assembly/final-assembly batch, output-quality/test-result data logging (administrative, not an operational decision)
- `:schedule-maintenance` — welding/assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a frame-weld-defect/brake-safety/emissions-compliance concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(welding/assembly/test-bench equipment, structural-integrity/brake-
safety hazard, emissions-compliance hazard, ECE R78/R40 type-approval
certification, direct rider/road-user-safety consequence):

- Does NOT control welding, assembly, or test-bench equipment directly
- Does NOT make plant-safety or type-approval decisions (that's the plant supervisor's / type-approval body's exclusive human/institutional authority)
- Does NOT actuate welding/assembly/test-bench equipment (human plant supervisor decides)
- Does NOT self-issue an ECE R78 (motorcycle brake system) / ECE R40 (motorcycle emissions) type-approval certification mark (the accredited type-approval body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and type-approval requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`motomfg.operation/build`, a langgraph-clj StateGraph):
1. **`motomfg.advisor`** (sealed intelligence node, `MotoAdvisor`): proposes decisions only, never commits
2. **`motomfg.governor`** (independent, `Motorcycle Plant Operations Governor`): validates against domain rules, re-derived from `motomfg.registry`'s pure functions and `motomfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct welding/assembly/test-bench-equipment control)
     - Directly actuating welding/assembly/test-bench equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing an ECE R78/ECE R40 motorcycle type-approval certification (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-category` value on a production-batch patch
     - No physically implausible `:engine-displacement-cc` value on a production-batch patch
     - No physically implausible `:weld-defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`motomfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`motomfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
