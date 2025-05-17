package org.apache.pekko.actor.typed.instrumentation

import com.github.pjfanning.micrometer.pekko.BaseSpec
import org.apache.pekko.actor.typed.internal.adapter.ActorAdapter
import org.apache.pekko.monitor.instrumentation.CellInfo

class ClassSpec extends BaseSpec {

  "Classloading" should {
    "match TypedActorAdapterClassName" in {
      Class.forName(CellInfo.TypedActorAdapterClassName) shouldEqual classOf[ActorAdapter[_]]
    }
  }
}
