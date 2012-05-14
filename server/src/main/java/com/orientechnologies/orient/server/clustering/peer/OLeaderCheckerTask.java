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
package com.orientechnologies.orient.server.clustering.peer;

import java.util.TimerTask;
import java.util.logging.Level;

import com.orientechnologies.orient.server.clustering.OClusterLogger;
import com.orientechnologies.orient.server.clustering.OClusterLogger.DIRECTION;
import com.orientechnologies.orient.server.clustering.OClusterLogger.TYPE;

/**
 * Checks the heart-beat sent by the leader node. If too much time is gone it tries to became the new leader.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class OLeaderCheckerTask extends TimerTask {
	private OPeerNode				peer;
	private long						heartBeatDelay;
	private OClusterLogger	logger	= new OClusterLogger();

	public OLeaderCheckerTask(final OPeerNode iPeerNode) {
		this.peer = iPeerNode;

		// COMPUTE THE HEARTBEAT THRESHOLD AS THE 30% MORE THAN THE HEARTBEAT TIME
		heartBeatDelay = peer.getManager().getConfig().getNetworkHeartbeatDelay() * 130 / 100;
	}

	@Override
	public void run() {
		final long time = System.currentTimeMillis() - peer.getLastHeartBeat();
		if (time > heartBeatDelay) {
			// NO LEADER HEARTBEAT RECEIVED FROM LONG TIME: BECAME THE LEADER!
			logger.log(this, Level.WARNING, TYPE.CLUSTER, DIRECTION.IN,
					"no heartbeat message has been received from the Leader node (last was %d ms ago)", time);

			cancel();
			peer.getManager().becameLeader();
		}
	}
}
