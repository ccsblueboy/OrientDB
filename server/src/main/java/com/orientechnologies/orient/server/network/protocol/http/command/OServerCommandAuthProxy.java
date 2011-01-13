/*
 *
 * Copyright 2011 Luca Molino (luca.molino--AT--assetdata.it)
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
package com.orientechnologies.orient.server.network.protocol.http.command;

import java.util.Arrays;

import com.orientechnologies.orient.core.config.OEntryConfiguration;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.server.config.OServerCommandConfiguration;
import com.orientechnologies.orient.server.network.protocol.http.OHttpRequest;

/**
 * @author luca.molino
 * 
 */
public class OServerCommandAuthProxy extends OServerCommandPatternAbstract {

	public static final String	USERNAME_CONF			= "username";
	public static final String	USERPASSWORD_CONF	= "userpassword";

	private String							authentication;

	public OServerCommandAuthProxy(OServerCommandConfiguration iConfig) {
		super(iConfig);
		if (iConfig.parameters.length != 2) {
			throw new OConfigurationException("AuthProxy Command requires database access data.");
		}
		String username = "";
		String userpassword = "";
		for (OEntryConfiguration conf : iConfig.parameters) {
			if (conf.name.equals(USERNAME_CONF))
				username = conf.value;
			if (conf.name.equals(USERPASSWORD_CONF))
				userpassword = conf.value;
		}
		authentication = username + ":" + userpassword;
	}

	@Override
	public boolean execute(OHttpRequest iRequest) throws Exception {
		iRequest.authorization = authentication;
		checkSyntax(iRequest.url, 3, "Syntax error: " + Arrays.toString(getNames()) + "/<nextCommand>/");
		iRequest.url = nextChainUrl(iRequest.url);
		return true;
	}
}
