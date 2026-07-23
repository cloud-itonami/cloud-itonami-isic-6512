# cloud-itonami-isic-6512

Open Business Blueprint for **ISIC Rev.5 6512**: Non-life insurance. This
repository publishes a property/casualty underwriting/policy-binding and
claim-settlement execution actor as an OSS business that any qualified,
licensed operator can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511)
(life insurance; Underwriter-LLM ⊣ UnderwritingGovernor). Here it is
**Underwriter-LLM ⊣ Non-Life Insurance Governor**.

> **Why an actor layer at all?** An LLM is great at drafting an
> underwriting-document checklist, normalizing policy intake, flagging a
> thin KYC file, or normalizing a filed claim -- but it has **no notion
> of which jurisdiction's insurance-regulator requirements are official,
> no license to underwrite, no business being the one that decides real
> property/casualty coverage is issued today, and no way to know on its
> own whether a settlement would pay out more than a policy's own
> coverage limit**. Letting it bind a policy or settle a claim directly
> invites fabricated underwriting requirements, laundering sanctioned
> parties into coverage or payouts, and silent liability for whoever
> runs it. This project seals the Underwriter-LLM into a single node and
> wraps it with an independent **Non-Life Insurance Governor**, a human
> **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor drafts and governs a non-life (property & casualty)
insurance workflow: policy intake, per-jurisdiction underwriting-
document checklisting, policyholder/claimant KYC-sanctions screening, a
policy-binding proposal, claim filing, and a claim-settlement proposal.
It does **not**, by itself, hold a license to underwrite insurance in
any jurisdiction, and it does not claim to. Whoever deploys and
operates a live instance (a licensed P&C insurer, an MGA's underwriting/
claims ops team) supplies the jurisdiction-specific license, the real
KYC/AML program, the real actuarial-rate/policy-administration
integrations and the real claims-adjustment expertise, and bears that
jurisdiction's liability -- the software supplies the governed,
spec-cited, audited execution scaffold so that operator does not have
to build the compliance layer from scratch for every new market.

### Actuation

