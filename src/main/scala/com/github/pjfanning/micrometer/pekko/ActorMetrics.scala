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

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import org.slf4j.LoggerFactory

object ActorMetrics {
  private val logger = LoggerFactory.getLogger(ActorMetrics.getClass)
  private val map = TrieMap[Entity, ActorMetrics]()
  def metricsFor(e: Entity): Option[ActorMetrics] = {
    try {
      Some(map.getOrElseUpdate(e, new ActorMetrics(e)))
    } catch {
      case NonFatal(t) => {
        logger.warn("Issue with getOrElseUpdate (failing over to simple get)", t)
        map.get(e)
      }
    }
  }
  def hasMetricsFor(e: Entity): Boolean = map.contains(e)

  private[pekko] def getMap(): Map[Entity, ActorMetrics] = map.toMap
  private[pekko] def clear(): Unit = map.clear()
}

class ActorMetrics(entity: Entity) {
  import PekkoMetricRegistry._
  val actorName = metricFriendlyActorName(entity.name)
  val mailboxSize = gauge(s"pekko_actor_mailbox_size_$actorName", Seq.empty)
  val processingTime = timer(s"pekko_actor_processing_time_$actorName", Seq.empty)
  val timeInMailbox = timer(s"pekko_actor_time_in_mailbox_$actorName", Seq.empty)
  val messages = counter(s"pekko_actor_message_count_$actorName", Seq.empty)
  val errors = counter(s"pekko_actor_error_count_$actorName", Seq.empty)
}
