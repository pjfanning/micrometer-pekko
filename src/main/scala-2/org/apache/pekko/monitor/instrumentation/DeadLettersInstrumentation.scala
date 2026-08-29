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

import org.apache.pekko.event.EventStream
import org.aspectj.lang.annotation.{After, Aspect, Pointcut}

@Aspect
class DeadLettersInstrumentation {

  @Pointcut("execution(void org.apache.pekko.event.EventStream.publish(Object)) && this(stream) && args(event)")
  def streamPublish(stream: EventStream, event: Object): Unit = {}

  @After("streamPublish(stream, event)")
  def afterStreamSubchannel(stream: EventStream, event: Object): Unit = {
    EventStreamTracking.trackEvent(stream, event)
  }

}
