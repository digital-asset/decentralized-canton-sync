// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.migration

import cats.implicits.toBifunctorOps
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.migration.{
  ParticipantUsersData,
  ParticipantUsersDataExporter,
}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.migration.SynchronizerNodeIdentities.getSynchronizerNodeIdentities
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Codec, Decoder}
import io.circe.syntax.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class DomainMigrationDump(
    migrationId: Long,
    nodeIdentities: SynchronizerNodeIdentities,
    domainDataSnapshot: DomainDataSnapshot,
    participantUsers: Option[ParticipantUsersData],
    createdAt: Instant,
) {
  def toHttp: http.GetDomainMigrationDumpResponse = http.GetDomainMigrationDumpResponse(
    migrationId,
    nodeIdentities.toHttp(),
    domainDataSnapshot.toHttp,
    participantUsers.map(_.toHttp),
    createdAt.toString,
  )
}

object DomainMigrationDump {

  implicit val domainMigrationDumpCodec: Codec[DomainMigrationDump] = Codec.from(
    Decoder.decodeJson.emap(json =>
      json
        .as[http.GetDomainMigrationDumpResponse]
        .leftMap(err => s"Failed to decode: ${err.message}")
        .flatMap(fromHttp)
    ),
    (a: DomainMigrationDump) => a.toHttp.asJson,
  )

  def fromHttp(
      response: http.GetDomainMigrationDumpResponse
  ): Either[String, DomainMigrationDump] = for {
    identities <- SynchronizerNodeIdentities.fromHttp(response.identities)
    snapshot <- DomainDataSnapshot.fromHttp(response.dataSnapshot)
    participantUsers = response.participantUsers.map(ParticipantUsersData.fromHttp)
    timestamp <- Try(Instant.parse(response.createdAt)).toEither.leftMap(_.getMessage)
  } yield DomainMigrationDump(
    response.migrationId,
    nodeIdentities = identities,
    domainDataSnapshot = snapshot,
    participantUsers = participantUsers,
    createdAt = timestamp,
  )

  def getDomainMigrationDump(
      synchronizerAlias: SynchronizerAlias,
      ledgerConnection: SpliceLedgerConnection,
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerNode: LocalSynchronizerNode,
      loggerFactory: NamedLoggerFactory,
      dsoStore: SvDsoStore,
      migrationId: Long,
      domainDataSnapshotGenerator: DomainDataSnapshotGenerator,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainMigrationDump] = {
    for {
      identities <- getSynchronizerNodeIdentities(
        participantAdminConnection,
        synchronizerNode,
        dsoStore,
        synchronizerAlias,
        loggerFactory,
      )
      participantUsersDataExporter = new ParticipantUsersDataExporter(ledgerConnection)
      participantUsersData <- participantUsersDataExporter.exportParticipantUsersData()
      snapshot <- domainDataSnapshotGenerator.getDomainMigrationSnapshot
    } yield DomainMigrationDump(
      migrationId,
      identities,
      snapshot,
      Some(participantUsersData),
      Instant.now(),
    )
  }

}
