// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.commands

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand.{
  DefaultUnboundedTimeout,
  TimeoutType,
}
import com.digitalasset.canton.admin.api.client.data.*
import com.digitalasset.canton.admin.api.client.data.topology.*
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.{Fingerprint, Hash}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc.BaseQuery
import com.digitalasset.canton.topology.admin.grpc.TopologyStore.Domain
import com.digitalasset.canton.topology.admin.v30
import com.digitalasset.canton.topology.admin.v30.*
import com.digitalasset.canton.topology.admin.v30.AuthorizeRequest.Type.{Proposal, TransactionHash}
import com.digitalasset.canton.topology.admin.v30.IdentityInitializationServiceGrpc.IdentityInitializationServiceStub
import com.digitalasset.canton.topology.admin.v30.TopologyAggregationServiceGrpc.TopologyAggregationServiceStub
import com.digitalasset.canton.topology.admin.v30.TopologyManagerReadServiceGrpc.TopologyManagerReadServiceStub
import com.digitalasset.canton.topology.admin.v30.TopologyManagerWriteServiceGrpc.TopologyManagerWriteServiceStub
import com.digitalasset.canton.topology.store.StoredTopologyTransactions
import com.digitalasset.canton.topology.store.StoredTopologyTransactions.GenericStoredTopologyTransactions
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.topology.transaction.{
  SignedTopologyTransaction,
  TopologyChangeOp,
  TopologyMapping,
  TopologyTransaction,
}
import com.digitalasset.canton.version.ProtocolVersionValidation
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp.Timestamp
import io.grpc.Context.CancellableContext
import io.grpc.stub.StreamObserver
import io.grpc.{Context, ManagedChannel}

import java.time.Instant
import scala.concurrent.Future
import scala.reflect.ClassTag

object TopologyAdminCommands {

  object Read {

    abstract class BaseCommand[Req, Res, Ret] extends GrpcAdminCommand[Req, Res, Ret] {
      override type Svc = TopologyManagerReadServiceStub
      override def createService(channel: ManagedChannel): TopologyManagerReadServiceStub =
        v30.TopologyManagerReadServiceGrpc.stub(channel)

      //  command will potentially take a long time
      override def timeoutType: TimeoutType = DefaultUnboundedTimeout

    }

