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

package org.hornetq.core.client.impl;

import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.Interceptor;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.api.core.client.SessionFailureListener;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.protocol.core.Channel;
import org.hornetq.core.protocol.core.ChannelHandler;
import org.hornetq.core.protocol.core.CoreRemotingConnection;
import org.hornetq.core.protocol.core.Packet;
import org.hornetq.core.protocol.core.impl.PacketImpl;
import org.hornetq.core.protocol.core.impl.RemotingConnectionImpl;
import org.hornetq.core.protocol.core.impl.wireformat.ClusterTopologyChangeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.CreateSessionMessage;
import org.hornetq.core.protocol.core.impl.wireformat.CreateSessionResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.DisconnectMessage;
import org.hornetq.core.protocol.core.impl.wireformat.NodeAnnounceMessage;
import org.hornetq.core.protocol.core.impl.wireformat.Ping;
import org.hornetq.core.protocol.core.impl.wireformat.SubscribeClusterTopologyUpdatesMessage;
import org.hornetq.core.remoting.FailureListener;
import org.hornetq.core.version.Version;
import org.hornetq.spi.core.protocol.ProtocolType;
import org.hornetq.spi.core.remoting.BufferHandler;
import org.hornetq.spi.core.remoting.Connection;
import org.hornetq.spi.core.remoting.ConnectionLifeCycleListener;
import org.hornetq.spi.core.remoting.Connector;
import org.hornetq.spi.core.remoting.ConnectorFactory;
import org.hornetq.utils.ClassloadingUtil;
import org.hornetq.utils.ConcurrentHashSet;
import org.hornetq.utils.ConfigurationHelper;
import org.hornetq.utils.ExecutorFactory;
import org.hornetq.utils.OrderedExecutorFactory;
import org.hornetq.utils.UUIDGenerator;
import org.hornetq.utils.VersionLoader;

/**
 * A ClientSessionFactoryImpl
 *
 * Encapsulates a connection to a server
 *
 * @author Tim Fox
 *
 *
 */
public class ClientSessionFactoryImpl implements ClientSessionFactoryInternal, ConnectionLifeCycleListener
{

   // Constants
   // ------------------------------------------------------------------------------------

   private static final long serialVersionUID = 2512460695662741413L;

   private static final Logger log = Logger.getLogger(ClientSessionFactoryImpl.class);

   // Attributes
   // -----------------------------------------------------------------------------------

   private final ServerLocatorInternal serverLocator;

   private TransportConfiguration connectorConfig;

   private TransportConfiguration backupConfig;

   private ConnectorFactory connectorFactory;

   private final long callTimeout;

   private final long clientFailureCheckPeriod;

   private final long connectionTTL;

   private final Set<ClientSessionInternal> sessions = new HashSet<ClientSessionInternal>();

   private final Object exitLock = new Object();

   private final Object createSessionLock = new Object();

   private boolean inCreateSession;

   private final Object failoverLock = new Object();

   private final ExecutorFactory orderedExecutorFactory;

   private final ExecutorService threadPool;

   private final ScheduledExecutorService scheduledThreadPool;

   private final Executor closeExecutor;

   private CoreRemotingConnection connection;

   private final long retryInterval;

   private final double retryIntervalMultiplier; // For exponential backoff

   private final long maxRetryInterval;

   private final int reconnectAttempts;

   private final Set<SessionFailureListener> listeners = new ConcurrentHashSet<SessionFailureListener>();

   private Connector connector;

   private Future<?> pingerFuture;

   private PingRunnable pingRunnable;

   private volatile boolean exitLoop;

   private final List<Interceptor> interceptors;

   private volatile boolean stopPingingAfterOne;

   private volatile boolean closed;

   public final Exception e = new Exception();

   private final Object waitLock = new Object();

   // Static
   // ---------------------------------------------------------------------------------------

   // Constructors
   // ---------------------------------------------------------------------------------

