// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.store.{PageLimit, VotesStore}
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import cats.syntax.either.*
import com.digitalasset.canton.util.ErrorUtil

import scala.concurrent.{ExecutionContext, Future}

trait HttpVotesHandler extends Spanning with NamedLogging {

  protected val votesStore: VotesStore
  protected val workflowId: String
  protected implicit val tracer: Tracer

  def listDsoRulesVoteRequests(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[definitions.ListDsoRulesVoteRequestsResponse] = {
    withSpan(s"$workflowId.listDsoRulesVoteRequests") { _ => _ =>
      for {
        dsoRulesVoteRequests <- votesStore.listVoteRequests()
      } yield definitions.ListDsoRulesVoteRequestsResponse(
        dsoRulesVoteRequests.map(_.toHttp).toVector
      )
    }
  }

  def listVoteRequestResults(
      body: definitions.ListVoteResultsRequest
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[definitions.ListDsoRulesVoteResultsResponse] = {
    withSpan(s"$workflowId.listDsoRulesVoteResults") { _ => _ =>
      for {
        voteResults <- votesStore.listVoteRequestResults(
          body.actionName,
          body.accepted,
          body.requester,
          body.effectiveFrom,
          body.effectiveTo,
          PageLimit.tryCreate(body.limit.intValue),
        )
      } yield {
        definitions.ListDsoRulesVoteResultsResponse(
          voteResults
            .map(voteResult => {
              io.circe.parser
                .parse(
                  ApiCodecCompressed
                    .apiValueToJsValue(Contract.javaValueToLfValue(voteResult.toValue))
                    .compactPrint
                )
                .valueOr(err =>
                  ErrorUtil.invalidState(s"Failed to convert from spray to circe: $err")
                )
            })
            .toVector
        )
      }
    }
  }

  def listVoteRequestsByTrackingCid(body: definitions.BatchListVotesByVoteRequestsRequest)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[definitions.ListVoteRequestByTrackingCidResponse] = {
    withSpan(s"$workflowId.listVoteRequestsByTrackingCid") { _ => _ =>
      for {
        dsoRulesVotes <- votesStore.listVoteRequestsByTrackingCid(
          body.voteRequestContractIds.map(new splice.dsorules.VoteRequest.ContractId(_))
        )
      } yield definitions.ListVoteRequestByTrackingCidResponse(
        dsoRulesVotes.map(_.toHttp).toVector
      )
    }
  }

  def lookupDsoRulesVoteRequest(voteRequestContractId: String)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[definitions.LookupDsoRulesVoteRequestResponse] = {
    withSpan(s"$workflowId.lookupDsoRulesVoteRequest") { _ => _ =>
      votesStore
        .lookupVoteRequest(
          new splice.dsorules.VoteRequest.ContractId(voteRequestContractId)
        )
        .flatMap {
          case Some(voteRequest) =>
            Future.successful(
              definitions.LookupDsoRulesVoteRequestResponse(
                voteRequest.toHttp
              )
            )
          case None =>
            Future.failed(
              HttpErrorHandler.notFound(
                s"No VoteRequest found contract: $voteRequestContractId"
              )
            )
        }
    }
  }

}
