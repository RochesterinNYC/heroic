/*
 * Copyright (c) 2019 Spotify AB.
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

package com.spotify.heroic.aggregation.simple

import com.spotify.heroic.aggregation.AbstractBucket
import com.spotify.heroic.aggregation.DoubleBucket
import com.spotify.heroic.metric.Point
import com.spotify.heroic.metric.Spread
import java.util.concurrent.atomic.DoubleAdder

/**
 * Bucket that keeps track of the amount of data points seen, and there summed value.
 *
 *
 * This bucket uses primitives based on striped atomic updates to reduce contention across CPUs.
 *
 * @author udoprog
 */
data class StripedSum2Bucket(override val timestamp: Long) : AbstractBucket(), DoubleBucket {

    /* the sum of all seen values */
    private val sum2 = DoubleAdder()

    /* if the sum is valid (e.g. has at least one value) */
    @Volatile
    private var valid = false

    fun timestamp(): Long {
        return timestamp
    }

    override fun updatePoint(key: Map<String, String>, d: Point) {
        valid = true
        sum2.add(d.value * d.value)
    }

    override fun updateSpread(key: Map<String, String>, d: Spread) {
        valid = true
        sum2.add(d.sum2)
    }

    override fun value(): Double {
        return if (!valid) {
            java.lang.Double.NaN
        } else sum2.sum()

    }
}
