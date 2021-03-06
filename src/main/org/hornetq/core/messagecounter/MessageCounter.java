/*
* JBoss, Home of Professional Open Source
* Copyright 2005, JBoss Inc., and individual contributors as indicated
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.hornetq.core.messagecounter;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import org.hornetq.core.server.Queue;

/**
 * This class stores message count informations for a given queue
 * 
 * At intervals this class samples the queue for message count data
 * 
 * Note that the underlying queue *does not* update statistics every time a message
 * is added since that would reall slow things down, instead we *sample* the queues at
 * regular intervals - this means we are less intrusive on the queue
 *
 * @author <a href="mailto:u.schroeter@mobilcom.de">Ulf Schroeter</a>
 * @author <a href="mailto:s.steinbacher@mobilcom.de">Stephan Steinbacher</a>
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @version $Revision: 1.8 $
 */
public class MessageCounter
{
   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // destination related information
   private final String destName;

   private final String destSubscription;

   private final boolean destTopic;

   private final boolean destDurable;

   private final Queue serverQueue;

   // counter
   private long countTotal;

   private long countTotalLast;

   private long depthLast;

   private long timeLastUpdate;

   private long timeLastAdd;

   // per hour day counter history
   private int dayCounterMax;

   private final List<DayCounter> dayCounters;

   private long lastMessagesAdded;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   /**
    *    Constructor
    *
    * @param name             destination name
    * @param subscription     subscription name
    * @param queue            internal queue object
    * @param topic            topic destination flag
    * @param durable          durable subsciption flag
    * @param daycountmax      max message history day count
    */
   public MessageCounter(final String name,
                         final String subscription,
                         final Queue serverQueue,
                         final boolean topic,
                         final boolean durable,
                         final int daycountmax)
   {
      // store destination related information
      destName = name;
      destSubscription = subscription;
      destTopic = topic;
      destDurable = durable;
      this.serverQueue = serverQueue;

      // initialize counter
      resetCounter();

      // initialize message history
      dayCounters = new ArrayList<DayCounter>();

      setHistoryLimit(daycountmax);
   }
   
   private Runnable onTimeExecutor = new Runnable()
   {
      public void run()
      {
         long latestMessagesAdded = serverQueue.getInstantMessagesAdded();

         long newMessagesAdded = latestMessagesAdded - lastMessagesAdded;

         countTotal += newMessagesAdded;
         
         lastMessagesAdded = latestMessagesAdded;

         if (newMessagesAdded > 0)
         {
            timeLastAdd = System.currentTimeMillis();
         }

         // update timestamp
         timeLastUpdate = System.currentTimeMillis();

         // update message history
         updateHistory(newMessagesAdded);
         
      }
   };

   // Public --------------------------------------------------------

   /*
    * This method is called periodically to update statistics from the queue
    */
   public synchronized void onTimer()
   {
      // Actor approach here: Instead of having the Counter locking the queue, we will use the Queue's executor
      // instead of possibly making an lock on the queue.
      // This way the scheduled Threads will be free to keep doing their pings in case the server is busy with paging or 
      // any other deliveries
      serverQueue.getExecutor().execute(onTimeExecutor);
   }

   public String getDestinationName()
   {
      return destName;
   }

   public String getDestinationSubscription()
   {
      return destSubscription;
   }

   public boolean isDestinationTopic()
   {
      return destTopic;
   }

   public boolean isDestinationDurable()
   {
      return destDurable;
   }

   /**
    * Gets the total message count since startup or
    * last counter reset
    */
   public long getCount()
   {
      return countTotal;
   }

   /**
    * Gets the message count delta since last method call
    */
   public long getCountDelta()
   {
      long delta = countTotal - countTotalLast;

      countTotalLast = countTotal;

      return delta;
   }

   /**
    * Gets the current message count of pending messages
    * within the destination waiting for dispatch
    */
   public long getMessageCount()
   {
      return serverQueue.getInstantMessageCount();
   }

   /**
    * Gets the message count delta of pending messages
    * since last method call.
    */
   public long getMessageCountDelta()
   {
      long current = serverQueue.getInstantMessageCount();
      int delta = (int)(current - depthLast);

      depthLast = current;

      return delta;
   }

