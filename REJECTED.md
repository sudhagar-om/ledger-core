# Criteria rejected

1. E7 causes exactly one overdraft fee to be assessed, on Day 2.

E7 adds fee to three days.


2. Any settlement referencing an authorization ID not present in the ledger must be rejected and the funds must not leave the account.

We are taking a explicit design call to support settlements without corresponding authorization. Please see *Authorization Hold & Settlement* section of AMBIGUITIES.md


3. After E9, all balances and fees return to their pre-E7 values

The ledger entries of fees are going to remain. We will add new compensating entries to negate them. In a sense, they do return to the pre-E7 values, but we would like to explicitly state that new compensating entries will be added and leave no room for mis-interpretation.

4. The three BHD instalments in E10 must each be BHD 3.334.

We have taken a design decision that ensure the instalments sum to the total value exactly. In case of this credit of BHD 10.000, the three instalments will be 3.334 + 3.333 + 3.333. Please see *Instalment calculation* section of AMBIGUITIES.md

5. If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded.

We have a non-negotiable rule that give an invariant that the rounded daily accruals must sum exactly to the capitalized total. The only way to achieve that is by rounding each days accrual and then sum them. So this can come up only if we haven't follow the rule. If this case does come up, then we will flag it for review, rather than silently discarding the reminder.


# Approaches abandoned mid-build

1. Money as a BigDecimal with a scale check

The first version of `Money` stored a `BigDecimal` and checked in its constructor that the amount had no more decimal places than the currency allows. It worked, but the guarantee lived in a runtime check rather than in the type, and `BigDecimal` equality is scale-sensitive (`1.2 != 1.20`), so `Money` needed hand-written `equals`/`hashCode` over a normalised copy.

Replaced with a `Long` count of minor units (fils for AED, 1/1000 for BHD). A sub-minor-unit amount now cannot be represented at all. `BigDecimal` is used only at the edges: parsing input, printing, and the interest calculation, which rounds (HALF_EVEN) straight back to minor units. Arithmetic uses `Math.addExact` and friends so an overflow throws rather than wrapping. All replay numbers were unchanged by the switch.
