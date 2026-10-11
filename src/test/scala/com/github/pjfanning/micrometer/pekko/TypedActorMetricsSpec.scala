package com.github.pjfanning.micrometer.pekko

import com.github.pjfanning.micrometer.pekko.TypedActor.{Greet, Greeted}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.{ActorSystem => TypedActorSystem, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.Await

class TypedActorMetricsSpec extends TestKitBaseSpec("TypedActorMetricsSpec") with ActorMetricsBehaviours {

  import TypedMetricsTestActor._

  "the typed actor metrics" should {
    behave like actorMetrics(ActorFlavour.Typed)

    // Typed supervision handles a failure inside the behavior, so for restart and resume the classic
    // ActorCell never sees the exception. These pin that it is still counted, the same as it is for a
    // classic actor under the matching directive.
    "count an error but no restart when a typed actor is resumed" in {
      val actor = spawn(resumeOnFailure(), "tracked-resuming-typed-actor")
      val metrics = actorMetricsRecorderOf(actor).get

      failActor(actor)
      pingActor(actor)

      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual 1.0
        metrics.restarts.count() shouldEqual 0.0
        metrics.messages.count() shouldEqual 3.0
      }
    }

    "count an error but no restart when a typed actor is stopped by its supervisor" in {
      assertStoppedOnFailure(spawn(stopOnFailure(), "tracked-stopping-typed-actor"))
    }

    "count an error but no restart when an unsupervised typed actor fails" in {
      assertStoppedOnFailure(spawn(TypedMetricsTestActor(), "tracked-unsupervised-typed-actor"))
    }

    "count each restart of a typed actor restarted with backoff" in {
      val actor = spawn(backoffOnFailure(), "tracked-backoff-typed-actor")
      val metrics = actorMetricsRecorderOf(actor).get

      failActor(actor)
      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual 1.0
        metrics.restarts.count() shouldEqual 1.0
      }
      // the restart is scheduled, so wait for the actor to answer again before failing it a second time
      pingActor(actor)
      failActor(actor)
      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual 2.0
        metrics.restarts.count() shouldEqual 2.0
      }
    }

    // Once the restart limit is reached the supervisor rethrows, so the final failure reaches the classic
    // ActorCell and stops the actor. It must be counted once, as an error and not as a restart.
    "count the failure that exhausts the restart limit of a typed actor once" in {
      val actor = spawn(
        Behaviors.supervise(TypedMetricsTestActor())
          .onFailure[Exception](SupervisorStrategy.restart.withLimit(1, 1.minute)),
        "tracked-limited-restart-typed-actor")
      val metrics = actorMetricsRecorderOf(actor).get
      val probe = TestProbe()
      probe.watch(actor)

      failActor(actor)
      pingActor(actor)
      failActor(actor)

      probe.expectTerminated(actor, 5.seconds)
      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual 2.0
        metrics.restarts.count() shouldEqual 1.0
      }
    }

    // A typed actor spawned by a typed parent, rather than by a classic one, is not wrapped in a stop
    // supervisor: an unsupervised failure reaches the classic ActorCell as it is, and a supervised stop
    // is rethrown to it as a TypedActorFailedException. Either way it must be counted once.
    "count the failure of an unsupervised typed child of a typed parent once" in {
      assertTypedChildFailureCountedOnce(TypedMetricsTestActor(), "tracked-typed-parent-of-unsupervised")
    }

    "count the failure of a typed child stopped by its supervisor under a typed parent once" in {
      assertTypedChildFailureCountedOnce(stopOnFailure(), "tracked-typed-parent-of-stopping")
    }

    "count each restart of a typed child of a typed parent" in {
      val child = spawnChild(restartOnFailure(), "tracked-typed-parent-of-restarting")
      val metrics = actorMetricsRecorderOf(child).get

      failActor(child)
      pingActor(child)
      failActor(child)
      pingActor(child)

      eventually(timeout(5.seconds)) {
        metrics.errors.count() shouldEqual 2.0
        metrics.restarts.count() shouldEqual 2.0
      }
    }

    // The user guardian of a typed ActorSystem is itself a typed actor, so unlike the classic one it is
    // tracked when the filters match it. It handles the system's own startup message as well as Greet.
    "track the user guardian of a typed actor system" in {
      val typedSystem: TypedActorSystem[Greet] = TypedActorSystem(TypedActor(), "greet-service")
      try {
        val fut = typedSystem.ask[Greeted](Greet("world", _))(Timeout(10.seconds), typedSystem.scheduler)
        Await.result(fut, 10.seconds)

        val metrics = ActorMetrics.getMap()(Entity("greet-service/user", MetricsConfig.Actor))
        eventually(timeout(5.seconds)) {
          metrics.messages.count() shouldEqual 2.0
        }
      } finally {
        typedSystem.terminate()
        Await.result(typedSystem.whenTerminated, 10.seconds)
      }
    }
  }

  private def assertStoppedOnFailure(actor: ActorRef): Unit = {
    val metrics = actorMetricsRecorderOf(actor).get
    val probe = TestProbe()
    probe.watch(actor)

    failActor(actor)

    probe.expectTerminated(actor, 5.seconds)
    eventually(timeout(5.seconds)) {
      metrics.errors.count() shouldEqual 1.0
      metrics.restarts.count() shouldEqual 0.0
    }
  }

  private def assertTypedChildFailureCountedOnce(behavior: Behavior[Command], parentName: String): Unit = {
    val child = spawnChild(behavior, parentName)
    val metrics = actorMetricsRecorderOf(child).get
    val probe = TestProbe()
    probe.watch(child)

    failActor(child)

    probe.expectTerminated(child, 5.seconds)
    // the parent decides on the failure after the child has stopped, so give a second count time to land
    Thread.sleep(200)
    metrics.errors.count() shouldEqual 1.0
    metrics.restarts.count() shouldEqual 0.0
  }

  // A typed parent that spawns the behavior as a child named "child" and hands back its ref.
  private def spawnChild(behavior: Behavior[Command], parentName: String): ActorRef = {
    val parent = Behaviors.setup[org.apache.pekko.actor.typed.ActorRef[org.apache.pekko.actor.typed.ActorRef[Command]]] { ctx =>
      val child = ctx.spawn(behavior, "child")
      Behaviors.receiveMessage { replyTo =>
        replyTo ! child
        Behaviors.same
      }
    }
    val parentRef = system.spawn(parent, parentName)
    val probe = TestProbe()
    parentRef ! probe.ref.toTyped[org.apache.pekko.actor.typed.ActorRef[Command]]
    val child = probe.expectMsgType[org.apache.pekko.actor.typed.ActorRef[Command]].toClassic
    pingActor(child)
    child
  }

  private def spawn(behavior: Behavior[Command], name: String): ActorRef = {
    val actor = system.spawn(behavior, name).toClassic
    pingActor(actor)
    actor
  }

  private def pingActor(actor: ActorRef): Unit = ActorFlavour.Typed.ping(system, actor)

  private def failActor(actor: ActorRef): Unit = ActorFlavour.Typed.fail(actor)
}