   public ClientSessionFactoryImpl(final ServerLocatorInternal serverLocator,
                                   final TransportConfiguration connectorConfig,
                                   final long callTimeout,
                                   final long clientFailureCheckPeriod,
                                   final long connectionTTL,
                                   final long retryInterval,
                                   final double retryIntervalMultiplier,
                                   final long maxRetryInterval,
                                   final int reconnectAttempts,
                                   final ExecutorService threadPool,
                                   final ScheduledExecutorService scheduledThreadPool,
                                   final List<Interceptor> interceptors)
   {

      e.fillInStackTrace();

      this.serverLocator = serverLocator;

      this.connectorConfig = connectorConfig;

      connectorFactory = instantiateConnectorFactory(connectorConfig.getFactoryClassName());

      checkTransportKeys(connectorFactory, connectorConfig.getParams());

      this.callTimeout = callTimeout;

      this.clientFailureCheckPeriod = clientFailureCheckPeriod;

      this.connectionTTL = connectionTTL;

      this.retryInterval = retryInterval;

      this.retryIntervalMultiplier = retryIntervalMultiplier;

      this.maxRetryInterval = maxRetryInterval;

      this.reconnectAttempts = reconnectAttempts;

      this.scheduledThreadPool = scheduledThreadPool;

      this.threadPool = threadPool;

      orderedExecutorFactory = new OrderedExecutorFactory(threadPool);

      closeExecutor = orderedExecutorFactory.getExecutor();

      this.interceptors = interceptors;
 
   }

   public void connect(int initialConnectAttempts, boolean failoverOnInitialConnection) throws HornetQException
   {
      // Get the connection
      getConnectionWithRetry(initialConnectAttempts);

      if (connection == null)
      {
         StringBuffer msg = new StringBuffer("Unable to connect to server using configuration ").append(connectorConfig);
         if(backupConfig != null)
         {
            msg.append(" and backup configuration ").append(backupConfig);
         }
         throw new HornetQException(HornetQException.NOT_CONNECTED,
               msg.toString());
      }

   }

   public TransportConfiguration getConnectorConfiguration()
   {
      return connectorConfig;
   }

   public void setBackupConnector(TransportConfiguration live, TransportConfiguration backUp)
   {
      if(live.equals(connectorConfig) && backUp != null)
      {
         if (log.isDebugEnabled())
         {
              log.debug("Setting up backup config = " + backUp + " for live = " + live);
         }
         backupConfig = backUp;
      }
      else
      {
         if (log.isDebugEnabled())
         {
            log.debug("ClientSessionFactoryImpl received backup update for live/backup pair = " + live + " / " + backUp + " but it didn't belong to " + this.connectorConfig);
         }
      }
   }

   public Object getBackupConnector()
   {
      return backupConfig;
   }

   public ClientSession createSession(final String username,
                                      final String password,
                                      final boolean xa,
                                      final boolean autoCommitSends,
                                      final boolean autoCommitAcks,
                                      final boolean preAcknowledge,
                                      final int ackBatchSize) throws HornetQException
   {
      return createSessionInternal(username,
                                   password,
                                   xa,
                                   autoCommitSends,
                                   autoCommitAcks,
                                   preAcknowledge,
                                   ackBatchSize);
   }

   public ClientSession createSession(final boolean autoCommitSends,
                                      final boolean autoCommitAcks,
                                      final int ackBatchSize) throws HornetQException
   {
      return createSessionInternal(null,
                                   null,
                                   false,
                                   autoCommitSends,
                                   autoCommitAcks,
                                   serverLocator.isPreAcknowledge(),
                                   ackBatchSize);
   }

   public ClientSession createXASession() throws HornetQException
   {
      return createSessionInternal(null,
                                   null,
                                   true,
                                   false,
                                   false,
                                   serverLocator.isPreAcknowledge(),
                                   serverLocator.getAckBatchSize());
   }

   public ClientSession createTransactedSession() throws HornetQException
   {
      return createSessionInternal(null,
                                   null,
                                   false,
                                   false,
                                   false,
                                   serverLocator.isPreAcknowledge(),
                                   serverLocator.getAckBatchSize());
   }

   public ClientSession createSession() throws HornetQException
   {
      return createSessionInternal(null,
                                   null,
                                   false,
                                   true,
                                   true,
                                   serverLocator.isPreAcknowledge(),
                                   serverLocator.getAckBatchSize());
   }

