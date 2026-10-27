## Oct 27 2026 12:32 PM IST

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

Long story short:
The events are not in chronological order.
