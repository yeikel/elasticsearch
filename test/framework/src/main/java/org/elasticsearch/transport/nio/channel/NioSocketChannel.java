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

package org.elasticsearch.transport.nio.channel;

import org.elasticsearch.transport.nio.NetworkBytesReference;
import org.elasticsearch.transport.nio.ESSelector;
import org.elasticsearch.transport.nio.SocketSelector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

public class NioSocketChannel extends AbstractNioChannel<SocketChannel> {

    private final InetSocketAddress remoteAddress;
    private final ConnectFuture connectFuture = new ConnectFuture();
    private volatile SocketSelector socketSelector;
    private WriteContext writeContext;
    private ReadContext readContext;

    public NioSocketChannel(String profile, SocketChannel socketChannel) throws IOException {
        super(profile, socketChannel);
        this.remoteAddress = (InetSocketAddress) socketChannel.getRemoteAddress();
    }

    @Override
    public CloseFuture closeAsync() {
        clearQueuedWrites();

        return super.closeAsync();
    }

    @Override
    public void closeFromSelector() {
        // Even if the channel has already been closed we will clear any pending write operations just in case
        clearQueuedWrites();

        super.closeFromSelector();
    }

    @Override
    public SocketSelector getSelector() {
        return socketSelector;
    }

    @Override
    boolean markRegistered(ESSelector selector) {
        this.socketSelector = (SocketSelector) selector;
        return super.markRegistered(selector);
    }

    public int write(NetworkBytesReference[] references) throws IOException {
        int written;
        if (references.length == 1) {
            written = socketChannel.write(references[0].getReadByteBuffer());
        } else {
            ByteBuffer[] buffers = new ByteBuffer[references.length];
            for (int i = 0; i < references.length; ++i) {
                buffers[i] = references[i].getReadByteBuffer();
            }
            written = (int) socketChannel.write(buffers);
        }
        if (written <= 0) {
            return written;
        }

        NetworkBytesReference.vectorizedIncrementReadIndexes(Arrays.asList(references), written);

        return written;
    }

    public int read(NetworkBytesReference reference) throws IOException {
        int bytesRead = socketChannel.read(reference.getWriteByteBuffer());

        if (bytesRead == -1) {
            return bytesRead;
        }

        reference.incrementWrite(bytesRead);
        return bytesRead;
    }

    public void setContexts(ReadContext readContext, WriteContext writeContext) {
        this.readContext = readContext;
        this.writeContext = writeContext;
    }

    public WriteContext getWriteContext() {
        return writeContext;
    }

    public ReadContext getReadContext() {
        return readContext;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public boolean isConnectComplete() {
        return connectFuture.isConnectComplete();
    }

    public boolean isWritable() {
        return state.get() == REGISTERED;
    }

    public boolean isReadable() {
        return state.get() == REGISTERED;
    }

    /**
     * This method will attempt to complete the connection process for this channel. It should be called for
     * new channels or for a channel that has produced a OP_CONNECT event. If this method returns true then
     * the connection is complete and the channel is ready for reads and writes. If it returns false, the
     * channel is not yet connected and this method should be called again when a OP_CONNECT event is
     * received.
     *
     * @return true if the connection process is complete
     * @throws IOException if an I/O error occurs
     */
    public boolean finishConnect() throws IOException {
        if (connectFuture.isConnectComplete()) {
            return true;
        } else if (connectFuture.connectFailed()) {
            Exception exception = connectFuture.getException();
            if (exception instanceof IOException) {
                throw (IOException) exception;
            } else {
                throw (RuntimeException) exception;
            }
        }

        boolean isConnected = socketChannel.isConnected();
        if (isConnected == false) {
            isConnected = internalFinish();
        }
        if (isConnected) {
            connectFuture.setConnectionComplete(this);
        }
        return isConnected;
    }

    public ConnectFuture getConnectFuture() {
        return connectFuture;
    }

    private boolean internalFinish() throws IOException {
        try {
            return socketChannel.finishConnect();
        } catch (IOException e) {
            connectFuture.setConnectionFailed(e);
            throw e;
        } catch (RuntimeException e) {
            connectFuture.setConnectionFailed(e);
            throw e;
        }
    }

    private void clearQueuedWrites() {
        // Even if the channel has already been closed we will clear any pending write operations just in case
        if (state.get() > UNREGISTERED) {
            SocketSelector selector = getSelector();
            if (selector != null && selector.isOnCurrentThread() && writeContext.hasQueuedWriteOps()) {
                writeContext.clearQueuedWriteOps(new ClosedChannelException());
            }
        }
    }
}