   public ClientSession createSession(final boolean autoCommitSends, final boolean autoCommitAcks) throws HornetQException
   {
      return createSessionInternal(null,
                                   null,
                                   false,
                                   autoCommitSends,
                                   autoCommitAcks,
                                   serverLocator.isPreAcknowledge(),
                                   serverLocator.getAckBatchSize());
   }

   public ClientSession createSession(final boolean xa, final boolean autoCommitSends, final boolean autoCommitAcks) throws HornetQException
   {
      return createSessionInternal(null,
                                   null,
                                   xa,
                                   autoCommitSends,
                                   autoCommitAcks,
                                   serverLocator.isPreAcknowledge(),
                                   serverLocator.getAckBatchSize());
   }

   public ClientSession createSession(final boolean xa,
                                      final boolean autoCommitSends,
                                      final boolean autoCommitAcks,
                                      final boolean preAcknowledge) throws HornetQException
   {
      return createSessionInternal(null,
                                   null,
                                   xa,
                                   autoCommitSends,
                                   autoCommitAcks,
                                   preAcknowledge,
                                   serverLocator.getAckBatchSize());
   }

   // ConnectionLifeCycleListener implementation --------------------------------------------------

   public void connectionCreated(final Connection connection, final ProtocolType protocol)
   {
   }

   public void connectionDestroyed(final Object connectionID)
   {
      handleConnectionFailure(connectionID,
                              new HornetQException(HornetQException.NOT_CONNECTED, "Channel disconnected"));
   }

   public void connectionException(final Object connectionID, final HornetQException me)
   {
      handleConnectionFailure(connectionID, me);
   }

   // Must be synchronized to prevent it happening concurrently with failover which can lead to
   // inconsistencies
   public void removeSession(final ClientSessionInternal session, boolean failingOver)
   {
      synchronized (sessions)
      {
         sessions.remove(session);
      }
   }
   
   public void connectionReadyForWrites(final Object connectionID, final boolean ready)
   {
   }

   public synchronized int numConnections()
   {
      return connection != null ? 1 : 0;
   }

   public int numSessions()
   {
      return sessions.size();
   }

   public void addFailureListener(final SessionFailureListener listener)
   {
      listeners.add(listener);
   }

   public boolean removeFailureListener(final SessionFailureListener listener)
   {
      return listeners.remove(listener);
   }

   public void causeExit()
   {
      exitLoop = true;
      synchronized (waitLock)
      {
         waitLock.notify();
      }
   }

   public void close()
   {
      if (closed)
      {
         return;
      }

      // we need to stopthe factory from connecting if it is in the middle aof trying to failover before we get the lock
      causeExit();
      synchronized (createSessionLock)
      {
         synchronized (failoverLock)
         {
            HashSet<ClientSession> sessionsToClose;
            synchronized (sessions)
            {
               sessionsToClose = new HashSet<ClientSession>(sessions);
            }
            // work on a copied set. the session will be removed from sessions when session.close() is called
            for (ClientSession session : sessionsToClose)
            {
               try
               {
                  session.close();
               }
               catch (HornetQException e)
               {
                  log.warn("Unable to close session", e);
               }
            }

            checkCloseConnection();
         }
      }

      closed = true;
   }

   public ServerLocator getServerLocator()
   {
      return serverLocator;
   }

   // Public
   // ---------------------------------------------------------------------------------------

   public void stopPingingAfterOne()
   {
      stopPingingAfterOne = true;
   }
   
   public void resumePinging()
   {
      stopPingingAfterOne = false;
   }

   // Protected
   // ------------------------------------------------------------------------------------

   // Package Private
   // ------------------------------------------------------------------------------

   // Private
   // --------------------------------------------------------------------------------------

   private void handleConnectionFailure(final Object connectionID, final HornetQException me)
   {
      failoverOrReconnect(connectionID, me);
   }

