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

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

class ClusterMetricsSpec extends BaseSpec with Eventually {

  private val SystemName = "ClusterMetricsSpec"

  // A one node cluster that joins itself. Enough to exercise the subscription, the snapshot handling and
  // every gauge, without the flakiness of standing up a second node in CI.
  private val config = ConfigFactory.parseString(
    """
      |pekko.actor.provider = "cluster"
      |pekko.remote.artery.canonical.hostname = "127.0.0.1"
      |pekko.remote.artery.canonical.port = 0
      |pekko.cluster.jmx.enabled = off
      |pekko.coordinated-shutdown.terminate-actor-system = on
      |""".stripMargin).withFallback(ConfigFactory.load())

  private var system: ActorSystem = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    system = ActorSystem(SystemName, config)
  }

  override def afterAll(): Unit = {
    if (system != null) Await.result(system.terminate(), 30.seconds)
    super.afterAll()
  }

  "the cluster metrics" should {
    "report the members of a cluster this node has joined" in {
      val cluster = Cluster(system)
      ClusterMetrics.monitor(system)
      cluster.join(cluster.selfAddress)

      eventually(timeout(30.seconds), interval(200.millis)) {
        ClusterMetrics.memberCount(SystemName, "up").get should be (1L)
      }

      ClusterMetrics.isLeader(SystemName).get should be (1L)
      ClusterMetrics.unreachableMemberCount(SystemName).get should be (0L)

      // the statuses nobody is in are reported at zero rather than left unregistered
      ClusterMetrics.memberCount(SystemName, "down").get should be (0L)
      ClusterMetrics.memberCount(SystemName, "leaving").get should be (0L)

      // and the gauges really are in the registry, not just in the holders
      gaugeValue(ClusterMetrics.MemberCountMetricName, "up") should be (1.0)
    }
  }

  private def gaugeValue(name: String, status: String): Double = {
    val gauge = PekkoMetricRegistry.getRegistry.find(name)
      .tag(ClusterMetrics.ActorSystem, SystemName)
      .tag(ClusterMetrics.Status, status)
      .gauge()
    if (gauge == null) -1.0 else gauge.value()
  }
}
