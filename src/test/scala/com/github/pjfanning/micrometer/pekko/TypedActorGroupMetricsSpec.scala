package com.github.pjfanning.micrometer.pekko

class TypedActorGroupMetricsSpec extends TestKitBaseSpec("TypedActorGroupMetricsSpec") with ActorGroupMetricsBehaviours {

  "the typed actor group metrics" should {
    behave like actorGroupMetrics(ActorFlavour.Typed)
  }
}