**A real policy binding (issuing real property/casualty coverage) or a
real claim settlement (paying out a real claim) is never autonomous, at
any phase, by construction.** Two independent layers enforce this
(`casualty.governor`'s `:actuation/bind`/`:actuation/settle-claim`
high-stakes gate and `casualty.phase`'s phase table, which never puts
either op in any phase's `:auto` set) -- see `casualty.phase`'s
docstring and `test/casualty/phase_test.clj`'s `policy-bind-never-auto-
at-any-phase`/`claim-settle-never-auto-at-any-phase`. The actor may
draft, check, screen and recommend; a human operator (a licensed
underwriter or claims adjuster) is always the one who actually binds
coverage or settles a claim.

## The core contract

```
policyholder/claimant intake + jurisdiction facts (casualty.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Underwriter- │ ─────────────▶ │ Non-Life Insurance      │  (independent system)
   │ LLM (sealed) │  + citations   │ Governor: spec-basis ·  │
   └──────────────┘                 │ KYC · policy-not-bound  │
                             commit ◀────┼──────────▶ hold │ claim-exceeds-coverage
                                 │             │           │ · claim-already-settled
                           record + ledger  escalate ─▶ human  (all un-overridable)
                                             (ALWAYS for
                                              :policy/bind AND
                                              :claim/settle)
```

**The Underwriter-LLM never binds a policy or settles a claim the
Non-Life Insurance Governor would reject, and never does either without
a human sign-off.** Hard violations (fabricated jurisdiction
requirements; sanctions hit; incomplete documents; a claim against an
unbound policy; a settlement exceeding the policy's own coverage limit;
a double-settlement) force **hold** and *cannot* be approved past; a
clean binding or settlement proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean policy-bind lifecycle + one clean claim-settlement lifecycle + six HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a claims-site damage-
inspection drone documents property and vehicle loss for the human
adjuster, under the actor, gated by the independent **Non-Life
Insurance Governor**. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Non-Life Insurance Governor, policy-binding + claim-settlement draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6512`). Related capability contracts (policy/premium/claim shapes) are
published as [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance);
this actor's `casualty.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship `cloud-itonami-isic-6511`'s
`underwriting.*` has toward the same lib.

## Layout

| File | Role |
|---|---|
| `src/casualty/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + policy-binding + claim-settlement history |
| `src/casualty/registry.cljc` | Policy-binding + claim-settlement draft records (no fabricated international check-digit/settlement-number standard -- see docstring) |
| `src/casualty/facts.cljc` | Per-jurisdiction non-life underwriting requirement catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/casualty/underwriterllm.cljc` | **Underwriter-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/KYC/binding/claim-filing/claim-settlement proposals |
| `src/casualty/governor.cljc` | **Non-Life Insurance Governor** -- 6 HARD checks (spec-basis · sanctions hold · document-complete · policy-not-bound · claim-missing · claim-exceeds-coverage) + double-settlement guard + 1 soft (confidence/actuation gate) |
| `src/casualty/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess/screen → supervised (binding and claim settlement always human; policy intake and claim filing auto-eligible, no capital risk) |
| `src/casualty/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/casualty/corporate_intel.cljc` | optional cross-reference into [`cloud-itonami-isic-8291`](https://github.com/cloud-itonami/cloud-itonami-isic-8291)'s `:disclosure/screen-name` (ADR-2607110400 §5) -- catches a policyholder/claimant clean on every LOCAL field but flagged in 8291's own sourced PEP/sanctions data; wired into `screen-kyc` via an injected fn, default is a no-op so every prior caller's behavior is unchanged unless explicitly opted in |
| `src/casualty/sim.cljc` | demo driver |
| `test/casualty/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage · corporate-intelligence integration |
| `wasm/claim_coverage.kotoba` | PoC: a WASM-compiled (`kotoba-lang/kotoba` -> `kotoba-lang/kototama`'s `actor:host` ABI) port of `governor.cljc`'s `claim-exceeds-coverage-violations` pure comparison -- see `wasm/README.md` for scope, the input/output ABI, and what's out of scope (Store, the claim/policy lookups, the StateGraph) |

## Business-process coverage (honest)

This actor covers policy intake through binding, and claim filing
through settlement -- the two governed lifecycles this blueprint's own
`docs/business-model.md` names as its core Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Policyholder policy-application intake + per-jurisdiction underwriting-document checklisting, HARD-gated on an official spec-basis citation (`:policy/intake`/`:jurisdiction/assess`) | Premium billing and collection (this blueprint's own Offer lists it; not yet a governed op here) |
| Policyholder/claimant KYC-sanctions screening, HARD-gated un-overridable on any hit (`:kyc/screen`) | Damage-evaluation ROUTING to a human adjuster (loss/damage assessment itself is a real-world physical inspection -- see "Robotics premise" -- not modeled as a governed data op) |
| Policy-binding proposal off a satisfied document checklist (`:policy/bind`) | Real transfer-agent/banking-payment integration, tax/regulatory reporting |
| Claim filing against a bound policy, HARD-gated on the policy actually being bound (`:claim/file`) | Post-binding policy endorsements (mid-term coverage changes) |
| Claim-settlement proposal, independently checked against the policy's OWN recorded coverage-amount and double-settlement-protected by claim id (`:claim/settle`) | |
| Immutable audit ledger for every intake/assessment/KYC/binding/claim/settlement decision | |

Extending coverage is additive: add the next gate as its own governed
op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records before
any real-world act" pattern this repo's two flagship ops already
establish.

## Jurisdiction coverage (honest)

`casualty.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `casualty.facts/catalog` --
currently 5 seeded (JPN, USA-NY, GBR, DEU, KEN) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `casualty.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Underwriter-LLM` + `Non-Life Insurance Governor` run
as real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the sibling
`cloud-itonami-isic-6511`'s architecture. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
