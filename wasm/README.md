# wasm/ — kotoba-wasm deployment of the claim-exceeds-coverage check

`claim_coverage.kotoba` is a port of `casualty.governor/claim-exceeds-
coverage-violations`'s pure ground-truth comparison — is a claim's
settlement amount within the policy's own recorded coverage-amount limit?
(see `src/casualty/governor.cljc` lines ~140-150) — into the minimal
`.kotoba` language subset, compiled to a real WASM module via
`kotoba wasm emit`, and hosted via `kototama.tender`
(`test/wasm/claim_coverage_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pattern
already proven by `cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`
and `cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`
(ADR-2607062330 addendum 5) — a third sibling actor's hot-path decision
function ported to real WASM.

## Why the source differs from `casualty.governor`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` — no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter). The port therefore:

- Ports ONLY the pure ground-truth arithmetic core — the direct comparison
  of `claimed-amount` against `coverage-amount` — never the store lookups
  (`store/claim`, `store/policy`) or the `:claim/settle` op-dispatch, both
  of which stay in Clojure and never get ported (no maps, no protocols, no
  op-keyword dispatch in the wasm-compilable subset).
- Uses plain positional integer args instead of `{:keys [...]}` map
  destructuring (no maps in the wasm-compilable subset).
- Compares `claimed-amount <= coverage-amount` directly as plain integers
  (smallest currency unit / cents) instead of `governor.cljc`'s
  `(> (double (:claimed-amount c)) (double (:coverage-amount p)))` —
  no floats needed for a direct integer comparison, consistent with
  `cloud-itonami-isic-6492`/`kotoba-card`/`kotoba-banking`'s own
  convention of representing amounts as plain integers.
- Inverts the polarity relative to `governor.cljc`'s violation check:
  `claim-exceeds-coverage-violations` returns non-nil (a violation) when
  the claim EXCEEDS coverage, whereas this module's `claim-within-
  coverage?` (and `main`) returns `1` when the claim is WITHIN coverage
  (i.e. NOT a violation) and `0` when it exceeds coverage — the more
  natural "is this OK" polarity for a boolean-shaped WASM export, same
  polarity convention as `affordability.kotoba`'s `affordable?`.

This is a simpler port than `affordability.kotoba`: one direct comparison,
no multi-term formula, no zero-guard branch.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` — the
compiler only ever exports a 0-arity `main`, see `compile-wasm-expr` in
`kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead — the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba` and
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba` use. A host
writes two little-endian i32 values (cents) before calling `main()`:

| offset | field              |
|--------|--------------------|
| 0      | `claimed-amount`   |
| 4      | `coverage-amount`  |

`main()` returns `1` (claim within coverage — settle-eligible on this
check) or `0` (claim exceeds coverage — a HARD `:claim-exceeds-coverage`
violation per `casualty.governor`). Both offsets are well below
`heap-base` (2048), so they never collide with anything the compiler
itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6512/wasm/claim_coverage.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6512/wasm/claim_coverage.wasm --json
```

Fleet deployment: not attempted in this pass — see cloud-itonami-isic-6492/6511 for the established pattern.
