/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.impl;

import org.terracotta.corestorage.monitoring.MonitoredResource;

import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.PostInit;
import com.tc.async.api.Sink;
import com.tc.async.api.StageManager;
import com.tc.config.HaConfig;
import com.tc.config.schema.setup.L2ConfigurationSetupManager;
import com.tc.io.TCFile;
import com.tc.l2.api.L2Coordinator;
import com.tc.l2.ha.L2HACoordinator;
import com.tc.l2.ha.WeightGeneratorFactory;
import com.tc.l2.objectserver.L2IndexStateManager;
import com.tc.l2.objectserver.L2ObjectStateManager;
import com.tc.l2.objectserver.L2ObjectStateManagerImpl;
import com.tc.l2.objectserver.L2PassiveSyncStateManager;
import com.tc.l2.objectserver.L2PassiveSyncStateManagerImpl;
import com.tc.l2.objectserver.NullL2IndexStateManager;
import com.tc.l2.objectserver.ServerTransactionFactory;
import com.tc.l2.state.StateSyncManager;
import com.tc.logging.DumpHandlerStore;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.L2Management;
import com.tc.management.beans.L2State;
import com.tc.management.beans.LockStatisticsMonitor;
import com.tc.management.beans.TCServerInfoMBean;
import com.tc.management.beans.object.ServerDBBackupMBean;
import com.tc.net.GroupID;
import com.tc.net.ServerID;
import com.tc.net.core.security.TCSecurityManager;
import com.tc.net.groups.GroupManager;
import com.tc.net.groups.SingleNodeGroupManager;
import com.tc.net.groups.StripeIDStateManager;
import com.tc.net.groups.TCGroupManagerImpl;
import com.tc.net.protocol.tcm.ChannelManager;
import com.tc.net.protocol.transport.ConnectionIDFactory;
import com.tc.object.msg.MessageRecycler;
import com.tc.object.net.ChannelStats;
import com.tc.object.net.ChannelStatsImpl;
import com.tc.object.net.DSOChannelManager;
import com.tc.object.persistence.api.PersistentMapStore;
import com.tc.objectserver.api.GarbageCollectionManager;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.api.ObjectRequestManager;
import com.tc.objectserver.api.ServerMapRequestManager;
import com.tc.objectserver.clustermetadata.ServerClusterMetaDataManager;
import com.tc.objectserver.core.api.DSOGlobalServerStats;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.core.impl.ServerConfigurationContextImpl;
import com.tc.objectserver.dgc.api.GarbageCollectionInfoPublisher;
import com.tc.objectserver.dgc.api.GarbageCollector;
import com.tc.objectserver.dgc.impl.DGCOperatorEventPublisher;
import com.tc.objectserver.dgc.impl.GCStatsEventPublisher;
import com.tc.objectserver.dgc.impl.MarkAndSweepGarbageCollector;
import com.tc.objectserver.gtx.ServerGlobalTransactionManager;
import com.tc.objectserver.handshakemanager.ServerClientHandshakeManager;
import com.tc.objectserver.l1.api.ClientStateManager;
import com.tc.objectserver.locks.LockManager;
import com.tc.objectserver.metadata.MetaDataManager;
import com.tc.objectserver.metadata.NullMetaDataManager;
import com.tc.objectserver.mgmt.ObjectStatsRecorder;
import com.tc.objectserver.persistence.HeapStorageManagerFactory;
import com.tc.objectserver.persistence.Persistor;
import com.tc.objectserver.search.IndexHACoordinator;
import com.tc.objectserver.search.IndexManager;
import com.tc.objectserver.search.NullIndexHACoordinator;
import com.tc.objectserver.search.NullSearchRequestManager;
import com.tc.objectserver.search.SearchRequestManager;
import com.tc.objectserver.tx.CommitTransactionMessageToTransactionBatchReader;
import com.tc.objectserver.tx.PassThruTransactionFilter;
import com.tc.objectserver.tx.ServerTransactionManager;
import com.tc.objectserver.tx.TransactionBatchManagerImpl;
import com.tc.objectserver.tx.TransactionFilter;
import com.tc.objectserver.tx.TransactionalObjectManager;
import com.tc.operatorevent.TerracottaOperatorEventCallbackLogger;
import com.tc.operatorevent.TerracottaOperatorEventHistoryProvider;
import com.tc.operatorevent.TerracottaOperatorEventLogger;
import com.tc.operatorevent.TerracottaOperatorEventLogging;
import com.tc.runtime.logging.LongGCLogger;
import com.tc.server.ServerConnectionValidator;
import com.tc.util.BlockingStartupLock;
import com.tc.util.NonBlockingStartupLock;
import com.tc.util.StartupLock;
import com.tc.util.runtime.ThreadDumpUtil;
import com.tc.util.sequence.DGCSequenceProvider;
import com.tc.util.sequence.ObjectIDSequence;
import com.tc.util.sequence.SequenceGenerator;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import javax.management.MBeanServer;

