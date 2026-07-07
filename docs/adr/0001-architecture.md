# ADR-0001: cloud-itonami-isic-6512 -- Underwriter-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511` ADR-0001 (Underwriter-LLM ⊣
  UnderwritingGovernor for life insurance, the pattern this ADR ports to
  non-life/property-casualty), ADR-2607032000 (`cloud-itonami` insurance
  (ISIC 65/66) + real-estate (ISIC 68) coverage push -- the blueprint
  scaffold this ADR deepens), langgraph-clj ADR-0001 (Pregel superstep +
  interrupt + Datomic checkpoint)
- Context: `cloud-itonami-isic-6512` published a business/operator-model
  blueprint (ADR-2607032000's insurance coverage push) but stopped at
  `:blueprint` maturity -- no governed actor implementation. This ADR
  deepens it to `:implemented`, modeled closely on the sibling
  `cloud-itonami-isic-6511`'s (life insurance) architecture, chosen as
  the next ISIC blueprint vertical to promote after the three-actor
  VC-fund system (`cloud-itonami-isic-6499`/`6430`/`6630`) reached its
  own documented gaps' saturation point.

## Problem

Non-life (property & casualty) insurance needs FOUR different kinds of
judgment life insurance's single "binding" actuation does not cover:

1. **Jurisdiction underwriting correctness** -- are the required
   underwriting/disclosure documents based on an official insurance-
   regulator source?
2. **KYC/sanctions screening** -- does the policyholder or claimant
   match a sanctions/PEP list?
3. **Real actuation #1: binding a policy** -- an irreversible real-world
   act (coverage begins, premium obligations attach) -- the SAME
   actuation `cloud-itonami-isic-6511`'s `:policy/bind` performs.
4. **Real actuation #2: settling a claim** -- a SECOND, DISTINCT
   irreversible real-world act (real money leaves the insurer) that
   life insurance's own R0 build never modeled at all, since a life
   policy pays out at maturity/death rather than against a filed loss
   event. Settling a claim needs its OWN independent check life
   insurance has no analog for: does the settlement amount exceed the
   policy's own coverage limit?

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run underwriting and claims with an LLM" but
"seal the LLM inside a trust boundary and layer requirement-
authenticity, KYC/sanctions, coverage-limit enforcement, audit and
human-approval on top of it, while structurally fixing BOTH real
actuation events as human-only."

## Decision

### 1. Underwriter-LLM is sealed into the bottom node; it never binds or settles directly

`casualty.underwriterllm` returns exactly six kinds of proposal: intake
normalization, jurisdiction underwriting-document checklist, KYC/
sanctions screening, policy-binding proposal, claim-filing
normalization, and claim-settlement proposal. No proposal writes the
SSoT or issues real coverage/payouts directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 non-life-insurance operation

`casualty.operation/build` is the SAME StateGraph shape as
`cloud-itonami-isic-6511`'s `underwriting.operation` (intake → advise →
govern → decide → commit | hold | request-approval), copied verbatim --
this graph shape is entirely generic across every op this actor
performs, life or non-life. One graph run corresponds to one non-life-
insurance operation, with no unbounded inner loop.

### 3. Non-Life Insurance Governor is a separate system from Underwriter-LLM

