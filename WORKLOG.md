# Oct 27 2026 

## 12:32 PM IST
I got the problem yesterday evening around 7. I have mostly been absorbing the problem space and trying to get to the core of what the problem is getting at. I feel I have a fairly idea now.

The high level work is building an in memory ledger and replaying the events as they come. But when looking at the details provided about different things, there are quite a few things that stand out.

Before I begin implementation work, I will spend some time here writing about the things that clearly stood out, assumptions I need to make and ambiguous I need to address. This writing will also bring out the ideas that are currently going on in my head and help me crystallize them.

### The Events
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

### The output
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

## 2:53 PM IST
I will move the above two sections to the AMBIGUITIES.md file. Continue from there as I go through the other areas that are underspecified.

I will now proceed to write about the concept of closing balance.

Done with it. Committing

## 3:36 PM IST
What else do we have. Lets see. 
BAH accounts overdraft fee isn't given.
Then interest round off method isn't given.
Then the whole Authorization piece.

## 4:37 PM IST
Have completed the sections about interest and overdraft fee.

There are 2 pieces pending now - Authorization and then also the installment computation. I will come back to it after a break.

## 7:38 PM IST
Resuming now with the concept Authorization.
