// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import cats.data.{EitherT, OptionT}
import com.digitalasset.canton.RequestCounter
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.participant.protocol.RequestJournal.{RequestData, RequestState}
import com.digitalasset.canton.participant.util.TimeOfRequest
import com.digitalasset.canton.tracing.TraceContext
import com.google.common.annotations.VisibleForTesting

import scala.concurrent.ExecutionContext

trait RequestJournalStore { this: NamedLogging =>

  private[store] implicit def ec: ExecutionContext

  /** Adds the initial request information to the store.
    *
    * @return
    *   A failed future, if a request is inserted more than once with differing `data`
    */
  def insert(data: RequestData)(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit]

  /** Find request information by request counter */
  def query(rc: RequestCounter)(implicit
      traceContext: TraceContext
  ): OptionT[FutureUnlessShutdown, RequestData]

  /** Finds the request with the lowest request counter whose commit time is after the given
    * timestamp
    */
  def firstRequestWithCommitTimeAfter(commitTimeExclusive: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Option[RequestData]]

  /** Finds the highest request time before or equal to the given timestamp */
  def lastRequestTimeWithRequestTimestampBeforeOrAt(requestTimestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Option[TimeOfRequest]]

  /** Replaces the state of the request. The operation will only succeed if the current state is
    * equal to the given `oldState` and the provided `requestTimestamp` matches the stored
    * timestamp, or if the current state is already the new state. If so, the state gets replaced
    * with `newState` and `commitTime`. If `commitTime` is [[scala.None$]], the commit time will not
    * be modified.
    *
    * The returned future may fail with a [[java.util.ConcurrentModificationException]] if the store
    * detects a concurrent modification.
    *
    * @param requestTimestamp
    *   The sequencing time of the request.
    */
  def replace(
      rc: RequestCounter,
      requestTimestamp: CantonTimestamp,
      newState: RequestState,
      commitTime: Option[CantonTimestamp],
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, RequestJournalStoreError, Unit]

  /** Deletes all request counters at or before the given timestamp. Calls to this method are
    * idempotent, independent of the order.
    *
    * @param beforeInclusive
    *   inclusive timestamp to prune up to
    */
  def prune(
      beforeInclusive: CantonTimestamp
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] =
    pruneInternal(beforeInclusive)

  /** Purges all data from the request journal.
    */
  def purge()(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit]

  /** Deletes all request counters at or before the given timestamp. Calls to this method are
    * idempotent, independent of the order.
    */
  @VisibleForTesting
  private[store] def pruneInternal(beforeInclusive: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Unit]

  /** Counts requests whose timestamps lie between the given timestamps (inclusive).
    *
    * @param start
    *   Count all requests after or at the given timestamp
    * @param end
    *   Count all requests before or at the given timestamp; use None to impose no upper limit
    */
  def size(start: CantonTimestamp = CantonTimestamp.Epoch, end: Option[CantonTimestamp] = None)(
      implicit traceContext: TraceContext
  ): FutureUnlessShutdown[Int]

  /** Deletes all the requests with a request timestamp equal to or higher than the given request
    * timestamp.
    */
  def deleteSinceRequestTimestamp(fromInclusive: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Unit]

  /** Returns the number of dirty requests.
    */
  def totalDirtyRequests()(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[NonNegativeInt]
}

sealed trait RequestJournalStoreError extends Product with Serializable

final case class UnknownRequestCounter(requestCounter: RequestCounter)
    extends RequestJournalStoreError
final case class CommitTimeBeforeRequestTime(
    requestCounter: RequestCounter,
    requestTime: CantonTimestamp,
    commitTime: CantonTimestamp,
) extends RequestJournalStoreError
final case class InconsistentRequestTimestamp(
    requestCounter: RequestCounter,
    storedTimestamp: CantonTimestamp,
    expectedTimestamp: CantonTimestamp,
) extends RequestJournalStoreError
