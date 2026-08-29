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
package org.apache.pekko.monitor.instrumentation

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor._
import org.apache.pekko.testkit.TestProbe
import com.github.pjfanning.micrometer.pekko._
import org.scalatest.concurrent.Eventually

/**
 * `system.actorOf` hands back a RepointableActorRef holding an UnstartedCell, and the real ActorCell is
 * only swapped in once the guardian processes the Supervise system message. A message that arrives in
 * that window is queued on the UnstartedCell and then handed to the dispatcher a second time when the
 * queue is drained, which used to capture it - and count it - twice.
 */
class UnstartedCellMetricsSpec extends TestKitBaseSpec("UnstartedCellMetricsSpec") with Eventually {

  import ActorMetricsTestActor._

  private val ActorCount = 200

  "an actor messaged before its cell is repointed" should {
    "count each message exactly once" in {
      val probe = TestProbe()
      var unstartedAtSend = 0

      val actors = (0 until ActorCount).map { i =>
        val actor = system.actorOf(Props[ActorMetricsTestActor](), s"tracked-unstarted-$i")
        if (!actor.asInstanceOf[RepointableActorRef].isStarted) unstartedAtSend += 1
        actor.tell(Ping, probe.ref)
        actor
      }
      (0 until ActorCount).foreach(_ => probe.expectMsg(Pong))

      // Nothing here forces the window, so on a fast enough machine there may be nothing to assert. That
      // is reported rather than passed off as a success.
      if (unstartedAtSend == 0) {
        cancel("no actor was still unstarted when it was sent to")
      }

      val metrics = actors.map(metricsFor)
      metrics.map(_.messages.count()) should contain only 1.0

      // the mailbox gauge is incremented on capture and decremented after the message is handled, so a
      // second capture would leave it permanently above zero
      eventually(timeout(5.seconds)) {
        metrics.map(mailboxSizeOf) should contain only 0.0
      }
    }
  }

  private def metricsFor(ref: ActorRef): ActorMetrics =
    ActorMetrics.metricsFor(Entity(CellInfo.cellName(system, ref), MetricsConfig.Actor)).get

  private def mailboxSizeOf(metrics: ActorMetrics): Double = {
    val gauge = PekkoMetricRegistry.getRegistry.find(s"pekko_actor_mailbox_size_${metrics.actorName}").gauge()
    if (gauge == null) -1.0 else gauge.value()
  }
}