   public long getLastUpdate()
   {
      return timeLastUpdate;
   }

   public long getLastAddedMessageTime()
   {
      return timeLastAdd;
   }

   public void resetCounter()
   {
      countTotal = 0;
      countTotalLast = 0;
      depthLast = 0;
      timeLastUpdate = 0;
      timeLastAdd = 0;
   }

   private void setHistoryLimit(final int daycountmax)
   {
      boolean bInitialize = false;

      // store new maximum day count
      dayCounterMax = daycountmax;

      // update day counter array
      synchronized (dayCounters)
      {
         if (dayCounterMax > 0)
         {
            // limit day history to specified day count
            int delta = dayCounters.size() - dayCounterMax;

            for (int i = 0; i < delta; i++)
            {
               // reduce array size to requested size by dropping
               // oldest day counters
               dayCounters.remove(0);
            }

            // create initial day counter when empty
            bInitialize = dayCounters.isEmpty();
         }
         else if (dayCounterMax == 0)
         {
            // disable history
            dayCounters.clear();
         }
         else
         {
            // unlimited day history

            // create initial day counter when empty
            bInitialize = dayCounters.isEmpty();
         }

         // optionally initialize first day counter entry
         if (bInitialize)
         {
            dayCounters.add(new DayCounter(new GregorianCalendar(), true));
         }
      }
   }

   public void resetHistory()
   {
      int max = dayCounterMax;

      setHistoryLimit(0);
      setHistoryLimit(max);
   }

   public List<DayCounter> getHistory()
   {
      updateHistory(0);

      return new ArrayList<DayCounter>(dayCounters);
   }

   /**
    * Get message counter history data as string in format
    * 
    * "day count\n  
    *  Date 1, hour counter 0, hour counter 1, ..., hour counter 23\n
    *  Date 2, hour counter 0, hour counter 1, ..., hour counter 23\n
    *  .....
    *  .....
    *  Date n, hour counter 0, hour counter 1, ..., hour counter 23\n"
    *
    * @return  String   message history data string
    */
   public String getHistoryAsString()
   {
      String ret = "";

      // ensure history counters are up to date
      updateHistory(0);

      // compile string
      synchronized (dayCounters)
      {
         // first line: history day count
         ret += dayCounters.size() + "\n";

         // following lines: day counter data
         for (int i = 0; i < dayCounters.size(); i++)
         {
            DayCounter counter = dayCounters.get(i);

            ret += counter.getDayCounterAsString() + "\n";
         }
      }

      return ret;
   }

