/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(ExperimentalStdlibApi::class)
package com.dropbox.flow.multicast

import com.dropbox.flow.multicast.ChannelManager.Message
import com.dropbox.flow.multicast.impl.operators.Notification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow

/**
 * Tracks active downstream channels and dispatches incoming upstream values to each of them in
 * parallel. The upstream is suspended after producing a value until at least one of the downstreams
 * acknowledges receiving it via [Message.Dispatch.Value.delivered].
 *
 * The [ChannelManager] will start the upstream from the given [upstream] [Flow] if there
 * is no active upstream and there's at least one downstream that has not received a value.
 *
 */
internal class ChannelManager<T>(
    /**
     * The scope in which ChannelManager actor runs
     */
    private val scope: CoroutineScope,
    /**
     * The buffer size that is used while the upstream is active
     */
    private val bufferSize: Int,
    /**
     * If true, downstream is never closed by the ChannelManager unless upstream throws an error.
     * Instead, it is kept open and if a new downstream shows up that causes us to restart the flow,
     * it will receive values as well.
     */
    private val piggybackingDownstream: Boolean = false,

    /**
     * If true, an active upstream will stay alive even if all downstreams are closed. A downstream
     * coming in later will receive a value from the live upstream.
     *
     * The upstream will be kept alive until [scope] cancels or [close] is called.
     */
    private val keepUpstreamAlive: Boolean = false,
    /**
     * Called when a value is dispatched
     */
    private val onEach: suspend (T) -> Unit,

    private val upstream: Flow<T>
) {
    init {
        require(!keepUpstreamAlive || bufferSize > 0) {
            "Must set bufferSize > 0 if keepUpstreamAlive is enabled"
        }
    }

    suspend fun addDownstream(channel: SendChannel<Message.Dispatch<T>>, piggybackOnly: Boolean = false) =
        actor.send(Message.AddChannel(channel, piggybackOnly))

    suspend fun removeDownstream(channel: SendChannel<Message.Dispatch<T>>) =
        actor.send(Message.RemoveChannel(channel))

    suspend fun close() = actor.close()

    private val actor = Actor()

    /**
     * Actor that does all the work. Any state and functionality should go here.
     */
    private inner class Actor : StoreRealActor<Message<T>>(scope) {

        private val buffer = Buffer<T>(bufferSize)

        /**
         * The current producer
         */
        private var producer: SharedFlowProducer<T>? = null

        /**
         * Tracks whether we've ever dispatched value or error from the current producer.
         * Reset when producer finishes.
         */
        private var dispatchedValue: Boolean = false

        /**
         * The ack for the very last message we've delivered.
         * When a new downstream comes and buffer is 0, we ack this message so that new downstream
         * can immediately start receiving values instead of waiting for values that it'll never
         * receive.
         */
        private var lastDeliveryAck: CompletableDeferred<Unit>? = null

        /**
         * List of downstream collectors.
         */
        private val channels = mutableListOf<ChannelEntry<T>>()

        override suspend fun handle(msg: Message<T>) {
            when (msg) {
                is Message.AddChannel -> doAdd(msg)
                is Message.RemoveChannel -> doRemove(msg.channel)
                is Message.Dispatch -> doDispatch(msg)
            }
        }

        /**
         * Called when the channel manager is active (e.g. it has downstream collectors and needs a
         * producer)
         */
        private fun newProducer() = SharedFlowProducer(scope, upstream, ::send)

        /**
         * We are closing. Do a cleanup on existing channels where we'll close them and also decide
         * on the list of leftovers.
         */
        private fun doHandleUpstreamClose(producer: SharedFlowProducer<T>?) {
            if (this.producer !== producer) {
                return
            }
            val piggyBacked = mutableListOf<ChannelEntry<T>>()
            val leftovers = mutableListOf<ChannelEntry<T>>()
            channels.forEach {
                when {
                    !it.awaitsDispatch -> {
                        if (!piggybackingDownstream) {
                            it.close()
                        } else {
                            piggyBacked.add(it)
                        }
                    }
                    dispatchedValue ->
                        // we dispatched a value but this channel didn't receive so put it into
                        // leftovers
                        leftovers.add(it)
                    else -> { // upstream didn't dispatch
                        if (!piggybackingDownstream) {
                            it.close()
                        } else {
                            piggyBacked.add(it)
                        }
                    }
                }
            }
            channels.clear() // empty references
            channels.addAll(leftovers)
            channels.addAll(piggyBacked)
            this.producer = null
            // we only reactivate if leftovers is not empty
            if (leftovers.isNotEmpty()) {
                activateIfNecessary()
            }
        }

        override fun onClosed() {
            channels.forEach {
                it.close()
            }
            channels.clear()
            producer?.cancel()
        }

        /**
         * Dispatch value to all downstream collectors.
         */
        private suspend fun doDispatch(msg: Message.Dispatch<T>) {
            if (msg.notification is Notification.Value<T>) {
                onEach(msg.notification.value)
                buffer.add(msg)
            }
            if (msg.notification !is Notification.Close) {
                dispatchedValue = true
            }

            lastDeliveryAck = msg.delivered

            channels.forEach {
                it.dispatchValue(msg)
            }

            if (msg.notification is Notification.Close<T>) {
                doHandleUpstreamClose(msg.producer)
            }
        }

        // /**
        //  * Dispatch value to all downstream collectors.
        //  */
        // private suspend fun doDispatchValue(msg: Message.Dispatch<T>) {
        //     onEach(msg.value)
        //     buffer.add(msg)
        //     dispatchedValue = true
        //     if (buffer.isEmpty()) {
        //         // if a new downstream arrives, we need to ack this so that it won't wait for
        //         // values that it'll never receive
        //         lastDeliveryAck = msg.delivered
        //     }
        //     channels.forEach {
        //         it.dispatchValue(msg)
        //     }
        // }
        //
        // /**
        //  * Dispatch an upstream error to downstream collectors.
        //  */
        // private fun doDispatchError(msg: Message.Dispatch<T>) {
        //     // dispatching error is as good as dispatching value
        //     dispatchedValue = true
        //     channels.forEach {
        //         it.dispatchError(msg.error)
        //     }
        // }

        /**
         * Remove a downstream collector.
         */
        private suspend fun doRemove(channel: SendChannel<Message.Dispatch<T>>) {
            val index = channels.indexOfFirst {
                it.hasChannel(channel)
            }
            if (index >= 0) {
                channels.removeAt(index)
                if (!keepUpstreamAlive && channels.isEmpty()) {
                    producer?.cancelAndJoin()
                }
            }
        }

        /**
         * Add a new downstream collector
         */
        private suspend fun doAdd(msg: Message.AddChannel<T>) {
            check(!msg.piggybackOnly || piggybackingDownstream) {
                "cannot add a piggyback only downstream when piggybackDownstream is disabled"
            }
            addEntry(
                entry = ChannelEntry(
                    channel = msg.channel,
                    piggybackOnly = msg.piggybackOnly
                )
            )
            if (!msg.piggybackOnly) {
                activateIfNecessary()
            }
        }

        private fun activateIfNecessary() {
            if (producer == null) {
                producer = newProducer()
                dispatchedValue = false
                producer!!.start()
            }
        }

        /**
         * Internally add the new downstream collector to our list, send it anything buffered.
         */
        private suspend fun addEntry(entry: ChannelEntry<T>) {
            val new = channels.none {
                it.hasChannel(entry)
            }
            check(new) {
                "$entry is already in the list."
            }
            channels.add(entry)
            if (buffer.items.isNotEmpty()) {
                // if there is anything in the buffer, send it
                buffer.items.forEach {
                    entry.dispatchValue(it)
                }
            } else {
                lastDeliveryAck?.complete(Unit)
            }
        }
    }

    /**
     * Holder for each downstream collector
     */
    internal data class ChannelEntry<T>(
        /**
         * The channel used by the collector
         */
        private val channel: SendChannel<Message.Dispatch<T>>,
        /**
         * Tracking whether this channel is a piggyback only channel that can be closed without ever
         * receiving a value or error.
         */
        val piggybackOnly: Boolean = false
    ) {
        private var _awaitsDispatch: Boolean = !piggybackOnly

        val awaitsDispatch
            get() = _awaitsDispatch

        suspend fun dispatchValue(value: Message.Dispatch<T>) {
            _awaitsDispatch = false
            channel.send(value)
        }

        // fun dispatchError(error: Throwable) {
        //     _awaitsDispatch = false
        //     channel.close(error)
        // }

        fun close() {
            channel.close()
        }

        fun hasChannel(channel: SendChannel<Message.Dispatch<T>>) = this.channel === channel

        fun hasChannel(entry: ChannelEntry<T>) = this.channel === entry.channel
    }

    /**
     * Messages accepted by the [ChannelManager].
     */
    sealed class Message<T> {
        /**
         * Add a new channel, that means a new downstream subscriber
         */
        class AddChannel<T>(
            val channel: SendChannel<Dispatch<T>>,
            val piggybackOnly: Boolean = false
        ) : Message<T>()

        /**
         * Remove a downstream subscriber, that means it completed
         */
        class RemoveChannel<T>(val channel: SendChannel<Dispatch<T>>) : Message<T>()

        class Dispatch<T>(
            val notification: Notification<T>,
            val producer: SharedFlowProducer<T>
        ) : Message<T>() {

            /**
             * Ack that is completed by all receiver. Upstream producer will await this before asking
             * for a new value from upstream
             */
            val delivered = CompletableDeferred<Unit>()

            /**
             * Upstream dispatched a new value, send it to all downstream items
             */
        }
    }
}

