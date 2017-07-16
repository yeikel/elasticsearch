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

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.nio.channel.NioChannel;
import org.junit.Before;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ESSelectorTests extends ESTestCase {

    private ESSelector selector;
    private EventHandler handler;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        handler = mock(EventHandler.class);
        selector = new TestSelector(handler);
    }

    public void testQueueChannelForClosed() throws IOException {
        NioChannel channel = mock(NioChannel.class);
        selector.registeredChannels.add(channel);

        selector.queueChannelClose(channel);

        assertEquals(1, selector.getRegisteredChannels().size());

        selector.singleLoop();

        verify(handler).handleClose(channel);

        assertEquals(0, selector.getRegisteredChannels().size());
    }

    public void testSelectorClosedExceptionIsNotCaughtWhileRunning() throws IOException {
        ((TestSelector) this.selector).setClosedSelectorException(new ClosedSelectorException());

        boolean closedSelectorExceptionCaught = false;
        try {
            this.selector.singleLoop();
        } catch (ClosedSelectorException e) {
            closedSelectorExceptionCaught = true;
        }

        assertTrue(closedSelectorExceptionCaught);
    }

    public void testIOExceptionWhileSelect() throws IOException {
        IOException ioException = new IOException();
        ((TestSelector) this.selector).setIOException(ioException);

        this.selector.singleLoop();

        verify(handler).selectException(ioException);
    }

    private static class TestSelector extends ESSelector {

        private ClosedSelectorException closedSelectorException;
        private IOException ioException;

        protected TestSelector(EventHandler eventHandler) throws IOException {
            super(eventHandler);
        }

        @Override
        void doSelect(int timeout) throws IOException, ClosedSelectorException {
            if (closedSelectorException != null) {
                throw closedSelectorException;
            }
            if (ioException != null) {
                throw ioException;
            }
        }

        @Override
        void cleanup() {

        }

        public void setClosedSelectorException(ClosedSelectorException exception) {
            this.closedSelectorException = exception;
        }

        public void setIOException(IOException ioException) {
            this.ioException = ioException;
        }
    }

}
