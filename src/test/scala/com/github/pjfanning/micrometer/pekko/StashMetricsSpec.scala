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

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor._
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.monitor.instrumentation.CellInfo
import io.micrometer.core.instrument.ImmutableTag
import org.scalatest.concurrent.Eventually

class StashMetricsSpec extends TestKitBaseSpec("StashMetricsSpec") with Eventually {

  import StashMetricsSpec._

  "the stash metrics" should {
    "follow the size of a tracked actor's stash" in {
      val actor = createStashingActor("tracked-stashing-actor")
      val metrics = actorMetricsOf(actor)

      (1 to 3).foreach(_ => actor ! Stashed)
      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (3.0)
      }

      val probe = TestProbe()
      actor.tell(UnstashAll, probe.ref)
      probe.expectMsg(Done)

      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (0.0)
      }
    }

    "follow the stash size of a tracked group" in {
      val actor = createStashingActor("tracked-group-stashing-actor")

      (1 to 2).foreach(_ => actor ! Stashed)
      eventually(timeout(5.seconds)) {
        groupStashSize("tracked") should be >= 2.0
      }

      val probe = TestProbe()
      actor.tell(UnstashAll, probe.ref)
      probe.expectMsg(Done)

      eventually(timeout(5.seconds)) {
        groupStashSize("tracked") should be (0.0)
      }
    }
  }

  private def actorMetricsOf(ref: ActorRef): ActorMetrics =
    ActorMetrics.metricsFor(Entity(CellInfo.cellName(system, ref), MetricsConfig.Actor)).get

  private def stashSizeOf(metrics: ActorMetrics): Double = {
    val gauge = PekkoMetricRegistry.getRegistry.find(s"pekko_actor_stash_size_${metrics.actorName}").gauge()
    if (gauge == null) -1.0 else gauge.value()
  }

  private def groupStashSize(group: String): Double =
    PekkoMetricRegistry.metricsForTags(Seq(new ImmutableTag(ActorGroupMetrics.GroupName, group)))
      .getOrElse(ActorGroupMetrics.StashSizeMetricName, -1.0)

  private def createStashingActor(name: String): ActorRef = {
    val actor = system.actorOf(Props[StashingActor](), name)
    val probe = TestProbe()
    actor.tell(Ready, probe.ref)
    probe.expectMsg(Done)
    actor
  }
}

object StashMetricsSpec {
  case object Ready
  case object Stashed
  case object UnstashAll
  case object Done
}

class StashingActor extends Actor with Stash {
  import StashMetricsSpec._

  override def receive: Receive = {
    case Ready   => sender() ! Done
    case Stashed => stash()
    case UnstashAll =>
      // become before unstashing: unstashAll puts the messages back on the mailbox, and the stashing
      // behaviour above would simply stash them all over again
      context.become(drained)
      unstashAll()
      sender() ! Done
  }

  private def drained: Receive = {
    case Ready   => sender() ! Done
    case Stashed =>
  }
}
