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

import org.apache.pekko.actor._
import org.apache.pekko.routing.RoundRobinPool
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration.DurationInt

class ActorGroupMetricsSpec extends TestKitBaseSpec("ActorGroupMetricsSpec") with ActorGroupMetricsBehaviours {

  import ActorGroupMetrics._

  "the actor group metrics" should {
    behave like actorGroupMetrics(ActorFlavour.Classic)

    "respect the configured include and exclude filters for routee actors" in {
      val trackedRouter = createTestPoolRouter("tracked-router")
      val nonTrackedRouter = createTestPoolRouter("non-tracked-router")
      val excludedTrackedRouter = createTestPoolRouter("tracked-explicitly-excluded-router")

      findGroupRecorder("tracked") should not be empty
      findGroupRecorder("exclusive") shouldBe empty
      val map = findGroupRecorder("tracked")
      map.getOrElse(ActorCountMetricName, -1.0) shouldEqual 5.0
      map.getOrElse(MessageCountMetricName, -1.0) shouldEqual 1.0

      system.stop(trackedRouter)
      eventually(timeout(5.seconds)) {
        findGroupRecorder("tracked").getOrElse(ActorCountMetricName, -1.0) shouldEqual 0.0
      }

      val trackedRouter2 = createTestPoolRouter("tracked-router2")
      val trackedRouter3 = createTestPoolRouter("tracked-router3")

      val map2 = findGroupRecorder("tracked")
      map.getOrElse(MessageCountMetricName, -1.0) shouldEqual 1.0
      //map.getOrElse(MailboxMetricName, -1.0) shouldEqual 0.0

      map2.getOrElse(ActorCountMetricName, -1.0) shouldEqual 10.0
      map2.getOrElse(MessageCountMetricName, -1.0) shouldEqual 3.0
    }
  }

  def createTestPoolRouter(routerName: String): ActorRef = {
    val router = system.actorOf(RoundRobinPool(5).props(Props[RouterMetricsTestActor]()), routerName)
    val initialiseListener = TestProbe()

    // Ensure that the router has been created before returning.
    import RouterMetricsTestActor._
    router.tell(Ping, initialiseListener.ref)
    initialiseListener.expectMsg(Pong)

    router
  }

}
