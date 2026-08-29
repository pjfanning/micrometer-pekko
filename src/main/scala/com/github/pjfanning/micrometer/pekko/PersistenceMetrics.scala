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

import io.micrometer.core.instrument.{Counter, ImmutableTag, Tag}

/**
 * Tagged by actor system rather than by persistence id. A persistence id is usually per entity, so
 * tagging by it would put unbounded cardinality into the registry.
 */
object PersistenceMetrics {

  val ActorSystem = "actorSystem"

  private[pekko] val RecoveryTimeMetricName = "pekko_persistence_recovery_time"
  private[pekko] val RecoveryFailureCountMetricName = "pekko_persistence_recovery_failure_count"
  private[pekko] val PersistCountMetricName = "pekko_persistence_persist_count"
  private[pekko] val PersistFailureCountMetricName = "pekko_persistence_persist_failure_count"
  private[pekko] val PersistRejectedCountMetricName = "pekko_persistence_persist_rejected_count"

  import PekkoMetricRegistry._

  def recoveryTime(system: String): TimerWrapper = timer(RecoveryTimeMetricName, tagSeq(system))
  def recoveryFailures(system: String): Counter = counter(RecoveryFailureCountMetricName, tagSeq(system))
  def persists(system: String): Counter = counter(PersistCountMetricName, tagSeq(system))
  def persistFailures(system: String): Counter = counter(PersistFailureCountMetricName, tagSeq(system))
  def persistRejections(system: String): Counter = counter(PersistRejectedCountMetricName, tagSeq(system))

  private def tagSeq(system: String): Iterable[Tag] = Seq(new ImmutableTag(ActorSystem, system))
}
