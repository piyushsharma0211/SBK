/**
 * Copyright (c) KMG. All Rights Reserved..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.sbk.api.impl;

import io.sbk.perl.MetricsConfig;
import io.sbk.perl.Time;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionsRWMetricsPrometheusServer extends RWMetricsPrometheusServer {
    final private AtomicInteger connections;
    final private AtomicInteger maxConnections;

    public ConnectionsRWMetricsPrometheusServer(String header, String action, double[] percentiles, Time time,
                                      MetricsConfig config)  throws IOException {
        super(header, action, percentiles, time, config);
        final String name = metricPrefix + "_Connections";
        final String maxName = metricPrefix + "_Max_Connections";
        this.connections = this.registry.gauge(name, new AtomicInteger());
        this.maxConnections = this.registry.gauge(maxName, new AtomicInteger());
    }

    public void increment(int val) {
        connections.set(connections.get() + val);
        maxConnections.set(maxConnections.get() + val);
    }

    public void decrement(int val) {
        connections.set(connections.get()-val);
    }
}
