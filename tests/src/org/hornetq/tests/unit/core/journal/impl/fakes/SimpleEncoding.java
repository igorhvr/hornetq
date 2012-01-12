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

package org.hornetq.tests.unit.core.journal.impl.fakes;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.core.journal.EncodingSupport;

/**
 * Provides a SimpleEncoding with a Fake Payload
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 *
 */
public class SimpleEncoding implements EncodingSupport
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------
   private final int size;

   private final byte bytetosend;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   public SimpleEncoding(final int size, final byte bytetosend)
   {
      this.size = size;
      this.bytetosend = bytetosend;
   }

   // Public --------------------------------------------------------
   public void decode(final HornetQBuffer buffer)
   {
      throw new UnsupportedOperationException();

   }

   public void encode(final HornetQBuffer buffer)
   {
      for (int i = 0; i < size; i++)
      {
         buffer.writeByte(bytetosend);
      }
   }

   public int getEncodeSize()
   {
      return size;
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
