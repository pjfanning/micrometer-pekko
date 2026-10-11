package com.github.pjfanning.micrometer.pekko

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.monitor.instrumentation.CellInfo
import org.apache.pekko.testkit.TestProbe
import io.micrometer.core.instrument.ImmutableTag
import org.scalatest.concurrent.Eventually

/**
 * The typed counterpart of StashMetricsSpec. A typed actor stashes into a StashBuffer rather than the
 * classic StashSupport, and the size has to be reported the same way.
 */
class TypedStashMetricsSpec extends TestKitBaseSpec("TypedStashMetricsSpec") with Eventually {

  import TypedStashMetricsSpec._

  "the typed stash metrics" should {
    "follow the size of a tracked actor's stash" in {
      val actor = createStashingActor("tracked-stashing-actor")
      val metrics = actorMetricsOf(actor)

      (1 to 3).foreach(_ => actor.toTyped[Command] ! Stashed)
      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (3.0)
      }

      sendAndAwait(actor, UnstashAll(_))

      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (0.0)
      }
    }

    "follow the stash size of a tracked group" in {
      val actor = createStashingActor("tracked-group-stashing-actor")

      (1 to 2).foreach(_ => actor.toTyped[Command] ! Stashed)
      eventually(timeout(5.seconds)) {
        groupStashSize("tracked") should be >= 2.0
      }

      sendAndAwait(actor, UnstashAll(_))

      eventually(timeout(5.seconds)) {
        groupStashSize("tracked") should be (0.0)
      }
    }

    // StashBuffer, unlike the classic stash, can hand back only part of what it holds
    "follow a partial unstash" in {
      val actor = createStashingActor("tracked-partial-stashing-actor")
      val metrics = actorMetricsOf(actor)

      (1 to 3).foreach(_ => actor.toTyped[Command] ! Stashed)
      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (3.0)
      }

      sendAndAwait(actor, UnstashOne(_))

      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (2.0)
      }
    }

    "drop to zero when the stash is cleared" in {
      val actor = createStashingActor("tracked-clearing-stashing-actor")
      val metrics = actorMetricsOf(actor)

      (1 to 3).foreach(_ => actor.toTyped[Command] ! Stashed)
      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (3.0)
      }

      sendAndAwait(actor, Clear(_))

      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (0.0)
      }
    }
    // a StashBuffer is dropped with the actor, without its messages being unstashed
    "drop to zero when the actor stops" in {
      val actor = createStashingActor("tracked-stopped-stashing-actor")
      val metrics = actorMetricsOf(actor)

      (1 to 3).foreach(_ => actor.toTyped[Command] ! Stashed)
      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (3.0)
      }

      system.stop(actor)

      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (0.0)
      }
    }

    // unlike a classic restart, which puts the stash back on the mailbox, a typed restart starts the
    // behavior again with a new StashBuffer and the old one's messages are dropped
    "drop to zero when the actor restarts" in {
      val actor = createStashingActor("tracked-restarted-stashing-actor")
      val metrics = actorMetricsOf(actor)

      (1 to 3).foreach(_ => actor.toTyped[Command] ! Stashed)
      eventually(timeout(5.seconds)) {
        stashSizeOf(metrics) should be (3.0)
      }

      actor.toTyped[Command] ! Fail

      eventually(timeout(5.seconds)) {
        metrics.restarts.count() should be (1.0)
        stashSizeOf(metrics) should be (0.0)
      }
    }
  }

  private def actorMetricsOf(ref: ActorRef): ActorMetrics =
    ActorMetrics.metricsFor(Entity(CellInfo.cellName(system, ref), MetricsConfig.Actor)).get

  private def stashSizeOf(metrics: ActorMetrics): Double = {
    val gauge = PekkoMetricRegistry.getRegistry.find(s"pekko_actor_stash_size_${metrics.actorName}").gauge()
    if (gauge == null) -1.0 else gauge.value()
  }

  private def groupStashSize(group: String): Double =
    PekkoMetricRegistry.metricsForTags(Seq(new ImmutableTag(ActorGroupMetrics.GroupName, group)))
      .getOrElse(ActorGroupMetrics.StashSizeMetricName, -1.0)

  private def sendAndAwait(actor: ActorRef, command: org.apache.pekko.actor.typed.ActorRef[Done.type] => Command): Unit = {
    val probe = TestProbe()
    actor.toTyped[Command] ! command(probe.ref.toTyped[Done.type])
    probe.expectMsg(Done)
  }

  private def createStashingActor(name: String): ActorRef = {
    val actor = system.spawn(TypedStashingActor(), name).toClassic
    sendAndAwait(actor, Ready(_))
    actor
  }
}

object TypedStashMetricsSpec {
  type ReplyTo = org.apache.pekko.actor.typed.ActorRef[Done.type]

  sealed trait Command
  final case class Ready(replyTo: ReplyTo) extends Command
  case object Stashed extends Command
  case object Fail extends Command
  final case class UnstashAll(replyTo: ReplyTo) extends Command
  final case class UnstashOne(replyTo: ReplyTo) extends Command
  final case class Clear(replyTo: ReplyTo) extends Command
  case object Done
}

object TypedStashingActor {
  import TypedStashMetricsSpec._

  // restarted on failure, as a classic actor is by default
  def apply(): Behavior[Command] =
    Behaviors.supervise(Behaviors.withStash(100) { (buffer: StashBuffer[Command]) =>
      stashing(buffer)
    }).onFailure[Exception](SupervisorStrategy.restart)

  private def stashing(buffer: StashBuffer[Command]): Behavior[Command] = Behaviors.receiveMessage {
    case Ready(replyTo) =>
      replyTo ! Done
      Behaviors.same
    case Stashed =>
      buffer.stash(Stashed)
      Behaviors.same
    case UnstashAll(replyTo) =>
      // switch behaviour as part of unstashing: the stashing behaviour above would simply stash them all
      // over again
      replyTo ! Done
      buffer.unstashAll(drained)
    case UnstashOne(replyTo) =>
      replyTo ! Done
      buffer.unstash(drained, 1, identity)
      Behaviors.same
    case Fail =>
      throw new ArithmeticException("Division by zero.")
    case Clear(replyTo) =>
      buffer.clear()
      replyTo ! Done
      Behaviors.same
  }

  private val drained: Behavior[Command] = Behaviors.receiveMessage {
    case Ready(replyTo) =>
      replyTo ! Done
      Behaviors.same
    case _ =>
      Behaviors.same
  }
}