   @Override
   public String toString()
   {
      return "MessageCounter[destName" + destName +
             ", destSubscription=" +
             destSubscription +
             ", destTopic=" +
             destTopic +
             ", destDurable=" +
             destDurable +
             ", serverQueue =" +
             serverQueue +
             "]";
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   /**
    * Update message counter history
    * 
    * @param newMessages number of new messages to add to the latest day counter
    */
   private void updateHistory(final long newMessages)
   {
      // check history activation
      if (dayCounters.isEmpty())
      {
         return;
      }

      // calculate day difference between current date and date of last day counter entry
      synchronized (dayCounters)
      {
         DayCounter counterLast = dayCounters.get(dayCounters.size() - 1);

         GregorianCalendar calNow = new GregorianCalendar();
         GregorianCalendar calLast = counterLast.getDate();

         // clip day time part for day delta calulation
         calNow.clear(Calendar.AM_PM);
         calNow.clear(Calendar.HOUR);
         calNow.clear(Calendar.HOUR_OF_DAY);
         calNow.clear(Calendar.MINUTE);
         calNow.clear(Calendar.SECOND);
         calNow.clear(Calendar.MILLISECOND);

         calLast.clear(Calendar.AM_PM);
         calLast.clear(Calendar.HOUR);
         calLast.clear(Calendar.HOUR_OF_DAY);
         calLast.clear(Calendar.MINUTE);
         calLast.clear(Calendar.SECOND);
         calLast.clear(Calendar.MILLISECOND);

         long millisPerDay = 86400000; // 24 * 60 * 60 * 1000
         long millisDelta = calNow.getTime().getTime() - calLast.getTime().getTime();

         int dayDelta = (int)(millisDelta / millisPerDay);

         if (dayDelta > 0)
         {
            // finalize last day counter
            counterLast.finalizeDayCounter();

            // add new intermediate empty day counter entries
            DayCounter counterNew;

            for (int i = 1; i < dayDelta; i++)
            {
               // increment date
               calLast.add(Calendar.DAY_OF_YEAR, 1);

               counterNew = new DayCounter(calLast, false);
               counterNew.finalizeDayCounter();

               dayCounters.add(counterNew);
            }

            // add new day counter entry for current day
            counterNew = new DayCounter(calNow, false);

            dayCounters.add(counterNew);

            // ensure history day count limit
            setHistoryLimit(dayCounterMax);
         }

         // update last day counter entry
         counterLast = dayCounters.get(dayCounters.size() - 1);
         counterLast.updateDayCounter(newMessages);
      }
   }

   // Inner classes -------------------------------------------------

   /**
    * Internal day counter class for one day hour based counter history
    */
   public static class DayCounter
   {
      static final int HOURS = 24;

      GregorianCalendar date = null;

      int[] counters = new int[DayCounter.HOURS];

      /**
       *    Constructor
       *
       * @param date          day counter date
       * @param isStartDay    true  first day counter
       *                      false follow up day counter
       */
      DayCounter(final GregorianCalendar date, final boolean isStartDay)
      {
         // store internal copy of creation date
         this.date = (GregorianCalendar)date.clone();

         // initialize the array with '0'- values to current hour (if it is not the
         // first monitored day) and the rest with default values ('-1')
         int hour = date.get(Calendar.HOUR_OF_DAY);

         for (int i = 0; i < DayCounter.HOURS; i++)
         {
            if (i < hour)
            {
               if (isStartDay)
               {
                  counters[i] = -1;
               }
               else
               {
                  counters[i] = 0;
               }
            }
            else
            {
               counters[i] = -1;
            }
         }

         // set the array element of the current hour to '0'
         counters[hour] = 0;
      }

      /**
       * Gets copy of day counter date
       *
       * @return GregorianCalendar        day counter date
       */
      public GregorianCalendar getDate()
      {
         return (GregorianCalendar)date.clone();
      }

      public int[] getCounters()
      {
         return counters;
      }

      /**
       * Update day counter hour array elements  
       *
       * @param newMessages number of new messages since the counter was last updated.
       */
      void updateDayCounter(final long newMessages)
      {
         // get the current hour of the day
         GregorianCalendar cal = new GregorianCalendar();

         int currentIndex = cal.get(Calendar.HOUR_OF_DAY);

         // check if the last array update is more than 1 hour ago, if so fill all
         // array elements between the last index and the current index with '0' values
         boolean bUpdate = false;

         for (int i = 0; i <= currentIndex; i++)
         {
            if (counters[i] > -1)
            {
               // found first initialized hour counter
               // -> set all following uninitialized
               // counter values to 0
               bUpdate = true;
            }

            if (bUpdate == true)
            {
               if (counters[i] == -1)
               {
                  counters[i] = 0;
               }
            }
         }

         // increment current counter with the new messages
         counters[currentIndex] += newMessages;
      }

      /**
       * Finalize day counter hour array elements  
       */
      void finalizeDayCounter()
      {
         // a new day has began, so fill all array elements from index to end with
         // '0' values
         boolean bFinalize = false;

         for (int i = 0; i < DayCounter.HOURS; i++)
         {
            if (counters[i] > -1)
            {
               // found first initialized hour counter
               // -> finalize all following uninitialized
               // counter values
               bFinalize = true;
            }

            if (bFinalize)
            {
               if (counters[i] == -1)
               {
                  counters[i] = 0;
               }
            }
         }
      }

      /**
       * Return day counter data as string with format
       * "Date, hour counter 0, hour counter 1, ..., hour counter 23"
       * 
       * @return  String   day counter data
       */
      String getDayCounterAsString()
      {
         // first element day counter date
         DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.SHORT);

         String strData = dateFormat.format(date.getTime());

         // append 24 comma separated hour counter values
         for (int i = 0; i < DayCounter.HOURS; i++)
         {
            strData += "," + counters[i];
         }

         return strData;
      }
   }
}
