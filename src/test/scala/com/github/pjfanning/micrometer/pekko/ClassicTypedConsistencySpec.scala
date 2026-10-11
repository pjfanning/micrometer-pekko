package com.github.pjfanning.micrometer.pekko

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.{Actor, ActorRef, OneForOneStrategy, Props, SupervisorStrategy => ClassicSupervisorStrategy}
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy => TypedSupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.testkit.TestProbe

/**
 * Runs one scenario through a classic and a typed actor side by side, under each supervision directive,
 * and requires the two to end up with the same actor metrics.
 *
 * System messages are left out of the comparison on purpose: a classic restart is carried out with a
 * Recreate system message, where typed supervision restarts the behavior in place without one.
 */
class ClassicTypedConsistencySpec extends TestKitBaseSpec("ClassicTypedConsistencySpec") with ActorMetricsBehaviours {

  import ClassicTypedConsistencySpec._

  private val directives = Seq(
    ("restart", ClassicSupervisorStrategy.Restart, TypedSupervisorStrategy.restart, true),
    ("resume", ClassicSupervisorStrategy.Resume, TypedSupervisorStrategy.resume, true),
    ("stop", ClassicSupervisorStrategy.Stop, TypedSupervisorStrategy.stop, false))

  "classic and typed actors" should {
    directives.foreach { case (label, classicDirective, typedStrategy, survives) =>
      s"report the same actor metrics when supervised with $label" in {
        val classic = runScenario(classicChild(s"tracked-consistency-classic-$label", classicDirective), survives)
        val typed = runScenario(typedActor(s"tracked-consistency-typed-$label", typedStrategy), survives)

        typed shouldEqual classic
      }
    }
  }

  // Pings, fails, pings again if the actor is still alive, then stops it and waits for the metrics to settle.
  private def runScenario(actor: (ActorRef, ActorFlavour), survives: Boolean): Snapshot = {
    val (ref, flavour) = actor
    val metrics = actorMetricsRecorderOf(ref).get
    val probe = TestProbe()
    probe.watch(ref)

    flavour.ping(system, ref)
    flavour.ping(system, ref)
    flavour.fail(ref)
    if (survives) {
      flavour.ping(system, ref)
      system.stop(ref)
    }
    probe.expectTerminated(ref, 5.seconds)

    val expectedMessages = if (survives) 5.0 else 4.0
    eventually(timeout(5.seconds)) {
      metrics.messages.count() shouldEqual expectedMessages
      metrics.errors.count() shouldEqual 1.0
      metrics.processingTime.timer.count() shouldEqual expectedMessages.toLong
    }
    Snapshot(metrics)
  }

  private def classicChild(name: String, directive: ClassicSupervisorStrategy.Directive): (ActorRef, ActorFlavour) = {
    val parent = system.actorOf(Props(new SupervisingParent(directive)), name)
    val probe = TestProbe()
    parent.tell(GetChild, probe.ref)
    val child = probe.expectMsgType[ActorRef]
    ActorFlavour.Classic.ping(system, child)
    (child, ActorFlavour.Classic)
  }

  private def typedActor(name: String, strategy: TypedSupervisorStrategy): (ActorRef, ActorFlavour) = {
    val behavior: Behavior[TypedMetricsTestActor.Command] =
      Behaviors.supervise(TypedMetricsTestActor()).onFailure[Exception](strategy)
    val actor = system.spawn(behavior, name).toClassic
    ActorFlavour.Typed.ping(system, actor)
    (actor, ActorFlavour.Typed)
  }
}

object ClassicTypedConsistencySpec {
  case object GetChild

  final case class Snapshot(messages: Double, errors: Double, restarts: Double, processed: Long,
      timedInMailbox: Long, mailboxSize: Double, stashSize: Double)

  object Snapshot {
    private def gaugeValue(gauge: GaugeWrapper): Double =
      PekkoMetricRegistry.getRegistry.find(gauge.name).gauge().value()

    def apply(metrics: ActorMetrics): Snapshot = Snapshot(
      metrics.messages.count(),
      metrics.errors.count(),
      metrics.restarts.count(),
      metrics.processingTime.timer.count(),
      metrics.timeInMailbox.timer.count(),
      gaugeValue(metrics.mailboxSize),
      gaugeValue(metrics.stashSize))
  }

  class SupervisingParent(directive: ClassicSupervisorStrategy.Directive) extends Actor {
    override val supervisorStrategy: ClassicSupervisorStrategy =
      OneForOneStrategy(loggingEnabled = false) { case _: Exception => directive }

    private val child = context.actorOf(Props[ActorMetricsTestActor](), "child")

    override def receive: Receive = {
      case GetChild => sender() ! child
    }
  }
}
