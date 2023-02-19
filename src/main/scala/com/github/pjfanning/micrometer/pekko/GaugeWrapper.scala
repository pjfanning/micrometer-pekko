package com.github.pjfanning.micrometer.pekko

import java.util.concurrent.atomic.DoubleAdder

import scala.collection.JavaConverters._

import com.github.pjfanning.micrometer.pekko.impl.DoubleFunction
import io.micrometer.core.instrument.{MeterRegistry, Tag}

case class GaugeWrapper(registry: MeterRegistry, name: String, tags: Iterable[Tag]) {
  private val adder = new DoubleAdder
  private val fn = new DoubleFunction[DoubleAdder](_.doubleValue)
  registry.gauge(name, tags.asJava, adder, fn)
  def decrement(): Unit = increment(-1.0)
  def increment(): Unit = increment(1.0)
  def increment(d: Double): Unit = adder.add(d)
}