   private void failoverOrReconnect(final Object connectionID, final HornetQException me)
   {
      Set<ClientSessionInternal> sessionsToClose = null;

      synchronized (failoverLock)
      {
         if (connection == null || connection.getID() != connectionID)
         {
            // We already failed over/reconnected - probably the first failure came in, all the connections were failed
            // over then a async connection exception or disconnect
            // came in for one of the already exitLoop connections, so we return true - we don't want to call the
            // listeners again

            return;
         }

         // We call before reconnection occurs to give the user a chance to do cleanup, like cancel messages
         callFailureListeners(me, false, false);

         // Now get locks on all channel 1s, whilst holding the failoverLock - this makes sure
         // There are either no threads executing in createSession, or one is blocking on a createSession
         // result.

         // Then interrupt the channel 1 that is blocking (could just interrupt them all)

         // Then release all channel 1 locks - this allows the createSession to exit the monitor

         // Then get all channel 1 locks again - this ensures the any createSession thread has executed the section and
         // returned all its connections to the connection manager (the code to return connections to connection manager
         // must be inside the lock

         // Then perform failover

         // Then release failoverLock

         // The other side of the bargain - during createSession:
         // The calling thread must get the failoverLock and get its' connections when this is locked.
         // While this is still locked it must then get the channel1 lock
         // It can then release the failoverLock
         // It should catch HornetQException.INTERRUPTED in the call to channel.sendBlocking
         // It should then return its connections, with channel 1 lock still held
         // It can then release the channel 1 lock, and retry (which will cause locking on failoverLock
         // until failover is complete


         if (reconnectAttempts != 0)
         {
            lockChannel1();

            final boolean needToInterrupt;

            synchronized (exitLock)
            {
               needToInterrupt = inCreateSession;
            }

            unlockChannel1();

            if (needToInterrupt)
            {
               // Forcing return all channels won't guarantee that any blocked thread will return immediately
               // So we need to wait for it
               forceReturnChannel1();

               // Now we need to make sure that the thread has actually exited and returned it's connections
               // before failover occurs

               synchronized (exitLock)
               {
                  while (inCreateSession)
                  {
                     try
                     {
                        exitLock.wait(5000);
                     }
                     catch (InterruptedException e)
                     {
                     }
                  }
               }
            }

            // Now we absolutely know that no threads are executing in or blocked in createSession, and no
            // more will execute it until failover is complete

            // So.. do failover / reconnection

            CoreRemotingConnection oldConnection = connection;

            connection = null;

            try
            {
               connector.close();
            }
            catch (Exception ignore)
            {
            }

            cancelScheduledTasks();

            connector = null;

            reconnectSessions(oldConnection, reconnectAttempts);

            oldConnection.destroy();
         }
         else
         {
            connection.destroy();

            connection = null;
         }

         if (connection == null)
         {
            synchronized (sessions)
            {
               sessionsToClose = new HashSet<ClientSessionInternal>(sessions);
            }
            callFailureListeners(me, true, false);
         }
      }

      // This needs to be outside the failover lock to prevent deadlock
      if (connection != null)
      {
         callFailureListeners(me, true, true);
      }
      if (sessionsToClose != null)
      {
         // If connection is null it means we didn't succeed in failing over or reconnecting
         // so we close all the sessions, so they will throw exceptions when attempted to be used

         for (ClientSessionInternal session : sessionsToClose)
         {
            try
            {
               session.cleanUp(true);
            }
            catch (Exception e)
            {
               log.error("Failed to cleanup session");
            }
         }
      }
   }

