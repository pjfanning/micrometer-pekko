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

object ActorSystemMetrics {

  val ActorSystem = "actorSystem"

  private[pekko] val ActorCountMetricName = "pekko_system_actor_count"
  private[pekko] val DeadLetterCountMetricName = "pekko_system_dead_letter_count"
  private[pekko] val UnhandledMessageCountMetricName = "pekko_system_unhandled_message_count"

  import PekkoMetricRegistry._

  def actorCount(system: String): GaugeWrapper = gauge(ActorCountMetricName, tagSeq(system))
  def deadLetterCount(system: String): Counter = counter(DeadLetterCountMetricName, tagSeq(system))
  def unhandledMessageCount(system: String): Counter = counter(UnhandledMessageCountMetricName, tagSeq(system))
  private def tagSeq(system: String): Iterable[Tag] = Seq(new ImmutableTag(ActorSystem, system))
}
