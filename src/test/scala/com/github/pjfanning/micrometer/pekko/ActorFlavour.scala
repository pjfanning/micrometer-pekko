package com.github.pjfanning.micrometer.pekko

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration.DurationInt

/**
 * The same scenario, run against a classic and a typed actor, has to produce the same metrics. Each spec
 * of shared behaviours is written against this rather than against either API, so that every check made
 * of a classic actor is made of a typed one too.
 *
 * Both flavours hand back a classic ActorRef, which is what the instrumentation keys its metrics by.
 */
sealed trait ActorFlavour {
  def name: String

  /** Creates the actor and waits for it to answer one Ping, so that it has handled exactly one message. */
  def create(system: ActorSystem, actorName: String): ActorRef

  /** Sends a Ping and waits for the reply. */
  def ping(system: ActorSystem, ref: ActorRef): Unit

  /** Makes the actor throw while handling a message; the actor is supervised to restart, as classic is by default. */
  def fail(ref: ActorRef): Unit

  /** Sends a message the actor does not handle. */
  def sendUnhandled(ref: ActorRef): Unit

  override def toString: String = name
}

object ActorFlavour {

  case object Classic extends ActorFlavour {
    import ActorMetricsTestActor._

    val name = "classic"

    def create(system: ActorSystem, actorName: String): ActorRef = {
      val actor = system.actorOf(org.apache.pekko.actor.Props[ActorMetricsTestActor](), actorName)
      ping(system, actor)
      actor
    }

    def ping(system: ActorSystem, ref: ActorRef): Unit = {
      val probe = TestProbe()(system)
      ref.tell(Ping, probe.ref)
      probe.expectMsg(10.seconds, Pong)
    }

    def fail(ref: ActorRef): Unit = ref ! Fail

    def sendUnhandled(ref: ActorRef): Unit = ref ! "unhandled"
  }

  case object Typed extends ActorFlavour {
    import TypedMetricsTestActor._

    val name = "typed"

    def create(system: ActorSystem, actorName: String): ActorRef = {
      val actor = system.spawn(TypedMetricsTestActor.restartOnFailure(), actorName).toClassic
      ping(system, actor)
      actor
    }

    def ping(system: ActorSystem, ref: ActorRef): Unit = {
      val probe = TestProbe()(system)
      ref.toTyped[Command] ! Ping(probe.ref.toTyped[Pong.type])
      probe.expectMsg(10.seconds, Pong)
    }

    def fail(ref: ActorRef): Unit = ref.toTyped[Command] ! Fail

    def sendUnhandled(ref: ActorRef): Unit = ref.toTyped[Command] ! Unhandled
  }

  val all: Seq[ActorFlavour] = Seq(Classic, Typed)
}

/**
 * The typed counterpart of ActorMetricsTestActor.
 */
object TypedMetricsTestActor {
  sealed trait Command
  final case class Ping(replyTo: org.apache.pekko.actor.typed.ActorRef[Pong.type]) extends Command
  case object Pong
  case object Fail extends Command
  case object Discard extends Command
  case object Unhandled extends Command

  def apply(): Behavior[Command] = Behaviors.receiveMessage {
    case Ping(replyTo) =>
      replyTo ! Pong
      Behaviors.same
    case Fail      => throw new ArithmeticException("Division by zero.")
    case Discard   => Behaviors.same
    case Unhandled => Behaviors.unhandled
  }

  // Typed actors stop on failure unless told otherwise, where a classic actor under the default supervisor
  // restarts. Supervising with restart gives both flavours the same lifecycle.
  def restartOnFailure(): Behavior[Command] =
    Behaviors.supervise(apply()).onFailure[Exception](SupervisorStrategy.restart)

  def resumeOnFailure(): Behavior[Command] =
    Behaviors.supervise(apply()).onFailure[Exception](SupervisorStrategy.resume)

  def stopOnFailure(): Behavior[Command] =
    Behaviors.supervise(apply()).onFailure[Exception](SupervisorStrategy.stop)

  def backoffOnFailure(): Behavior[Command] =
    Behaviors.supervise(apply()).onFailure[Exception](
      SupervisorStrategy.restartWithBackoff(10.millis, 100.millis, 0.0))
}
