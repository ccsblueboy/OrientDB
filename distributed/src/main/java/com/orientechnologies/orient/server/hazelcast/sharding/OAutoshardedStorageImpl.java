/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientechnologies.orient.server.hazelcast.sharding;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.orientechnologies.common.concur.resource.OSharedResourceAdaptiveExternal;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.util.MersenneTwister;
import com.orientechnologies.orient.core.cache.OLevel2RecordCache;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.config.OStorageConfiguration;
import com.orientechnologies.orient.core.id.OClusterPosition;
import com.orientechnologies.orient.core.id.OClusterPositionFactory;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.storage.*;
import com.orientechnologies.orient.core.tx.OTransaction;
import com.orientechnologies.orient.core.version.ORecordVersion;
import com.orientechnologies.orient.server.distributed.ODistributedException;
import com.orientechnologies.orient.server.distributed.ODistributedThreadLocal;
import com.orientechnologies.orient.server.hazelcast.sharding.distributed.ODHTConfiguration;
import com.orientechnologies.orient.server.hazelcast.sharding.distributed.ODHTNode;
import com.orientechnologies.orient.server.hazelcast.sharding.hazelcast.ServerInstance;

/**
 * Implementation of the Autosharded storage. This storage is build on Distributed Hash Table principle. Each storage is responsible
 * for the part of record ids range (ids are generated by random). In addition storage holds data from other ranges to prevent data
 * lose.
 * 
 * @author <a href="mailto:enisher@gmail.com">Artem Orobets</a>
 * @since 8/28/12
 */
public class OAutoshardedStorageImpl implements OAutoshardedStorage {
  protected final OStorageEmbedded wrapped;
  private final ServerInstance     serverInstance;

  private final MersenneTwister    positionGenerator     = new MersenneTwister();

  private final Set<Integer>       undistributedClusters = new HashSet<Integer>();

  /**
   * For parallel query execution on different nodes
   */

  public OAutoshardedStorageImpl(ServerInstance serverInstance, OStorageEmbedded wrapped, ODHTConfiguration dhtConfiguration) {
    this.serverInstance = serverInstance;
    this.wrapped = wrapped;

    for (String clusterName : dhtConfiguration.getUndistributableClusters()) {
      undistributedClusters.add(wrapped.getClusterIdByName(clusterName));
    }
  }

  @Override
  public long getStorageId() {
    return serverInstance.getLocalNode().getNodeId();
  }

  @Override
  public OStorageOperationResult<OPhysicalPosition> createRecord(int iDataSegmentId, ORecordId iRecordId, byte[] iContent,
      ORecordVersion iRecordVersion, byte iRecordType, int iMode, ORecordCallback<OClusterPosition> iCallback) {
    if (undistributedClusters.contains(iRecordId.getClusterId())) {
      return wrapped.createRecord(iDataSegmentId, iRecordId, iContent, iRecordVersion, iRecordType, iMode, iCallback);
    }

    OPhysicalPosition result;
    int retryCount = 0;
    while (true) {
      try {
        if (iRecordId.isNew())
          iRecordId.clusterPosition = OClusterPositionFactory.INSTANCE.valueOf(Math.abs(positionGenerator.nextLong()));

        final ODHTNode node = serverInstance.findSuccessor(iRecordId.clusterPosition.longValue());
        if (node.isLocal()) {
          OLogManager.instance().info(this, "Record " + iRecordId.toString() + " has been distributed to this node.");

          return wrapped.createRecord(iDataSegmentId, iRecordId, iContent, iRecordVersion, iRecordType, iMode, iCallback);
        } else
          result = node.createRecord(wrapped.getName(), iRecordId, iContent, iRecordVersion, iRecordType);
        break;
      } catch (ORecordDuplicatedException e) {
        if (retryCount > 10) {
          throw e;
        }
        retryCount++;
      }
    }
    iRecordId.clusterPosition = result.clusterPosition;

    return new OStorageOperationResult<OPhysicalPosition>(result, true);
  }

