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
package org.hornetq.tests.integration.client;

import junit.framework.Assert;

import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.*;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.tests.util.ServiceTestBase;

/**
 * @author <a href="mailto:andy.taylor@jboss.org">Andy Taylor</a>
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 */
public class MessageCounterTest extends ServiceTestBase
{
   private static final Logger log = Logger.getLogger(MessageCounterTest.class);

   private HornetQServer server;

   private final SimpleString QUEUE = new SimpleString("ConsumerTestQueue");

   private ServerLocator locator;

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      server = createServer(false);

      server.start();

      locator = createInVMNonHALocator();
   }

   @Override
   protected void tearDown() throws Exception
   {
      locator.close();

      server.stop();

      server = null;

      super.tearDown();
   }

   public void testMessageCounter() throws Exception
   {

      locator.setBlockOnNonDurableSend(true);
      locator.setBlockOnDurableSend(true);

      ClientSessionFactory sf = locator.createSessionFactory();
      ClientSession session = sf.createSession(null, null, false, false, false, false, 0);

      session.createQueue(QUEUE, QUEUE, null, false);

      ClientProducer producer = session.createProducer(QUEUE);

      final int numMessages = 100;

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = createTextMessage("m" + i, session);
         producer.send(message);
      }

      session.commit();
      session.start();

      Assert.assertEquals(100, getMessageCount(server.getPostOffice(), QUEUE.toString()));

      ClientConsumer consumer = session.createConsumer(QUEUE, null, false);

      for (int i = 0; i < numMessages; i++)
      {
         ClientMessage message = consumer.receive(1000);

         Assert.assertNotNull(message);
         message.acknowledge();

         session.commit();

         Assert.assertEquals("m" + i, message.getBodyBuffer().readString());
      }

      session.close();

      Assert.assertEquals(0, getMessageCount(server.getPostOffice(), QUEUE.toString()));

   }

}
