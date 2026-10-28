# Numbers

Ledger constants, their locations, and the rationale for their values.

Configurable values are defined in `Policy.DEFAULT` ([Policy.kt](src/main/kotlin/ledger/Policy.kt)). Currency precision lives in `Currency` ([Money.kt](src/main/kotlin/ledger/model/Money.kt)).

## Summary

| Number | Value | Where | Source |
|---|---|---|---|
| Overdraft fee | AED 25.00 | `Policy.overdraftFees` | spec |
| Overdraft fee, BHD | none | `Policy.overdraftFees` | assumption |
| Daily interest rate | 0.0004 | `Policy.dailyInterestRate` | spec (0.04%) |
| Interest capitalization interval | 6 days | `Policy.interestCapitalizationIntervalDays` | derived from spec |
| Hold expiry | 7 days | `Policy.holdExpiryDays` | assumption |
| Settlement overage tolerance | 20% of the hold | `Policy.settlementOverageTolerance` | assumption |
| Back-dating limit | 45 days | `Policy.backdatingLimitDays` | assumption |
| AED precision | 2 decimals | `Currency.AED` | spec / ISO 4217 |
| BHD precision | 3 decimals | `Currency.BHD` | spec / ISO 4217 |
| Interest round-off mode | HALF_EVEN | `Money.rounded` | assumption |

## Overdraft fee: AED 25.00

The spec defines the fee as AED 25.00. It applies as follows:

- **Once per value day, not once per close.** A day that is negative every time it is evaluated is charged once. `OverdraftFeeTest` closes the same negative day repeatedly and checks there is only one fee.
- **Re-assessed when a back-dated entry changes a closed day.** This is why E7 produces fees on Days 2, 4 and 5 and E9 refunds all three (see AMBIGUITIES.md, *Retrospective Overdraft Fees*).

## Overdraft fee for BHD: none

The spec defines an overdraft fee only for AED. No BHD fee is configured. A negative BHD day incurs no fee and raises an `OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY` alert once per value day (`OverdraftFeeTest`).

## Daily interest rate: 0.0004

The spec defines a daily rate of 0.04%, stored as the fraction `0.0004` in `Policy`.

Each day's accrual is `closing balance × 0.0004`, rounded to the currency's precision (see *Interest round-off mode*). It is computed only when the balance is positive. The capitalized amount is the sum of those rounded daily accruals, as required by the spec.

For the replay:

| Account | Daily accruals | Capitalized on Day 6 |
|---|---|---|
| ACC-001 | 0.10, 0.10, 0.26, 0.11, 0.11, 0.11 (on 250, 250, 650, 285, 285, 285) | AED 0.79 |
| ACC-002 | 0.004, 0.004 (on 10.000 for Days 5 and 6) | BHD 0.008 |

Interest is computed on the *current* value-dated balance of each day. Day 2 earns on 250.00, not on −370.00, because E9 reversed E7 before Day 6 closed.

## Interest capitalization interval: 6 days

The spec says accruals capitalize "as a single credit at end of Day 6". With the window starting on Day 1, that is every 6 days, anchored to the ledger's first day (`(today − firstDay + 1) % 6 == 0`).

The interval is configurable and repeats beyond the six-day window. `InterestIntervalTest` runs several cycles, checks that a back-dated change after capitalization is settled at the next capitalization, and checks that the interval is counted from the ledger start, not from absolute day numbers.

## Hold expiry: 7 days

The spec does not define a hold expiry period. The assumed expiry is 7 days: an unsettled hold placed on Day N is released at the close of Day N + 6. Auth-B, placed on Day 5, remains `ACTIVE` at the end of the six-day window.

A late settlement is accepted and raises `SETTLEMENT_AFTER_HOLD_EXPIRY` (`AuthorizationTest`).

## Settlement overage tolerance: 20%

The spec does not say what happens when a settlement is larger than its hold. A settlement is always debited, but anything above the hold was never reserved, so it can take the account negative without an approval. A settlement more than 20% above its active hold raises `SETTLEMENT_EXCEEDS_AUTHORIZATION`. Up to 20% over is accepted silently.

The usual cause of a legitimate overage is a tip added after authorization, and tips commonly run up to about 20%. At half the value, 10%, ordinary restaurant tips would raise alerts and train reviewers to ignore them. Twice the value, 40%, would let a settlement nearly half again the approved amount through unflagged.

The comparison is exact: on a AED 40.00 hold, 48.00 is silent and 48.01 alerts (`AuthorizationTest`). A settlement below its hold releases the whole hold and raises nothing. Auth-A (hold 200.00, settled 185.00) is this case.

## Back-dating limit: 45 days

The spec does not define a back-dating limit. The configured limit is 45 days, allowing one month plus a buffer for delays around month-end close.

A back-dating limit allows older daily summaries to be frozen and their ledger entries archived. An entry dated before the checkpoint needs a backdated authorization and is re-dated to the day after the checkpoint (see AMBIGUITIES.md, *Entries Dated Before the Checkpoint*).

The limit must allow at least 4 days to support E9, booked on Day 6 with a value date of Day 2.

## Currency precision: AED 2, BHD 3

The precisions are defined by the spec and match ISO 4217: the dirham has 100 fils, the Bahraini dinar has 1000 fils. Each `Money` is a whole number of minor units, so an amount with too many decimals cannot be represented. `Money.of` refuses one at parse time rather than rounding it silently.

## Interest round-off mode: HALF_EVEN (banker's rounding)

The spec requires rounding to currency precision but does not specify a rounding mode. The only place rounding happens is the daily interest accrual. Every other amount arrives in minor units and is posted as it is.

HALF_EVEN rounds exact ties to the nearest even minor unit, reducing systematic rounding bias. Discarded fractions are not tracked (see AMBIGUITIES.md, *Daily Interest*).

In this replay no accrual lands exactly on a half (e.g. 285.00 × 0.0004 = 0.114), so HALF_EVEN and HALF_UP give the same numbers.
