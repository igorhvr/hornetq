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

package org.hornetq.tests.integration.cluster.failover;

import java.util.HashMap;
import java.util.Map;

import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.core.remoting.impl.netty.TransportConstants;

/**
 * A NettyFailoverTest
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 *
 */
public class NettyFailoverTest extends FailoverTest
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   protected TransportConfiguration getAcceptorTransportConfiguration(final boolean live)
   {
      if (live)
      {
         return new TransportConfiguration("org.hornetq.core.remoting.impl.netty.NettyAcceptorFactory");
      }
      else
      {
         Map<String, Object> server1Params = new HashMap<String, Object>();

         server1Params.put(TransportConstants.PORT_PROP_NAME, TransportConstants.DEFAULT_PORT + 1);

         return new TransportConfiguration("org.hornetq.core.remoting.impl.netty.NettyAcceptorFactory",
                                           server1Params);
      }
   }

   @Override
   protected TransportConfiguration getConnectorTransportConfiguration(final boolean live)
   {
      if (live)
      {
         return new TransportConfiguration("org.hornetq.core.remoting.impl.netty.NettyConnectorFactory");
      }
      else
      {
         Map<String, Object> server1Params = new HashMap<String, Object>();

         server1Params.put(TransportConstants.PORT_PROP_NAME, TransportConstants.DEFAULT_PORT + 1);

         return new TransportConfiguration("org.hornetq.core.remoting.impl.netty.NettyConnectorFactory",
                                           server1Params);
      }
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
