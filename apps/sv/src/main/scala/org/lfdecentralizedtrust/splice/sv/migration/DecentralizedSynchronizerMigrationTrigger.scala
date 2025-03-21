// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.migration

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SpliceLedgerConnection,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.migration.{AcsExporter, DomainMigrationTrigger}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.topology.transaction.DomainParametersState
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

final class DecentralizedSynchronizerMigrationTrigger(
    override protected val currentMigrationId: Long,
    baseContext: TriggerContext,
    domainAlias: DomainAlias,
    localSynchronizerNode: LocalSynchronizerNode,
    dsoStore: SvDsoStore,
    ledgerConnection: SpliceLedgerConnection,
    protected val participantAdminConnection: ParticipantAdminConnection,
    sequencerAdminConnection0: SequencerAdminConnection,
    protected val dumpPath: Path,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends DomainMigrationTrigger[DomainMigrationDump] {

  // Disabling domain time and domain paused sync, as it runs after the domain is paused
  override protected lazy val context: TriggerContext =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  override val sequencerAdminConnection
      : Some[org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection] = Some(
    sequencerAdminConnection0
  )

  val domainDataSnapshotGenerator = new DomainDataSnapshotGenerator(
    participantAdminConnection,
    sequencerAdminConnection,
    dsoStore,
    new AcsExporter(participantAdminConnection, context.retryProvider, loggerFactory),
    context.retryProvider,
    loggerFactory,
  )

  override protected def getSchedule(implicit
      tc: TraceContext
  ): OptionT[Future, DomainMigrationTrigger.ScheduledMigration] = {
    for {
      dsoRules <- OptionT(dsoStore.lookupDsoRules())
      schedule <- OptionT.fromOption[Future](
        dsoRules.contract.payload.config.nextScheduledSynchronizerUpgrade.toScala
      )
    } yield DomainMigrationTrigger.ScheduledMigration(schedule.time, schedule.migrationId)
  }

  override protected def getDomainId()(implicit tc: TraceContext): Future[DomainId] = {
    dsoStore.getDsoRules().map(_.domain)
  }

  override protected def existingDumpFileMigrationId(dump: DomainMigrationDump): Long =
    dump.migrationId

  override protected def existingDumpFileTimestamp(dump: DomainMigrationDump): Instant =
    dump.createdAt

  override protected def generateDump(task: DomainMigrationTrigger.Task)(implicit
      tc: TraceContext
  ): Future[DomainMigrationDump] = for {
    _ <- ensureDomainIsPaused(task.domainId)
    dump <- exportMigrationDump(task.migrationId)
  } yield dump

  private def ensureDomainIsPaused(
      decentralizedSynchronizerId: DomainId
  )(implicit tc: TraceContext): Future[TopologyResult[DomainParametersState]] = for {
    domainParamsTopologyResult <- participantAdminConnection
      .ensureDomainParameters(
        decentralizedSynchronizerId,
        _.tryUpdate(confirmationRequestsMaxRate = NonNegativeInt.zero),
      )
  } yield domainParamsTopologyResult

  private def exportMigrationDump(migrationId: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainMigrationDump] = {
    DomainMigrationDump
      .getDomainMigrationDump(
        domainAlias,
        ledgerConnection,
        participantAdminConnection,
        localSynchronizerNode,
        loggerFactory,
        dsoStore,
        migrationId,
        domainDataSnapshotGenerator,
      )
  }

}
