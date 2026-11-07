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

import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Address, Props}
import org.apache.pekko.cluster.ClusterEvent._
import org.apache.pekko.cluster.{Cluster, Member, MemberStatus}
import io.micrometer.core.instrument.{ImmutableTag, Tag}

import scala.collection.immutable.SortedSet

/**
 * Cluster metrics are gathered by subscribing to the cluster event stream, not by weaving. `aop.xml`
 * excludes `org.apache.pekko.cluster..*` because of
 * [[https://github.com/kontainers/micrometer-akka/issues/84 issue 84]], so an aspect based approach is
 * closed off here. `Cluster.subscribe` is public API and needs no weaving, which sidesteps that entirely.
 *
 * It also cannot start itself. Everything else in this library is driven by the java agent, but there is
 * no safe moment to call `Cluster(system)` on an actor system that may not be a cluster at all, so this
 * is opt in: call [[monitor]] once, after the actor system is up.
 */
object ClusterMetrics {

  val ActorSystem = "actorSystem"
  val Status = "status"

  private[pekko] val MemberCountMetricName = "pekko_cluster_member_count"
  private[pekko] val UnreachableMemberCountMetricName = "pekko_cluster_unreachable_member_count"
  private[pekko] val IsLeaderMetricName = "pekko_cluster_is_leader"

  // Reported at zero when absent, so that a status disappearing is visible as a drop rather than as a
  // series that stops. Any status pekko adds later still shows up, it just is not zero filled.
  private val KnownStatuses = Seq(MemberStatus.Joining, MemberStatus.WeaklyUp, MemberStatus.Up,
    MemberStatus.Leaving, MemberStatus.Exiting, MemberStatus.Down, MemberStatus.Removed)

  import PekkoMetricRegistry._

  /**
   * Subscribes to the cluster event stream and keeps the cluster gauges up to date. Returns the actor
   * doing the work; stop it to stop collecting.
   */
  def monitor(system: ActorSystem): ActorRef =
    system.actorOf(Props(new ClusterMetricsListener(system.name)), "micrometerClusterMetrics")

  def memberCount(system: String, status: String): SettableGauge =
    settableGauge(MemberCountMetricName, Seq(new ImmutableTag(ActorSystem, system), new ImmutableTag(Status, status)))

  def unreachableMemberCount(system: String): SettableGauge =
    settableGauge(UnreachableMemberCountMetricName, tagSeq(system))

  def isLeader(system: String): SettableGauge = settableGauge(IsLeaderMetricName, tagSeq(system))

  private[pekko] def record(system: String, state: CurrentClusterState, selfAddress: Address): Unit = {
    val byStatus = state.members.groupBy(_.status)
    (KnownStatuses ++ byStatus.keys).distinct.foreach { status =>
      memberCount(system, statusName(status)).set(byStatus.get(status).map(_.size.toLong).getOrElse(0L))
    }
    unreachableMemberCount(system).set(state.unreachable.size.toLong)
    isLeader(system).set(if (state.leader.contains(selfAddress)) 1L else 0L)
  }

  private def statusName(status: MemberStatus): String = status.toString.toLowerCase
  private def tagSeq(system: String): Iterable[Tag] = Seq(new ImmutableTag(ActorSystem, system))
}

private[pekko] class ClusterMetricsListener(systemName: String) extends Actor {

  private val cluster = Cluster(context.system)

  // The state is built from the events delivered to this actor. Reading `cluster.state` instead is racy:
  // it is maintained by a different subscriber, so when the last event of a burst (e.g. LeaderChanged)
  // arrives here, `cluster.state` may not reflect it yet, and with no further events the gauges stay stale.
  private var state = CurrentClusterState()

  override def preStart(): Unit =
    cluster.subscribe(self, InitialStateAsSnapshot, classOf[ClusterDomainEvent])

  override def postStop(): Unit = cluster.unsubscribe(self)

  override def receive: Receive = {
    case snapshot: CurrentClusterState => update(snapshot)
    case MemberRemoved(member, _)      => update(state.copy(
        members = withoutMember(state.members, member),
        unreachable = state.unreachable.filterNot(_.uniqueAddress == member.uniqueAddress)))
    case event: MemberEvent            => update(state.copy(members = withoutMember(state.members, event.member) + event.member))
    case UnreachableMember(member)     => update(state.copy(unreachable =
        state.unreachable.filterNot(_.uniqueAddress == member.uniqueAddress) + member))
    case ReachableMember(member)       => update(state.copy(unreachable =
        state.unreachable.filterNot(_.uniqueAddress == member.uniqueAddress)))
    case LeaderChanged(leader)         => update(state.copy(leader = leader))
    case _: ClusterDomainEvent         => // not reflected in any gauge
  }

  private def withoutMember(members: SortedSet[Member], member: Member): SortedSet[Member] =
    members.filterNot(_.uniqueAddress == member.uniqueAddress)

  private def update(newState: CurrentClusterState): Unit = {
    state = newState
    ClusterMetrics.record(systemName, state, cluster.selfAddress)
  }
}
