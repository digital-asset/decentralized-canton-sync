// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.dao.events

import com.daml.metrics.Timed
import com.daml.metrics.api.MetricHandle.Timer
import com.digitalasset.canton.data.Offset
import com.digitalasset.canton.logging.{LoggingContextWithTrace, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.metrics.LedgerApiServerMetrics
import com.digitalasset.canton.platform.store.backend.ContractStorageBackend
import com.digitalasset.canton.platform.store.backend.ContractStorageBackend.{
  RawArchivedContract,
  RawCreatedContract,
}
import com.digitalasset.canton.platform.store.dao.DbDispatcher
import com.digitalasset.canton.platform.store.dao.events.ContractsReader.*
import com.digitalasset.canton.platform.store.interfaces.LedgerDaoContractsReader
import com.digitalasset.canton.platform.store.interfaces.LedgerDaoContractsReader.*
import com.digitalasset.canton.platform.store.serialization.{Compression, ValueSerializer}
import com.digitalasset.canton.platform.{Contract, ContractId, *}
import com.digitalasset.daml.lf.data.Ref.PackageName
import com.digitalasset.daml.lf.transaction.GlobalKey
import com.digitalasset.daml.lf.value.Value.VersionedValue

import java.io.{ByteArrayInputStream, InputStream}
import scala.concurrent.{ExecutionContext, Future}

private[dao] sealed class ContractsReader(
    contractLoader: ContractLoader,
    storageBackend: ContractStorageBackend,
    dispatcher: DbDispatcher,
    metrics: LedgerApiServerMetrics,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends LedgerDaoContractsReader
    with NamedLogging {

  /** Batch lookup of contract keys
    *
    * Used to unit test the SQL queries for key lookups. Does not use batching.
    */
  override def lookupKeyStatesFromDb(keys: Seq[Key], notEarlierThanOffset: Offset)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Map[Key, KeyState]] =
    Timed.future(
      metrics.index.db.lookupKey,
      dispatcher.executeSql(metrics.index.db.lookupContractByKeyDbMetrics)(
        storageBackend.keyStates(keys, notEarlierThanOffset)
      ),
    )

  /** Lookup a contract key state at a specific ledger offset.
    *
    * @param key
    *   the contract key
    * @param notEarlierThanOffset
    *   the lower bound offset of the ledger for which to query for the key state
    * @return
    *   the key state.
    */
  override def lookupKeyState(key: Key, notEarlierThanOffset: Offset)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[KeyState] =
    Timed.future(
      metrics.index.db.lookupKey,
      contractLoader.keys.load(key -> notEarlierThanOffset).map {
        case Some(value) => value
        case None =>
          logger
            .error(s"Key $key resulted in an invalid empty load at offset $notEarlierThanOffset")(
              loggingContext.traceContext
            )
          KeyUnassigned
      },
    )

  override def lookupContractState(contractId: ContractId, notEarlierThanOffset: Offset)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Option[ContractState]] =
    Timed.future(
      metrics.index.db.lookupActiveContract,
      contractLoader.contracts
        .load(contractId -> notEarlierThanOffset)
        .map(_.map {
          case raw: RawCreatedContract =>
            val decompressionTimer =
              metrics.index.db.lookupCreatedContractsDbMetrics.compressionTimer
            val deserializationTimer =
              metrics.index.db.lookupCreatedContractsDbMetrics.translationTimer

            val contract = toContract(
              contractId = contractId,
              templateId = raw.templateId,
              packageName = raw.packageName,
              createArgument = raw.createArgument,
              createArgumentCompression =
                Compression.Algorithm.assertLookup(raw.createArgumentCompression),
              decompressionTimer = decompressionTimer,
              deserializationTimer = deserializationTimer,
            )

            val globalKey = raw.createKey.map { key =>
              val keyCompression = Compression.Algorithm.assertLookup(raw.createKeyCompression)
              val decompressed = decompress(key, keyCompression, decompressionTimer)
              val value = deserializeValue(
                decompressed,
                deserializationTimer,
                s"Failed to deserialize create key for contract ${contractId.coid}",
              )
              GlobalKey.assertBuild(
                contract.unversioned.template,
                value.unversioned,
                contract.unversioned.packageName,
              )
            }

            ActiveContract(
              contract = contract,
              stakeholders = raw.flatEventWitnesses,
              ledgerEffectiveTime = raw.ledgerEffectiveTime,
              signatories = raw.signatories,
              globalKey = globalKey,
              keyMaintainers = raw.keyMaintainers,
              driverMetadata = raw.driverMetadata,
            )
          case raw: RawArchivedContract => ArchivedContract(raw.flatEventWitnesses)
        }),
    )
}

private[dao] object ContractsReader {

  private[dao] def apply(
      contractLoader: ContractLoader,
      dispatcher: DbDispatcher,
      metrics: LedgerApiServerMetrics,
      storageBackend: ContractStorageBackend,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): ContractsReader =
    new ContractsReader(
      contractLoader = contractLoader,
      storageBackend = storageBackend,
      dispatcher = dispatcher,
      metrics = metrics,
      loggerFactory = loggerFactory,
    )

  private def decompress(
      data: Array[Byte],
      algorithm: Compression.Algorithm,
      timer: Timer,
  ): InputStream =
    Timed.value(
      timer,
      value = algorithm.decompress(new ByteArrayInputStream(data)),
    )

  private def deserializeValue(
      decompressed: InputStream,
      timer: Timer,
      errorContext: String,
  ): VersionedValue =
    Timed.value(
      timer,
      value = ValueSerializer.deserializeValue(decompressed, errorContext),
    )

  // The contracts table _does not_ store agreement texts as they are
  // unnecessary for interpretation and validation. The contracts returned
  // from this table will _always_ have an empty agreement text.
  private def toContract(
      contractId: ContractId,
      templateId: String,
      packageName: String,
      createArgument: Array[Byte],
      createArgumentCompression: Compression.Algorithm,
      decompressionTimer: Timer,
      deserializationTimer: Timer,
  ): Contract = {
    val decompressed = decompress(createArgument, createArgumentCompression, decompressionTimer)
    val deserialized = deserializeValue(
      decompressed,
      deserializationTimer,
      s"Failed to deserialize create argument for contract ${contractId.coid}",
    )
    Contract(
      packageName = PackageName.assertFromString(packageName),
      template = Identifier.assertFromString(templateId),
      arg = deserialized,
    )
  }
}
