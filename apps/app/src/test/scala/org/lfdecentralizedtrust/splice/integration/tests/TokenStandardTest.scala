package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2
import com.daml.ledger.api.v2.value.Identifier
import com.daml.ledger.javaapi
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{SpliceTestConsoleEnvironment}
import org.lfdecentralizedtrust.splice.util.{FactoryChoiceWithDisclosures, TokenStandardMetadata}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait TokenStandardTest extends ExternallySignedPartyTestUtil {

  val emptyExtraArgs =
    org.lfdecentralizedtrust.splice.util.ChoiceContextWithDisclosures.emptyExtraArgs

  def executeTransferViaTokenStandard(
      participant: ParticipantClientReference,
      sender: RichPartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
      advanceTimeBeforeExecute: Option[Duration] = None,
      description: Option[String] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    actAndCheck(
      s"Instructing transfer of $amount amulets via token standard from $sender to $receiver", {
        val (factoryChoice, senderHoldingCids) = transferViaTokenStandardCommands(
          participant,
          sender.partyId,
          receiver,
          amount,
          expectedKind,
          timeToLife,
          description,
        )
        participant.ledger_api_extensions.commands
          .submitJavaExternalOrLocal(
            sender,
            commands = factoryChoice.factoryId
              .exerciseTransferFactory_Transfer(factoryChoice.args)
              .commands
              .asScala
              .toSeq,
            disclosedContracts = factoryChoice.disclosedContracts,
            expectedTimeBounds = expectedTimeBounds,
            advanceTimeBeforeExecute = advanceTimeBeforeExecute,
          )
        senderHoldingCids.head
      },
    )(
      // Prepared tx execution does not wait for the tx being committed.
      // We thus wait here, as otherwise multiple transfer commands will use the same input holdings.
      "Wait until we see at least one of the input holdings being consumed",
      trackingHoldingCid => {
        participant.ledger_api.event_query
          .by_contract_id(trackingHoldingCid.contractId, requestingParties = Seq(sender.partyId))
          .archived should not be empty
      },
    )
  }

  def transferViaTokenStandardCommands(
      participant: ParticipantClientReference,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      description: Option[String] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): (
      FactoryChoiceWithDisclosures[
        transferinstructionv1.TransferFactory.ContractId,
        transferinstructionv1.TransferFactory_Transfer,
      ],
      Seq[holdingv1.Holding.ContractId],
  ) = {
    val now = env.environment.clock.now.toInstant
    def unlocked(optLock: java.util.Optional[holdingv1.Lock]): Boolean =
      optLock.toScala.forall(lock => lock.expiresAt.toScala.exists(t => t.isBefore(now)))
    val senderHoldingCids = listHoldings(participant, sender)
      .collect {
        case (holdingCid, holding)
            if holding.owner == sender.toProtoPrimitive && unlocked(holding.lock) =>
          new holdingv1.Holding.ContractId(holdingCid.contractId)
      }
    val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
      dsoParty.toProtoPrimitive,
      new transferinstructionv1.Transfer(
        sender.toProtoPrimitive,
        receiver.toProtoPrimitive,
        amount.bigDecimal,
        new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        now,
        now.plus(timeToLife),
        senderHoldingCids.asJava,
        new metadatav1.Metadata(
          description.toList.map(TokenStandardMetadata.reasonMetaKey -> _).toMap.asJava
        ),
      ),
      emptyExtraArgs,
    )
    val scanResponse @ (factory, kind) = sv1ScanBackend.getTransferFactory(choiceArgs)
    aliceValidatorBackend.scanProxy.getTransferFactory(choiceArgs) shouldBe scanResponse
    kind shouldBe expectedKind
    (factory, senderHoldingCids)
  }

  def listHoldings(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        holdingv1.Holding.ContractId,
        holdingv1.HoldingView,
    )
  ] = {
    val holdings =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces = Seq(holdingv1.Holding.TEMPLATE_ID).map(templateId =>
          Identifier(
            templateId.getPackageId,
            templateId.getModuleName,
            templateId.getEntityName,
          )
        ),
      )
    holdings.map(instr => {
      val instrViewRaw = (instr.event.interfaceViews.head.viewValue
        .getOrElse(throw new RuntimeException("expected an interface view to be present")))
      val instrView = holdingv1.HoldingView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new holdingv1.Holding.ContractId(instr.contractId), instrView)
    })
  }

  def listTransferInstructions(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        transferinstructionv1.TransferInstruction.ContractId,
        transferinstructionv1.TransferInstructionView,
    )
  ] = {
    val instructions =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces =
          Seq(transferinstructionv1.TransferInstruction.TEMPLATE_ID).map(templateId =>
            Identifier(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
      )
    instructions.map(instr => {
      val instrViewRaw = (instr.event.interfaceViews.head.viewValue
        .getOrElse(throw new RuntimeException("expected an interface view to be present")))
      val instrView = transferinstructionv1.TransferInstructionView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new TransferInstruction.ContractId(instr.contractId), instrView)
    })
  }

  def acceptTransferInstruction(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionAcceptContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionAcceptContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Accept(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def rejectTransferInstruction(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionRejectContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionRejectContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Reject(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def withdrawTransferInstruction(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionWithdrawContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionWithdrawContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Withdraw(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }
}
