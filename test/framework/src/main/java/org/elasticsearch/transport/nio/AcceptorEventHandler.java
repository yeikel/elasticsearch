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

import org.apache.logging.log4j.Logger;
import org.elasticsearch.transport.nio.channel.ChannelFactory;
import org.elasticsearch.transport.nio.channel.NioServerSocketChannel;
import org.elasticsearch.transport.nio.channel.NioSocketChannel;
import org.elasticsearch.transport.nio.channel.SelectionKeyUtils;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Event handler designed to handle events from server sockets
 */
public class AcceptorEventHandler extends EventHandler {

    private final Supplier<SocketSelector> selectorSupplier;
    private final OpenChannels openChannels;

    public AcceptorEventHandler(Logger logger, OpenChannels openChannels, Supplier<SocketSelector> selectorSupplier) {
        super(logger);
        this.openChannels = openChannels;
        this.selectorSupplier = selectorSupplier;
    }

    /**
     * This method is called when a NioServerSocketChannel is successfully registered. It should only be
     * called once per channel.
     *
     * @param nioServerSocketChannel that was registered
     */
    public void serverChannelRegistered(NioServerSocketChannel nioServerSocketChannel) {
        SelectionKeyUtils.setAcceptInterested(nioServerSocketChannel);
        openChannels.serverChannelOpened(nioServerSocketChannel);
    }

    /**
     * This method is called when a server channel signals it is ready to accept a connection. All of the
     * accept logic should occur in this call.
     *
     * @param nioServerChannel that can accept a connection
     */
    public void acceptChannel(NioServerSocketChannel nioServerChannel) throws IOException {
        ChannelFactory channelFactory = nioServerChannel.getChannelFactory();
        NioSocketChannel nioSocketChannel = channelFactory.acceptNioChannel(nioServerChannel);
        openChannels.acceptedChannelOpened(nioSocketChannel);
        nioSocketChannel.getCloseFuture().setListener(openChannels::channelClosed);
        selectorSupplier.get().registerSocketChannel(nioSocketChannel);
    }

    /**
     * This method is called when an attempt to accept a connection throws an exception.
     *
     * @param nioServerChannel that accepting a connection
     * @param exception that occurred
     */
    public void acceptException(NioServerSocketChannel nioServerChannel, Exception exception) {
        logger.debug("exception while accepting new channel", exception);
    }

    /**
     * This method is called when handling an event from a channel fails due to an unexpected exception.
     * An example would be if checking ready ops on a {@link java.nio.channels.SelectionKey} threw
     * {@link java.nio.channels.CancelledKeyException}.
     *
     * @param channel that caused the exception
     * @param exception that was thrown
     */
    public void genericServerChannelException(NioServerSocketChannel channel, Exception exception) {
        logger.debug("event handling exception", exception);
    }
}
