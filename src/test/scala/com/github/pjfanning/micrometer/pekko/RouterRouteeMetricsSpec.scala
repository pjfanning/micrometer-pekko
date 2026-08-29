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
import org.apache.pekko.routing._
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.monitor.instrumentation.CellInfo
import org.scalatest.concurrent.Eventually

class RouterRouteeMetricsSpec extends TestKitBaseSpec("RouterRouteeMetricsSpec") with Eventually {

  import RouterMetricsTestActor._

  private val PoolSize = 5

  "the router routee count" should {
    "count the routees a pool router starts with" in {
      val router = createTestPoolRouter("tracked-routee-count-router")
      eventually(timeout(5.seconds)) {
        routeeCountOf(router) should be (PoolSize.toDouble)
      }
    }

    // AddRoutee and RemoveRoutee are the router management messages, and both go through the
    // addRoutees/removeRoutees advice that drives the gauge.
    "follow routees added and removed through the router management messages" in {
      val router = createTestPoolRouter("tracked-routee-managed-router")
      val routee = ActorRefRoutee(TestProbe().ref)

      router ! AddRoutee(routee)
      eventually(timeout(5.seconds)) {
        routeeCountOf(router) should be ((PoolSize + 1).toDouble)
      }

      router ! RemoveRoutee(routee)
      eventually(timeout(5.seconds)) {
        routeeCountOf(router) should be (PoolSize.toDouble)
      }
    }
  }

  // the per-router metrics carry no tags, so they are looked up by name among the untagged meters
  private def routeeCountOf(router: ActorRef): Double = {
    val entity = Entity(CellInfo.cellName(system, router), MetricsConfig.Router)
    val metrics = RouterMetrics.metricsFor(entity).get
    PekkoMetricRegistry.metricsForTags(Seq.empty)
      .getOrElse(s"pekko_router_routee_count_${metrics.actorName}", -1.0)
  }

  private def createTestPoolRouter(routerName: String): ActorRef = {
    val router = system.actorOf(RoundRobinPool(PoolSize).props(Props[RouterMetricsTestActor]()), routerName)
    val initialiseListener = TestProbe()

    // Ensure that the router has been created before returning.
    router.tell(Ping, initialiseListener.ref)
    initialiseListener.expectMsg(Pong)

    router
  }
}
