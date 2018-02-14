/**
 * Copyright 2016 Netflix, Inc.
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
package com.netflix.dyno.connectionpool.impl;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.HostSupplier;
import com.netflix.dyno.connectionpool.TokenMapSupplier;
import com.netflix.dyno.connectionpool.exception.DynoException;
import com.netflix.dyno.connectionpool.exception.NoAvailableHostsException;
import com.netflix.dyno.connectionpool.impl.lb.HostToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostsUpdater {

	private static final org.slf4j.Logger Logger = LoggerFactory.getLogger(ConnectionPoolImpl.class);

	private final HostSupplier hostSupplier;
	private final TokenMapSupplier tokenMapSupplier;

	private final AtomicBoolean stop = new AtomicBoolean(false);
	private final AtomicReference<HostStatusTracker> hostTracker = new AtomicReference<HostStatusTracker>(null);
	
	public HostsUpdater(HostSupplier hSupplier, TokenMapSupplier tokenMapSupplier) {
		this.hostSupplier = hSupplier;
		this.tokenMapSupplier = tokenMapSupplier;
		this.hostTracker.set(new HostStatusTracker());
	}
	
		
	public HostStatusTracker refreshHosts() {

		if (stop.get() || Thread.currentThread().isInterrupted()) {
			return null;
		}
		
		List<Host> allHosts = hostSupplier.getHosts();
		if (allHosts == null || allHosts.isEmpty()) {
			throw new NoAvailableHostsException("No available hosts when starting HostsUpdater");
		}

		List<Host> hostsUp = new ArrayList<>();
		List<Host> hostsDown = new ArrayList<>();

		for (Host host : allHosts) {
			if (host.isUp()) {
				hostsUp.add(host);
			} else {
				hostsDown.add(host);
			}
		}

		// if nothing has changed, just return the earlier hosttracker.
		if (!hostTracker.get().checkIfChanged(new HashSet<>(hostsUp), new HashSet<>(hostsDown))) {
			return hostTracker.get();
		}

		/**
		 * HostTracker should return the hosts that we get from TMS.
		 * Hence get the hosts from HostSupplier and map them to TMS
		 * and return them.
		 */
		Collections.sort(allHosts);
		Set<Host> hostSet = new HashSet<>(allHosts);
		// Create a list of host/Tokens
		List<HostToken> hostTokens;
		if (tokenMapSupplier != null) {
			Logger.info("Getting Hosts from TMS");
			hostTokens = tokenMapSupplier.getTokens(hostSet);

			if (hostTokens.isEmpty()) {
				throw new DynoException("No hosts in the TokenMapSupplier");
			}
		} else {
			throw new DynoException("TokenMapSupplier not provided");
		}

		Map<Host, Host> allHostSetFromTMS = new HashMap<>();
		for (HostToken ht : hostTokens) {
			allHostSetFromTMS.put(ht.getHost(), ht.getHost());
		}

		hostsUp.clear();
		hostsDown.clear();

		for (Host host : allHosts) {
			if (host.isUp()) {
				hostsUp.add(allHostSetFromTMS.get(host));
			} else {
				hostsDown.add(allHostSetFromTMS.get(host));
			}
		}

		HostStatusTracker newTracker = hostTracker.get().computeNewHostStatus(hostsUp, hostsDown);
		hostTracker.set(newTracker);

		return hostTracker.get();
	}
	
	public void stop() {
		stop.set(true);
	}
}
