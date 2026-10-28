# Criteria rejected

1. E7 causes exactly one overdraft fee to be assessed, on Day 2.

E7 adds fees to three days.


2. Any settlement referencing an authorization ID not present in the ledger must be rejected and the funds must not leave the account.

We are taking an explicit design call to support settlements without corresponding authorization. Please see *Authorization Hold & Settlement* section of AMBIGUITIES.md


3. After E9, all balances and fees return to their pre-E7 values

The ledger entries of fees are going to remain. We will add new compensating entries to negate them. In a sense, they do return to the pre-E7 values, but we would like to explicitly state that new compensating entries will be added and leave no room for misinterpretation.

4. The three BHD instalments in E10 must each be BHD 3.334.

We have taken a design decision that ensures the instalments sum to the total value exactly. In case of this credit of BHD 10.000, the three instalments will be 3.334 + 3.333 + 3.333. Please see *Instalment calculation* section of AMBIGUITIES.md

5. If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded.

We have a non-negotiable rule that gives an invariant that the rounded daily accruals must sum exactly to the capitalized total. The only way to achieve that is by rounding each day's accrual and then summing them. So this can come up only if we haven't followed the rule. If this case does come up, then we will flag it for review, rather than silently discarding the remainder.


# Approaches abandoned mid-build

1. Money as a BigDecimal with a scale check

The first version of `Money` stored a `BigDecimal` and checked in its constructor that the amount had no more decimal places than the currency allows. It worked, but the guarantee lived in a runtime check rather than in the type, and `BigDecimal` equality is scale-sensitive (`1.2 != 1.20`), so `Money` needed hand-written `equals`/`hashCode` over a normalised copy.

Replaced with a `Long` count of minor units (fils for AED, 1/1000 for BHD). A sub-minor-unit amount now cannot be represented at all. `BigDecimal` is used only at the edges: parsing input, printing, and the interest calculation, which rounds (HALF_EVEN) straight back to minor units. Arithmetic uses `Math.addExact` and friends so an overflow throws rather than wrapping. All replay numbers were unchanged by the switch.


2. A notion of "replay" inside the system

The first version treated the event stream as a replay. I removed it. The ledger should not care whether events are being replayed from history or streamed live. It takes an event, books it, and that's it. Replaying is just one way of feeding it events.


3. Errors and alerts as one list

The first version had a single kind of error that mixed two very different things: events we refused, and events we accepted but want someone to look at. Reading the output, you couldn't tell whether money had moved or not.

I split them. A rejection means the event was refused and nothing was posted. An alert means we went ahead and posted, but it needs a review. A declined authorization stopped being an error altogether, because the hold's own status already says it was declined.


4. Storing a daily interest record

The first version stored a separate interest record for every day. But we had said everything would be derived from the ledger, and these records only repeated what the entries' booking and value days already tell us. Worse, a back-dated entry would make them stale and they would need fixing up.

I dropped them. Interest is now worked out from the daily balances when we capitalize.


5. The ledger doing its own storage

The ledger mixed business rules with how and where things were stored. I moved all storage behind a repository, which is in-memory for now, so the ledger only holds the rules. Swapping in a real database later should not touch the rules.


6. The ledger splitting instalments

At first the ledger took the total of an instalment credit and split it into instalments itself. But the split is the sender's business decision, not the ledger's. If the ledger and the sender ever split differently, the customer sees one schedule and the books show another.

Now the event carries each instalment amount. The ledger posts them as sent and only checks that they add up to the total, are all positive and are in the account's currency. If not, it rejects the event.


7. Scanning the whole ledger to get a balance

The simplest design was one flat list of entries, with every balance, fee and interest figure computed by scanning it. This is what I started with. It works for the sample, but it doesn't hold up. A busy account with 10 million entries in a year took about 26 seconds to close one day in a load test, and closing every account every day means scanning everything again and again.

I looked at two ways out:
1. Keep the entries grouped by account, so we only scan one account's entries. A normal customer may have 30,000 entries a year and a small business 100,000. Better, but we still scan the whole history every day, and it keeps growing.
2. Keep a daily balance table per account, updated as entries come in.

I went with (2). End of day now only re-walks from the earliest day that changed since the last close. To keep that walk short, I also limited back-dating to 45 days (see *Backdating window* in AMBIGUITIES.md), so older days can be checkpointed and archived. I kept the slow scanning version in the tests to check the table against.


8. Working out a hold's status from its history

At first a hold had no status of its own. To know whether it was active, we replayed its list of transitions. Every settlement and every daily report had to do this.

But a hold isn't a ledger entry. It is fine for its status to change in place. So a hold now carries its current status, and the transitions stay as the history of how it got there.


9. Separate per-account indexes for holds

While moving to per-account state, I also kept holds and their transitions in per-account indexes. Nothing actually needed them. We look a hold up by account and auth id, and a simple key does that. I went back to storing them flat, like entries, rejections and alerts.


10. Rejections decided in two places

The event processor checked two things itself, a reused event id and a booking day already closed, and then asked the ledger to record the rejection. So the decision and the record lived in different places, and anyone calling the ledger directly skipped both checks.

Now the ledger is the only way an event gets in, and it makes every rejection itself.


11. One processor for events and for closing days

After the above, the event processor was doing two jobs with different triggers: passing each event to the ledger, and closing days when a scheduler says so. The first had become a one-line pass-through, so I removed it. Events go straight to the ledger, and a separate end-of-day processor closes days and builds the reports.


12. Printing everything in the replay

The replay first printed postings, available balance, interest and a full ledger dump along with the daily figures. It was useful while building but buried what the problem asks for. The replay now prints only the closing balance, fees, authorization states and errors per day. Everything else moved to the stepper, where you can look at it event by event.
