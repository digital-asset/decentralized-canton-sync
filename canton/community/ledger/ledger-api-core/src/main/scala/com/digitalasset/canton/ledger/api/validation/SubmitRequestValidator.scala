// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api.validation

import cats.implicits.{toBifunctorOps, toTraverseOps}
import com.daml.error.{ContextualizedErrorLogger, DamlError}
import com.daml.ledger.api.v2.command_submission_service.{SubmitReassignmentRequest, SubmitRequest}
import com.daml.ledger.api.v2.interactive.interactive_submission_service as iss
import com.daml.ledger.api.v2.interactive.interactive_submission_service.{
  PartySignatures,
  PrepareSubmissionRequest,
  Signature as InteractiveSignature,
  SignatureFormat as InteractiveSignatureFormat,
  SinglePartySignatures,
}
import com.daml.ledger.api.v2.reassignment_command.ReassignmentCommand
import com.digitalasset.canton.crypto.{
  Fingerprint,
  Signature,
  SignatureFormat,
  SigningAlgorithmSpec,
}
import com.digitalasset.canton.ledger.api.SubmissionIdGenerator
import com.digitalasset.canton.ledger.api.messages.command.submission
import com.digitalasset.canton.ledger.api.services.InteractiveSubmissionService.ExecuteRequest
import com.digitalasset.canton.ledger.api.validation.ValidationErrors.invalidField
import com.digitalasset.canton.ledger.api.validation.ValueValidator.*
import com.digitalasset.canton.ledger.error.groups.RequestValidationErrors
import com.digitalasset.canton.topology.{DomainId, PartyId as TopologyPartyId}
import com.digitalasset.canton.util.ReassignmentTag.{Source, Target}
import com.digitalasset.canton.version.HashingSchemeVersion
import com.digitalasset.canton.version.HashingSchemeVersion.V1
import com.digitalasset.daml.lf.data.Time
import io.grpc.StatusRuntimeException
import scalaz.syntax.tag.*

import java.time.{Duration, Instant}
import scala.util.Try

