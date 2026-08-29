/*
 * =========================================================================================
 * Copyright © 2017,2018 Workday, Inc.
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */
package com.github.pjfanning.micrometer.pekko

import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConverters._

import com.github.pjfanning.micrometer.pekko.impl.DoubleFunction
import io.micrometer.core.instrument.{MeterRegistry, Tag}

/**
 * Unlike [[GaugeWrapper]], which accumulates increments, this holds a value that is assigned outright.
 * Cluster state is read as a whole rather than observed as deltas, so a count is set rather than adjusted.
 */
case class SettableGauge(registry: MeterRegistry, name: String, tags: Iterable[Tag]) {
  private val holder = new AtomicLong(0L)
  private val fn = new DoubleFunction[AtomicLong](_.doubleValue)
  registry.gauge(name, tags.asJava, holder, fn)
  def set(value: Long): Unit = holder.set(value)
  def get: Long = holder.get()
}
