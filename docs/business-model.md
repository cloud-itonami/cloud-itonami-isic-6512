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

## Trust Controls

- no policy is bound and no claim is settled without human sign-off
- fabricated loss evidence or an inflated damage estimate forces a hold, not an override
- every bind, claim and payout path is auditable
- emergency manual override paths remain outside LLM control