  @Override
  public OStorageOperationResult<ORawBuffer> readRecord(ORecordId iRid, String iFetchPlan, boolean iIgnoreCache,
      ORecordCallback<ORawBuffer> iCallback) {
    if (undistributedClusters.contains(iRid.getClusterId())) {
      return wrapped.readRecord(iRid, iFetchPlan, iIgnoreCache, iCallback);
    }

    final ODHTNode node = serverInstance.findSuccessor(iRid.clusterPosition.longValue());

    if (node.isLocal())
      return wrapped.readRecord(iRid, iFetchPlan, iIgnoreCache, iCallback);
    else
      return new OStorageOperationResult<ORawBuffer>(node.readRecord(wrapped.getName(), iRid), true);
  }

  @Override
  public OStorageOperationResult<ORecordVersion> updateRecord(ORecordId iRecordId, byte[] iContent, ORecordVersion iVersion,
      byte iRecordType, int iMode, ORecordCallback<ORecordVersion> iCallback) {
    if (undistributedClusters.contains(iRecordId.getClusterId())) {
      return wrapped.updateRecord(iRecordId, iContent, iVersion, iRecordType, iMode, iCallback);
    }

    final ODHTNode node = serverInstance.findSuccessor(iRecordId.clusterPosition.longValue());
    if (node.isLocal())
      return wrapped.updateRecord(iRecordId, iContent, iVersion, iRecordType, iMode, iCallback);
    else
      return new OStorageOperationResult<ORecordVersion>(node.updateRecord(wrapped.getName(), iRecordId, iContent, iVersion,
          iRecordType), true);
  }

  @Override
  public OStorageOperationResult<Boolean> deleteRecord(ORecordId iRecordId, ORecordVersion iVersion, int iMode,
      ORecordCallback<Boolean> iCallback) {
    if (ODistributedThreadLocal.INSTANCE.distributedExecution || undistributedClusters.contains(iRecordId.getClusterId())) {
      return wrapped.deleteRecord(iRecordId, iVersion, iMode, iCallback);
    }

    final ODHTNode node = serverInstance.findSuccessor(iRecordId.clusterPosition.longValue());
    if (node.isLocal())
      return wrapped.deleteRecord(iRecordId, iVersion, iMode, iCallback);
    else
      return new OStorageOperationResult<Boolean>(node.deleteRecord(wrapped.getName(), iRecordId, iVersion), true);
  }

  @Override
  public Object command(OCommandRequestText iCommand) {
    return OQueryExecutorsFactory.INSTANCE.getExecutor(iCommand, wrapped, serverInstance, undistributedClusters).execute();
  }

  public boolean existsResource(final String iName) {
    return wrapped.existsResource(iName);
  }

  public <T> T removeResource(final String iName) {
    return wrapped.removeResource(iName);
  }

  public <T> T getResource(final String iName, final Callable<T> iCallback) {
    return wrapped.getResource(iName, iCallback);
  }

  public void open(final String iUserName, final String iUserPassword, final Map<String, Object> iProperties) {
    wrapped.open(iUserName, iUserPassword, iProperties);
  }

  public void create(final Map<String, Object> iProperties) {
    wrapped.create(iProperties);
  }

  public boolean exists() {
    return wrapped.exists();
  }

  public void reload() {
    wrapped.reload();
  }

  public void delete() {
    wrapped.delete();
  }

  public void close() {
    wrapped.close();
  }

  public void close(final boolean iForce) {
    wrapped.close(iForce);
  }

  public boolean isClosed() {
    return wrapped.isClosed();
  }

  public OLevel2RecordCache getLevel2Cache() {
    return wrapped.getLevel2Cache();
  }

  public void commit(final OTransaction iTx) {
    throw new ODistributedException("Transactions are not supported in distributed environment");
  }

  public void rollback(final OTransaction iTx) {
    throw new ODistributedException("Transactions are not supported in distributed environment");
  }

  public OStorageConfiguration getConfiguration() {
    return wrapped.getConfiguration();
  }

  public int getClusters() {
    return wrapped.getClusters();
  }

