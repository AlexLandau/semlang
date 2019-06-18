package net.semlang.refill

import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

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
    // Use with care; note the use of synchronizedMap.
    // Keys should be added by the instantiator of the async timestamp.
    // Keys will be removed by garbage collection only.
    private val asyncToRawTimestampMap = Collections.synchronizedMap(WeakHashMap<TrickleAsyncTimestamp, CompletableFuture<Long>>())
    // Unguarded; BlockingQueue handles multithreaded access
    private val inputsQueue: BlockingQueue<TimestampedInput> = LinkedBlockingQueue<TimestampedInput>()

    /*
    So these two bits work in conjunction to ensure we run management jobs whenever we need to while making sure no more
    than one management job works at a time...

    When we run a management job, we do the following:
    1) Flip the "should run management" boolean to true
    2) Try grabbing the lock; end if we fail
    If we succeed at grabbing the lock:
    3) Flip the "should run management" boolean to false
    4) Run management
    5) Release the lock
    6) If the "should run management" boolean is true, go to 2

    This ensures that for each job, either 1) we run the management job ourselves (when the lock is available), or 2)
    the job that does have the lock either will see that it needs to rerun in step 6, or hasn't run step 3 yet and therefore
    will run management after the set of changes we care about have been made.

    This lets us enqueue management jobs whenever we want without thinking about optimizations on the caller's part.
     */
    private val shouldRunManagement = AtomicBoolean(false)
    private val managementJobLock = ReentrantLock()

    // This is guarded by the management job lock; it should only be used by the management job
    private val enqueuedJobTasks = HashMap<TrickleStep, Future<*>>()

    // Unguarded; operations are synchronized
    private val timestampBarriers = TimestampBarrierMultimap()

    /*
When do we want to trigger the management job?
Current resolution: Trigger frequently, collapse redundant jobs
At some point, we may want to improve how this handles for single-threaded executors, or just have some different model for that
     */
    fun getManagementJob(): Runnable {
        return Runnable runnable@{
/*
            Coordination between management jobs:
            1) Flip the "should run management" boolean to true
            2) Try grabbing the lock; end if we fail
                If we succeed at grabbing the lock:
            3) Flip the "should run management" boolean to false
            4) Run management
            5) Release the lock
            6) If the "should run management" boolean is true, go to 2
 */

            shouldRunManagement.set(true)
            while (true) {
                val acquiredLock = managementJobLock.tryLock()
                if (!acquiredLock) {
                    return@runnable
                }
                try {
                    shouldRunManagement.set(false)

                    runManagement()
                } finally {
                    managementJobLock.unlock()
                }
                if (!shouldRunManagement.get()) {
                    return@runnable
                }
            }
        }
    }

    private fun runManagement() {
        // TODO: This needs to take things from the queue, pass them to the instance, and track their timestamps

        // TODO:
        val inputsToAdd = ArrayList<TimestampedInput>()
        inputsQueue.drainTo(inputsToAdd)
        val newRawTimestamp = instance.setInputs(inputsToAdd.map { it.inputs }.flatten())
        for (asyncTimestamp in inputsToAdd.map { it.timestamp }) {
            asyncToRawTimestampMap[asyncTimestamp]!!.complete(newRawTimestamp)
        }
        for (input in inputsToAdd.map { it.inputs }.flatten()) {
            updateInputTimestampBarrier(input, newRawTimestamp)
        }

        val nextSteps = instance.getNextSteps()

        // In some cases, getNextSteps() will cause the timestamps for certain ValueIds to advance, so we need to check
        // all the ones we're waiting on
        for ((valueId, barrier) in timestampBarriers.getAll()) {
            val curTimestamp = instance.getTimestamp(valueId)
            if (curTimestamp >= barrier.minTimestampWanted) {
                barrier.latch.countDown()
                timestampBarriers.remove(valueId, barrier)
            }
        }

        val alreadyEnqueuedSteps = enqueuedJobTasks.keys.toSet()
        for (step in nextSteps) {
            if (!alreadyEnqueuedSteps.contains(step)) {
                val future = executor.submit(getExecuteJob(step))
                enqueuedJobTasks[step] = future
            }
        }
        // TODO: What if something is getting removed? I guess the easy way is just to have management do the removal
        // TODO: Also cancel tasks that are no longer in nextSteps
        val nextStepsSet = nextSteps.toSet()
        for (step in alreadyEnqueuedSteps) {
            if (!nextStepsSet.contains(step)) {
                enqueuedJobTasks[step]!!.cancel(true)
                enqueuedJobTasks.remove(step)
            }
        }

        // TODO: This needs to make runnables for those steps and pass them to the executor (done)

    }

    private fun updateInputTimestampBarrier(input: TrickleInputChange, newTimestamp: Long) {
        // TODO: Is this done elsewhere? Should this be a utility method on TIC?
        val valueId = when (input) {
            is TrickleInputChange.SetBasic<*> -> ValueId.Nonkeyed(input.nodeName)
            is TrickleInputChange.SetKeys<*> -> TODO()
            is TrickleInputChange.AddKey<*> -> TODO()
            is TrickleInputChange.RemoveKey<*> -> TODO()
            is TrickleInputChange.SetKeyed<*, *> -> TODO()
        }
        unblockWaitingTimestampBarriers(valueId, newTimestamp)
    }

    private fun unblockWaitingTimestampBarriers(valueId: ValueId, newTimestamp: Long) {
        for (barrier in timestampBarriers.get(valueId)) {
            if (barrier.minTimestampWanted <= newTimestamp) {
                barrier.latch.countDown()
                timestampBarriers.remove(valueId, barrier)
            }
        }
    }

    fun getExecuteJob(step: TrickleStep): Runnable {
        return Runnable {
            val result = step.execute()

            instance.reportResult(result)

            // Unblock things waiting on this result at this timestamp
            unblockWaitingTimestampBarriers(result.valueId, result.timestamp)
            /*
            TODO: Design flaw here...
            There are certain cases (e.g. failure outcomes where something upstream threw) where timestamps update during
            getNextSteps(), and we'll need to check those somehow
            One approach is to just iterate through the listeners in the management thread after we run getNextSteps()
            and look at the relevant ValueIds
             */

            // TODO: Support listeners, by either invoking them here or enqueueing new runnables for them

            // Trigger the management job in case new steps are available
            enqueueManagementJob()
        }
    }


    fun setInputs(changes: List<TrickleInputChange>): TrickleAsyncTimestamp {
        val timestamp = TrickleAsyncTimestamp()
        asyncToRawTimestampMap[timestamp] = CompletableFuture()
        inputsQueue.put(TimestampedInput(changes, timestamp))
        enqueueManagementJob()
        return timestamp
    }

    private fun enqueueManagementJob() {
        executor.submit(getManagementJob())
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

    // TODO: Add a variant that waits a limited amount of time
    fun <T> getOutcome(name: NodeName<T>, minTimestamp: TrickleAsyncTimestamp): NodeOutcome<T> {
        // TODO: This map access needs to be guarded (but separate the guarding from the get())
        val rawMinTimestamp = asyncToRawTimestampMap[minTimestamp]!!.get()

        // Wait for the timestamp to be done
        val valueId = ValueId.Nonkeyed(name)
        waitForTimestamp(valueId, rawMinTimestamp)

        return instance.getNodeOutcome(name)
    }

    private fun waitForTimestamp(valueId: ValueId, rawMinTimestamp: Long) {
        if (instance.getTimestamp(valueId) < rawMinTimestamp) {
            // TODO: Do something to wait longer until the new min timestamp is satisfied
            // This wants to be a map where the keys are ValueIds, so when execute tasks finish they look for these barriers and update them
            // However, we also want the values to be per-getOutcome and not shared for simplicity, right? So a multimap, but an actual
            // proper one that removes its key when all associated values are removed
            // Though I'd rather not add Guava as a dependency...
            val timestampBarrier = timestampBarriers.add(valueId, rawMinTimestamp)
            // Now that it's been added, double-check before waiting, in case the timestamp was updated to a new value before the barrier
            // was created
            if (instance.getTimestamp(valueId) < rawMinTimestamp) {
                // General case, we need to wait on the barrier
                // TODO: Do we need to handle interruption here?
                timestampBarrier.latch.await()
            } else {
                // Rare case, don't bother waiting
                timestampBarriers.remove(valueId, timestampBarrier)
            }
        }
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
//    fun <T> addListener(name: NodeName<T>, listener: RefillListener<T>) {
//        TODO()
//    }
}

//interface RefillListener<T> {
//    fun nodeEvaluated(outcome: NodeOutcome<T>)
//}

// Note: This should explicitly *not* implement hashCode/equals
class TimestampBarrier(val minTimestampWanted: Long) {
    val latch = CountDownLatch(1)
}

class TimestampBarrierMultimap {
    private val map = HashMap<ValueId, HashSet<TimestampBarrier>>()

    // TODO: Reconsider if this is the best way to guard all these properly
    @Synchronized
    fun add(valueId: ValueId, minTimestampWanted: Long): TimestampBarrier {
        val existingSet = map[valueId]
        val set = if (existingSet != null) {
            existingSet
        } else {
            val newSet = HashSet<TimestampBarrier>()
            map[valueId] = newSet
            newSet
        }
        val newBarrier = TimestampBarrier(minTimestampWanted)
        set.add(newBarrier)
        return newBarrier
    }

    @Synchronized
    fun remove(valueId: ValueId, barrier: TimestampBarrier) {
        val set = map[valueId]
        if (set != null) {
            set.remove(barrier)
            if (set.isEmpty()) {
                map.remove(valueId)
            }
        }
    }

    @Synchronized
    fun get(valueId: ValueId): Collection<TimestampBarrier> {
        return map[valueId]?.toList() ?: emptyList()
    }

    @Synchronized
    fun getAll(): Collection<Pair<ValueId, TimestampBarrier>> {
        return map.flatMap { (valueId, barriers) ->
            barriers.map { valueId to it }
        }
    }
}