public class StandardDSOServerBuilder implements DSOServerBuilder {
  private final HaConfig          haConfig;
  private final GroupID           thisGroupID;

  protected final TCSecurityManager securityManager;
  protected final TCLogger          logger;

  public StandardDSOServerBuilder(final HaConfig haConfig, final TCLogger logger, final TCSecurityManager securityManager) {
    this.logger = logger;
    this.securityManager = securityManager;
    this.logger.info("Standard DSO Server created");
    this.haConfig = haConfig;
    this.thisGroupID = this.haConfig.getThisGroupID();
  }

  @Override
  public GarbageCollector createGarbageCollector(final List<PostInit> toInit,
                                                 final ObjectManagerConfig objectManagerConfig,
                                                 final ObjectManager objectMgr, final ClientStateManager stateManager,
                                                 final StageManager stageManager, final int maxStageSize,
                                                 final GarbageCollectionInfoPublisher gcPublisher,
                                                 final ObjectManager objectManager,
                                                 final ClientStateManager clientStateManger,
                                                 final GCStatsEventPublisher gcEventListener,
                                                 final DGCSequenceProvider dgcSequenceProvider,
                                                 final ServerTransactionManager serverTransactionManager,
                                                 final GarbageCollectionManager garbageCollectionManager) {
    final MarkAndSweepGarbageCollector gc = new MarkAndSweepGarbageCollector(objectManagerConfig, objectMgr,
                                                                             stateManager, gcPublisher,
                                                                             dgcSequenceProvider,
                                                                             garbageCollectionManager);
    gc.addListener(gcEventListener);
    gc.addListener(new DGCOperatorEventPublisher());
    return gc;
  }

  @Override
  public GroupManager createGroupCommManager(final boolean networkedHA,
                                             final L2ConfigurationSetupManager configManager,
                                             final StageManager stageManager, final ServerID serverNodeID,
                                             final Sink httpSink, final StripeIDStateManager stripeStateManager,
                                             final ServerGlobalTransactionManager gtxm) {
    if (networkedHA) {
      return new TCGroupManagerImpl(configManager, stageManager, serverNodeID, httpSink, this.haConfig.getNodesStore(),
                                    securityManager);
    } else {
      return new SingleNodeGroupManager();
    }
  }

  @Override
  public MetaDataManager createMetaDataManager(Sink sink) {
    return new NullMetaDataManager();
  }

  @Override
  @SuppressWarnings("unused")
  public IndexHACoordinator createIndexHACoordinator(L2ConfigurationSetupManager configSetupManager, Sink sink)
      throws IOException {
    return new NullIndexHACoordinator();
  }

  @Override
  public L2IndexStateManager createL2IndexStateManager(IndexHACoordinator indexHACoordinator,
                                                       ServerTransactionManager transactionManager,
                                                       SequenceGenerator indexSequenceGenerator,
                                                       GroupManager groupManager) {
    return new NullL2IndexStateManager();
  }

  @Override
  public L2ObjectStateManager createL2ObjectStateManager(ObjectManager objectManager,
                                                         ServerTransactionManager transactionManager) {
    return new L2ObjectStateManagerImpl(objectManager, transactionManager);
  }