   private ClientSession createSessionInternal(final String username,
                                               final String password,
                                               final boolean xa,
                                               final boolean autoCommitSends,
                                               final boolean autoCommitAcks,
                                               final boolean preAcknowledge,
                                               final int ackBatchSize) throws HornetQException
   {
      synchronized (createSessionLock)
      {
         String name = UUIDGenerator.getInstance().generateStringUUID();

         boolean retry = false;
         do
         {
            Version clientVersion = VersionLoader.getVersion();

            Lock lock = null;

            try
            {
               Channel channel1;

               synchronized (failoverLock)
               {
                  if (connection == null)
                  {
                     throw new IllegalStateException("Connection is null");
                  }

                  channel1 = connection.getChannel(1, -1);

                  // Lock it - this must be done while the failoverLock is held
                  channel1.getLock().lock();

                  lock = channel1.getLock();
               } // We can now release the failoverLock

               // We now set a flag saying createSession is executing
               synchronized (exitLock)
               {
                  inCreateSession = true;
               }

               long sessionChannelID = connection.generateChannelID();

               Packet request = new CreateSessionMessage(name,
                                                         sessionChannelID,
                                                         clientVersion.getIncrementingVersion(),
                                                         username,
                                                         password,
                                                         serverLocator.getMinLargeMessageSize(),
                                                         xa,
                                                         autoCommitSends,
                                                         autoCommitAcks,
                                                         preAcknowledge,
                                                         serverLocator.getConfirmationWindowSize(),
                                                         null);

               Packet pResponse;
               try
               {
                  pResponse = channel1.sendBlocking(request);
               }
               catch (HornetQException e)
               {
                  if (e.getCode() == HornetQException.INCOMPATIBLE_CLIENT_SERVER_VERSIONS)
                  {
                     connection.destroy();
                  }

                  if (e.getCode() == HornetQException.UNBLOCKED)
                  {
                     // This means the thread was blocked on create session and failover unblocked it
                     // so failover could occur

                     retry = true;

                     continue;
                  }
                  else
                  {
                     throw e;
                  }
               }

               CreateSessionResponseMessage response = (CreateSessionResponseMessage)pResponse;

               Channel sessionChannel = connection.getChannel(sessionChannelID,
                                                              serverLocator.getConfirmationWindowSize());

               ClientSessionInternal session = new ClientSessionImpl(this,
                                                                     name,
                                                                     username,
                                                                     password,
                                                                     xa,
                                                                     autoCommitSends,
                                                                     autoCommitAcks,
                                                                     preAcknowledge,
                                                                     serverLocator.isBlockOnAcknowledge(),
                                                                     serverLocator.isAutoGroup(),
                                                                     ackBatchSize,
                                                                     serverLocator.getConsumerWindowSize(),
                                                                     serverLocator.getConsumerMaxRate(),
                                                                     serverLocator.getConfirmationWindowSize(),
                                                                     serverLocator.getProducerWindowSize(),
                                                                     serverLocator.getProducerMaxRate(),
                                                                     serverLocator.isBlockOnNonDurableSend(),
                                                                     serverLocator.isBlockOnDurableSend(),
                                                                     serverLocator.isCacheLargeMessagesClient(),
                                                                     serverLocator.getMinLargeMessageSize(),
                                                                     serverLocator.isCompressLargeMessage(),
                                                                     serverLocator.getInitialMessagePacketSize(),
                                                                     serverLocator.getGroupID(),
                                                                     connection,
                                                                     response.getServerVersion(),
                                                                     sessionChannel,
                                                                     orderedExecutorFactory.getExecutor());

               synchronized (sessions)
               {
                  sessions.add(session);
               }

               ChannelHandler handler = new ClientSessionPacketHandler(session, sessionChannel);

               sessionChannel.setHandler(handler);

               return new DelegatingSession(session);
            }
            catch (Throwable t)
            {
               if (lock != null)
               {
                  lock.unlock();

                  lock = null;
               }

               if (t instanceof HornetQException)
               {
                  throw (HornetQException)t;
               }
               else
               {
                  HornetQException me = new HornetQException(HornetQException.INTERNAL_ERROR,
                                                             "Failed to create session",
                                                             t);

                  throw me;
               }
            }
            finally
            {
               if (lock != null)
               {
                  lock.unlock();
               }

               // Execution has finished so notify any failover thread that may be waiting for us to be done
               synchronized (exitLock)
               {
                  inCreateSession = false;

                  exitLock.notify();
               }
            }
         }
         while (retry);
      }

      // Should never get here
      throw new IllegalStateException("Oh my God it's full of stars!");
   }

   private void callFailureListeners(final HornetQException me, final boolean afterReconnect, boolean failedOver)
   {
      final List<SessionFailureListener> listenersClone = new ArrayList<SessionFailureListener>(listeners);

      for (final SessionFailureListener listener : listenersClone)
      {
         try
         {
            if (afterReconnect)
            {
               listener.connectionFailed(me, failedOver);
            }
            else
            {
               listener.beforeReconnect(me);
            }
         }
         catch (final Throwable t)
         {
            // Failure of one listener to execute shouldn't prevent others
            // from
            // executing
            log.error("Failed to execute failure listener", t);
         }
      }
   }

