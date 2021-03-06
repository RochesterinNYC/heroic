/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.metric.datastax;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.datastax.driver.core.policies.RetryPolicy;
import com.datastax.driver.core.policies.RoundRobinPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.spotify.heroic.common.Duration;
import com.spotify.heroic.metric.datastax.schema.Schema;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.ManagedSetup;
import java.net.InetSocketAddress;
import java.util.Collection;

public class ManagedSetupConnection implements ManagedSetup<Connection> {
    private final AsyncFramework async;
    private final Collection<InetSocketAddress> seeds;
    private final Schema schema;
    private final boolean configure;
    private final int fetchSize;
    private final Duration readTimeout;
    private final ConsistencyLevel consistencyLevel;
    private final RetryPolicy retryPolicy;
    private final DatastaxAuthentication authentication;
    private final DatastaxPoolingOptions poolingOptions;

    @java.beans.ConstructorProperties({ "async", "seeds", "schema", "configure", "fetchSize",
                                        "readTimeout", "consistencyLevel", "retryPolicy",
                                        "authentication", "poolingOptions" })
    public ManagedSetupConnection(final AsyncFramework async,
                                  final Collection<InetSocketAddress> seeds,
                                  final Schema schema,
                                  final boolean configure,
                                  final int fetchSize,
                                  final Duration readTimeout,
                                  final ConsistencyLevel consistencyLevel,
                                  final RetryPolicy retryPolicy,
                                  final DatastaxAuthentication authentication,
                                  final DatastaxPoolingOptions poolingOptions) {
        this.async = async;
        this.seeds = seeds;
        this.schema = schema;
        this.configure = configure;
        this.fetchSize = fetchSize;
        this.readTimeout = readTimeout;
        this.consistencyLevel = consistencyLevel;
        this.retryPolicy = retryPolicy;
        this.authentication = authentication;
        this.poolingOptions = poolingOptions;
    }

    public AsyncFuture<Connection> construct() {
        AsyncFuture<Session> session = async.call(() -> {


            final QueryOptions queryOptions = new QueryOptions()
                .setFetchSize(fetchSize)
                .setConsistencyLevel(consistencyLevel);

            final SocketOptions socketOptions = new SocketOptions()
                .setReadTimeoutMillis((int) readTimeout.toMilliseconds());

            final Cluster.Builder cluster = Cluster.builder()
                .addContactPointsWithPorts(seeds)
                .withRetryPolicy(retryPolicy)
                .withQueryOptions(queryOptions)
                .withSocketOptions(socketOptions)
                .withLoadBalancingPolicy(new TokenAwarePolicy(new RoundRobinPolicy()))
                .withoutJMXReporting();

            authentication.accept(cluster);
            poolingOptions.apply(cluster);

            return cluster.build().connect();
        });

        if (configure) {
            session = session.lazyTransform(s -> schema.configure(s).directTransform(i -> s));
        }

        return session.lazyTransform(
            s -> schema.instance(s).directTransform(schema -> new Connection(s, schema)));
    }

    @Override
    public AsyncFuture<Void> destruct(final Connection c) {
        return Async.bind(async, c.session.closeAsync()).directTransform(ign -> null);
    }

    public String toString() {
        return "ManagedSetupConnection(seeds=" + this.seeds + ")";
    }
}
