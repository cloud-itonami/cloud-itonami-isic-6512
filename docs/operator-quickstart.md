# Operator Quickstart

Get this Non-Life Insurance Governor up and running in minutes.

## Who This Is For

- **Licensed P&C insurers** and **MGAs** building new underwriting and claims operations
- **Engineering teams** that need a governed, auditable insurance workflow scaffold
- **Operators** who want to seal an LLM into a single proposal node, not let it decide coverage autonomously

If you're evaluating whether an LLM should bind policies or settle claims: the answer is **no by construction here**, via an independent Governor that re-verifies every LLM proposal against your actual records.

## Prerequisites

- **Clojure CLI** (`clojure` 1.12.0+)
- **Java 11+**
- *Optional*: ClojureScript test suite requires Node.js 18+

This repository is standalone (forkable outside the monorepo). If you're inside the workspace and want local dev with transitive dependency overrides, the `:dev` alias pins `langgraph-clj` and `langchain-clj` to local checkouts.

## Run the demo

Walk a clean policy-binding lifecycle and a clean claim-settlement lifecycle through the actor, plus six hard-hold cases:

```bash
clojure -M:dev:run
```

This runs `src/casualty/sim.cljc`, the demo driver. You'll see:
- Policy intake and underwriting assessment
- KYC-sanctions screening
- Policy-binding proposal (human sign-off required)
- Claim filing against the bound policy
- Claim-settlement proposal with coverage validation

## Run tests

The governor contract, phase invariants, and policy/claim store integrity are verified by:

```bash
clojure -M:dev:test
```

This suite covers:
- Governor hard checks (spec-basis, sanctions, document-complete, policy-not-bound, claim-missing, claim-exceeds-coverage)
- Phase table invariants (binding and claim settlement never auto-eligible)
- Store and registry conformance
- Facts coverage (jurisdiction citations)

**Optional: ClojureScript portable suite** (primary gate for cross-platform .cljc):

```bash
clojure -Sdeps '{:paths ["src" "test"]}' -M:dev:cljs \
  -m cljs.main --target node -m casualty.portable-cljs-test-runner
```

## Static analysis

Lint with clj-kondo (errors fail CI):

```bash
clojure -M:lint
```

## Architecture reference

**Governor location:** `src/casualty/governor.cljc`

The Non-Life Insurance Governor is a pure function that:
1. Accepts a proposal from the Underwriter-LLM and the current actor state
2. Performs six hard checks (see docstring for details)
3. Returns a gate result: `:pass` (human approval route), `:hold` (forces escalation), or `:escalate-high` (high-stakes action)

**Core components:**
- `src/casualty/operation.cljc` — OperationActor (langgraph-clj StateGraph)
- `src/casualty/underwriterllm.cljc` — Underwriter-LLM Advisor (mock-advisor ‖ llm-advisor)
- `src/casualty/governor.cljc` — Non-Life Insurance Governor
- `src/casualty/phase.cljc` — Phase state machine (read-only → intake → assess/screen → supervised)
- `src/casualty/store.cljc` — Store protocol (MemStore ‖ DatomicStore) + append-only audit ledger
- `src/casualty/facts.cljc` — Per-jurisdiction underwriting requirements with spec-basis citations

## Demo page

The demo driver (`casualty.sim`) outputs JSON records of each operation. To publish or integrate with a web interface:

1. Capture the JSON output from `clojure -M:dev:run`
2. Wire it into a static HTML dashboard (or use an existing REST adapter)
3. Serve via your preferred host (GitHub Pages, Netlify, or your own server)

See `docs/adr/0001-architecture.md` for the full architecture and decision record.

## Next steps

- **Fork this repository** for your licensed operator instance
- **Configure the governor's hold/escalation policy** (see `docs/operator-guide.md`)
- **Integrate your Store** (DatomicStore for production, MemStore for testing)
- **Wrap the OperationActor in your jurisdiction's compliance layer** (real KYC/AML, real actuarial rates, real claims adjusters)
- **Publish your audit ledger** to an immutable backend (blockchain, append-only database)

See `docs/business-model.md` for the full business model and revenue streams.
