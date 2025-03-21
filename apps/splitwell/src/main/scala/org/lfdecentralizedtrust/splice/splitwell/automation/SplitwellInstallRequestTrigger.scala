// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.splitwell.store.SplitwellStore
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SplitwellInstallRequestTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      splitwellCodegen.SplitwellInstallRequest.ContractId,
      splitwellCodegen.SplitwellInstallRequest,
    ](
      store,
      splitwellCodegen.SplitwellInstallRequest.COMPANION,
    ) {

  override def completeTask(
      req: AssignedContract[
        splitwellCodegen.SplitwellInstallRequest.ContractId,
        splitwellCodegen.SplitwellInstallRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val user = PartyId.tryFromProtoPrimitive(req.payload.user)
    val provider = store.key.providerParty
    for {
      queryResult <- store.lookupInstallWithOffset(req.domain, user)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          logger.info(s"Rejecting duplicate install request from user party $user")
          val cmd = req.exercise(_.exerciseSplitwellInstallRequest_Reject())
          connection
            .submit(Seq(provider), Seq(), cmd)
            .noDedup
            .yieldResult()
            .map(_ => TaskSuccess("rejected request for already existing installation."))

        case QueryResult(offset, None) =>
          val acceptCmd =
            req.exercise(_.exerciseSplitwellInstallRequest_Accept())
          connection
            .submit(
              actAs = Seq(provider),
              readAs = Seq(),
              acceptCmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "org.lfdecentralizedtrust.splice.splitwell.createSplitwellInstall",
                Seq(provider, user),
                req.domain.toProtoPrimitive,
              ),
              deduplicationOffset = offset,
            )
            .yieldUnit()
            .map(_ => TaskSuccess("accepted install request."))
      }
    } yield taskOutcome
  }
}
