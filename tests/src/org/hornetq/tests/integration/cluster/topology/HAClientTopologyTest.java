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

package org.hornetq.tests.integration.cluster.topology;

import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.logging.Logger;

/**
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 */
public class HAClientTopologyTest extends TopologyClusterTestBase
{
   private static final Logger log = Logger.getLogger(HAClientTopologyTest.class);

   protected boolean isNetty()
   {
      return false;
   }

   protected void setupCluster() throws Exception
   {
      setupCluster(false);
   }

   protected void setupCluster(final boolean forwardWhenNoConsumers) throws Exception
   {
      setupClusterConnection("cluster0", "queues", forwardWhenNoConsumers, 1, isNetty(), 0, 1, 2, 3, 4);
      setupClusterConnection("cluster1", "queues", forwardWhenNoConsumers, 1, isNetty(), 1, 0, 2, 3, 4);
      setupClusterConnection("cluster2", "queues", forwardWhenNoConsumers, 1, isNetty(), 2, 0, 1, 3, 4);
      setupClusterConnection("cluster3", "queues", forwardWhenNoConsumers, 1, isNetty(), 3, 0, 1, 2, 4);
      setupClusterConnection("cluster4", "queues", forwardWhenNoConsumers, 1, isNetty(), 4, 0, 1, 2, 3);
   }

   protected void setupServers() throws Exception
   {
      setupServer(0, isFileStorage(), isNetty());
      setupServer(1, isFileStorage(), isNetty());
      setupServer(2, isFileStorage(), isNetty());
      setupServer(3, isFileStorage(), isNetty());
      setupServer(4, isFileStorage(), isNetty());
   }

   @Override
   protected ServerLocator createHAServerLocator()
   {
      TransportConfiguration tc = createTransportConfiguration(isNetty(), false, generateParams(0, isNetty()));
      ServerLocator locator = HornetQClient.createServerLocatorWithHA(tc);
      locator.setBlockOnNonDurableSend(true);
      locator.setBlockOnDurableSend(true);
      return locator;
   }
}