  @Override
  public L2PassiveSyncStateManager createL2PassiveSyncStateManager(L2IndexStateManager l2IndexStateManager,
                                                                   L2ObjectStateManager l2ObjectStateManager,
                                                                   StateSyncManager stateSyncManager) {
    return new L2PassiveSyncStateManagerImpl(l2IndexStateManager, l2ObjectStateManager, stateSyncManager);
  }

  @Override
  public SearchRequestManager createSearchRequestManager(DSOChannelManager channelManager, Sink managedObjectRequestSink) {
    return new NullSearchRequestManager();
  }

  @Override
  public ObjectRequestManager createObjectRequestManager(ObjectManager objectMgr, DSOChannelManager channelManager,
                                                         ClientStateManager clientStateMgr,
                                                         ServerTransactionManager transactionMgr,
                                                         Sink objectRequestSink, Sink respondObjectRequestSink,
                                                         ObjectStatsRecorder statsRecorder, List<PostInit> toInit,
                                                         StageManager stageManager, int maxStageSize,
                                                         DumpHandlerStore dumpHandlerStore) {
    ObjectRequestManagerImpl orm = new ObjectRequestManagerImpl(objectMgr, channelManager, clientStateMgr,
                                                                objectRequestSink, respondObjectRequestSink,
                                                                statsRecorder);
    return new ObjectRequestManagerRestartImpl(objectMgr, transactionMgr, orm);
  }

  @Override
  public ServerMapRequestManager createServerMapRequestManager(final ObjectManager objectMgr,
                                                               final DSOChannelManager channelManager,
                                                               final Sink respondToServerTCMapSink,
                                                               final Sink managedObjectRequestSink,
                                                               ClientStateManager clientStateManager,
                                                               ChannelStats channelStats) {
    return new ServerMapRequestManagerImpl(objectMgr, channelManager, respondToServerTCMapSink,
                                           managedObjectRequestSink, clientStateManager, channelStats);
  }

  @Override
  public ServerConfigurationContext createServerConfigurationContext(StageManager stageManager,
                                                                     ObjectManager objMgr,
                                                                     ObjectRequestManager objRequestMgr,
                                                                     ServerMapRequestManager serverTCMapRequestManager,
                                                                     PersistentManagedObjectStore objStore,
                                                                     LockManager lockMgr,
                                                                     DSOChannelManager channelManager,
                                                                     ClientStateManager clientStateMgr,
                                                                     ServerTransactionManager txnMgr,
                                                                     TransactionalObjectManager txnObjectMgr,
                                                                     ChannelStatsImpl channelStats,
                                                                     L2Coordinator coordinator,
                                                                     TransactionBatchManagerImpl transactionBatchManager,
                                                                     ServerGlobalTransactionManager gtxm,
                                                                     ServerClientHandshakeManager clientHandshakeManager,
                                                                     ServerClusterMetaDataManager clusterMetaDataManager,
                                                                     DSOGlobalServerStats serverStats,
                                                                     ConnectionIDFactory connectionIdFactory,
                                                                     int maxStageSize,
                                                                     ChannelManager genericChannelManager,
                                                                     DumpHandlerStore dumpHandlerStore,
                                                                     MetaDataManager metaDataManager,
                                                                     IndexManager indexManager,
                                                                     SearchRequestManager searchRequestManager,
                                                                     GarbageCollectionManager deleteObjectManager) {
    return new ServerConfigurationContextImpl(stageManager, objMgr, objRequestMgr, serverTCMapRequestManager, objStore,
                                              lockMgr, channelManager, clientStateMgr, txnMgr, txnObjectMgr,
                                              clientHandshakeManager, channelStats, coordinator,
                                              new CommitTransactionMessageToTransactionBatchReader(serverStats),
                                              transactionBatchManager, gtxm, clusterMetaDataManager, metaDataManager,
                                              indexManager, searchRequestManager, deleteObjectManager);
  }

  @Override
  public TransactionFilter getTransactionFilter(final List<PostInit> toInit, final StageManager stageManager,
                                                final int maxStageSize) {
    final PassThruTransactionFilter txnFilter = new PassThruTransactionFilter();
    toInit.add(txnFilter);
    return txnFilter;
  }

  @Override
  public GroupManager getClusterGroupCommManager() {
    throw new AssertionError("Not supported");
  }

