/*
 * Copyright 1999-2010 Luca Garulli (l.garulli--at--orientechnologies.com)
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
package com.orientechnologies.orient.core.metadata.security;

import java.util.List;

import com.orientechnologies.common.concur.resource.OSharedResourceExternal;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.exception.OSecurityAccessException;
import com.orientechnologies.orient.core.exception.OSecurityException;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.metadata.security.OUser.STATUSES;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.OStorageEmbedded;

/**
 * Shared security class. It's shared by all the getDatabase() instances that point to the same storage.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class OSecurityShared {
	private OSharedResourceExternal	lock	= new OSharedResourceExternal();

	public OUser authenticate(final String iUserName, final String iUserPassword) {
		lock.acquireSharedLock();
		try {

			final String dbName = getDatabase().getName();

			final OUser user = getUser(iUserName);
			if (user == null)
				throw new OSecurityAccessException(dbName, "User or password not valid for database: '" + dbName + "'");

			if (user.getAccountStatus() != STATUSES.ACTIVE)
				throw new OSecurityAccessException(dbName, "User '" + iUserName + "' is not active");

			if (getDatabase().getStorage() instanceof OStorageEmbedded) {
				// CHECK USER & PASSWORD
				if (!user.checkPassword(iUserPassword)) {
					// WAIT A BIT TO AVOID BRUTE FORCE
					try {
						Thread.sleep(200);
					} catch (InterruptedException e) {
					}
					throw new OSecurityAccessException(dbName, "User or password not valid for database: '" + dbName + "'");
				}
			}

			return user;

		} finally {
			lock.releaseSharedLock();
		}
	}

	public OUser getUser(final String iUserName) {
		lock.acquireSharedLock();
		try {

			final List<ODocument> result = getDatabase().<OCommandRequest> command(
					new OSQLSynchQuery<ODocument>("select from OUser where name = '" + iUserName + "'").setFetchPlan("*:-1")).execute();

			if (result != null && result.size() > 0)
				return new OUser(result.get(0));

			return null;

		} finally {
			lock.releaseSharedLock();
		}
	}

	public OUser createUser(final String iUserName, final String iUserPassword, final String[] iRoles) {
		lock.acquireExclusiveLock();
		try {

			final OUser user = new OUser(getDatabase(), iUserName, iUserPassword);

			if (iRoles != null)
				for (String r : iRoles) {
					user.addRole(r);
				}

			return user.save();

		} finally {
			lock.releaseExclusiveLock();
		}
	}

	public ORole getRole(final String iRoleName) {
		lock.acquireSharedLock();
		try {

			final List<ODocument> result = getDatabase().<OCommandRequest> command(
					new OSQLSynchQuery<ODocument>("select from ORole where name = '" + iRoleName + "'").setFetchPlan("*:-1")).execute();

			if (result != null && result.size() > 0)
				return new ORole(result.get(0));

			return null;

		} finally {
			lock.releaseSharedLock();
		}
	}

	public ORole createRole(final String iRoleName, final ORole.ALLOW_MODES iAllowMode) {
		return createRole(iRoleName, null, iAllowMode);
	}

	public ORole createRole(final String iRoleName, final ORole iParent, final ORole.ALLOW_MODES iAllowMode) {
		lock.acquireExclusiveLock();
		try {

			final ORole role = new ORole(getDatabase(), iRoleName, iParent, iAllowMode);
			return role.save();

		} finally {
			lock.releaseExclusiveLock();
		}
	}

	public List<ODocument> getUsers() {
		lock.acquireSharedLock();
		try {

			return getDatabase().<OCommandRequest> command(new OSQLSynchQuery<ODocument>("select from OUser")).execute();

		} finally {
			lock.releaseSharedLock();
		}
	}

	public List<ODocument> getRoles() {
		lock.acquireSharedLock();
		try {

			return getDatabase().<OCommandRequest> command(new OSQLSynchQuery<ODocument>("select from ORole")).execute();

		} finally {
			lock.releaseSharedLock();
		}
	}

	public OUser create() {
		if (getDatabase().getMetadata().getSchema().getClasses().size() > 0)
			throw new OSecurityException("Default users and roles already installed");

		// CREATE ROLE AND USER SCHEMA CLASSES
		final OClass roleClass = getDatabase().getMetadata().getSchema().createClass("ORole");
		roleClass.createProperty("mode", OType.BYTE);
		roleClass.createProperty("rules", OType.EMBEDDEDMAP, OType.BYTE);

		final OClass userClass = getDatabase().getMetadata().getSchema().createClass("OUser");
		userClass.createProperty("roles", OType.LINKSET, roleClass);

		// CREATE ROLES AND USERS
		final ORole adminRole = createRole(ORole.ADMIN, ORole.ALLOW_MODES.ALLOW_ALL_BUT);
		final OUser adminUser = createUser(OUser.ADMIN, OUser.ADMIN, new String[] { adminRole.getName() });

		final ORole readerRole = createRole("reader", ORole.ALLOW_MODES.DENY_ALL_BUT);
		readerRole.addRule(ODatabaseSecurityResources.DATABASE, ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.SCHEMA, ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.CLUSTER + "." + OStorage.CLUSTER_INTERNAL_NAME, ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.CLUSTER + ".orole", ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.CLUSTER + ".ouser", ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.ALL_CLASSES, ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.ALL_CLUSTERS, ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.QUERY, ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.COMMAND, ORole.PERMISSION_READ);
		readerRole.addRule(ODatabaseSecurityResources.RECORD_HOOK, ORole.PERMISSION_READ);
		readerRole.save();
		createUser("reader", "reader", new String[] { readerRole.getName() });

		final ORole writerRole = createRole("writer", ORole.ALLOW_MODES.DENY_ALL_BUT);
		writerRole.addRule(ODatabaseSecurityResources.DATABASE, ORole.PERMISSION_READ);
		writerRole
				.addRule(ODatabaseSecurityResources.SCHEMA, ORole.PERMISSION_READ + ORole.PERMISSION_CREATE + ORole.PERMISSION_UPDATE);
		writerRole.addRule(ODatabaseSecurityResources.CLUSTER + "." + OStorage.CLUSTER_INTERNAL_NAME, ORole.PERMISSION_READ);
		writerRole.addRule(ODatabaseSecurityResources.CLUSTER + ".orole", ORole.PERMISSION_READ);
		writerRole.addRule(ODatabaseSecurityResources.CLUSTER + ".ouser", ORole.PERMISSION_READ);
		writerRole.addRule(ODatabaseSecurityResources.ALL_CLASSES, ORole.PERMISSION_ALL);
		writerRole.addRule(ODatabaseSecurityResources.ALL_CLUSTERS, ORole.PERMISSION_ALL);
		writerRole.addRule(ODatabaseSecurityResources.QUERY, ORole.PERMISSION_READ);
		writerRole.addRule(ODatabaseSecurityResources.COMMAND, ORole.PERMISSION_ALL);
		writerRole.addRule(ODatabaseSecurityResources.RECORD_HOOK, ORole.PERMISSION_ALL);
		writerRole.save();
		createUser("writer", "writer", new String[] { writerRole.getName() });

		return adminUser;
	}

	public void load() {
	}

	private ODatabaseRecord getDatabase() {
		return ODatabaseRecordThreadLocal.INSTANCE.get();
	}

}