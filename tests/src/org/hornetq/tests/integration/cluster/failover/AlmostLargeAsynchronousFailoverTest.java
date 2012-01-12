/*
 * Copyright 2010 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.tests.integration.cluster.failover;

import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.core.client.impl.ServerLocatorInternal;

/**
 * Validating failover when the size of the message Size > flow Control && message Size < minLargeMessageSize
 *
 * @author clebertsuconic
 *
 *
 */
public class AlmostLargeAsynchronousFailoverTest extends AsynchronousFailoverTest
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   protected void createConfigs() throws Exception
   {
      super.createConfigs();
      liveServer.getServer().getConfiguration().setJournalFileSize(1024 * 1024);
      backupServer.getServer().getConfiguration().setJournalFileSize(1024 * 1024);
   }

   protected ServerLocatorInternal getServerLocator() throws Exception
   {
      ServerLocatorInternal locator = super.getServerLocator();
      locator.setMinLargeMessageSize(1024 * 1024);
      locator.setProducerWindowSize(10 * 1024);
      return locator;
   }

   protected void addPayload(ClientMessage message)
   {
      message.putBytesProperty("payload", new byte[20 * 1024]);
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
