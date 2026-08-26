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
import com.github.pjfanning.micrometer.pekko.{ActorSystemMetrics, MetricsConfig}
import org.aspectj.lang.annotation.{After, Aspect, Pointcut}

@Aspect
class DeadLettersInstrumentation {

  // Matched on the trait that declares publish, restricted to EventStream instances. Scala 3 emits
  // EventStream's mixin forwarder as ACC_BRIDGE/ACC_SYNTHETIC and AspectJ never weaves those, so an
  // `execution` pointcut naming EventStream matches nothing here - the trait's default method is what
  // actually runs. (The Scala 2 variant keeps naming EventStream: there the forwarder overrides the
  // default method, so matching the trait instead would advise twice.)
  //
  // This replaces a `call` pointcut, which only ever saw call sites inside the woven pekko packages whose
  // static receiver type was exactly EventStream. The weaver reported 38 sites it therefore skipped:
  //   "does not match because declaring type is org.apache.pekko.event.LoggingBus"
  // and publishes from outside pekko were never seen at all, unlike under Scala 2.
  @Pointcut("execution(void org.apache.pekko.event.SubchannelClassification.publish(Object)) && this(org.apache.pekko.event.EventStream) && args(event)")
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