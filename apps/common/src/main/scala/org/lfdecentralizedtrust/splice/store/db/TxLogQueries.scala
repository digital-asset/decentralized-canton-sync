// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import org.lfdecentralizedtrust.splice.store.{StoreErrors, TxLogStore}
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.SelectFromTxLogTableResult
import com.digitalasset.canton.config.CantonRequireTypes.String3
import slick.jdbc.{GetResult, PositionedResult}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.DomainId
import slick.jdbc.canton.SQLActionBuilder

import scala.reflect.ClassTag

trait TxLogQueries[TXE] extends AcsJdbcTypes with StoreErrors {

  /** @param tableName Must be SQL-safe, as it needs to be interpolated unsafely.
    *                   This is fine, as all calls to this method should use static string constants.
    */
  protected def selectFromTxLogTable(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    TxLogQueries.selectFromTxLogTable(tableName, storeId, where, orderLimit)

  /** Same as [[selectFromAcsTableWithOffset]], but for tx log tables.
    */
  protected def selectFromTxLogTableWithOffset(
      tableName: String,
      storeId: Int,
      migrationId: Long,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) = {
    // TODO(#9957): filter in across all migrationIds.
    (sql"""select
            tx.store_id,
            o.last_ingested_offset,
            tx.entry_number,
            tx.transaction_offset,
            tx.domain_id,
            tx.entry_type,
            tx.entry_data
       from store_descriptors sd
           left join store_last_ingested_offsets o
               on sd.id = o.store_id
           left join #$tableName tx
               on o.store_id = tx.store_id
               and """ ++ where ++ sql"""
       where sd.id = $storeId and o.migration_id = $migrationId
       """ ++ orderLimit).toActionBuilder.as[TxLogQueries.SelectFromTxLogTableResultWithOffset]
  }

  implicit val GetResultSelectFromTxLogTableWithOffset
      : GetResult[TxLogQueries.SelectFromTxLogTableResultWithOffset] = { (pp: PositionedResult) =>
    val storeIdFromTxLogRow = pp.<<[Option[Int]]
    TxLogQueries.SelectFromTxLogTableResultWithOffset(
      pp.<<,
      storeIdFromTxLogRow.map { storeId =>
        SelectFromTxLogTableResult(
          storeId,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
        )
      },
    )
  }

  protected def txLogEntryFromRow[TXER <: TXE](
      config: TxLogStore.Config[TXE]
  )(row: SelectFromTxLogTableResult)(implicit tag: ClassTag[TXER]): TXER = {
    config.decodeEntry(row.entryType, row.entryData) match {
      case e: TXER => e
      case _ => throw txLogIsOfWrongType(row.entryType.str)
    }
  }
}

object TxLogQueries {
  case class SelectFromTxLogTableResult(
      storeId: Int,
      entryNumber: Long,
      offset: String,
      domainId: DomainId,
      entryType: String3,
      entryData: String,
  )

  object SelectFromTxLogTableResult {
    implicit val GetResultSelectFromTxLogTable: GetResult[TxLogQueries.SelectFromTxLogTableResult] =
      GetResult { prs =>
        import prs.*
        (TxLogQueries.SelectFromTxLogTableResult.apply _).tupled(
          (
            <<[Int],
            <<[Long],
            <<[String],
            <<[DomainId],
            <<[String3],
            <<[String],
          )
        )
      }

    def sqlColumnsCommaSeparated(qualifier: String = "") =
      s"""${qualifier}store_id,
          ${qualifier}entry_number,
          ${qualifier}transaction_offset,
          ${qualifier}domain_id,
          ${qualifier}entry_type,
          ${qualifier}entry_data"""
  }

  case class SelectFromTxLogTableResultWithOffset(
      offset: String,
      row: Option[SelectFromTxLogTableResult],
  )

  /** @param tableName Must be SQL-safe, as it needs to be interpolated unsafely.
    *                   This is fine, as all calls to this method should use static string constants.
    */
  def selectFromTxLogTable(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select #${SelectFromTxLogTableResult.sqlColumnsCommaSeparated()}
       from #$tableName
       where store_id = $storeId and """ ++ where ++ sql" " ++
      orderLimit).toActionBuilder
      .as[TxLogQueries.SelectFromTxLogTableResult]

}
