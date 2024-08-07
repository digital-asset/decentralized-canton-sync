/*
 * Copyright (C) 2015-2021 Lightbend Inc. <https://www.lightbend.com>
 */

// This file has been copied from Pekko 2.6.18

package org.apache.pekko.stream.testkit

import scala.concurrent.Future
import scala.concurrent.duration.*

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.Failed

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.stream.impl.StreamSupervisor
import org.apache.pekko.stream.snapshot.{MaterializerState, StreamSnapshotImpl}
import org.apache.pekko.testkit.{PekkoSpec, TestProbe}
import org.apache.pekko.testkit.TestKitUtils

abstract class StreamSpec(_system: ActorSystem) extends PekkoSpec(_system) {
  def this(config: Config) =
    this(
      ActorSystem(
        TestKitUtils.testNameFromCallStack(classOf[StreamSpec], "".r),
        ConfigFactory.load(config.withFallback(PekkoSpec.testConf)),
      )
    )

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(PekkoSpec.mapToConfig(configMap))

  def this() = this(
    ActorSystem(TestKitUtils.testNameFromCallStack(classOf[StreamSpec], "".r), PekkoSpec.testConf)
  )

  override def withFixture(test: NoArgTest) = {
    super.withFixture(test) match {
      case failed: Failed =>
        implicit val ec = system.dispatcher
        val probe = TestProbe()(system)
        // FIXME I don't think it always runs under /user anymore (typed)
        // FIXME correction - I'm not sure this works at _all_ - supposed to dump stream state if test fails
        val streamSupervisors = system.actorSelection("/user/" + StreamSupervisor.baseName + "*")
        streamSupervisors.tell(StreamSupervisor.GetChildren, probe.ref)
        val children: Seq[ActorRef] = probe
          .receiveWhile(2.seconds) { case StreamSupervisor.Children(children) =>
            children
          }
          .flatten
        println("--- Stream actors debug dump ---")
        if (children.isEmpty) println("Stream is completed. No debug information is available")
        else {
          println("Stream actors alive: " + children)
          Future
            .sequence(children.map(MaterializerState.requestFromChild))
            .foreach(snapshots =>
              snapshots.foreach(s =>
                org.apache.pekko.stream.testkit.scaladsl.StreamTestKit
                  .snapshotString(s.asInstanceOf[StreamSnapshotImpl])
              )
            )
        }
        failed
      case other => other
    }
  }
}
