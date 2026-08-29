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

import com.github.pjfanning.micrometer.pekko.{MetricsConfig, SerializationMetrics}
import org.apache.pekko.serialization.Serializer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{Around, Aspect, Pointcut}

/**
 * Serialization cost is invisible to the rest of the library: it happens between an actor sending a
 * message and another receiving it, so it lands in neither processing time nor time in mailbox.
 *
 * `Serializer.toBinary` and `fromBinary` are advised rather than `Serialization.serialize`, because
 * pekko-remote does not go through `Serialization` - it looks the serializer up and calls `toBinary`
 * directly - so advising the higher level API would miss the path that matters most.
 *
 * The trade off is that only serializers whose classes are inside `org.apache.pekko` are woven, which is
 * every serializer pekko ships but no custom one. `toBinary` is abstract, so there is no trait method to
 * advise that would cover the implementations generically.
 */
@Aspect
class SerializationInstrumentation {

  // Pinned to the single argument signature. ByteBufferSerializer adds a toBinary(Object, ByteBuffer)
  // that some serializers implement in terms of this one, which (..) would count a second time.
  @Pointcut("execution(* org.apache.pekko.serialization.Serializer+.toBinary(Object)) && this(serializer)")
  def toBinary(serializer: Serializer): Unit = {}

  @Around("toBinary(serializer)")
  def aroundToBinary(pjp: ProceedingJoinPoint, serializer: Serializer): AnyRef = {
    if (MetricsConfig.serializationEnabled) {
      val name = serializerName(serializer)
      val timer = SerializationMetrics.time(name, SerializationMetrics.Serialize).startTimer()
      try {
        val bytes = pjp.proceed()
        bytes match {
          case payload: Array[Byte] =>
            SerializationMetrics.payloadSize(name, SerializationMetrics.Serialize).record(payload.length.toDouble)
          case _ =>
        }
        bytes
      } finally {
        timer.close()
      }
    } else {
      pjp.proceed()
    }
  }

  // Likewise pinned: Serializer declares fromBinary(byte[]) and fromBinary(byte[], Class) as default
  // methods that both delegate here, and (..) would count those delegations as well.
  @Pointcut("execution(* org.apache.pekko.serialization.Serializer+.fromBinary(byte[], scala.Option)) && " +
    "this(serializer) && args(bytes, ..)")
  def fromBinary(serializer: Serializer, bytes: Array[Byte]): Unit = {}

  @Around("fromBinary(serializer, bytes)")
  def aroundFromBinary(pjp: ProceedingJoinPoint, serializer: Serializer, bytes: Array[Byte]): AnyRef = {
    if (MetricsConfig.serializationEnabled) {
      val name = serializerName(serializer)
      val timer = SerializationMetrics.time(name, SerializationMetrics.Deserialize).startTimer()
      try {
        SerializationMetrics.payloadSize(name, SerializationMetrics.Deserialize).record(bytes.length.toDouble)
        pjp.proceed()
      } finally {
        timer.close()
      }
    } else {
      pjp.proceed()
    }
  }

  // the class name rather than the numeric identifier, which is what a dashboard can be read against
  private def serializerName(serializer: Serializer): String = serializer.getClass.getSimpleName
}