    final case class ListNamespaceDelegation(
        query: BaseQuery,
        filterNamespace: String,
        filterTargetKey: Option[Fingerprint],
    ) extends BaseCommand[
          v30.ListNamespaceDelegationRequest,
          v30.ListNamespaceDelegationResponse,
          Seq[ListNamespaceDelegationResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListNamespaceDelegationRequest] =
        Right(
          new v30.ListNamespaceDelegationRequest(
            baseQuery = Some(query.toProtoV1),
            filterNamespace = filterNamespace,
            filterTargetKeyFingerprint = filterTargetKey.map(_.toProtoPrimitive).getOrElse(""),
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListNamespaceDelegationRequest,
      ): Future[v30.ListNamespaceDelegationResponse] =
        service.listNamespaceDelegation(request)

      override protected def handleResponse(
          response: v30.ListNamespaceDelegationResponse
      ): Either[String, Seq[ListNamespaceDelegationResult]] =
        response.results.traverse(ListNamespaceDelegationResult.fromProtoV30).leftMap(_.toString)
    }

    final case class ListDecentralizedNamespaceDefinition(
        query: BaseQuery,
        filterNamespace: String,
    ) extends BaseCommand[
          v30.ListDecentralizedNamespaceDefinitionRequest,
          v30.ListDecentralizedNamespaceDefinitionResponse,
          Seq[ListDecentralizedNamespaceDefinitionResult],
        ] {

      override protected def createRequest()
          : Either[String, v30.ListDecentralizedNamespaceDefinitionRequest] =
        Right(
          new v30.ListDecentralizedNamespaceDefinitionRequest(
            baseQuery = Some(query.toProtoV1),
            filterNamespace = filterNamespace,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListDecentralizedNamespaceDefinitionRequest,
      ): Future[v30.ListDecentralizedNamespaceDefinitionResponse] =
        service.listDecentralizedNamespaceDefinition(request)

      override protected def handleResponse(
          response: v30.ListDecentralizedNamespaceDefinitionResponse
      ): Either[String, Seq[ListDecentralizedNamespaceDefinitionResult]] =
        response.results
          .traverse(ListDecentralizedNamespaceDefinitionResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class ListIdentifierDelegation(
        query: BaseQuery,
        filterUid: String,
        filterTargetKey: Option[Fingerprint],
    ) extends BaseCommand[
          v30.ListIdentifierDelegationRequest,
          v30.ListIdentifierDelegationResponse,
          Seq[ListIdentifierDelegationResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListIdentifierDelegationRequest] =
        Right(
          new v30.ListIdentifierDelegationRequest(
            baseQuery = Some(query.toProtoV1),
            filterUid = filterUid,
            filterTargetKeyFingerprint = filterTargetKey.map(_.toProtoPrimitive).getOrElse(""),
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListIdentifierDelegationRequest,
      ): Future[v30.ListIdentifierDelegationResponse] =
        service.listIdentifierDelegation(request)

      override protected def handleResponse(
          response: v30.ListIdentifierDelegationResponse
      ): Either[String, Seq[ListIdentifierDelegationResult]] =
        response.results.traverse(ListIdentifierDelegationResult.fromProtoV30).leftMap(_.toString)
    }

    final case class ListOwnerToKeyMapping(
        query: BaseQuery,
        filterKeyOwnerType: Option[MemberCode],
        filterKeyOwnerUid: String,
    ) extends BaseCommand[v30.ListOwnerToKeyMappingRequest, v30.ListOwnerToKeyMappingResponse, Seq[
          ListOwnerToKeyMappingResult
        ]] {

      override protected def createRequest(): Either[String, v30.ListOwnerToKeyMappingRequest] =
        Right(
          new v30.ListOwnerToKeyMappingRequest(
            baseQuery = Some(query.toProtoV1),
            filterKeyOwnerType = filterKeyOwnerType.map(_.toProtoPrimitive).getOrElse(""),
            filterKeyOwnerUid = filterKeyOwnerUid,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListOwnerToKeyMappingRequest,
      ): Future[v30.ListOwnerToKeyMappingResponse] =
        service.listOwnerToKeyMapping(request)

      override protected def handleResponse(
          response: v30.ListOwnerToKeyMappingResponse
      ): Either[String, Seq[ListOwnerToKeyMappingResult]] =
        response.results.traverse(ListOwnerToKeyMappingResult.fromProtoV30).leftMap(_.toString)
    }

    final case class ListPartyToKeyMapping(
        query: BaseQuery,
        filterParty: String,
    ) extends BaseCommand[v30.ListPartyToKeyMappingRequest, v30.ListPartyToKeyMappingResponse, Seq[
          ListPartyToKeyMappingResult
        ]] {

      override protected def createRequest(): Either[String, v30.ListPartyToKeyMappingRequest] =
        Right(
          new v30.ListPartyToKeyMappingRequest(
            baseQuery = Some(query.toProtoV1),
            filterParty = filterParty,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListPartyToKeyMappingRequest,
      ): Future[v30.ListPartyToKeyMappingResponse] =
        service.listPartyToKeyMapping(request)

      override protected def handleResponse(
          response: v30.ListPartyToKeyMappingResponse
      ): Either[String, Seq[ListPartyToKeyMappingResult]] =
        response.results.traverse(ListPartyToKeyMappingResult.fromProtoV30).leftMap(_.toString)
    }

    final case class ListDomainTrustCertificate(
        query: BaseQuery,
        filterUid: String,
    ) extends BaseCommand[
          v30.ListDomainTrustCertificateRequest,
          v30.ListDomainTrustCertificateResponse,
          Seq[ListDomainTrustCertificateResult],
        ] {

      override protected def createRequest()
          : Either[String, v30.ListDomainTrustCertificateRequest] =
        Right(
          new v30.ListDomainTrustCertificateRequest(
            baseQuery = Some(query.toProtoV1),
            filterUid = filterUid,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListDomainTrustCertificateRequest,
      ): Future[v30.ListDomainTrustCertificateResponse] =
        service.listDomainTrustCertificate(request)

      override protected def handleResponse(
          response: v30.ListDomainTrustCertificateResponse
      ): Either[String, Seq[ListDomainTrustCertificateResult]] =
        response.results.traverse(ListDomainTrustCertificateResult.fromProtoV30).leftMap(_.toString)
    }

    final case class ListParticipantDomainPermission(
        query: BaseQuery,
        filterUid: String,
    ) extends BaseCommand[
          v30.ListParticipantDomainPermissionRequest,
          v30.ListParticipantDomainPermissionResponse,
          Seq[ListParticipantDomainPermissionResult],
        ] {

      override protected def createRequest()
          : Either[String, v30.ListParticipantDomainPermissionRequest] =
        Right(
          new v30.ListParticipantDomainPermissionRequest(
            baseQuery = Some(query.toProtoV1),
            filterUid = filterUid,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListParticipantDomainPermissionRequest,
      ): Future[v30.ListParticipantDomainPermissionResponse] =
        service.listParticipantDomainPermission(request)

      override protected def handleResponse(
          response: v30.ListParticipantDomainPermissionResponse
      ): Either[String, Seq[ListParticipantDomainPermissionResult]] =
        response.results
          .traverse(ListParticipantDomainPermissionResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class ListPartyHostingLimits(
        query: BaseQuery,
        filterUid: String,
    ) extends BaseCommand[
          v30.ListPartyHostingLimitsRequest,
          v30.ListPartyHostingLimitsResponse,
          Seq[ListPartyHostingLimitsResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListPartyHostingLimitsRequest] =
        Right(
          new v30.ListPartyHostingLimitsRequest(
            baseQuery = Some(query.toProtoV1),
            filterUid = filterUid,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListPartyHostingLimitsRequest,
      ): Future[v30.ListPartyHostingLimitsResponse] =
        service.listPartyHostingLimits(request)

      override protected def handleResponse(
          response: v30.ListPartyHostingLimitsResponse
      ): Either[String, Seq[ListPartyHostingLimitsResult]] =
        response.results
          .traverse(ListPartyHostingLimitsResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class ListVettedPackages(
        query: BaseQuery,
        filterParticipant: String,
    ) extends BaseCommand[
          v30.ListVettedPackagesRequest,
          v30.ListVettedPackagesResponse,
          Seq[ListVettedPackagesResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListVettedPackagesRequest] =
        Right(
          new v30.ListVettedPackagesRequest(
            baseQuery = Some(query.toProtoV1),
            filterParticipant = filterParticipant,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListVettedPackagesRequest,
      ): Future[v30.ListVettedPackagesResponse] =
        service.listVettedPackages(request)

      override protected def handleResponse(
          response: v30.ListVettedPackagesResponse
      ): Either[String, Seq[ListVettedPackagesResult]] =
        response.results
          .traverse(ListVettedPackagesResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class ListPartyToParticipant(
        query: BaseQuery,
        filterParty: String,
        filterParticipant: String,
    ) extends BaseCommand[
          v30.ListPartyToParticipantRequest,
          v30.ListPartyToParticipantResponse,
          Seq[ListPartyToParticipantResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListPartyToParticipantRequest] =
        Right(
          new v30.ListPartyToParticipantRequest(
            baseQuery = Some(query.toProtoV1),
            filterParty = filterParty,
            filterParticipant = filterParticipant,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListPartyToParticipantRequest,
      ): Future[v30.ListPartyToParticipantResponse] =
        service.listPartyToParticipant(request)

      override protected def handleResponse(
          response: v30.ListPartyToParticipantResponse
      ): Either[String, Seq[ListPartyToParticipantResult]] =
        response.results
          .traverse(ListPartyToParticipantResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class DomainParametersState(
        query: BaseQuery,
        filterDomain: String,
    ) extends BaseCommand[
          v30.ListDomainParametersStateRequest,
          v30.ListDomainParametersStateResponse,
          Seq[ListDomainParametersStateResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListDomainParametersStateRequest] =
        Right(
          new v30.ListDomainParametersStateRequest(
            baseQuery = Some(query.toProtoV1),
            filterDomain = filterDomain,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListDomainParametersStateRequest,
      ): Future[v30.ListDomainParametersStateResponse] =
        service.listDomainParametersState(request)

      override protected def handleResponse(
          response: v30.ListDomainParametersStateResponse
      ): Either[String, Seq[ListDomainParametersStateResult]] =
        response.results
          .traverse(ListDomainParametersStateResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class MediatorDomainState(
        query: BaseQuery,
        filterDomain: String,
    ) extends BaseCommand[
          v30.ListMediatorDomainStateRequest,
          v30.ListMediatorDomainStateResponse,
          Seq[ListMediatorDomainStateResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListMediatorDomainStateRequest] =
        Right(
          v30.ListMediatorDomainStateRequest(
            baseQuery = Some(query.toProtoV1),
            filterDomain = filterDomain,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListMediatorDomainStateRequest,
      ): Future[v30.ListMediatorDomainStateResponse] =
        service.listMediatorDomainState(request)

      override protected def handleResponse(
          response: v30.ListMediatorDomainStateResponse
      ): Either[String, Seq[ListMediatorDomainStateResult]] =
        response.results
          .traverse(ListMediatorDomainStateResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class SequencerDomainState(
        query: BaseQuery,
        filterDomain: String,
    ) extends BaseCommand[
          v30.ListSequencerDomainStateRequest,
          v30.ListSequencerDomainStateResponse,
          Seq[ListSequencerDomainStateResult],
        ] {

      override protected def createRequest(): Either[String, v30.ListSequencerDomainStateRequest] =
        Right(
          new v30.ListSequencerDomainStateRequest(
            baseQuery = Some(query.toProtoV1),
            filterDomain = filterDomain,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListSequencerDomainStateRequest,
      ): Future[v30.ListSequencerDomainStateResponse] =
        service.listSequencerDomainState(request)

      override protected def handleResponse(
          response: v30.ListSequencerDomainStateResponse
      ): Either[String, Seq[ListSequencerDomainStateResult]] =
        response.results
          .traverse(ListSequencerDomainStateResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class PurgeTopologyTransaction(
        query: BaseQuery,
        filterDomain: String,
    ) extends BaseCommand[
          v30.ListPurgeTopologyTransactionRequest,
          v30.ListPurgeTopologyTransactionResponse,
          Seq[ListPurgeTopologyTransactionResult],
        ] {

      override protected def createRequest()
          : Either[String, v30.ListPurgeTopologyTransactionRequest] =
        Right(
          new v30.ListPurgeTopologyTransactionRequest(
            baseQuery = Some(query.toProtoV1),
            filterDomain = filterDomain,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListPurgeTopologyTransactionRequest,
      ): Future[v30.ListPurgeTopologyTransactionResponse] =
        service.listPurgeTopologyTransaction(request)

      override protected def handleResponse(
          response: v30.ListPurgeTopologyTransactionResponse
      ): Either[String, Seq[ListPurgeTopologyTransactionResult]] =
        response.results
          .traverse(ListPurgeTopologyTransactionResult.fromProtoV30)
          .leftMap(_.toString)
    }

    final case class ListStores()
        extends BaseCommand[v30.ListAvailableStoresRequest, v30.ListAvailableStoresResponse, Seq[
          String
        ]] {

      override protected def createRequest(): Either[String, v30.ListAvailableStoresRequest] =
        Right(v30.ListAvailableStoresRequest())

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListAvailableStoresRequest,
      ): Future[v30.ListAvailableStoresResponse] =
        service.listAvailableStores(request)

      override protected def handleResponse(
          response: v30.ListAvailableStoresResponse
      ): Either[String, Seq[String]] =
        Right(response.storeIds)
    }

    final case class ListAll(
        query: BaseQuery,
        excludeMappings: Seq[String],
        filterNamespace: String,
    ) extends BaseCommand[
          v30.ListAllRequest,
          v30.ListAllResponse,
          GenericStoredTopologyTransactions,
        ] {
      override protected def createRequest(): Either[String, v30.ListAllRequest] =
        Right(
          new v30.ListAllRequest(
            baseQuery = Some(query.toProtoV1),
            excludeMappings = excludeMappings,
            filterNamespace = filterNamespace,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ListAllRequest,
      ): Future[v30.ListAllResponse] = service.listAll(request)

      override protected def handleResponse(
          response: v30.ListAllResponse
      ): Either[String, GenericStoredTopologyTransactions] =
        response.result
          .fold[Either[String, GenericStoredTopologyTransactions]](
            Right(StoredTopologyTransactions.empty)
          ) { collection =>
            StoredTopologyTransactions.fromProtoV30(collection).leftMap(_.toString)
          }
    }
    final case class ExportTopologySnapshot(
        query: BaseQuery,
        excludeMappings: Seq[String],
        filterNamespace: String,
    ) extends BaseCommand[
          v30.ExportTopologySnapshotRequest,
          v30.ExportTopologySnapshotResponse,
          ByteString,
        ] {
      override protected def createRequest(): Either[String, v30.ExportTopologySnapshotRequest] =
        Right(
          new v30.ExportTopologySnapshotRequest(
            baseQuery = Some(query.toProtoV1),
            excludeMappings = excludeMappings,
            filterNamespace = filterNamespace,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.ExportTopologySnapshotRequest,
      ): Future[v30.ExportTopologySnapshotResponse] = service.exportTopologySnapshot(request)

      override protected def handleResponse(
          response: v30.ExportTopologySnapshotResponse
      ): Either[String, ByteString] =
        Right(response.result)
    }

    final case class GenesisState(
        observer: StreamObserver[GenesisStateResponse],
        filterDomainStore: Option[String],
        timestamp: Option[CantonTimestamp],
    ) extends BaseCommand[
          v30.GenesisStateRequest,
          CancellableContext,
          CancellableContext,
        ] {
      override protected def createRequest(): Either[String, v30.GenesisStateRequest] = {
        val domainStore = filterDomainStore.traverse(DomainId.fromString)
        domainStore.flatMap(domainId =>
          Right(
            v30.GenesisStateRequest(
              domainId.map(Domain.apply).map(_.toProto),
              timestamp.map(_.toProtoTimestamp),
            )
          )
        )
      }

      override protected def submitRequest(
          service: TopologyManagerReadServiceStub,
          request: v30.GenesisStateRequest,
      ): Future[CancellableContext] = {
        val context = Context.current().withCancellation()
        context.run(() => service.genesisState(request, observer))
        Future.successful(context)
      }

      override protected def handleResponse(
          response: CancellableContext
      ): Either[String, CancellableContext] =
        Right(response)

      override def timeoutType: TimeoutType = DefaultUnboundedTimeout
    }
  }

  object Aggregation {

    abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
      override type Svc = TopologyAggregationServiceStub

      override def createService(channel: ManagedChannel): TopologyAggregationServiceStub =
        v30.TopologyAggregationServiceGrpc.stub(channel)
    }

    final case class ListParties(
        filterDomain: String,
        filterParty: String,
        filterParticipant: String,
        asOf: Option[Instant],
        limit: PositiveInt,
    ) extends BaseCommand[v30.ListPartiesRequest, v30.ListPartiesResponse, Seq[
          ListPartiesResult
        ]] {

      override protected def createRequest(): Either[String, v30.ListPartiesRequest] =
        Right(
          v30.ListPartiesRequest(
            filterDomain = filterDomain,
            filterParty = filterParty,
            filterParticipant = filterParticipant,
            asOf = asOf.map(ts => Timestamp(ts.getEpochSecond)),
            limit = limit.value,
          )
        )

      override protected def submitRequest(
          service: TopologyAggregationServiceStub,
          request: v30.ListPartiesRequest,
      ): Future[v30.ListPartiesResponse] =
        service.listParties(request)

      override protected def handleResponse(
          response: v30.ListPartiesResponse
      ): Either[String, Seq[ListPartiesResult]] =
        response.results.traverse(ListPartiesResult.fromProtoV30).leftMap(_.toString)

      //  command will potentially take a long time
      override def timeoutType: TimeoutType = DefaultUnboundedTimeout

    }

    final case class ListKeyOwners(
        filterDomain: String,
        filterKeyOwnerType: Option[MemberCode],
        filterKeyOwnerUid: String,
        asOf: Option[Instant],
        limit: PositiveInt,
    ) extends BaseCommand[v30.ListKeyOwnersRequest, v30.ListKeyOwnersResponse, Seq[
          ListKeyOwnersResult
        ]] {

      override protected def createRequest(): Either[String, v30.ListKeyOwnersRequest] =
        Right(
          v30.ListKeyOwnersRequest(
            filterDomain = filterDomain,
            filterKeyOwnerType = filterKeyOwnerType.map(_.toProtoPrimitive).getOrElse(""),
            filterKeyOwnerUid = filterKeyOwnerUid,
            asOf = asOf.map(ts => Timestamp(ts.getEpochSecond)),
            limit = limit.value,
          )
        )

      override protected def submitRequest(
          service: TopologyAggregationServiceStub,
          request: v30.ListKeyOwnersRequest,
      ): Future[v30.ListKeyOwnersResponse] =
        service.listKeyOwners(request)

      override protected def handleResponse(
          response: v30.ListKeyOwnersResponse
      ): Either[String, Seq[ListKeyOwnersResult]] =
        response.results.traverse(ListKeyOwnersResult.fromProtoV30).leftMap(_.toString)

      //  command will potentially take a long time
      override def timeoutType: TimeoutType = DefaultUnboundedTimeout

    }
  }

  object Write {
    abstract class BaseWriteCommand[Req, Res, Ret] extends GrpcAdminCommand[Req, Res, Ret] {
      override type Svc = TopologyManagerWriteServiceStub

      override def createService(channel: ManagedChannel): TopologyManagerWriteServiceStub =
        v30.TopologyManagerWriteServiceGrpc.stub(channel)

      //  command will potentially take a long time
      override def timeoutType: TimeoutType = DefaultUnboundedTimeout
    }

    final case class AddTransactions(
        transactions: Seq[GenericSignedTopologyTransaction],
        store: String,
        forceChanges: ForceFlags,
    ) extends BaseWriteCommand[AddTransactionsRequest, AddTransactionsResponse, Unit] {
      override protected def createRequest(): Either[String, AddTransactionsRequest] =
        Right(
          AddTransactionsRequest(
            transactions.map(_.toProtoV30),
            forceChanges = forceChanges.toProtoV30,
            store,
          )
        )
      override protected def submitRequest(
          service: TopologyManagerWriteServiceStub,
          request: AddTransactionsRequest,
      ): Future[AddTransactionsResponse] = service.addTransactions(request)
      override protected def handleResponse(
          response: AddTransactionsResponse
      ): Either[String, Unit] =
        Either.unit
    }
    final case class ImportTopologySnapshot(
        topologySnapshot: ByteString,
        store: String,
    ) extends BaseWriteCommand[
          ImportTopologySnapshotRequest,
          ImportTopologySnapshotResponse,
          Unit,
        ] {
      override protected def createRequest(): Either[String, ImportTopologySnapshotRequest] =
        Right(
          ImportTopologySnapshotRequest(
            topologySnapshot,
            store,
          )
        )
      override protected def submitRequest(
          service: TopologyManagerWriteServiceStub,
          request: ImportTopologySnapshotRequest,
      ): Future[ImportTopologySnapshotResponse] = service.importTopologySnapshot(request)
      override protected def handleResponse(
          response: ImportTopologySnapshotResponse
      ): Either[String, Unit] = Either.unit
    }

    final case class SignTransactions(
        transactions: Seq[GenericSignedTopologyTransaction],
        store: String,
        signedBy: Seq[Fingerprint],
        forceFlags: ForceFlags,
    ) extends BaseWriteCommand[SignTransactionsRequest, SignTransactionsResponse, Seq[
          GenericSignedTopologyTransaction
        ]] {
      override protected def createRequest(): Either[String, SignTransactionsRequest] =
        Right(
          SignTransactionsRequest(
            transactions.map(_.toProtoV30),
            signedBy.map(_.toProtoPrimitive),
            store,
            forceFlags.toProtoV30,
          )
        )

      override protected def submitRequest(
          service: TopologyManagerWriteServiceStub,
          request: SignTransactionsRequest,
      ): Future[SignTransactionsResponse] = service.signTransactions(request)

      override protected def handleResponse(
          response: SignTransactionsResponse
      ): Either[String, Seq[GenericSignedTopologyTransaction]] =
        response.transactions
          .traverse(tx =>
            SignedTopologyTransaction.fromProtoV30(ProtocolVersionValidation.NoValidation, tx)
          )
          .leftMap(_.message)
    }

    final case class GenerateTransactions(
        proposals: Seq[GenerateTransactions.Proposal]
    ) extends BaseWriteCommand[
          GenerateTransactionsRequest,
          GenerateTransactionsResponse,
          Seq[TopologyTransaction[TopologyChangeOp, TopologyMapping]],
        ] {

      override protected def createRequest(): Either[String, GenerateTransactionsRequest] =
        Right(GenerateTransactionsRequest(proposals.map(_.toGenerateTransactionProposal)))
      override protected def submitRequest(
          service: TopologyManagerWriteServiceStub,
          request: GenerateTransactionsRequest,
      ): Future[GenerateTransactionsResponse] = service.generateTransactions(request)

      override protected def handleResponse(
          response: GenerateTransactionsResponse
      ): Either[String, Seq[TopologyTransaction[TopologyChangeOp, TopologyMapping]]] =
        response.generatedTransactions
          .traverse { generatedTransaction =>
            val serializedTransaction = generatedTransaction.serializedTransaction
            val serializedHash = generatedTransaction.transactionHash
            for {
              parsedTopologyTransaction <-
                TopologyTransaction
                  .fromByteString(ProtocolVersionValidation.NoValidation)(serializedTransaction)
                  .leftMap(_.message)
              // We don't really need the hash from the response here because we can re-build it from the deserialized
              // topology transaction. But users of the API without access to this code wouldn't be able to do that,
              // which is why the hash is returned by the API. Let's still verify that they match here.
              parsedHash <- Hash.fromByteString(serializedHash).leftMap(_.message)
              _ = Either.cond(
                parsedTopologyTransaction.hash.hash.compare(parsedHash) == 0,
                (),
                s"Response hash did not match transaction hash",
              )
            } yield parsedTopologyTransaction
          }
    }
    object GenerateTransactions {
      final case class Proposal(
          mapping: TopologyMapping,
          store: String,
          change: TopologyChangeOp = TopologyChangeOp.Replace,
          serial: Option[PositiveInt] = None,
      ) {
        def toGenerateTransactionProposal: GenerateTransactionsRequest.Proposal =
          GenerateTransactionsRequest.Proposal(
            change.toProto,
            serial.map(_.value).getOrElse(0),
            Some(mapping.toProtoV30),
            store,
          )
      }
    }

    final case class Propose[M <: TopologyMapping: ClassTag](
        mapping: Either[String, M],
        signedBy: Seq[Fingerprint],
        change: TopologyChangeOp,
        serial: Option[PositiveInt],
        mustFullyAuthorize: Boolean,
        forceChanges: ForceFlags,
        store: String,
    ) extends BaseWriteCommand[
          AuthorizeRequest,
          AuthorizeResponse,
          SignedTopologyTransaction[TopologyChangeOp, M],
        ] {

      override protected def createRequest(): Either[String, AuthorizeRequest] = mapping.map(m =>
        AuthorizeRequest(
          Proposal(
            AuthorizeRequest.Proposal(
              change.toProto,
              serial.map(_.value).getOrElse(0),
              Some(m.toProtoV30),
            )
          ),
          mustFullyAuthorize = mustFullyAuthorize,
          forceChanges = forceChanges.toProtoV30,
          signedBy = signedBy.map(_.toProtoPrimitive),
          store,
        )
      )
      override protected def submitRequest(
          service: TopologyManagerWriteServiceStub,
          request: AuthorizeRequest,
      ): Future[AuthorizeResponse] = service.authorize(request)

      override protected def handleResponse(
          response: AuthorizeResponse
      ): Either[String, SignedTopologyTransaction[TopologyChangeOp, M]] = response.transaction
        .toRight("no transaction in response")
        .flatMap(
          SignedTopologyTransaction
            .fromProtoV30(ProtocolVersionValidation.NoValidation, _)
            .leftMap(_.message)
            .flatMap(tx =>
              tx.selectMapping[M]
                .toRight(
                  s"Expected mapping ${ClassTag[M].getClass.getSimpleName}, but received: ${tx.mapping.getClass.getSimpleName}"
                )
            )
        )
    }
    object Propose {
      def apply[M <: TopologyMapping: ClassTag](
          mapping: M,
          signedBy: Seq[Fingerprint],
          store: String,
          serial: Option[PositiveInt] = None,
          change: TopologyChangeOp = TopologyChangeOp.Replace,
          mustFullyAuthorize: Boolean = false,
          forceChanges: ForceFlags = ForceFlags.none,
      ): Propose[M] =
        Propose(Right(mapping), signedBy, change, serial, mustFullyAuthorize, forceChanges, store)

    }

    final case class Authorize[M <: TopologyMapping: ClassTag](
        transactionHash: String,
        mustFullyAuthorize: Boolean,
        signedBy: Seq[Fingerprint],
        store: String,
    ) extends BaseWriteCommand[
          AuthorizeRequest,
          AuthorizeResponse,
          SignedTopologyTransaction[TopologyChangeOp, M],
        ] {

      override protected def createRequest(): Either[String, AuthorizeRequest] = Right(
        AuthorizeRequest(
          TransactionHash(transactionHash),
          mustFullyAuthorize = mustFullyAuthorize,
          forceChanges = Seq.empty,
          signedBy = signedBy.map(_.toProtoPrimitive),
          store = store,
        )
      )

      override protected def submitRequest(
          service: TopologyManagerWriteServiceStub,
          request: AuthorizeRequest,
      ): Future[AuthorizeResponse] = service.authorize(request)

      override protected def handleResponse(
          response: AuthorizeResponse
      ): Either[String, SignedTopologyTransaction[TopologyChangeOp, M]] = response.transaction
        .toRight("no transaction in response")
        .flatMap(
          SignedTopologyTransaction
            .fromProtoV30(ProtocolVersionValidation.NoValidation, _)
            .leftMap(_.message)
            .flatMap(tx =>
              tx.selectMapping[M]
                .toRight(
                  s"Expected mapping ${ClassTag[M].getClass.getSimpleName}, but received: ${tx.mapping.getClass.getSimpleName}"
                )
            )
        )
    }
  }

  object Init {

    abstract class BaseInitializationService[Req, Resp, Res]
        extends GrpcAdminCommand[Req, Resp, Res] {
      override type Svc = IdentityInitializationServiceStub
      override def createService(channel: ManagedChannel): IdentityInitializationServiceStub =
        v30.IdentityInitializationServiceGrpc.stub(channel)
    }

    final case class InitId(identifier: String)
        extends BaseInitializationService[v30.InitIdRequest, v30.InitIdResponse, Unit] {

      override protected def createRequest(): Either[String, v30.InitIdRequest] =
        Right(v30.InitIdRequest(identifier))

      override protected def submitRequest(
          service: IdentityInitializationServiceStub,
          request: v30.InitIdRequest,
      ): Future[v30.InitIdResponse] =
        service.initId(request)

      override protected def handleResponse(response: v30.InitIdResponse): Either[String, Unit] =
        Either.unit
    }

    final case class GetId()
        extends BaseInitializationService[v30.GetIdRequest, v30.GetIdResponse, GetIdResult] {
      override protected def createRequest(): Either[String, v30.GetIdRequest] =
        Right(v30.GetIdRequest())

      override protected def submitRequest(
          service: IdentityInitializationServiceStub,
          request: v30.GetIdRequest,
      ): Future[v30.GetIdResponse] =
        service.getId(request)

      override protected def handleResponse(
          response: v30.GetIdResponse
      ): Either[String, GetIdResult] =
        if (response.uniqueIdentifier.nonEmpty)
          UniqueIdentifier
            .fromProtoPrimitive_(response.uniqueIdentifier)
            .leftMap(_.message)
            .map(id => GetIdResult(response.initialized, Some(id)))
        else
          Right(GetIdResult(response.initialized, None))
    }

    final case class GetIdResult(
        initialized: Boolean,
        uniqueIdentifier: Option[UniqueIdentifier],
    )
  }
}
