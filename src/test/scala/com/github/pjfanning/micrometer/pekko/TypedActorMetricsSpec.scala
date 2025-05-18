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
