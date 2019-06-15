package net.semlang.refill

import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

/*

Basic design thoughts:

1) Two input interfaces here: One is to get the timestamp return values from the setters and pass them into the
getOutcome/getValue calls, which will return something at least as recent as that timestamp. The other is to add a
listener that will get called with any values generated for a given ValueId.
2) Ideally we make the setter calls not block on the whole getNextSteps() process and instead use a BlockingQueue or
something to queue up inputs for processing. That does make the use of timestamps a bit trickier, since that *does*
seem to require some centralization, and also means the timestamps from the raw instance maybe aren't suitable.
Maybe we use a different type of timestamp? Just a specialty object type or something? Make one in the setter and put
it in the queue alongside the input, so later things can correlate an external timestamp to an internal one?
3) Part of the benefit of managing the inputs in a queue at this level is that we can actually implement fairness this
way, just by requiring the getNextSteps() to clear out before submitting the next batch of input changes. This should
be configurable by the user: choose faster response to user actions vs. no starvation if there are constant inputs.
4) So what are the threads/runnables here actually going to look like?

- It would be nice if nothing were running when things are in a quiescent state. This implies that in that case, the
setters are going to need to submit a management job to the executor.
- The management job is what runs getNextSteps() and decides which steps to execute. The runnables we execute should
not only compute values and report their results, but make sure the management job gets resubmitted if it needs to be.
- All of this should work on a single-threaded executor while also taking advantage of multithreading the execute() calls
when more threads are available.

5) How will we track timestamps and doneness for the synchronous getters? Maybe there's some kind of latch or barrier
system per-value; clearly we want the call to be waiting. That wouldn't be part of the raw instance, so that's something
else the internal "execute something" job is going to need to do.

6) We really ought to include a limit on how long to wait.

 */

// This is probably not a great name, but needs to be public API
// TODO: Explain how this works, because it's unintuitive
class TrickleAsyncTimestamp

data class TimestampedInput(val inputs: List<TrickleInputChange>, val timestamp: TrickleAsyncTimestamp)

class TrickleAsyncInstance(private val instance: TrickleInstance, private val executor: ExecutorService) {
    // TODO: Guard this
    private val timestamps = WeakHashMap<TrickleAsyncTimestamp, Long>()
    // TODO: Guard this if/when appropriate?
    private val inputsQueue = LinkedBlockingQueue<TimestampedInput>()


    /*
When do we want to trigger the management job?

Obviously, if we're in a situation where we're reporting a result or setting an input and nothing else is running that
is going to trigger the job, we should trigger it. What about the remaining situations?

Strawman 1: Rerun the job after every execute job reports.

Strawman 2: When the management job creates a bunch of execute jobs, rerun the job after the last of those finishes.

Version 1 adds additional overhead time of running getNextSteps() constantly and version 2 could lag in its response
to new changes or otherwise fail to take advantage of full parallelism (i.e. A, B, and C are being computed; D depends
only on A; D won't get enqueued until B and C are finished).

It's possible that the raw instance could be reworked so that getNextSteps() has lower overhead. In that case, just
running it frequently may be fine (with the caveat of offering a no-starvation mode, but that would be handled within
the runnable itself).


     */
    fun getManagementJob(): Runnable {
        return Runnable {
            // TODO: This needs to take things from the queue, pass them to the instance, and track their timestamps

            val nextSteps = instance.getNextSteps()

            // TODO: This needs to make runnables for those steps and pass them to the executor
        }
    }

    fun getExecuteJob(step: TrickleStep): Runnable {
        return Runnable {
            val result = step.execute()

            instance.reportResult(result)

            // TODO: This needs to do something to trigger things waiting on this result at this timestamp
            // TODO: This should trigger the management job under certain circumstances
        }
    }


    fun setInputs(changes: List<TrickleInputChange>): TrickleAsyncTimestamp {
        val timestamp = TrickleAsyncTimestamp()
        inputsQueue.put(TimestampedInput(changes, timestamp))
        triggerManagementJob()
        return timestamp
    }

    fun <T> setInput(nodeName: NodeName<T>, value: T): TrickleAsyncTimestamp {
        return setInputs(listOf(TrickleInputChange.SetBasic(nodeName, value)))
    }

    fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): TrickleAsyncTimestamp {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): TrickleAsyncTimestamp {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): TrickleAsyncTimestamp {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): TrickleAsyncTimestamp {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> getOutcome(name: NodeName<T>, minTimestamp: TrickleAsyncTimestamp): NodeOutcome<T> {
        TODO()
    }

    fun <T> getOutcome(name: KeyListNodeName<T>, minTimestamp: TrickleAsyncTimestamp): NodeOutcome<List<T>> {
        TODO()
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, minTimestamp: TrickleAsyncTimestamp): NodeOutcome<List<T>> {
        TODO()
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, key: K, minTimestamp: TrickleAsyncTimestamp): NodeOutcome<T> {
        TODO()
    }

    // TODO: Add some way to unsubscribe (?)
    // TODO: How do we make sure listeners run in order when that's important? Is that something the instance promises
    // to account for when calling listeners, or do we just pass timestamps and make the user take care of it?
    fun <T> addListener(name: NodeName<T>, listener: RefillListener<T>) {
        TODO()
    }
}

interface RefillListener<T> {
    fun nodeEvaluated(outcome: NodeOutcome<T>)
}