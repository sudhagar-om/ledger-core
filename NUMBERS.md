# Numbers

Every number the ledger depends on, where it lives, and why it has that value and not half of it.

The tunable ones live in `Policy.DEFAULT` ([Policy.kt](src/main/kotlin/ledger/Policy.kt)). Currency precision lives in `Currency` ([Money.kt](src/main/kotlin/ledger/model/Money.kt)).

## Summary

| Number | Value | Where | Source |
|---|---|---|---|
| Overdraft fee | AED 25.00 | `Policy.overdraftFees` | spec |
| Overdraft fee, BHD | none | `Policy.overdraftFees` | assumption |
| Daily interest rate | 0.0004 | `Policy.dailyInterestRate` | spec (0.04%) |
| Interest capitalization interval | 6 days | `Policy.interestCapitalizationIntervalDays` | derived from spec |
| Hold expiry | 7 days | `Policy.holdExpiryDays` | assumption |
| AED precision | 2 decimals | `Currency.AED` | spec / ISO 4217 |
| BHD precision | 3 decimals | `Currency.BHD` | spec / ISO 4217 |
| Interest round-off mode | HALF_EVEN | `Money.rounded` | assumption |

## Overdraft fee: AED 25.00

The amount is given by the spec, so it is not mine to tune. What I chose is how it applies:

- **Once per value day, not once per close.** A day that is negative every time it is evaluated is charged once. `OverdraftFeeTest` closes the same negative day repeatedly and checks there is only one fee.
- **Re-assessed when a back-dated entry changes a closed day.** This is why E7 produces fees on Days 2, 4 and 5 and E9 refunds all three (see AMBIGUITIES.md, *Retrospective Overdraft Fees*).

Half (AED 12.50) would simply contradict the spec. A rule-level "half" would be charging per event instead of per day, or only on the day the event is booked. The first double-charges whenever a day is re-evaluated. The second misses Days 2 and 4, which the value-dated definition of closing balance clearly makes negative.

## Overdraft fee for BHD: none

The spec only gives an AED fee. I deliberately left BHD out of the map instead of inventing a number:

- Converting AED 25.00 at some exchange rate makes the fee move with the rate, which is a product decision, not an engineering one.
- Picking a round number such as BHD 2.500 would be a made-up fee charged to a customer.

So a negative BHD day charges nothing and raises an `OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY` alert, once per value day (`OverdraftFeeTest`). There is no "half" of a fee that doesn't exist. The alternative was to charge *something*, and charging a customer a number nobody approved is worse than charging nothing and asking.

## Daily interest rate: 0.0004

0.04% per day, stored as the fraction `0.0004`. The rate is from the spec. The risk this number carries is units, not size: 0.04 (4% a day) or 0.004 would both be off by orders of magnitude. The fraction is written out in `Policy` with a comment saying it means 0.04%.

Each day's accrual is `closing balance × 0.0004`, rounded to the currency's precision (see *Interest round-off mode*). It is computed only when the balance is positive. The capitalized amount is the sum of those rounded daily accruals, so the spec's "must sum exactly" holds by construction.

For the replay:

| Account | Daily accruals | Capitalized on Day 6 |
|---|---|---|
| ACC-001 | 0.10, 0.10, 0.26, 0.11, 0.11, 0.11 (on 250, 250, 650, 285, 285, 285) | AED 0.79 |
| ACC-002 | 0.004, 0.004 (on 10.000 for Days 5 and 6) | BHD 0.008 |

Interest is computed on the *current* value-dated balance of each day. Day 2 earns on 250.00, not on −370.00, because E9 reversed E7 before Day 6 closed.

## Interest capitalization interval: 6 days

The spec says accruals capitalize "as a single credit at end of Day 6". With the window starting on Day 1, that is every 6 days, anchored to the ledger's first day (`(today − firstDay + 1) % 6 == 0`).

I made it a parameter rather than hard-coding "Day 6" so the ledger keeps working past the window. `InterestIntervalTest` runs several cycles, checks that a back-dated change after capitalization is settled at the next capitalization, and checks that the interval is counted from the ledger start, not from absolute day numbers.

Half (3 days) would post two credits in the window, on Days 3 and 6. That breaks "a single credit", and from Day 4 the capitalized interest would itself earn interest. In this scenario that compounding rounds to zero, but it changes the rule.

## Hold expiry: 7 days

The spec never says how long a hold lasts, but a hold that never ends would lock part of the balance forever. A hold placed on Day N is released at the close of Day N + 6 if nothing settles it. Auth-B (placed Day 5) is therefore still `ACTIVE` at the end of the window, which matches "Auth-B is never settled inside the window".

Why 7: it is in line with how long card networks commonly let a standard purchase authorization stand before it lapses. Longer windows exist for merchant categories such as hotels and car rental. One value for every hold is a simplification; in production it would depend on the merchant category.

Why not half (3–4 days): settlements are routinely late. Clearing can take several business days, and weekends and holidays stretch that. With a short expiry the hold is released, the customer spends the money again, and then the settlement arrives and pushes the account negative. A late settlement is still accepted and raises `SETTLEMENT_AFTER_HOLD_EXPIRY` (`AuthorizationTest`), but a short expiry would make that the normal case instead of the exception. Neither 7 nor 3 changes anything inside this six-day window. The choice only matters past it.

## Currency precision: AED 2, BHD 3

From the spec, and these match ISO 4217: the dirham has 100 fils, the Bahraini dinar has 1000 fils. Each `Money` is a whole number of minor units, so an amount with too many decimals cannot be represented. `Money.of` refuses one at parse time rather than rounding it silently. There is no other sensible value here.

## Interest round-off mode: HALF_EVEN (banker's rounding)

The spec says amounts are rounded to their precision but not how. The only place rounding happens is the daily interest accrual. Every other amount arrives in minor units and is posted as it is.

I chose HALF_EVEN because it rounds exact halves up and down equally often, so over many accruals the rounding error averages out instead of always favouring one side. HALF_UP always rounds halves up, which over many days pays slightly more interest than earned. I don't track the discarded fractions (see AMBIGUITIES.md, *Daily Interest*).

In this replay no accrual lands exactly on a half (e.g. 285.00 × 0.0004 = 0.114), so HALF_EVEN and HALF_UP give the same numbers. The choice only matters at scale.