  public Set<String> getClusterNames() {
    return wrapped.getClusterNames();
  }

  public OCluster getClusterById(int iId) {
    return wrapped.getClusterById(iId);
  }

  public Collection<? extends OCluster> getClusterInstances() {
    return wrapped.getClusterInstances();
  }

  public int addCluster(final String iClusterType, final String iClusterName, final String iLocation,
      final String iDataSegmentName, final Object... iParameters) {
    return wrapped.addCluster(iClusterType, iClusterName, iLocation, iDataSegmentName, iParameters);
  }

  public boolean dropCluster(final String iClusterName) {
    return wrapped.dropCluster(iClusterName);
  }

  public boolean dropCluster(final int iId) {
    return wrapped.dropCluster(iId);
  }

  public int addDataSegment(final String iDataSegmentName) {
    return wrapped.addDataSegment(iDataSegmentName);
  }

  public int addDataSegment(final String iSegmentName, final String iDirectory) {
    return wrapped.addDataSegment(iSegmentName, iDirectory);
  }

  public long count(final int iClusterId) {
    return wrapped.count(iClusterId);
  }

  public long count(final int[] iClusterIds) {
    return wrapped.count(iClusterIds);
  }

  public long getSize() {
    return wrapped.getSize();
  }

  public long countRecords() {
    return wrapped.countRecords();
  }

  public int getDefaultClusterId() {
    return wrapped.getDefaultClusterId();
  }

  public void setDefaultClusterId(final int defaultClusterId) {
    wrapped.setDefaultClusterId(defaultClusterId);
  }

  public int getClusterIdByName(String iClusterName) {
    return wrapped.getClusterIdByName(iClusterName);
  }

  public String getClusterTypeByName(final String iClusterName) {
    return wrapped.getClusterTypeByName(iClusterName);
  }

  public String getPhysicalClusterNameById(final int iClusterId) {
    return wrapped.getPhysicalClusterNameById(iClusterId);
  }

  public boolean checkForRecordValidity(final OPhysicalPosition ppos) {
    return wrapped.checkForRecordValidity(ppos);
  }

  public String getName() {
    return wrapped.getName();
  }

  public String getURL() {
    return wrapped.getURL();
  }

  public long getVersion() {
    return wrapped.getVersion();
  }

  public void synch() {
    wrapped.synch();
  }

  public int getUsers() {
    return wrapped.getUsers();
  }

  public int addUser() {
    return wrapped.addUser();
  }

  public int removeUser() {
    return wrapped.removeUser();
  }

  public long[] getClusterDataRange(final int currentClusterId) {
    return wrapped.getClusterDataRange(currentClusterId);
  }

  public <V> V callInLock(final Callable<V> iCallable, final boolean iExclusiveLock) {
    return wrapped.callInLock(iCallable, iExclusiveLock);
  }

  public ODataSegment getDataSegmentById(final int iDataSegmentId) {
    return wrapped.getDataSegmentById(iDataSegmentId);
  }

  public int getDataSegmentIdByName(final String iDataSegmentName) {
    return wrapped.getDataSegmentIdByName(iDataSegmentName);
  }

  public boolean dropDataSegment(final String iName) {
    return wrapped.dropDataSegment(iName);
  }

  public STATUS getStatus() {
    return wrapped.getStatus();
  }

  @Override
  public void changeRecordIdentity(ORID originalId, ORID newId) {
    wrapped.changeRecordIdentity(originalId, newId);
  }

  @Override
  public boolean isLHClustersAreUsed() {
    return wrapped.isLHClustersAreUsed();
  }

  @Override
  public OClusterPosition[] getClusterPositionsForEntry(int currentClusterId, long entry) {
    return wrapped.getClusterPositionsForEntry(currentClusterId, entry);
  }

  @Override
  public OSharedResourceAdaptiveExternal getLock() {
    return wrapped.getLock();
  }

  @Override
  public String getType() {
    return "autoshareded";
  }

  @Override
  public void checkForClusterPermissions(final String iClusterName) {
    wrapped.checkForClusterPermissions(iClusterName);
  }
}
