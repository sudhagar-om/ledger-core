# Ledger Core

An in-memory account ledger written in Kotlin. It replays the six-day event stream for ACC-001 (AED) and ACC-002 (BHD). For each account and day it prints the closing ledger balance, fee assessments, authorization states and errors. The interactive stepper shows the detail behind them.

There is no web layer, persistence or UI. Everything lives in memory and is driven by `Main.kt`, the interactive stepper and the JUnit suite.

## Requirements

- JDK 21 (anything 17+ should work)
- Nothing else. The Maven wrapper (`./mvnw`) downloads Maven and Kotlin on first run.

## Running

Replay the whole event stream and print the per-day report:

```bash
./mvnw -q compile exec:java
```

Step through the replay one event at a time:

```bash
./mvnw -q compile exec:java -Dexec.args=step
```

Run the test suite:

```bash
./mvnw test
```

This reports **one failure on purpose** (see [Known failing test](#known-failing-test)), so the build ends in `BUILD FAILURE`. To run everything else:

```bash
./mvnw test -Dtest='!SettlementWithoutAuthorizationTest'
```

## Reading the replay output

### Per-day blocks

Each block is one account on one **booking day**, and holds only the four things the spec asks for:

```
=== ACC-001 | Day 5 ===
  Closing ledger balance  AED -410.00
  Fee assessments         OVERDRAFT_FEE AED -25.00 for Day 2 (Day 2 closing AED -370.00)
                          OVERDRAFT_FEE AED -25.00 for Day 4 (Day 4 closing AED -360.00)
                          OVERDRAFT_FEE AED -25.00 for Day 5 (Day 5 closing AED -385.00)
  Authorizations          Auth-A SETTLED
                          Auth-B DECLINED
  Errors                  none
```

- **Closing ledger balance**: the sum of all entries with value day ≤ that day, taken when the day closes. It includes that close's fees and, on Day 6, interest capitalization. It is printed once and never revised. A later back-dated event changes the balance used for fees and interest, not this printed figure (see [AMBIGUITIES.md](AMBIGUITIES.md), *Closing Ledger Balance*).
- **Fee assessments**: overdraft fees and fee refunds booked at this day's close. `for Day N` is the value day the fee was assessed for. The note in brackets is the closing balance that triggered it. E7 is booked on Day 5 with value day Day 2, so its three fees (Days 2, 4 and 5) appear in the Day 5 block, and E9's three refunds appear in Day 6.
- **Authorizations**: every authorization on the account and its state at the close (`ACTIVE`, `SETTLED`, `EXPIRED`, `DECLINED`).
- **Errors**: events raised on that day that need attention. `rejected` means the event posted nothing. `alert` means the event was applied but needs a person to look at it, e.g. E6 settling Auth-Z, which was never authorized.

Available balance, the individual postings, daily interest accruals and the full ledger are not part of the required output. Use the stepper to see them.

### Why the blocks are not in strict day order

Days close **per account**, triggered by the next event for that account. An event booked on Day N closes that account's days before N. The window end (Day 6) closes whatever is left. So ACC-001's Day 5 is closed by E9 (booked Day 6), and ACC-002's Days 1–4 are only closed when E10 arrives. That is why ACC-002's early days print after ACC-001's Day 5, and ACC-001 Day 6 prints before ACC-002 Day 5. The reasoning is in [AMBIGUITIES.md](AMBIGUITIES.md) under *The Events* and *The output*.

## The interactive stepper

`-Dexec.args=step` runs the same replay, but pauses before each event. After each step it shows:

- which days the event closed, and what each close posted
- what the event itself posted
- any rejections or alerts
- a value-day timeline of the account (closing balance, how much this step moved each day, fees, interest, open/closed). This makes the effect of back-dated events like E7 and E9 visible.

At the prompt:

| Input | Does |
|---|---|
| Enter / `n` | process the next event |
| `q` | quit |
| `help` | list commands |
| `view-accounts` | accounts with balances as of the current day |
| `view-entries` / `view-ledger` | every ledger entry |
| `view-holds` | every hold, its status and transition history |
| `view-rejections` | every rejected event |
| `view-alerts` | every alert |
| `view-events` | the scenario's events, marked processed / rejected / pending |

Any `view-` command takes an optional account filter, e.g. `view-entries ACC-001`.

## Tests

`./mvnw test` runs the suite in `src/test/kotlin/ledger`:

| Test class | Covers |
|---|---|
| `ReplayTest` | the full stream: Day 2 at −370.00 before fees, E7's fees and E9's refunds, Auth-Z, interest summing exactly |
| `OverdraftFeeTest` | one fee per value day across repeated closes; BHD gets an alert, not a fee |
| `AuthorizationTest` | approval at exactly zero, decline by one fil, holds stacking, partial settlement, expiry, merchant release |
| `RejectionTest` | unknown account, wrong currency, bad amounts, instalments that don't add up to the total, invalid reversals, duplicate auth ids |
| `IdempotencyTest` | redelivered events are ignored; a reused id with different content is rejected |
| `InterestIntervalTest` | capitalization cycles and back-dated adjustments after capitalization |
| `BackdatingLimitTest` | value dates at the 45-day limit, and before the checkpoint with and without a backdated authorization |
| `SettlementWithoutAuthorizationTest` | **fails on purpose**: rejected criterion 2, a settlement with no authorization (see below) |

### Known failing test

`SettlementWithoutAuthorizationTest` > `settlement for an authorization never seen is rejected and no funds leave` fails by design:

```
expected: <AED 100.00> but was: <AED -80.00>
```

The test is acceptance criterion 2, which I refused in [REJECTED.md](REJECTED.md): a settlement for an authorization the ledger has never seen must be rejected, and no money may leave the account. The spec says nothing about this case, so how to handle it is my own assumption. I accept such a settlement, debit it and raise an alert, because a settlement means the card scheme has already moved the money, e.g. for an offline-approved payment (AMBIGUITIES.md, *Authorization Hold & Settlement*). The test credits AED 100.00 and settles 180.00 against an unknown Auth-Z. The criterion expects the balance to stay at 100.00. My design leaves it at −80.00.

What the failure shows is the cost of that assumption:

- **Money leaves on an unverified message.** No available-balance check runs, so a settlement can overdraw an account that no authorization would have been approved for. In the replay, E6 doubles ACC-001's Day 4 overdraft, from −180.00 to −360.00.
- **The customer can pay for it.** In the test, the Day 1 overdraft fee is caused entirely by a debit the bank never approved.
- **The only safeguard is an alert.** Until someone handles it, the debit and any fees it causes stand.

Rejecting the settlement outright doesn't fix this, because the merchant gets paid either way and the ledger would then understate what the customer owes. The production answer would be to post the debit to a suspense account until it is matched or charged back, and not charge fees caused by it. The comments in the test cover the details.

## Code map

| File | Role |
|---|---|
| `AccountConfiguration.kt` | startup account definitions, independent of the event stream |
| `AccountBootstrap.kt` | provisions accounts through the repository before processing starts |
| `Scenario.kt` | the ten events from the spec and reporting cutoff |
| `EventStream.kt` | feeds events to the ledger in order and infers end-of-day per account |
| `EndOfDayProcessor.kt` | closes days when told to, catching up missed ones, and builds the daily report |
| `LedgerCore.kt` | all business rules: idempotency and every rejection, posting, holds, fees and refunds, interest, balances |
| `Policy.kt` | the constants (fee, rate, hold expiry); see [NUMBERS.md](NUMBERS.md) |
| `repo/` | append-only storage, with no rules in it |
| `ReportPrinter.kt`, `Table.kt` | output formatting |
| `Stepper.kt` | the interactive walkthrough |

## Other documents

- [NUMBERS.md](NUMBERS.md): every constant and why it has that value
- [AMBIGUITIES.md](AMBIGUITIES.md): every ambiguity in the spec and how it was resolved
- [REJECTED.md](REJECTED.md): acceptance criteria refused, and approaches abandoned
- [WORKLOG.md](WORKLOG.md): timestamped work log
