# Ledger Core

An in-memory account ledger written in Kotlin. It replays the six-day event stream for ACC-001 (AED) and ACC-002 (BHD). For each account and day it prints the closing ledger balance, available balance, hold states, entries posted, fees, rejections and alerts.

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

## Reading the replay output

### Per-day blocks

Each block is one account on one **booking day**:

```
=== ACC-001 | Day 4 ===  closing AED 285.00 · available AED 285.00 · holds: Auth-A SETTLED
┌──────┬────┬────────────┬─────────┬───────────┬────────────────┐
│ From │ #  │ Type       │  Amount │ Value day │ Note           │
├──────┼────┼────────────┼─────────┼───────────┼────────────────┤
│ E5   │ #4 │ SETTLEMENT │ -185.00 │ Day 4     │ settles Auth-A │
│ E6   │ #5 │ SETTLEMENT │ -180.00 │ Day 4     │ settles Auth-Z │
└──────┴────┴────────────┴─────────┴───────────┴────────────────┘
Alerts
...
```

- **Header line**
  - `closing`: that day's closing ledger balance, i.e. the sum of all entries with value day ≤ that day. It is computed when the day closes, so it includes fees.
  - `available`: ledger balance minus active holds.
  - `holds`: every authorization seen on the account and its current state (`ACTIVE`, `SETTLED`, `EXPIRED`, `DECLINED`).
- **Table**: everything *booked* that day, in the order it happened. Event postings come first, then the close (fees, then interest capitalization).
  - `From` is the event id, or `end of day` for entries the ledger posts itself (fees, fee refunds, interest).
  - `#` is the global entry id. It increases in the order entries were appended.
  - `Value day` is the day the entry counts towards. When it differs from the block's day, the entry is back-dated. For example, E7 is booked on Day 5 with value day Day 2, so it appears in the Day 5 block. The three overdraft fees it causes are all booked on Day 5, with value days 2, 4 and 5.
  - `Note` explains system entries. Fees show the closing balance that triggered them. Refunds and reversals name the entry they compensate.
  - `no activity` means nothing was booked that day.
- **Rejected events / Alerts**: shown under the day they were raised. A rejection means the event posted nothing. An alert means the event was applied but needs a person to look at it (e.g. E6 settling Auth-Z, which was never authorized).

### Why the blocks are not in strict day order

Days close **per account**, triggered by the next event for that account. An event booked on Day N closes that account's days before N. The window end (Day 6) closes whatever is left. So ACC-001's Day 5 is closed by E9 (booked Day 6), and ACC-002's Days 1–4 are only closed when E10 arrives. That is why ACC-002's early days print after ACC-001's Day 5, and ACC-001 Day 6 prints before ACC-002 Day 5. The reasoning is in [AMBIGUITIES.md](AMBIGUITIES.md) under *The Events* and *The output*.

### Summary tables

After the replay, three tables cover the whole run:

- **Final ledger**: every entry ever appended, with booking day and value day side by side. Nothing in it is ever edited or removed. Corrections are new rows with `compensates #n`.
- **All rejected events**: empty for the given stream.
- **All alerts**: the Auth-Z settlement.

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
| `AuthorizationTest` | approval at exactly zero, decline by one fil, holds stacking, partial settlement, expiry |
| `RejectionTest` | unknown account, wrong currency, bad amounts, instalments that don't add up to the total, invalid reversals, duplicate auth ids |
| `IdempotencyTest` | redelivered events are ignored; a reused id with different content is rejected |
| `InterestIntervalTest` | capitalization cycles and back-dated adjustments after capitalization |
| `BackdatingLimitTest` | value dates at the 45-day limit, and before the checkpoint with and without a backdated authorization |

## Code map

| File | Role |
|---|---|
| `Scenario.kt` | the accounts and the ten events from the spec |
| `EventStream.kt` | feeds events in order and infers end-of-day per account |
| `EventProcessor.kt` | idempotency check, then applies the event or closes a day, and builds the daily report |
| `Ledger.kt` | all business rules: posting, holds, fees and refunds, interest, balances |
| `Policy.kt` | the constants (fee, rate, hold expiry); see [NUMBERS.md](NUMBERS.md) |
| `repo/` | append-only storage, with no rules in it |
| `ReportPrinter.kt`, `Table.kt` | output formatting |
| `Stepper.kt` | the interactive walkthrough |

## Other documents

- [NUMBERS.md](NUMBERS.md): every constant and why it has that value
- [AMBIGUITIES.md](AMBIGUITIES.md): every ambiguity in the spec and how it was resolved
- [REJECTED.md](REJECTED.md): acceptance criteria refused, and approaches abandoned
- [WORKLOG.md](WORKLOG.md): timestamped work log