   /*
    * Re-attach sessions all pre-existing sessions to the new remoting connection
    */
   private void reconnectSessions(final CoreRemotingConnection oldConnection, final int reconnectAttempts)
   {
      getConnectionWithRetry(reconnectAttempts);

      if (connection == null)
      {
         log.warn("Failed to connect to server.");

         return;
      }

      List<FailureListener> oldListeners = oldConnection.getFailureListeners();

      List<FailureListener> newListeners = new ArrayList<FailureListener>(connection.getFailureListeners());

      for (FailureListener listener : oldListeners)
      {
         // Add all apart from the first one which is the old DelegatingFailureListener

         if (listener instanceof DelegatingFailureListener == false)
         {
            newListeners.add(listener);
         }
      }

      connection.setFailureListeners(newListeners);

      HashSet<ClientSessionInternal> sessionsToFailover;
      synchronized (sessions)
      {
         sessionsToFailover = new HashSet<ClientSessionInternal>(sessions);
      }
      
      for (ClientSessionInternal session : sessionsToFailover)
      {
         session.handleFailover(connection);
      }
   }

   private void getConnectionWithRetry(final int reconnectAttempts)
   {
      long interval = retryInterval;

      int count = 0;

      synchronized (waitLock)
      {
         while (true)
         {
            if (exitLoop)
            {
               return;
            }

            if (log.isDebugEnabled())
            {
               log.debug("Trying reconnection attempt " + count);
            }

            getConnection();

            if (connection == null)
            {
               // Failed to get connection

               if (reconnectAttempts != 0)
               {
                  count++;
                  
                  if (reconnectAttempts != -1 && count == reconnectAttempts)
                  {
                     log.warn("Tried " + reconnectAttempts + " times to connect. Now giving up on reconnecting it.");

                     return;
                  }

                  try
                  {
                     waitLock.wait(interval);
                  }
                  catch (InterruptedException ignore)
                  {
                  }

                  // Exponential back-off
                  long newInterval = (long)(interval * retryIntervalMultiplier);

                  if (newInterval > maxRetryInterval)
                  {
                     newInterval = maxRetryInterval;
                  }

                  interval = newInterval;
               }
               else
               {
                  return;
               }
            }
            else
            {
               return;
            }
         }
      }
   }

   private void cancelScheduledTasks()
   {
      if (pingerFuture != null)
      {
         pingRunnable.cancel();

         pingerFuture.cancel(false);

         pingRunnable = null;

         pingerFuture = null;
      }
   }

   private void checkCloseConnection()
   {
      if (connection != null && sessions.size() == 0)
      {
         cancelScheduledTasks();

         try
         {
            connection.destroy();
         }
         catch (Throwable ignore)
         {
         }

         connection = null;

         try
         {
            if (connector != null)
            {
               connector.close();
            }
         }
         catch (Throwable ignore)
         {
         }

         connector = null;
      }
   }