`casualty.governor` has seven checks: spec-basis · sanctions-hit ·
document-complete · policy-not-bound · claim-missing · claim-exceeds-
coverage (HARD, un-overridable) + confidence-floor · actuation-gate
(SOFT, human decides). A separate double-settlement guard needs no
upstream comparison at all (off this actor's own claim history).

### 4. Claims are a genuinely new lifecycle, not a copy of policy-binding

Unlike life insurance (which pays out once, at maturity or death, with
no analog to a "filed claim"), property/casualty insurance is
claims-driven: a policyholder files a claim citing a loss event, and
the insurer settles it. `casualty.store`'s `:claims` collection is NOT
pre-seeded (unlike `:policies`, which mirrors `underwriting.store`'s
seeded `:applications`) -- claims only come into existence via the
governed `:claim/file` op, since a real claim is filed after the fact,
not known at directory-seed time. `:claim/file` itself moves no capital
(it is data entry, the same posture `:policy/intake` has), so it is
auto-eligible at phase 3 once its own HARD check
(`policy-not-bound-violations` -- a claim cannot be filed against
coverage that was never bound) passes; `:claim/settle` is where real
money actually moves, and is gated exactly like `:policy/bind`.

### 5. Real actuation is structurally always human-only (enforced by two independent layers), for BOTH actuation events

`casualty.governor`'s `high-stakes` set has TWO members,
`:actuation/bind` and `:actuation/settle-claim` (`:actuation/bind`
always escalates; `:actuation/settle-claim` always escalates), and
`casualty.phase`'s phase table never puts EITHER op in any phase's
`:auto` set. Neither depends on the other being implemented correctly.

### 6. No fabricated international policy-number or settlement-number standard

Same discipline as `cloud-itonami-isic-6511`'s `underwriting.registry`:
there is no single international check-digit standard for a property/
casualty policy number or a claim-settlement number. `casualty.registry`
therefore does not invent one; it validates required fields and assigns
a jurisdiction-scoped sequence number only, for both `register-binding`
and the new `register-claim-settlement`.

### 7. Relationship to `kotoba-lang/insurance`

`kotoba-lang/insurance` (the blueprint-tier capability lib backing all 7
insurance ISIC classes) publishes pure policy/premium/claim/underwriting-
decision contracts with no governor or human-approval workflow.
`casualty.*` is a self-contained governed implementation for this one
class -- the same relationship `cloud-itonami-isic-6511`'s
`underwriting.*` has to the same lib. The two are not required to share
code: the capability lib is for operators who only need the data
contracts; the actor is for operators who want the full governed
execution scaffold.

## A real bug caught during demo verification

The first draft of `sanctions-violations` wrapped the entire check in
an `(contains? #{:policy/bind :claim/settle} op)` guard, copying the
SHAPE of `underwriting.governor`'s equivalent check without its most
important detail: in the ORIGINAL, `hit-in-proposal?` (does THIS
proposal itself report a `:hit` verdict -- i.e. a `:kyc/screen` that
just found one) is evaluated UNCONDITIONALLY, with no `op` guard at
all; only the SEPARATE `hit-on-file?` branch (a PRIOR hit already on
record, checked when binding/settling) is scoped to specific ops. My
initial op-guard silently prevented `:kyc/screen` itself from EVER
being HARD-held on its own sanctions finding -- caught by running the
demo and inspecting the audit ledger, where the sanctioned-party
kyc/screen scenario showed `:disposition :escalate` (a mere phase-
approval routing) instead of the expected un-overridable HOLD. Fixed by
removing the outer guard so `hit-in-proposal?` is unconditional again,
matching the sibling repo exactly. This is a good illustration of why
"model closely on an existing pattern" still needs line-by-line
verification, not just shape-matching -- and why running the demo and
reading the actual ledger output (not just trusting lint/compile
success) is part of this fleet's standard verification discipline.

## Consequences

- (+) Non-life insurance gets the same governed, auditable-actor
  treatment as life insurance (`cloud-itonami-isic-6511`), without
  centralizing liability in one vendor -- any licensed P&C insurer/MGA
  can fork and run their own instance.
- (+) The actuation invariant (governor + phase, two layers, for BOTH
  `:policy/bind` and `:claim/settle`) is regression-tested by
  `test/casualty/phase_test.clj`'s `policy-bind-never-auto-at-any-phase`/
  `claim-settle-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/casualty/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern `underwriting.store` uses.
- (+) The claims lifecycle is a genuine extension beyond the sibling
  repo's own scope (life insurance never modeled claims at all), proven
  by a clean claim-filing-through-settlement demo path plus four
  claims-specific HARD-hold cases (unbound policy, exceeds coverage,
  missing claim, double-settlement).
- (-) This R0 seeds only 4 jurisdictions (JPN, USA-NY, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `casualty.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) Premium billing/collection, damage-evaluation routing to a human
  adjuster (a real-world physical inspection, not a governed data op),
  post-binding endorsements, real actuarial-rate-table integration, real
  policy-administration-system integration, and real KYC/sanctions-
  screening provider integration are all out of scope for this OSS actor
  -- each operator's responsibility (see README's coverage table).
- 34 tests / 172 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6512` at `:blueprint` only | ❌ | Leaves non-life insurance without an `:implemented` reference actor, unlike its life-insurance sibling |
| Model claims as a variant of policy-binding (one actuation op, not two) | ❌ | Claims are a genuinely distinct real-world act (a payout against an EXISTING bound policy, gated by that policy's own coverage limit) from binding (issuing NEW coverage) -- collapsing them would hide the claim-exceeds-coverage check life insurance has no analog for |
| Pre-seed the `:claims` collection like `:policies`/`:applications` | ❌ | A real claim is filed by a policyholder AFTER a loss event, not known at directory-seed time -- claims must come into existence only via the governed `:claim/file` op, the same "don't invent facts that don't exist yet" discipline `casualty.facts` applies to jurisdictions |
| Require `kotoba.insurance` (the capability lib) directly from `casualty.*` | ❌ | Neither `underwriting.*` nor any other sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
| Fabricate a global policy-number/settlement-number check-digit standard for conformance-test rigor | ❌ | No such standard exists for non-life insurance (same as life insurance); inventing one would be dishonest |
