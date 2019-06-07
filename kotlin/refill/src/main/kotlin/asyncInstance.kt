package net.semlang.refill

import java.util.concurrent.ExecutorService

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

 */
class TrickleAsyncInstance(private val instance: TrickleInstance, private val executor: ExecutorService): TrickleInputReceiver {
    override fun setInputs(changes: List<TrickleInputChange>): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun <T> setInput(nodeName: NodeName<T>, value: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun <T> setInput(nodeName: KeyListNodeName<T>, list: List<T>): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun <T> addKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun <T> removeKeyInput(nodeName: KeyListNodeName<T>, key: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun <K, T> setKeyedInput(nodeName: KeyedNodeName<K, T>, key: K, value: T): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun <T> getOutcome(name: NodeName<T>, minTimestamp: Long): NodeOutcome<T> {
        TODO()
    }

    fun <T> getOutcome(name: KeyListNodeName<T>, minTimestamp: Long): NodeOutcome<List<T>> {
        TODO()
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, minTimestamp: Long): NodeOutcome<List<T>> {
        TODO()
    }

    fun <K, T> getOutcome(name: KeyedNodeName<K, T>, key: K, minTimestamp: Long): NodeOutcome<T> {
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