   public CoreRemotingConnection getConnection()
   {
      if (connection == null)
      {
         Connection tc = null;

         try
         {
            DelegatingBufferHandler handler = new DelegatingBufferHandler();

            connector = connectorFactory.createConnector(connectorConfig.getParams(),
                                                         handler,
                                                         this,
                                                         closeExecutor,
                                                         threadPool,
                                                         scheduledThreadPool);

            if (connector != null)
            {
               connector.start();

               if (log.isDebugEnabled())
               {
                  log.debug("Trying to connect at the main server using connector :" + connectorConfig);
               }
               
               tc = connector.createConnection();

               if (tc == null)
               {
                  if (log.isDebugEnabled())
                  {
                     log.debug("Main server is not up. Hopefully there's a backup configured now!");
                  }
                  
                  try
                  {
                     connector.close();
                  }
                  catch (Throwable t)
                  {
                  }

                  connector = null;
               }
            }
            //if connection fails we can try the backup in case it has come live
            if(connector == null && backupConfig != null)
            {
               if (log.isDebugEnabled())
               {
                  log.debug("Trying backup config = " + backupConfig);
               }
               ConnectorFactory backupConnectorFactory = instantiateConnectorFactory(backupConfig.getFactoryClassName());
               connector = backupConnectorFactory.createConnector(backupConfig.getParams(),
                                                         handler,
                                                         this,
                                                         closeExecutor,
                                                         threadPool,
                                                         scheduledThreadPool);
               if (connector != null)
               {
                  connector.start();

                  tc = connector.createConnection();

                  if (tc == null)
                  {
                     if (log.isDebugEnabled())
                     {
                        log.debug("Backup is not active yet");
                     }
                     
                     try
                     {
                        connector.close();
                     }
                     catch (Throwable t)
                     {
                     }

                     connector = null;
                  }
                  else
                  {
                     /*looks like the backup is now live, lets use that*/
                     
                     if (log.isDebugEnabled())
                     {
                        log.debug("Connected to the backup at " + backupConfig);
                     }
                     
                     connectorConfig = backupConfig;

                     backupConfig = null;

                     connectorFactory = backupConnectorFactory;
                  }
               }
            }
         }
         catch (Exception e)
         {
            // Sanity catch for badly behaved remoting plugins

            log.warn("connector.create or connectorFactory.createConnector should never throw an exception, implementation is badly behaved, but we'll deal with it anyway.",
                     e);

            if (tc != null)
            {
               try
               {
                  tc.close();
               }
               catch (Throwable t)
               {
               }
            }

            if (connector != null)
            {
               try
               {
                  connector.close();
               }
               catch (Throwable t)
               {
               }
            }

            tc = null;

            connector = null;
         }

         if (tc == null)
         {
            return connection;
         }

         connection = new RemotingConnectionImpl(tc, callTimeout, interceptors);

         connection.addFailureListener(new DelegatingFailureListener(connection.getID()));

         Channel channel0 = connection.getChannel(0, -1);

         channel0.setHandler(new Channel0Handler(connection));

         if (clientFailureCheckPeriod != -1)
         {
            if (pingerFuture == null)
            {
               pingRunnable = new PingRunnable();

               pingerFuture = scheduledThreadPool.scheduleWithFixedDelay(new ActualScheduledPinger(pingRunnable),
                                                                         0,
                                                                         clientFailureCheckPeriod,
                                                                         TimeUnit.MILLISECONDS);
               // To make sure the first ping will be sent
               pingRunnable.send();
            }
            // send a ping every time we create a new remoting connection
            // to set up its TTL on the server side
            else
            {
               pingRunnable.run();
            }
         }

         if (serverLocator.isHA())
         {
            channel0.send(new SubscribeClusterTopologyUpdatesMessage(serverLocator.isClusterConnection()));
            if (serverLocator.isClusterConnection())
            {
               TransportConfiguration config = serverLocator.getClusterTransportConfiguration();
               channel0.send(new NodeAnnounceMessage(serverLocator.getNodeID(),
                                                     serverLocator.isBackup(),
                                                     config));
            }
         }
      }

      return connection;
   }

   public void finalize() throws Throwable
   {
      if (!closed)
      {
         log.warn("I'm closing a core ClientSessionFactory you left open. Please make sure you close all ClientSessionFactories explicitly " + "before letting them go out of scope! " +
                  System.identityHashCode(this));

         log.warn("The ClientSessionFactory you didn't close was created here:", e);

         close();
      }

      super.finalize();
   }

   private ConnectorFactory instantiateConnectorFactory(final String connectorFactoryClassName)
   {
      return (ConnectorFactory) ClassloadingUtil.safeInitNewInstance(connectorFactoryClassName);
   }

   private void lockChannel1()
   {
      Channel channel1 = connection.getChannel(1, -1);

      channel1.getLock().lock();
   }

   private void unlockChannel1()
   {
      Channel channel1 = connection.getChannel(1, -1);

      channel1.getLock().unlock();
   }

   private void forceReturnChannel1()
   {
      Channel channel1 = connection.getChannel(1, -1);

      channel1.returnBlocking();
   }

   private void checkTransportKeys(final ConnectorFactory factory, final Map<String, Object> params)
   {
      if (params != null)
      {
         Set<String> invalid = ConfigurationHelper.checkKeys(factory.getAllowableProperties(), params.keySet());

         if (!invalid.isEmpty())
         {
            String msg = ConfigurationHelper.stringSetToCommaListString("The following keys are invalid for configuring a connector: ",
                                                                        invalid);

            throw new IllegalStateException(msg);

         }
      }
   }

