# Ambiguites

## Overview
1. Properties of the Event Stream
2. The Output Format
3. Closing Ledger Balance
4. Overdraft Fee
5. Interest

## The Events
We have to replay the events. Now, what are these events? Are the events given to us as they happened in real life? Or, are these events already registered in the system are being replayed for some purpose like reconiliation. 

If I take the former to the case, then the events given in the problem don't conform it. The particularly problematic events are E8, E9, E10. 

E8 - Day 5
E9 - Day 6
E10 - Day 5

So E10 goes back in history. I will take this as indication that we are not replaying the events as they happened in real life. Here I must emphasis that I am taking BookingDate to be authoritative. It is something that the system assigned when the actual events came to the system. So a system had already received these events, for each event the system adds the bookingDate and doesn't take it as an input. So BookingDate is always the date of when the system received the event.

There is an angle of timezone here. Given E10 is happening on Bahrain account and Bahrain is 1 hour behind UAE, we can say E10 actually happened after E10 in actuality. 

E9 - Day 6 - Dubai 00:30 AM
E10 - Day 5 - Bahrain 11:45 PM

We can do away with this complexity by assuming the system that recorded the booking date follows a common booking calendar.

Long story short: The events across *accounts* are not in chronological order. Note the emphasis on accounts. We will discuss that in the next section.

## The output
The problem description simply says "replays the event stream and prints, per day: closing ledger balance, fee assessments, authorization states, and errors" and leaves a lot for us to interpret.

When do we print this?
1. Do we print this as events are being processed?
2. Do we take in all the events, we processed them and then we output?

I would go with the first method. In the previous section, we had established that events are not in chronological order when we look at them as a whole. But within each account, we can proceed with the assumption that booking order is preserved. The given input also indicates the same as E9 and E10 belong to different accounts.

The primary reason for going with (1) is it seems more natural to have the closing balances and other details revealed as we are proceeding through the events rather than collect all event and then have a dump of it towards the end. If I want to see, what was the closing_balance before a particular event, I will have that view. When we dump the data post processing of all events, we lose that context.

Another property you can assume from the input is, after account A is exhaused account B's events are sent. It is possible that some worker is iterating through each account and for each account it is taking all the events and sending it to the stream. In which case we will never have interleaving of accounts. 

Account 1's events
Account 2's events
Account 3's events

From the limited events, we have got this feels like a strong assumption to make. But simplies the question of which account to output. If I am going to processes all events of one account and then move on to another account, then I can always output the data for current account in view.

We are making a strong assumption here. For the purpose of this exercise and the simplicity of presentation of output it leads to, we can proceed with it.

It is also important to illustrate what kind of simplicity I am alluding to.

If we are not making this assumption:
We have 2 accounts, Acc1 and Acc2. At the turn of day, we output both the accounts' closing data.

E3 Day 5 Acc1
E4 Day 6 Acc1 -> day moved - print Acc1 and Acc2's closing data as per day 5
E5 Day 2 Acc2 -> day moved - what do I do now? I had already posted Acc1 and Acc2's for day 5

We can solve this by keep some data and indicating it in our output table. Acc1's data is at Day 6, Acc2's data is at Day 1. Rather than dealing with all this, we will proceed with the assumption that events belongs to an account are going to be grouped together.

## Closing Ledger Balance
There are 2 notions of closing balance.

One is whatever gets recorded in your statement. This is frozen and the back dated events don't change this. Generation of statement follows the similar mechanics as our replaying of the events. We will use this notion of closing balance when *printing output*. It is printed once the day ends and its never changed. Even if we print output after processing all the events, we won't let backdated events influence this.

Then the notion of closing ledger balance that is used to compute your interest and fee calculations. The back dated events do influence this. This is also explicitly called out in the spec under non-negotiable rules.

> day's closing ledger balance (all entries with value_date ≤ that day) is negative

Having established the 2 notions of closing ledger balance, lets take a look at this acceptance criteria

> - The Day 2 closing ledger balance, evaluated at end of Day 5 and before any fee is assessed, is AED −370.00.

Which notion of closing ledger balance do we apply to this. If we apply the first, this has to be rejected. Given this talks about evaluation and that too at a later day, I will go ahead and apply the second notion to it and take it to be a valid acceptance criteria.

## Overdraft Fee
The spec gives the overdraft fee for AED accounts but not for BHD accounts. If a BHD account goes negative, what do we do?

We have few options
1. Convert the AED 25 to its equivalent BHD and use that
2. Don't apply the fee and trigger an alert.
3. Throw an error and trigger an alert.

Given the nature of the system, we should never at any point show some balance that could be wrong. Hence we will go with (3) instead of (2).

About (1), we will discard it because exchange rates keep changing. Then we have to deal with dynamic fee which add complexity for the system and also not a great product experience for the user.

## Daily Interest
The spec says 0.04% is the interest percentage. Can we have different interest percentages for different currency accounts? Given the currency agnostic nature of it, we can assume that the same interest is applied for all kinds of accounts.

The spec also doesn't specify how do we do the rounding of daily interest value.

In some countries, the regulator give instructions on how to do the rounding. Eg. RBI in India.

For our purpose, we will go with banker's rounding or nearest even. 

We also won't concern ourself with the digits we are discarding. The rounding could be adding little extra money to the user's account or not adding that. We won't accumulate or track them.
