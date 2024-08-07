// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.scan.store.AcsSnapshotStore
import com.daml.network.scan.store.AcsSnapshotStore.AcsSnapshot
import com.daml.network.store.{PageLimit, UpdateHistory}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.time.temporal.ChronoField
import java.time.{Duration, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotTrigger(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    snapshotPeriodHours: Int,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
    // we always return 1 task, so PollingParallelTaskExecutionTrigger in effect does nothing in parallel
) extends PollingParallelTaskExecutionTrigger[AcsSnapshotTrigger.Task] {

  private val timesToDoSnapshot = (0 to 23).filter(_ % snapshotPeriodHours == 0)

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTrigger.Task]] = {
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else {
      val now = context.pollingClock.now
      for {
        lastSnapshot <- store.lookupSnapshotBefore(store.migrationId, CantonTimestamp.MaxValue)
        possibleTask <- lastSnapshot match {
          case None =>
            firstSnapshotForMigrationIdTask()
          case Some(lastSnapshot) => // new snapshot should be created, if ACS for it is complete
            val newSnapshotRecordTime =
              lastSnapshot.snapshotRecordTime.plus(Duration.ofHours(snapshotPeriodHours.toLong))
            Future.successful(
              Some(AcsSnapshotTrigger.Task(newSnapshotRecordTime, Some(lastSnapshot)))
            )
        }
        task <- possibleTask match {
          case None =>
            logger.info("No snapshots to take.")
            Future.successful(None)
          case Some(task) if task.snapshotRecordTime > now =>
            logger.info(
              s"Still not time to take a snapshot. Next snapshot time: ${task.snapshotRecordTime}."
            )
            Future.successful(None)
          case Some(task) =>
            updateHistory
              .getUpdates(
                Some((store.migrationId, task.snapshotRecordTime)),
                PageLimit.tryCreate(1),
              )
              .map(_.headOption)
              .map {
                case None =>
                  logger.info("There might still be updates pending. Skipping snapshot creation.")
                  None
                case Some(_) =>
                  Some(task)
              }
        }
      } yield task.toList
    }
  }

  override protected def completeTask(task: AcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case AcsSnapshotTrigger.Task(snapshotRecordTime, lastSnapshot) =>
      store
        .insertNewSnapshot(lastSnapshot, snapshotRecordTime)
        .map { insertCount =>
          TaskSuccess(
            s"Successfully inserted $insertCount entries for snapshot $snapshotRecordTime."
          )
        }
  }

  override protected def isStaleTask(task: AcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    store
      .lookupSnapshotBefore(store.migrationId, task.snapshotRecordTime)
      .map(_.exists(_.snapshotRecordTime == task.snapshotRecordTime))
  }

  private def firstSnapshotForMigrationIdTask()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[AcsSnapshotTrigger.Task]] = {
    updateHistory
      .getUpdates(
        Some(
          (
            store.migrationId,
            // exclude ACS imports, which have record_time=epoch
            CantonTimestamp.Epoch.plusSeconds(1L),
          )
        ),
        PageLimit.tryCreate(1),
      )
      .map(_.headOption)
      .map {
        case None =>
          logger.info(s"No updates other than ACS imports found. Retrying snapshot creation later.")
          None
        case Some(firstNonAcsImport) =>
          val firstNonAcsImportRecordTime =
            firstNonAcsImport.update.update.recordTime.toInstant.atOffset(ZoneOffset.UTC)
          val hourForSnapshot = timesToDoSnapshot
            .find(_ > firstNonAcsImportRecordTime.get(ChronoField.HOUR_OF_DAY))
            .getOrElse(0)
          val until = firstNonAcsImportRecordTime.toLocalDate
            .atTime(hourForSnapshot, 0)
            .toInstant(ZoneOffset.UTC)
          Some(AcsSnapshotTrigger.Task(CantonTimestamp.assertFromInstant(until), None))
      }
  }
}

object AcsSnapshotTrigger {

  case class Task(snapshotRecordTime: CantonTimestamp, lastSnapshot: Option[AcsSnapshot])
      extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotRecordTime", _.snapshotRecordTime),
      param("lastSnapshot", _.lastSnapshot),
    )
  }

}