   private class Channel0Handler implements ChannelHandler
   {
      private final CoreRemotingConnection conn;

      private Channel0Handler(final CoreRemotingConnection conn)
      {
         this.conn = conn;
      }

      public void handlePacket(final Packet packet)
      {
         final byte type = packet.getType();

         if (type == PacketImpl.DISCONNECT)
         {
            final DisconnectMessage msg = (DisconnectMessage)packet;
            
            closeExecutor.execute(new Runnable()
            {
               // Must be executed on new thread since cannot block the netty thread for a long time and fail can
               // cause reconnect loop
               public void run()
               {
                  SimpleString nodeID = msg.getNodeID();
                  if (nodeID != null)
                  {
                     serverLocator.notifyNodeDown(msg.getNodeID().toString());
                  }

                  conn.fail(new HornetQException(HornetQException.DISCONNECTED,
                                                 "The connection was disconnected because of server shutdown"));

               }
            });
         }
         else if (type == PacketImpl.CLUSTER_TOPOLOGY)
         {
            ClusterTopologyChangeMessage topMessage = (ClusterTopologyChangeMessage)packet;

            if (topMessage.isExit())
            {
               if (log.isDebugEnabled())
               {
                  log.debug("Notifying " + topMessage.getNodeID() + " going down");
               }
               serverLocator.notifyNodeDown(topMessage.getNodeID());
            }
            else
            {
               if (log.isDebugEnabled())
               {
                  log.debug("Node " + topMessage.getNodeID() + " going up, connector = " + topMessage.getPair() + ", isLast=" + topMessage.isLast());
               }
               serverLocator.notifyNodeUp(topMessage.getNodeID(),
                                          topMessage.getPair(),
                                          topMessage.isLast());
            }
         }
      }
   }

   private class DelegatingBufferHandler implements BufferHandler
   {
      public void bufferReceived(final Object connectionID, final HornetQBuffer buffer)
      {
         CoreRemotingConnection theConn = connection;

         if (theConn != null && connectionID == theConn.getID())
         {
            theConn.bufferReceived(connectionID, buffer);
         }
      }
   }

   private class DelegatingFailureListener implements FailureListener
   {
      private final Object connectionID;

      DelegatingFailureListener(final Object connectionID)
      {
         this.connectionID = connectionID;
      }

      public void connectionFailed(final HornetQException me, boolean failedOver)
      {
         handleConnectionFailure(connectionID, me);
      }
   }

   private static final class ActualScheduledPinger implements Runnable
   {
      private final WeakReference<PingRunnable> pingRunnable;

      ActualScheduledPinger(final PingRunnable runnable)
      {
         pingRunnable = new WeakReference<PingRunnable>(runnable);
      }

      public void run()
      {
         PingRunnable runnable = pingRunnable.get();

         if (runnable != null)
         {
            runnable.run();
         }
      }

   }

   private final class PingRunnable implements Runnable
   {
      private boolean cancelled;

      private boolean first;

      private long lastCheck = System.currentTimeMillis();

      public synchronized void run()
      {
         if (cancelled || stopPingingAfterOne && !first)
         {
            return;
         }

         first = false;

         long now = System.currentTimeMillis();
         
         if (clientFailureCheckPeriod != -1 && connectionTTL != -1 && now >= lastCheck + connectionTTL )
         {
            if (!connection.checkDataReceived())
            {
               final HornetQException me = new HornetQException(HornetQException.CONNECTION_TIMEDOUT,
                                                                "Did not receive data from server for " + connection.getTransportConnection());

               cancelled = true;

               threadPool.execute(new Runnable()
               {
                  // Must be executed on different thread
                  public void run()
                  {
                     connection.fail(me);
                  }
               });

               return;
            }
            else
            {
               lastCheck = now;
            }
         }

         send();
      }

      /**
       * 
       */
      public void send()
      {
         // Send a ping

         Ping ping = new Ping(connectionTTL);

         Channel channel0 = connection.getChannel(0, -1);

         channel0.send(ping);

         connection.flush();
      }

      public synchronized void cancel()
      {
         cancelled = true;
      }
   }
}