class SubmitRequestValidator(
    commandsValidator: CommandsValidator
) {
  import FieldValidator.*
  def validate(
      req: SubmitRequest,
      currentLedgerTime: Instant,
      currentUtcTime: Instant,
      maxDeduplicationDuration: Duration,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, submission.SubmitRequest] =
    for {
      commands <- requirePresence(req.commands, "commands")
      validatedCommands <- commandsValidator.validateCommands(
        commands,
        currentLedgerTime,
        currentUtcTime,
        maxDeduplicationDuration,
      )
    } yield submission.SubmitRequest(validatedCommands)

  def validatePrepare(
      req: PrepareSubmissionRequest,
      currentLedgerTime: Instant,
      currentUtcTime: Instant,
      maxDeduplicationDuration: Duration,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, submission.SubmitRequest] =
    for {
      validatedCommands <- commandsValidator.validatePrepareRequest(
        req,
        currentLedgerTime,
        currentUtcTime,
        maxDeduplicationDuration,
      )
    } yield submission.SubmitRequest(validatedCommands)

  private def validateSignatureFormat(
      formatP: InteractiveSignatureFormat,
      fieldName: String,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, SignatureFormat] =
    formatP match {
      case InteractiveSignatureFormat.SIGNATURE_FORMAT_RAW => Right(SignatureFormat.Raw)
      case other =>
        Left(invalidField(fieldName, message = s"Signature format $other not supported"))
    }

  private def validateSigningAlgorithmSpec(
      signingAlgorithmSpecP: iss.SigningAlgorithmSpec,
      fieldName: String,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, SigningAlgorithmSpec] =
    signingAlgorithmSpecP match {
      case iss.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_ED25519 =>
        Right(SigningAlgorithmSpec.Ed25519)
      case iss.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_EC_DSA_SHA_256 =>
        Right(SigningAlgorithmSpec.EcDsaSha256)
      case iss.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_EC_DSA_SHA_384 =>
        Right(SigningAlgorithmSpec.EcDsaSha384)
      case other =>
        Left(invalidField(fieldName, message = s"Signature format $other not supported"))
    }

  private def validateSignature(
      issSignatureP: iss.Signature,
      fieldName: String,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, Signature] = {
    val InteractiveSignature(formatP, signatureP, signedByP, signingAlgorithmSpecP) =
      issSignatureP
    for {
      format <- validateSignatureFormat(formatP, "format")
      signature = signatureP
      signedBy <- Fingerprint
        .fromProtoPrimitive(signedByP)
        .leftMap(err => invalidField(fieldName = fieldName, message = err.message))
      signingAlgorithmSpec <- validateSigningAlgorithmSpec(signingAlgorithmSpecP, fieldName)
    } yield Signature.fromExternalSigning(format, signature, signedBy, signingAlgorithmSpec)
  }

  private def validatePartySignatures(
      proto: PartySignatures
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, Map[TopologyPartyId, Seq[Signature]]] =
    proto.signatures
      .traverse { case SinglePartySignatures(partyP, signaturesP) =>
        for {
          partyId <- requireTopologyPartyIdField(partyP, "SinglePartySignatures.party")
          signatures <- signaturesP.traverse(s =>
            validateSignature(s, "SinglePartySignatures.signature")
          )
        } yield partyId -> signatures
      }
      .map(_.foldLeft(Map.empty[TopologyPartyId, Seq[Signature]]) { case (m, (p, s)) =>
        m.updatedWith(p) {
          case None => Some(s)
          // This covers the test case where a client submits multiple SinglePartySignatures
          // objects for a single party (the more usual use case would be to submit all signatures in one go)
          case Some(existing) => Some((s.toSet ++ existing.toSet).toSeq)
        }
      })

  def validateExecute(
      req: iss.ExecuteSubmissionRequest,
      currentLedgerTime: Instant,
      submissionIdGenerator: SubmissionIdGenerator,
      maxDeduplicationDuration: Duration,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, ExecuteRequest] = {
    val iss.ExecuteSubmissionRequest(
      preparedTransactionP,
      partySignaturesOP,
      deduplicationPeriodP,
      minLedgerTime,
      submissionIdP,
      applicationIdP,
      hashingSchemeVersionP,
    ) = req
    for {
      submissionId <- validateSubmissionId(submissionIdP)
        .map(_.map(_.unwrap))
        .map(
          _.getOrElse(submissionIdGenerator.generate())
        )
      applicationId <- requireApplicationId(applicationIdP, "application_id")
      deduplicationPeriod <- commandsValidator.validateExecuteDeduplicationPeriod(
        deduplicationPeriodP,
        maxDeduplicationDuration,
      )
      ledgerEffectiveTimeInstant <- commandsValidator.validateLedgerTime(
        currentLedgerTime,
        minLedgerTime.flatMap(_.time.minLedgerTimeAbs),
        minLedgerTime.flatMap(_.time.minLedgerTimeRel),
      )
      // Backup because we'll only use this LET if the transaction does not already contain one that has been
      // set during its preparation.
      backupLedgerEffectiveTime <- Time.Timestamp
        .fromInstant(ledgerEffectiveTimeInstant)
        .leftMap(err =>
          RequestValidationErrors.InvalidArgument
            .Reject(s"Invalid signature argument: $err")
            .asGrpcError
        )
      preparedTransaction <- preparedTransactionP.toRight(
        RequestValidationErrors.MissingField
          .Reject("prepared_transaction")
          .asGrpcError
      )
      partySignaturesP <- requirePresence(partySignaturesOP, "parties_signatures")
      partySignatures <- validatePartySignatures(partySignaturesP)
      version <- validateHashingSchemeVersion(hashingSchemeVersionP).leftMap(_.asGrpcError)
      domainIdString <- requirePresence(
        preparedTransactionP.flatMap(_.metadata.map(_.domainId)),
        "domain_id",
      )
      domainId <- validateDomainId(domainIdString).leftMap(_.asGrpcError)
    } yield {
      ExecuteRequest(
        applicationId,
        submissionId,
        deduplicationPeriod,
        backupLedgerEffectiveTime,
        partySignatures,
        preparedTransaction,
        version,
        domainId,
      )
    }
  }

  private def validateDomainId(string: String)(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[DamlError, DomainId] =
    DomainId
      .fromString(string)
      .leftMap(err =>
        RequestValidationErrors.InvalidField
          .Reject("domain_id", err)
      )

  private def validateHashingSchemeVersion(protoVersion: iss.HashingSchemeVersion)(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[DamlError, HashingSchemeVersion] = protoVersion match {
    case iss.HashingSchemeVersion.HASHING_SCHEME_VERSION_V1 => Right(V1)
    case iss.HashingSchemeVersion.HASHING_SCHEME_VERSION_UNSPECIFIED =>
      Left(
        RequestValidationErrors.InvalidField
          .Reject("hashing_scheme_version", "Unspecified version")
      )
    case iss.HashingSchemeVersion.Unrecognized(unrecognizedValue) =>
      Left(
        RequestValidationErrors.InvalidField
          .Reject("hashing_scheme_version", s"Unrecognized version $unrecognizedValue")
      )
  }

  def validateReassignment(
      req: SubmitReassignmentRequest
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, submission.SubmitReassignmentRequest] =
    for {
      reassignmentCommand <- requirePresence(req.reassignmentCommand, "reassignment_command")
      submitter <- requirePartyField(reassignmentCommand.submitter, "submitter")
      applicationId <- requireApplicationId(reassignmentCommand.applicationId, "application_id")
      commandId <- requireCommandId(reassignmentCommand.commandId, "command_id")
      submissionId <- requireSubmissionId(reassignmentCommand.submissionId, "submission_id")
      workflowId <- validateOptional(Some(reassignmentCommand.workflowId).filter(_.nonEmpty))(
        requireWorkflowId(_, "workflow_id")
      )
      reassignmentCommand <- reassignmentCommand.command match {
        case ReassignmentCommand.Command.Empty =>
          Left(ValidationErrors.missingField("command"))
        case assignCommand: ReassignmentCommand.Command.AssignCommand =>
          for {
            sourceDomainId <- requireDomainId(assignCommand.value.source, "source")
            targetDomainId <- requireDomainId(assignCommand.value.target, "target")
            longUnassignId <- Try(assignCommand.value.unassignId.toLong).toEither.left.map(_ =>
              ValidationErrors.invalidField("unassign_id", "Invalid unassign ID")
            )
            timestampUnassignId <- Time.Timestamp
              .fromLong(longUnassignId)
              .left
              .map(_ => ValidationErrors.invalidField("unassign_id", "Invalid unassign ID"))
          } yield Left(
            submission.AssignCommand(
              sourceDomainId = Source(sourceDomainId),
              targetDomainId = Target(targetDomainId),
              unassignId = timestampUnassignId,
            )
          )
        case unassignCommand: ReassignmentCommand.Command.UnassignCommand =>
          for {
            sourceDomainId <- requireDomainId(unassignCommand.value.source, "source")
            targetDomainId <- requireDomainId(unassignCommand.value.target, "target")
            cid <- requireContractId(unassignCommand.value.contractId, "contract_id")
          } yield Right(
            submission.UnassignCommand(
              sourceDomainId = Source(sourceDomainId),
              targetDomainId = Target(targetDomainId),
              contractId = cid,
            )
          )
      }
    } yield submission.SubmitReassignmentRequest(
      submitter = submitter,
      applicationId = applicationId,
      commandId = commandId,
      submissionId = submissionId,
      workflowId = workflowId,
      reassignmentCommand = reassignmentCommand,
    )
}
