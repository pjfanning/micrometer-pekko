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

import java.util.concurrent.TimeUnit

import org.apache.pekko.serialization.SerializationExtension
import com.github.pjfanning.micrometer.pekko.SerializationMetrics._

class SerializationMetricsSpec extends TestKitBaseSpec("SerializationMetricsSpec") {

  "the serialization metrics" should {
    // Which serializer pekko picks for a String is its business, so the name is read back rather than
    // assumed. Whatever it is, it is one pekko ships and so inside the woven packages.
    "time a serialize and record its payload size" in {
      val serialization = SerializationExtension(system)
      val message = "a message to serialize"
      val serializer = serializerNameFor(message)
      val before = time(serializer, Serialize).timer.count()

      val bytes = serialization.serialize(message).get
      bytes.length should be > 0

      time(serializer, Serialize).timer.count() should be (before + 1L)
      time(serializer, Serialize).timer.totalTime(TimeUnit.NANOSECONDS) should be >= 0.0

      val sizes = payloadSize(serializer, Serialize)
      sizes.count() should be >= 1L
      // the summary saw the bytes that came back, so the maximum is at least that big
      sizes.max() should be >= bytes.length.toDouble
    }

    "time a deserialize and record its payload size" in {
      val serialization = SerializationExtension(system)
      val message = "a message to deserialize"
      val serializer = serializerNameFor(message)
      val bytes = serialization.serialize(message).get
      val before = time(serializer, Deserialize).timer.count()

      serialization.deserialize(bytes, classOf[String]).get should be (message)

      time(serializer, Deserialize).timer.count() should be (before + 1L)
      payloadSize(serializer, Deserialize).max() should be >= bytes.length.toDouble
    }
  }

  private def serializerNameFor(message: AnyRef): String =
    SerializationExtension(system).findSerializerFor(message).getClass.getSimpleName
}
