# Business Model: Non-life insurance

## Classification

- Repository: `cloud-itonami-isic-6512`
- ISIC Rev.5: `6512`
- Activity: property, casualty, motor and health insurance -- underwriting, premium collection, and claims adjustment for damage and loss events
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- mutual and cooperative general insurers
- community motor and crop-insurance pools
- licensed independent property & casualty insurers avoiding closed core-insurance SaaS lock-in

## Offer

- policy intake and underwriting proposal
- premium billing and collection
- claim intake, damage-evaluation routing, and settlement proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per policy-in-force
- support: monthly retainer with SLA
- migration: import from an incumbent core-insurance system or spreadsheets
- claims-API access fee

| Package | Customer | Price shape |
|---|---|---|
| Managed Starter | one mutual/cooperative/small licensed P&C insurer or motor/crop pool, 5-15 underwriting + claims staff | ¥45,000/月 flat |

**Market-anchored (2026-08-10)**: benchmarked against 5 real competitor
products, converted at ~¥150/$ for the assumed customer above (8 working
seats). **3 of the 5 publish real numbers; the 2 that do not are exactly the
two that sit on the carrier side of this market.**

- **ClaimWizard** (published): "Single User Package $99/Month"; "Business
  Package $250 Starting at Per Month" including up to 3 assignable licenses,
  each additional license "$50/Month", no setup or signup fees —
  <https://claimwizard.com/pricing/>. At 8 seats: $250 + 5 x $50 = $500/月
  ≈ **¥75,000/月**.
- **Claimable** (published, starting price only): "three price plans,
  starting at £59/$79/€69" per user/month, no setup fee, no minimum user
  count, 15% annual discount —
  <https://www.claimable.com/product/benefits/affordability/>. At 8 seats:
  8 x $79 = $632/月 ≈ **¥94,800/月**.
- **ComplyCube** (published; single-function comparator for the KYC/sanctions
  screening step only): Starter $99/mo, Core $299/mo, AML screening
  $0.50/check (Starter) / $0.35/check (Core+), additional team member
  $30/month — <https://www.complycube.com/en/pricing/>. ≈ **¥14,850-44,850/月**
  plus per-check usage.
- **Insly** (MGA/insurer policy administration): **price not disclosed** —
  the pricing page carries "Starting from" with no amount and routes to "Get
  a tailored quote", describing only a monthly base package plus modules —
  <https://insly.com/pricing/>.
- **EXEX少額短期保険** (システムエグゼ; Japanese small-amount short-term
  insurance core system): **price not disclosed** — the product page explains
  the cloud-licence vs server-licence split but states no figure and routes to
  a contact form — <https://www.system-exe.co.jp/product/exex/ssi>.

The shape of that disclosure matters more than any single number: **the
products that publish are claims-handling and screening tools bought by
adjusters and compliance teams, while the carrier-side core (policy
administration / underwriting) publishes nothing at all** — neither Insly nor
EXEX, and the same is true of every core-insurance vendor reached during this
survey. On the underwriting side of this industry, price opacity is the
default, not the exception. That is why the confidence attached to this
anchor is **medium**: the measured band brackets the number, but no measured
comparator is itself a carrier-side underwriting product.

**¥45,000/月 sits in the middle of the measured band** (¥14,850-94,800/月).
It is deliberately below the claims-ledger band (ClaimWizard ¥75,000/月,
Claimable ¥94,800/月 at 8 seats), because those are priced as the system of
record that runs loss adjustment, whereas this actor holds no policy or claim
ledger, no rating engine, and no premium-collection or payout rail — it
normalizes intake, checklists jurisdiction documents, screens KYC/sanctions,
and stops at a proposal (`:policy/bind` and `:claim/settle` are never auto at
any phase). It is deliberately above the single-function screening floor
(ComplyCube Starter ¥14,850/月), because this actor carries both the
underwriting and the claim flow, and because the buyer is a licensed insurer
for whom the un-overridable holds — a settlement exceeding the policy's own
coverage limit, a double settlement of an already-paid claim, a claim against
a policy that was never bound — are liability containment that none of the
five comparators sells. The figure is derived only from the measurements
above; it is **not** carried over from the ¥50,000-150,000/月 range used by
the HR/recruiting/CRM-anchored flagships, which has no evidenced relationship
to non-life insurance pricing.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed
Starter tier (¥45,000/月 flat) is available now —
[**subscribe to Managed Starter**](https://buy.stripe.com/8x27sM86z10Y0iY5PQeEo0j).
This is a no-code Stripe-hosted checkout (Gftd Japan 株式会社, JPY); nothing
in this repo's actor code changed. Managed-tenant setup is manual today —
there is no automated onboarding. **No insurer or pool has subscribed to this
tier yet — this is a live, working checkout with zero paid tenants, not a
claim of existing revenue.**

## Trust Controls

- no policy is bound and no claim is settled without human sign-off
- an unaccredited/sanctioned party, a fabricated jurisdiction requirement,
  an incomplete underwriting document set, a claim filed against a
  policy that was never actually bound, a settlement that would exceed
  the policy's own coverage limit, or a double-settlement of an
  already-paid claim -- each forces a hold, not an override
- every intake, assessment, KYC, binding, claim and settlement path is
  auditable
- emergency manual override paths remain outside LLM control
