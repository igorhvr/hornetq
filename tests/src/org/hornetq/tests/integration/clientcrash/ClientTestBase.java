/*
 * Copyright 2009 Red Hat, Inc.
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

package org.hornetq.tests.integration.clientcrash;

import junit.framework.Assert;

import org.hornetq.core.config.Configuration;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.tests.util.ServiceTestBase;

/**
 * A ClientTestBase
 *
 * @author <a href="jmesnil@redhat.com">Jeff Mesnil</a>
 *
 *
 */
public abstract class ClientTestBase extends ServiceTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   private HornetQServer server;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      Configuration config = createDefaultConfig(true);
      config.setSecurityEnabled(false);
      server = createServer(false, config);
      server.start();
   }

   @Override
   protected void tearDown() throws Exception
   {
      server.stop();

      server = null;

      super.tearDown();
   }

   protected void assertActiveConnections(final int expectedActiveConnections) throws Exception
   {
      Assert.assertEquals(expectedActiveConnections, server.getHornetQServerControl().getConnectionCount());
   }

   protected void assertActiveSession(final int expectedActiveSession) throws Exception
   {
      Assert.assertEquals(expectedActiveSession, server.getSessions().size());
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
