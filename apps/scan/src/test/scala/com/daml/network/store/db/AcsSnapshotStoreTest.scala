package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.DarResources
import com.daml.network.environment.ledger.api.TransactionTreeUpdate
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.scan.store.AcsSnapshotStore
import com.daml.network.store.{PageLimit, StoreErrors, StoreTest, UpdateHistory}
import com.daml.network.util.{Contract, PackageQualifiedName}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.scalatest.Succeeded

import java.time.Instant
import scala.concurrent.Future
import scala.util.{Failure, Success}

class AcsSnapshotStoreTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  private val timestamp1 = CantonTimestamp.Epoch.plusSeconds(3600)
  private val timestamp2 = CantonTimestamp.Epoch.plusSeconds(3600 * 2)
  private val timestamp3 = CantonTimestamp.Epoch.plusSeconds(3600 * 3)
  private val timestamp4 = CantonTimestamp.Epoch.plusSeconds(3600 * 4)
  private val timestamps = Seq(timestamp1, timestamp2, timestamp3, timestamp4)

  "AcsSnapshotStoreTest" should {

    "lookupSnapshotBefore" should {

      "return None when no snapshot is available" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          result <- store.lookupSnapshotBefore(0L, CantonTimestamp.MaxValue)
        } yield result should be(None)
      }

      "only return the last snapshot of the passed migration id" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestCreate(
            updateHistory,
            amuletRules(),
            timestamp1.minusSeconds(1L),
          )
          _ <- store.insertNewSnapshot(None, timestamp1)
          result <- store.lookupSnapshotBefore(migrationId = 1L, CantonTimestamp.MaxValue)
        } yield result should be(None)
      }

      "return the latest snapshot before the given timestamp" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- MonadUtil.sequentialTraverse(Seq(timestamp1, timestamp2, timestamp3)) { timestamp =>
            for {
              _ <- ingestCreate(
                updateHistory,
                openMiningRound(dsoParty, 0L, 1.0),
                timestamp.minusSeconds(1L),
              )
              snapshot <- store.insertNewSnapshot(None, timestamp)
            } yield snapshot
          }
          result <- store.lookupSnapshotBefore(0L, timestamp4)
        } yield result.map(_.snapshotRecordTime) should be(Some(timestamp3))
      }

    }

    "snapshotting" should {

      "fail when attempting to create two snapshots with the same record time" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestCreate(
            updateHistory,
            amuletRules(),
            timestamp1.minusSeconds(1L),
          )
          _ <- store.insertNewSnapshot(None, timestamp1)
          result <- store.insertNewSnapshot(None, timestamp1).transform {
            case Success(_) =>
              Failure(new RuntimeException("This insert shouldn't have succeeded!"))
            case Failure(ex)
                if ex.getMessage.contains(
                  "ERROR: duplicate key value violates unique constraint \"acs_snapshot_pkey\""
                ) =>
              Success("OK")
            case Failure(ex) =>
              Failure(ex)
          }
        } yield result should be("OK")
      }

      "build snapshots incrementally" in {
        // each snapshot has a new contract
        val contracts = timestamps.zipWithIndex.map { case (_, i) =>
          openMiningRound(dsoParty, (i + 3).toLong, 1.0)
        }
        val expectedContractsPerTimestamp = timestamps.zipWithIndex.map { case (tx, idx) =>
          tx -> contracts.slice(0, idx + 1)
        }.toMap

        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- MonadUtil.sequentialTraverse(
            Seq(timestamp1, timestamp2, timestamp3, timestamp4).zipWithIndex
          ) { case (timestamp, i) =>
            for {
              _ <- ingestCreate(
                updateHistory,
                contracts(i),
                timestamp.minusSeconds(10L).plusSeconds(i.toLong),
              )
              _ <- store.insertNewSnapshot(None, timestamp)
              contractsInSnapshot <- queryAll(store, timestamp)
            } yield contractsInSnapshot.createdEventsInPage.map(_.getContractId) should be(
              expectedContractsPerTimestamp(timestamp).map(_.contractId.contractId)
            )
          }
        } yield Succeeded
      }

      "include the ACS in the snapshots" in {
        val acs = Seq(
          openMiningRound(dsoParty, 0L, 1.0),
          openMiningRound(dsoParty, 1L, 1.0),
          openMiningRound(dsoParty, 2L, 1.0),
        )
        val omr1 = openMiningRound(dsoParty, 3L, 1.0)
        val omr2 = openMiningRound(dsoParty, 4L, 1.0)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestAcs(updateHistory, acs)
          // t1
          _ <- ingestCreate(
            updateHistory,
            omr1,
            timestamp1.minusSeconds(2L),
          )
          _ <- store.insertNewSnapshot(None, timestamp1)
          // t2
          _ <- ingestCreate(
            updateHistory,
            omr2,
            timestamp2.minusSeconds(1L),
          )
          lastSnapshot <- store.lookupSnapshotBefore(0L, timestamp2)
          _ <- store.insertNewSnapshot(lastSnapshot, timestamp2)
          result <- queryAll(store, timestamp2)
        } yield result.createdEventsInPage.map(_.getContractId) should be(
          (acs ++ Seq(omr1, omr2)).map(_.contractId.contractId)
        )
      }

      "exercises remove from the ACS" in {
        // t1 -> create
        // t2 -> archives
        // t3 -> creates a different one
        val toArchive = openMiningRound(dsoParty, 1L, 1.0)
        val alwaysThere = openMiningRound(dsoParty, 0L, 1.0) // without it there's no snapshots
        val toCreateT3 = openMiningRound(dsoParty, 3L, 1.0)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(updateHistory, alwaysThere, timestamp1.minusSeconds(2L))
          _ <- ingestCreate(updateHistory, toArchive, timestamp1.minusSeconds(1L))
          _ <- store.insertNewSnapshot(None, timestamp1)
          // t2
          _ <- ingestArchive(updateHistory, toArchive, timestamp2.minusSeconds(2L))
          _ <- store.insertNewSnapshot(None, timestamp2)
          // t3
          _ <- ingestCreate(updateHistory, toCreateT3, timestamp3.minusSeconds(1L))
          _ <- store.insertNewSnapshot(None, timestamp3)
          // querying at the end to prove anything happening in between doesn't matters
          afterT1 <- queryAll(store, timestamp1)
          afterT2 <- queryAll(store, timestamp2)
          afterT3 <- queryAll(store, timestamp3)
        } yield {
          afterT1.createdEventsInPage.map(_.getContractId) should be(
            Seq(alwaysThere, toArchive).map(_.contractId.contractId)
          )
          afterT2.createdEventsInPage.map(_.getContractId) should be(
            Seq(alwaysThere).map(_.contractId.contractId)
          )
          afterT3.createdEventsInPage.map(_.getContractId) should be(
            Seq(alwaysThere, toCreateT3).map(_.contractId.contractId)
          )
        }
      }

    }

    "queryAcsSnapshot" should {

      "filter by party" in {
        val p1 = openMiningRound(dsoParty, 1L, 1.0)
        val p2 = openMiningRound(dsoParty, 2L, 1.0)
        val bothParties = openMiningRound(dsoParty, 3L, 1.0)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(
            updateHistory,
            p1,
            timestamp1.minusSeconds(3L),
            signatories = Seq(providerParty(1)),
          )
          _ <- ingestCreate(
            updateHistory,
            p2,
            timestamp1.minusSeconds(2L),
            signatories = Seq(providerParty(2)),
          )
          _ <- ingestCreate(
            updateHistory,
            bothParties,
            timestamp1.minusSeconds(1L),
            signatories = Seq(providerParty(1), providerParty(2)),
          )
          _ <- store.insertNewSnapshot(None, timestamp1)
          resultParty1 <- store.queryAcsSnapshot(
            0L,
            timestamp1,
            None,
            PageLimit.tryCreate(2),
            Seq(providerParty(1)),
            Seq.empty,
          )
          resultParty2 <- store.queryAcsSnapshot(
            0L,
            timestamp1,
            None,
            PageLimit.tryCreate(2),
            Seq(providerParty(2)),
            Seq.empty,
          )
        } yield {
          resultParty1.createdEventsInPage.map(_.getContractId) should be(
            Seq(p1, bothParties).map(_.contractId.contractId)
          )
          resultParty2.createdEventsInPage.map(_.getContractId) should be(
            Seq(p2, bothParties).map(_.contractId.contractId)
          )
        }
      }

      "filter by template id" in {
        val t1 = openMiningRound(dsoParty, 1L, 1.0)
        val t2 = closedMiningRound(dsoParty, 2L)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(
            updateHistory,
            t1,
            timestamp1.minusSeconds(2L),
            signatories = Seq(dsoParty),
          )
          _ <- ingestCreate(
            updateHistory,
            t2,
            timestamp1.minusSeconds(1L),
            signatories = Seq(dsoParty),
          )
          _ <- store.insertNewSnapshot(None, timestamp1)
          resultTemplate1 <- store.queryAcsSnapshot(
            0L,
            timestamp1,
            None,
            PageLimit.tryCreate(2),
            Seq.empty,
            Seq(PackageQualifiedName(t1.identifier)),
          )
          resultTemplate2 <- store.queryAcsSnapshot(
            0L,
            timestamp1,
            None,
            PageLimit.tryCreate(2),
            Seq.empty,
            Seq(PackageQualifiedName(t2.identifier)),
          )
        } yield {
          resultTemplate1.createdEventsInPage.map(_.getContractId) should be(
            Seq(t1).map(_.contractId.contractId)
          )
          resultTemplate2.createdEventsInPage.map(_.getContractId) should be(
            Seq(t2).map(_.contractId.contractId)
          )
        }
      }

      "filter by both" in {
        val ok = openMiningRound(providerParty(1), 1L, 1.0)
        val differentParty = openMiningRound(providerParty(2), 2L, 1.0)
        val differentTemplate = closedMiningRound(providerParty(1), 3L)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(
            updateHistory,
            ok,
            timestamp1.minusSeconds(3L),
            signatories = Seq(providerParty(1)),
          )
          _ <- ingestCreate(
            updateHistory,
            differentParty,
            timestamp1.minusSeconds(2L),
            signatories = Seq(providerParty(2)),
          )
          _ <- ingestCreate(
            updateHistory,
            differentTemplate,
            timestamp1.minusSeconds(1L),
            signatories = Seq(providerParty(1)),
          )
          _ <- store.insertNewSnapshot(None, timestamp1)
          result <- store.queryAcsSnapshot(
            0L,
            timestamp1,
            None,
            PageLimit.tryCreate(2),
            Seq(providerParty(1)),
            Seq(PackageQualifiedName(ok.identifier)),
          )
        } yield {
          result.createdEventsInPage.map(_.getContractId) should be(
            Seq(ok).map(_.contractId.contractId)
          )
        }
      }

      "paginate" in {
        val contracts = (1 to 10).map { i =>
          openMiningRound(dsoParty, i.toLong, 1.0)
        }
        def queryRecursive(
            store: AcsSnapshotStore,
            after: Option[Long],
            acc: Vector[String],
        ): Future[Vector[String]] = {
          store
            .queryAcsSnapshot(
              0L,
              timestamp1,
              after,
              PageLimit.tryCreate(1),
              Seq.empty,
              Seq.empty,
            )
            .flatMap { result =>
              val newAcc = acc ++ result.createdEventsInPage.map(_.getContractId)
              result.afterToken match {
                case Some(value) => queryRecursive(store, Some(value), newAcc)
                case None => Future.successful(newAcc)
              }
            }
        }
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- MonadUtil.sequentialTraverse(contracts.zipWithIndex) { case (contract, i) =>
            ingestCreate(
              updateHistory,
              contract,
              timestamp1.minusSeconds(10L - i.toLong),
            )
          }
          _ <- store.insertNewSnapshot(None, timestamp1)
          result <- queryRecursive(store, None, Vector.empty)
        } yield {
          result should be(contracts.map(_.contractId.contractId))
        }
      }

    }

  }

  private def mkUpdateHistory(migrationId: Long = 0L): Future[UpdateHistory] = {
    val updateHistory = new UpdateHistory(
      storage.underlying, // not under test
      new DomainMigrationInfo(migrationId, None),
      "update_history_acs_snapshot_test",
      mkParticipantId("whatever"),
      dsoParty,
      loggerFactory,
      true,
    )
    updateHistory.ingestionSink.initialize().map(_ => updateHistory)
  }

  private def mkStore(updateHistory: UpdateHistory, migrationId: Long = 0L): AcsSnapshotStore = {
    new AcsSnapshotStore(
      // we're guaranteed to only execute the insert once (in the context of AcsSnapshotTrigger),
      // and the insert query is already complicated enough as-is, so I'm not gonna make it worse just for tests.
      storage.underlying,
      updateHistory,
      migrationId,
      loggerFactory,
    )
  }

  private def ingestCreate[TCid <: ContractId[T], T](
      updateHistory: UpdateHistory,
      create: Contract[TCid, T],
      recordTime: CantonTimestamp,
      signatories: Seq[PartyId] = Seq(dsoParty),
  ): Future[Unit] = {
    updateHistory.ingestionSink.ingestUpdate(
      dummyDomain,
      TransactionTreeUpdate(
        mkCreateTx(
          nextOffset(),
          Seq(create.copy(createdAt = recordTime.toInstant).asInstanceOf[Contract[TCid, T]]),
          Instant.now(),
          signatories,
          dummyDomain,
          "acs-snapshot-store-test",
          recordTime.toInstant,
          packageName = DarResources
            .lookupPackageId(create.identifier.getPackageId)
            .getOrElse(
              throw new IllegalArgumentException(
                s"No package found for template ${create.identifier}"
              )
            )
            .metadata
            .name,
        )
      ),
    )
  }

  private def ingestArchive[TCid <: ContractId[T], T](
      updateHistory: UpdateHistory,
      archive: Contract[TCid, T],
      recordTime: CantonTimestamp,
  ): Future[Unit] = {
    updateHistory.ingestionSink.ingestUpdate(
      dummyDomain,
      TransactionTreeUpdate(
        mkTx(
          nextOffset(),
          Seq(toArchivedEvent(archive)),
          dummyDomain,
          recordTime = recordTime.toInstant,
        )
      ),
    )
  }

  private def ingestAcs[TCid <: ContractId[T], T](
      updateHistory: UpdateHistory,
      acs: Seq[Contract[TCid, T]],
  ): Future[Unit] = {
    MonadUtil
      .sequentialTraverse(acs) { contract =>
        ingestCreate(updateHistory, contract, recordTime = CantonTimestamp.Epoch)
      }
      .map(_ => ())
  }

  private def queryAll(
      store: AcsSnapshotStore,
      timestamp: CantonTimestamp,
  ): Future[AcsSnapshotStore.QueryAcsSnapshotResult] = {
    store.queryAcsSnapshot(
      0L,
      timestamp,
      None,
      PageLimit.tryCreate(1000),
      Seq.empty,
      Seq.empty,
    )
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()

}