  @Override
  public GCStatsEventPublisher getLocalDGCStatsEventPublisher() {
    throw new AssertionError("Not supported");
  }

  @Override
  public void dump() {
    TCLogging.getDumpLogger().info(ThreadDumpUtil.getThreadDump());
  }

  @Override
  public void initializeContext(final ConfigurationContext context) {
    // Nothing to initialize here
  }

  @Override
  public L2Coordinator createL2HACoordinator(final TCLogger consoleLogger, final DistributedObjectServer server,
                                             final StageManager stageManager, final GroupManager groupCommsManager,
                                             final PersistentMapStore persistentMapStore,
                                             final L2PassiveSyncStateManager l2PassiveSyncStateManager,
                                             final L2ObjectStateManager l2ObjectStateManager,
                                             final L2IndexStateManager l2IndexStateManager,
                                             final ObjectManager objectManager,
                                             final IndexHACoordinator indexHACoordinator,
                                             final ServerTransactionManager transactionManager,
                                             final ServerGlobalTransactionManager gtxm,
                                             final WeightGeneratorFactory weightGeneratorFactory,
                                             final L2ConfigurationSetupManager configurationSetupManager,
                                             final MessageRecycler recycler,
                                             final StripeIDStateManager stripeStateManager,
                                             final ServerTransactionFactory serverTransactionFactory,
                                             final DGCSequenceProvider dgcSequenceProvider,
                                             final SequenceGenerator indexSequenceGenerator, final ObjectIDSequence objectIDSequence,
                                             final MonitoredResource resource) {
    return new L2HACoordinator(consoleLogger, server, stageManager, groupCommsManager, persistentMapStore,
                               objectManager, indexHACoordinator, l2PassiveSyncStateManager, l2ObjectStateManager,
                               l2IndexStateManager, transactionManager, gtxm, weightGeneratorFactory,
                               configurationSetupManager, recycler, this.thisGroupID, stripeStateManager,
                               serverTransactionFactory, dgcSequenceProvider, indexSequenceGenerator, objectIDSequence, resource);
  }

  @Override
  public L2Management createL2Management(final TCServerInfoMBean tcServerInfoMBean,
                                         final LockStatisticsMonitor lockStatisticsMBean,
                                         final L2ConfigurationSetupManager configSetupManager,
                                         final DistributedObjectServer distributedObjectServer, final InetAddress bind,
                                         final int jmxPort, final Sink remoteEventsSink,
                                         final ServerConnectionValidator serverConnectionValidator,
                                         final ServerDBBackupMBean serverDBBackupMBean) throws Exception {
    return new L2Management(tcServerInfoMBean, lockStatisticsMBean, configSetupManager, distributedObjectServer, bind,
                            jmxPort, remoteEventsSink);
  }

  @Override
  public void registerForOperatorEvents(final L2Management l2Management,
                                        final TerracottaOperatorEventHistoryProvider operatorEventHistoryProvider,
                                        final MBeanServer l2MbeanServer) {
    // register logger for OSS version
    TerracottaOperatorEventLogger tcEventLogger = TerracottaOperatorEventLogging.getEventLogger();
    tcEventLogger.registerEventCallback(new TerracottaOperatorEventCallbackLogger());
  }

  @Override
  public LongGCLogger createLongGCLogger(long gcTimeOut) {
    return new LongGCLogger(gcTimeOut);
  }

  @Override
  public StartupLock createStartupLock(final TCFile location, final boolean retries) {
    if (this.haConfig.isNetworkedActivePassive()) {
      return new NonBlockingStartupLock(location, retries);
    } else if (this.haConfig.isDiskedBasedActivePassive()) {
      return new BlockingStartupLock(location, retries);
    } else {
      throw new AssertionError("Invalid HA mode");
    }
  }

  @Override
  public Persistor createPersistor(final boolean persistent, final File l2DataPath, final L2State l2State)
      throws IOException {
    // make warning go away
    if (false) { throw new IOException(); }

    if (persistent) throw new UnsupportedOperationException("Restartability is not supported in open source servers.");
    return new Persistor(HeapStorageManagerFactory.INSTANCE);
  }
}
