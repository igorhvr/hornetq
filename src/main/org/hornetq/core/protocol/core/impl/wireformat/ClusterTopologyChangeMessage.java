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

package org.hornetq.core.protocol.core.impl.wireformat;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.Pair;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.protocol.core.impl.PacketImpl;

/**
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 */
public class ClusterTopologyChangeMessage extends PacketImpl
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(ClusterTopologyChangeMessage.class);

   // Attributes ----------------------------------------------------

   private boolean exit;
   
   private String nodeID;
   
   private Pair<TransportConfiguration, TransportConfiguration> pair;
   
   private boolean last;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   public ClusterTopologyChangeMessage(final String nodeID, final Pair<TransportConfiguration, TransportConfiguration> pair, final boolean last)
   {
      super(PacketImpl.CLUSTER_TOPOLOGY);

      this.nodeID = nodeID;
      
      this.pair = pair;
      
      this.last = last;
      
      this.exit = false;
   }
   
   public ClusterTopologyChangeMessage(final String nodeID)
   {
      super(PacketImpl.CLUSTER_TOPOLOGY);
      
      this.exit = true;
      
      this.nodeID = nodeID;
   }

   public ClusterTopologyChangeMessage()
   {
      super(PacketImpl.CLUSTER_TOPOLOGY);
   }

   // Public --------------------------------------------------------

   public String getNodeID()
   {
      return nodeID;
   }

   public Pair<TransportConfiguration, TransportConfiguration> getPair()
   {
      return pair;
   }
   
   public boolean isLast()
   {
      return last;
   }
   
   public boolean isExit()
   {
      return exit;
   }

   @Override
   public void encodeRest(final HornetQBuffer buffer)
   {
      buffer.writeBoolean(exit);
      buffer.writeString(nodeID);
      if (!exit)
      {
         if (pair.a != null)
         {
            buffer.writeBoolean(true);
            pair.a.encode(buffer);
         }
         else
         {
            buffer.writeBoolean(false);
         }
         if (pair.b != null)
         {
            buffer.writeBoolean(true);
            pair.b.encode(buffer);
         }
         else
         {
            buffer.writeBoolean(false);
         }
         buffer.writeBoolean(last);
      }
   }

   @Override
   public void decodeRest(final HornetQBuffer buffer)
   {
      exit = buffer.readBoolean();
      nodeID = buffer.readString();
      if (!exit)
      {
         boolean hasLive = buffer.readBoolean();
         TransportConfiguration a;
         if(hasLive)
         {
            a = new TransportConfiguration();
            a.decode(buffer);
         }
         else
         {
            a = null;
         }
         boolean hasBackup = buffer.readBoolean();
         TransportConfiguration b;
         if (hasBackup)
         {
            b = new TransportConfiguration();
            b.decode(buffer);
         }
         else
         {
            b = null;
         }
         pair = new Pair<TransportConfiguration, TransportConfiguration>(a, b);
         last = buffer.readBoolean();
      }
   }


   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