/**
 * Buffer implementation for any late arrivals.
 */
private interface Buffer<T> {
    fun add(item: Message.Dispatch<T>)
    fun isEmpty() = items.isEmpty()
    val items: Collection<ChannelManager.Message.Dispatch<T>>
}

/**
 * Default implementation of buffer which does not buffer anything.
 */
private class NoBuffer<T> : Buffer<T> {
    override val items: Collection<Message.Dispatch<T>>
        get() = emptyList()

    // ignore
    override fun add(item: Message.Dispatch<T>) = Unit
}

/**
 * Create a new buffer insteance based on the provided limit.
 */
@Suppress("FunctionName")
private fun <T> Buffer(limit: Int): Buffer<T> = if (limit > 0) {
    BufferImpl(limit)
} else {
    NoBuffer()
}

/**
 * A real buffer implementation that has a FIFO queue.
 */
private class BufferImpl<T>(private val limit: Int) :
    Buffer<T> {
    override val items = ArrayDeque<Message.Dispatch<T>>(limit.coerceAtMost(10))
    override fun add(item: Message.Dispatch<T>) {
        while (items.size >= limit) {
            items.removeFirst()
        }
        items.addLast(item)
    }
}

@ExperimentalCoroutinesApi
internal fun <T> ChannelManager.Message.Dispatch<T>.markDelivered() =
    delivered.complete(Unit)
