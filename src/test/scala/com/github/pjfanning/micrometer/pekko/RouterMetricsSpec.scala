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

import scala.concurrent.{Await, Future}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor._
import org.apache.pekko.routing._
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.monitor.instrumentation.CellInfo
import org.scalatest.concurrent.Eventually

class RouterMetricsSpec extends TestKitBaseSpec("RouterMetricsSpec") with Eventually {

  import RouterMetricsTestActor._

  "the router metrics" should {
    "respect the configured include and exclude filters" in {
      val trackedRouter = createTestPoolRouter("tracked-pool-router")
      val nonTrackedRouter = createTestPoolRouter("non-tracked-pool-router")
      val excludedTrackedRouter = createTestPoolRouter("tracked-explicitly-excluded-pool-router")

      routerMetricsRecorderOf(trackedRouter) should not be empty
      routerMetricsRecorderOf(nonTrackedRouter) shouldBe empty
      routerMetricsRecorderOf(excludedTrackedRouter) shouldBe empty

      val metrics = routerMetricsRecorderOf(trackedRouter).get
      metrics.actorName shouldEqual "routermetricsspec_user_tracked_pool_router"
      metrics.messages.count() shouldEqual 1.0
    }

    "count the errors thrown by the routees of a tracked router" in {
      val trackedRouter = createTestPoolRouter("tracked-failing-pool-router")
      val metrics = routerMetricsRecorderOf(trackedRouter).get
      val originalErrors = metrics.errors.count()

      trackedRouter ! Fail

      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual (originalErrors + 1.0)
      }
    }

    // routingTime is recorded around RoutedActorCell.sendMessage, the other two around the routee's
    // invoke. Asserted broadly - the counts are deterministic, the durations are not.
    "record routing time, processing time and time in mailbox for a tracked router" in {
      val trackedRouter = createTestPoolRouter("tracked-timing-pool-router")
      val metrics = routerMetricsRecorderOf(trackedRouter).get

      eventually(timeout(5.seconds)) {
        metrics.routingTime.timer.count() should be >= 1L
        metrics.processingTime.timer.count() should be >= 1L
        metrics.timeInMailbox.timer.count() should be >= 1L
      }
      metrics.routingTime.timer.totalTime(TimeUnit.NANOSECONDS) should be >= 0.0
    }

    "handle concurrent metric getOrElseUpdate calls" in {
      implicit val ec = system.dispatcher
      val e = Entity("fake-actor-name", MetricsConfig.Actor)
      val futures = (1 to 100).map{ _ => Future(ActorMetrics.metricsFor(e)) }
      val future = Future.sequence(futures)
      val metrics = Await.result(future, 10.seconds)
      metrics.fold(metrics.head) { (compare, metric) =>
        metric shouldEqual compare
        compare
      }
    }
  }

  def routerMetricsRecorderOf(ref: ActorRef): Option[RouterMetrics] = {
    val name = CellInfo.cellName(system, ref)
    val entity = Entity(name, MetricsConfig.Router)
    if (RouterMetrics.hasMetricsFor(entity)) {
      RouterMetrics.metricsFor(entity)
    } else {
      None
    }
  }

  def createTestPoolRouter(routerName: String): ActorRef = {
    val router = system.actorOf(RoundRobinPool(5).props(Props[RouterMetricsTestActor]()), routerName)
    val initialiseListener = TestProbe()

    // Ensure that the router has been created before returning.
    router.tell(Ping, initialiseListener.ref)
    initialiseListener.expectMsg(Pong)

    router
  }
}
