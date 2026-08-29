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

import java.util.concurrent.{CountDownLatch, RejectedExecutionException, SynchronousQueue, ThreadPoolExecutor, TimeUnit}

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor._
import org.apache.pekko.testkit.TestProbe
import org.scalatest.concurrent.Eventually

class DispatcherLatencyMetricsSpec extends TestKitBaseSpec("DispatcherLatencyMetricsSpec") with Eventually {

  import ActorMetricsTestActor._

  "the dispatcher metrics" should {
    // Only that something was recorded is deterministic; the durations are not.
    "record time in mailbox against the actor's dispatcher" in {
      val actor = system.actorOf(Props[ActorMetricsTestActor]().withDispatcher("tracked-fjp"), "tracked-fjp-actor")
      val probe = TestProbe()
      actor.tell(Ping, probe.ref)
      probe.expectMsg(Pong)

      eventually(timeout(5.seconds)) {
        DispatcherMetrics.timeInMailbox("tracked-fjp").timer.count() should be >= 1L
      }
      DispatcherMetrics.timeInMailbox("tracked-fjp").timer.totalTime(TimeUnit.NANOSECONDS) should be >= 0.0
    }

    "count the actors attached to a dispatcher" in {
      val actor = system.actorOf(Props[ActorMetricsTestActor]().withDispatcher("tracked-tpe"), "tracked-tpe-actor")
      val probe = TestProbe()
      actor.tell(Ping, probe.ref)
      probe.expectMsg(Pong)

      eventually(timeout(5.seconds)) {
        inhabitantsOf("tracked-tpe") should be >= 1.0
      }
    }

    "count rejected tasks" in {
      val name = "DispatcherLatencyMetricsSpec-rejecting-pool"
      // one thread, no queue capacity, so a second task while the first is blocked has nowhere to go
      val executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      try {
        ThreadPoolMetrics.add(name, executor)
        DispatcherMetrics.rejectedTaskCount(name).count() should be (0.0)

        executor.execute(new Runnable {
          def run(): Unit = { started.countDown(); release.await() }
        })
        started.await(5, TimeUnit.SECONDS) should be (true)

        a[RejectedExecutionException] should be thrownBy {
          executor.execute(new Runnable { def run(): Unit = () })
        }
        DispatcherMetrics.rejectedTaskCount(name).count() should be (1.0)
      } finally {
        release.countDown()
        executor.shutdownNow()
      }
    }
  }

  private def inhabitantsOf(dispatcher: String): Double = {
    val gauge = PekkoMetricRegistry.getRegistry
      .find(DispatcherMetrics.InhabitantsMetricName)
      .tag(DispatcherMetrics.DispatcherName, dispatcher)
      .gauge()
    if (gauge == null) -1.0 else gauge.value()
  }
}
