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

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.persistence.PersistentActor
import org.apache.pekko.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

class PersistenceMetricsSpec extends BaseSpec with Eventually {

  import PersistenceMetricsSpec._

  private val SystemName = "PersistenceMetricsSpec"

  private val config = ConfigFactory.parseString(
    """
      |pekko.persistence.journal.plugin = "pekko.persistence.journal.inmem"
      |pekko.persistence.journal.inmem.test-serialization = off
      |pekko.persistence.snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
      |pekko.persistence.snapshot-store.local.dir = "target/persistence-metrics-spec-snapshots"
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

  "the persistence metrics" should {
    // Every persistent actor recovers on start, even with an empty journal, so starting one is enough to
    // exercise the recovery timer. Only that something was recorded is deterministic.
    "time the recovery of a persistent actor" in {
      val before = PersistenceMetrics.recoveryTime(SystemName).timer.count()
      startActor("recovery-id")

      eventually(timeout(10.seconds)) {
        PersistenceMetrics.recoveryTime(SystemName).timer.count() should be > before
      }
      PersistenceMetrics.recoveryTime(SystemName).timer.totalTime(TimeUnit.NANOSECONDS) should be >= 0.0
      PersistenceMetrics.recoveryFailures(SystemName).count() should be (0.0)
    }

    "count the events a persistent actor persists" in {
      val probe = TestProbe()(system)
      val actor = startActor("persist-id")
      val before = PersistenceMetrics.persists(SystemName).count()

      actor.tell(Persist("one"), probe.ref)
      probe.expectMsg(10.seconds, Persisted)
      actor.tell(PersistAll(List("two", "three")), probe.ref)
      probe.expectMsg(10.seconds, Persisted)

      eventually(timeout(10.seconds)) {
        PersistenceMetrics.persists(SystemName).count() should be (before + 3.0)
      }
      PersistenceMetrics.persistFailures(SystemName).count() should be (0.0)
      PersistenceMetrics.persistRejections(SystemName).count() should be (0.0)
    }
  }

  private def startActor(persistenceId: String) = {
    val probe = TestProbe()(system)
    val actor = system.actorOf(Props(new TestPersistentActor(persistenceId)))
    actor.tell(Ping, probe.ref)
    probe.expectMsg(10.seconds, Pong)
    actor
  }
}

object PersistenceMetricsSpec {
  case object Ping
  case object Pong
  case object Persisted
  case class Persist(event: String)
  case class PersistAll(events: List[String])
}

class TestPersistentActor(override val persistenceId: String) extends PersistentActor {
  import PersistenceMetricsSpec._

  override def receiveRecover: Receive = { case _ => }

  override def receiveCommand: Receive = {
    case Ping => sender() ! Pong
    case Persist(event) =>
      val replyTo = sender()
      persist(event)(_ => replyTo ! Persisted)
    case PersistAll(events) =>
      val replyTo = sender()
      var remaining = events.size
      persistAll(events) { _ =>
        remaining -= 1
        if (remaining == 0) replyTo ! Persisted
      }
  }
}
