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

import com.github.pjfanning.micrometer.pekko.TypedActor.{Greet, Greeted}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.Await

class TypedActorMetricsSpec extends BaseSpec {

  private val system: ActorSystem[Greet] = ActorSystem(TypedActor(), "greet-service")

  override def afterAll(): Unit = {
    super.afterAll()
    system.terminate()
    ActorMetrics.clear()
  }

  "the typed actor metrics" should {
    "respect the configured include and exclude filters" in {
      sendMessage("world")
      val map = ActorMetrics.getMap()
      map should not be empty
      val entity = Entity("greet-service/user", MetricsConfig.Actor)
      val metrics = map(entity)
      metrics.messages.count() shouldEqual 2.0
    }
  }

  def sendMessage(name: String): Unit = {
    val fut = system.ask[Greeted](Greet(name, _))(Timeout(10.seconds), system.scheduler)
    Await.result(fut, 10.seconds)
  }
}
