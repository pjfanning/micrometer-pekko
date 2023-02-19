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
package org.apache.pekko.monitor.instrumentation

import org.apache.pekko.actor.{DeadLetter, UnhandledMessage}
import com.github.pjfanning.micrometer.akka.{ActorSystemMetrics, MetricsConfig}
import org.aspectj.lang.annotation.{After, Aspect, Pointcut}

@Aspect
class DeadLettersInstrumentation {

  @Pointcut("call(void org.apache.pekko.event.EventStream.publish(Object)) && args(event)")
  def streamPublish(event: Object): Unit = {}

  @After("streamPublish(event)")
  def afterStreamSubchannel(event: Object): Unit = {
    trackEvent(event)
  }

  private def trackEvent(event: Object): Unit = {
    if (MetricsConfig.matchEvents) {
      event match {
        case dl: DeadLetter => {
          val systemName = dl.sender.path.address.system
          ActorSystemMetrics.deadLetterCount(systemName).increment()
        }
        case um: UnhandledMessage => {
          val systemName = um.sender.path.address.system
          ActorSystemMetrics.unhandledMessageCount(systemName).increment()
        }
        case _ =>
      }
    }
  }

}