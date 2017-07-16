/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport.nio;

import org.elasticsearch.transport.nio.channel.NioSocketChannel;
import org.elasticsearch.transport.nio.channel.SelectionKeyUtils;
import org.elasticsearch.transport.nio.channel.WriteContext;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Selector implementation that handles {@link NioSocketChannel}. It's main piece of functionality is
 * handling connect, read, and write events.
 */
public class SocketSelector extends ESSelector {

    private final ConcurrentLinkedQueue<NioSocketChannel> newChannels = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<WriteOperation> queuedWrites = new ConcurrentLinkedQueue<>();
    private final SocketEventHandler eventHandler;

    public SocketSelector(SocketEventHandler eventHandler) throws IOException {
        super(eventHandler);
        this.eventHandler = eventHandler;
    }

    public SocketSelector(SocketEventHandler eventHandler, Selector selector) throws IOException {
        super(eventHandler, selector);
        this.eventHandler = eventHandler;
    }

    @Override
    void doSelect(int timeout) throws IOException, ClosedSelectorException {
        setUpNewChannels();
        handleQueuedWrites();

        int ready = selector.select(timeout);
        if (ready > 0) {
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            processKeys(selectionKeys);
        }

    }

    @Override
    void cleanup() {
        WriteOperation op;
        while ((op = queuedWrites.poll()) != null) {
            op.getListener().onFailure(new ClosedSelectorException());
        }
        channelsToClose.addAll(newChannels);
        channelsToClose.addAll(registeredChannels);
        closePendingChannels();
    }

    /**
     * Registers a NioSocketChannel to be handled by this selector. The channel will by queued and eventually
     * registered next time through the event loop.
     * @param nioSocketChannel the channel to register
     */
    public void registerSocketChannel(NioSocketChannel nioSocketChannel) {
        newChannels.offer(nioSocketChannel);
        ensureSelectorOpenForEnqueuing(newChannels, nioSocketChannel);
        wakeup();
    }


    /**
     * Queues a write operation to be handled by the event loop. This can be called by any thread and is the
     * api available for non-selector threads to schedule writes.
     *
     * @param writeOperation to be queued
     */
    public void queueWrite(WriteOperation writeOperation) {
        queuedWrites.offer(writeOperation);
        if (isOpen() == false) {
            boolean wasRemoved = queuedWrites.remove(writeOperation);
            if (wasRemoved) {
                writeOperation.getListener().onFailure(new ClosedSelectorException());
            }
        } else {
            wakeup();
        }
    }

    /**
     * Queues a write operation directly in a channel's buffer. Channel buffers are only safe to be accessed
     * by the selector thread. As a result, this method should only be called by the selector thread.
     *
     * @param writeOperation to be queued in a channel's buffer
     */
    public void queueWriteInChannelBuffer(WriteOperation writeOperation) {
        assert isOnCurrentThread() : "Must be on selector thread";
        NioSocketChannel channel = writeOperation.getChannel();
        WriteContext context = channel.getWriteContext();
        try {
            SelectionKeyUtils.setWriteInterested(channel);
            context.queueWriteOperations(writeOperation);
        } catch (Exception e) {
            writeOperation.getListener().onFailure(e);
        }
    }

    private void processKeys(Set<SelectionKey> selectionKeys) {
        Iterator<SelectionKey> keyIterator = selectionKeys.iterator();
        while (keyIterator.hasNext()) {
            SelectionKey sk = keyIterator.next();
            keyIterator.remove();
            NioSocketChannel nioSocketChannel = (NioSocketChannel) sk.attachment();
            if (sk.isValid()) {
                try {
                    int ops = sk.readyOps();
                    if ((ops & SelectionKey.OP_CONNECT) != 0) {
                        attemptConnect(nioSocketChannel);
                    }

                    if (nioSocketChannel.isConnectComplete()) {
                        if ((ops & SelectionKey.OP_WRITE) != 0) {
                            handleWrite(nioSocketChannel);
                        }

                        if ((ops & SelectionKey.OP_READ) != 0) {
                            handleRead(nioSocketChannel);
                        }
                    }
                } catch (CancelledKeyException e) {
                    eventHandler.genericChannelException(nioSocketChannel, e);
                }
            } else {
                eventHandler.genericChannelException(nioSocketChannel, new CancelledKeyException());
            }
        }
    }


    private void handleWrite(NioSocketChannel nioSocketChannel) {
        try {
            eventHandler.handleWrite(nioSocketChannel);
        } catch (Exception e) {
            eventHandler.writeException(nioSocketChannel, e);
        }
    }

    private void handleRead(NioSocketChannel nioSocketChannel) {
        try {
            eventHandler.handleRead(nioSocketChannel);
        } catch (Exception e) {
            eventHandler.readException(nioSocketChannel, e);
        }
    }

    private void handleQueuedWrites() {
        WriteOperation writeOperation;
        while ((writeOperation = queuedWrites.poll()) != null) {
            if (writeOperation.getChannel().isWritable()) {
                queueWriteInChannelBuffer(writeOperation);
            } else {
                writeOperation.getListener().onFailure(new ClosedChannelException());
            }
        }
    }

    private void setUpNewChannels() {
        NioSocketChannel newChannel;
        while ((newChannel = this.newChannels.poll()) != null) {
            setupChannel(newChannel);
        }
    }

    private void setupChannel(NioSocketChannel newChannel) {
        try {
            if (newChannel.register(this)) {
                registeredChannels.add(newChannel);
                SelectionKey key = newChannel.getSelectionKey();
                key.attach(newChannel);
                eventHandler.handleRegistration(newChannel);
                attemptConnect(newChannel);
            }
        } catch (Exception e) {
            eventHandler.registrationException(newChannel, e);
        }
    }

    private void attemptConnect(NioSocketChannel newChannel) {
        try {
            if (newChannel.finishConnect()) {
                eventHandler.handleConnect(newChannel);
            }
        } catch (Exception e) {
            eventHandler.connectException(newChannel, e);
        }
    }
}
