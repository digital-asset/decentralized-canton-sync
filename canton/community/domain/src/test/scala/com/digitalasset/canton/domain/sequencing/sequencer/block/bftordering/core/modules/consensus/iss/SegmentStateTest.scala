// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss

import com.daml.metrics.api.MetricsContext
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashPurpose}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.metrics.SequencerMetrics
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.BftSequencerBaseTest
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.EpochState.Segment
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.SegmentState.computeLeaderOfView
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.EpochStore.Block
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.fakeSequencerId
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.NumberIdentifiers.{
  BlockNumber,
  EpochNumber,
  ViewNumber,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.SignedMessage
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.availability.OrderingBlock
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.bfttime.CanonicalCommitSet
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.ordering.iss.{
  BlockMetadata,
  EpochInfo,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.ordering.{
  CommitCertificate,
  PrepareCertificate,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.topology.Membership
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.{
  Commit,
  NewView,
  PbftNestedViewChangeTimeout,
  PbftNormalTimeout,
  PbftSignedNetworkMessage,
  PbftTimeout,
  PrePrepare,
  Prepare,
  ViewChange,
}
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.SequencerId
import com.google.protobuf.ByteString
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.event.Level.INFO

import java.time.Duration

import PbftBlockState.{
  CompletedBlock,
  SendPbftMessage,
  StorePrePrepare,
  StorePrepares,
  StoreViewChangeMessage,
  ViewChangeCompleted,
  ViewChangeStartNestedTimer,
}
import BftSequencerBaseTest.FakeSigner

class SegmentStateTest extends AsyncWordSpec with BftSequencerBaseTest {

  import SegmentStateTest.*

  private val clock = new SimClock(loggerFactory = loggerFactory)

  private implicit val mc: MetricsContext = MetricsContext.Empty
  private val metrics = SequencerMetrics.noop(getClass.getSimpleName).bftOrdering

  "SegmentState" should {
    "complete once all blocks are committed" in {
      val segmentState = createSegmentState()

      slotNumbers.foreach { blockNumber =>
        segmentState.isSegmentComplete shouldBe false
        segmentState.isBlockComplete(blockNumber) shouldBe false

        val prePrepare = createPrePrepare(blockNumber, ViewNumber.First, myId)
        segmentState.processEvent(PbftSignedNetworkMessage(prePrepare))
        otherPeers.foreach { peer =>
          val prepare = createPrepare(blockNumber, ViewNumber.First, peer, prePrepare.message.hash)
          val commit = createCommit(blockNumber, ViewNumber.First, peer, prePrepare.message.hash)
          segmentState.processEvent(PbftSignedNetworkMessage(prepare))
          segmentState.processEvent(PbftSignedNetworkMessage(commit))
        }
        segmentState.isBlockComplete(blockNumber) shouldBe false
        segmentState.confirmCompleteBlockStored(blockNumber, ViewNumber.First)
        segmentState.isBlockComplete(blockNumber) shouldBe true
      }

      segmentState.isSegmentComplete shouldBe true
    }

    "compute round-robin leader rotation" in {

      val originalLeader = myId
      val originalLeaderIndex = allPeers.indexOf(originalLeader)
      val farIntoTheFutureLeader = allPeers((allPeers.indexOf(originalLeader) + 50) % allPeers.size)

      forAll(
        Table(
          ("View", "Expected Leader"),
          (ViewNumber.First, myId),
          (ViewNumber.First + 1, otherPeer1),
          (ViewNumber.First + 2, otherPeer2),
          (ViewNumber.First + 3, otherPeer3),
          (ViewNumber.First + 4, myId),
          (ViewNumber.First + 50, farIntoTheFutureLeader),
        )
      ) { case (n, expectedLeader) =>
        computeLeaderOfView(ViewNumber(n), originalLeaderIndex, allPeers) shouldBe expectedLeader
      }
    }

    "start view change via local timeout" in {
      val originalLeader = otherPeer1
      val segment = createSegmentState(originalLeader)
      segment.isViewChangeInProgress shouldBe false

      // Create and receive "local" timeout event
      val nextView = ViewNumber(ViewNumber.First + 1)
      val results = assertNoLogs(segment.processEvent(createTimeout(ViewNumber.First)))
      val message = ViewChange
        .create(
          blockMetaData,
          allPeers.indexOf(originalLeader),
          nextView,
          clock.now,
          Seq.empty,
          myId,
        )
        .fakeSign
      results should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          message,
          store = Some(StoreViewChangeMessage(message)),
        )
      )
      segment.isViewChangeInProgress shouldBe true
    }

    "start view change via local timeout with only one node" in {
      val originalLeader = myId
      val segment =
        createSegmentState(originalLeader, otherPeers = Seq.empty, eligibleLeaders = Seq(myId))

      segment.isViewChangeInProgress shouldBe false

      // Create and receive "local" timeout event
      val nextView = ViewNumber(ViewNumber.First + 1)
      val results = assertNoLogs(segment.processEvent(createTimeout(ViewNumber.First)))
      val viewChangeMessage = ViewChange
        .create(
          blockMetaData,
          0,
          nextView,
          clock.now,
          Seq.empty,
          myId,
        )
        .fakeSign
      val newViewMessage = NewView
        .create(
          blockMetaData,
          0,
          nextView,
          clock.now,
          Seq(
            viewChangeMessage
          ),
          Vector(
            createBottomPrePrepare(slotNumbers(0), nextView, myId),
            createBottomPrePrepare(slotNumbers(1), nextView, myId),
            createBottomPrePrepare(slotNumbers(2), nextView, myId),
          ),
          from = myId,
        )
        .fakeSign
      results.take(4) should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          viewChangeMessage,
          store = Some(StoreViewChangeMessage(viewChangeMessage)),
        ),
        ViewChangeStartNestedTimer(blockMetaData, nextView),
        SendPbftMessage(
          newViewMessage,
          store = Some(StoreViewChangeMessage(newViewMessage)),
        ),
        ViewChangeCompleted(blockMetaData, nextView, store = None),
      )

      // Below, each 3-tuple represents: SendPbft(Prepare), SendPbft(Commit), CompletedBlock
      results.drop(4) should matchPattern {
        case Seq(
              _: SendPbftMessage[?],
              _: SendPbftMessage[?],
              _: CompletedBlock,
              _: SendPbftMessage[?],
              _: SendPbftMessage[?],
              _: CompletedBlock,
              _: SendPbftMessage[?],
              _: SendPbftMessage[?],
              _: CompletedBlock,
            ) =>
      }

      segment.isViewChangeInProgress shouldBe false
    }

    "not duplicate completed block results after re-completing a block as part of a view change" in {
      val originalLeader = myId
      val segment =
        createSegmentState(originalLeader, otherPeers = Seq.empty, eligibleLeaders = Seq(myId))

      segment.isViewChangeInProgress shouldBe false

      val view1 = ViewNumber.First
      val block1 = slotNumbers.head1
      val pp1 = createPrePrepare(block1, view1, from = myId)
      val myCommit = createCommit(block1, view1, from = myId, pp1.message.hash)

      // Complete slotNumbers.head1 in view; in a 1-node network, processing the PrePrepare produces
      // local Prepare, local Commit, and CompletedBlock processResults
      val _ = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(pp1)))
      segment.confirmCompleteBlockStored(block1, view1)
      segment.isBlockComplete(block1) shouldBe true

      // Create and receive "local" timeout event
      val nextView = ViewNumber(ViewNumber.First + 1)
      val results = assertNoLogs(segment.processEvent(createTimeout(ViewNumber.First)))
      val commitCertificate = CommitCertificate(pp1, Seq(myCommit))
      val viewChangeMessage = ViewChange
        .create(
          blockMetaData,
          0,
          nextView,
          clock.now,
          Seq(commitCertificate),
          myId,
        )
        .fakeSign
      val newViewMessage = NewView
        .create(
          blockMetaData,
          0,
          nextView,
          clock.now,
          Seq(
            viewChangeMessage
          ),
          Vector(
            pp1,
            createBottomPrePrepare(slotNumbers(1), nextView, myId),
            createBottomPrePrepare(slotNumbers(2), nextView, myId),
          ),
          from = myId,
        )
        .fakeSign
      results.take(4) should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          viewChangeMessage,
          store = Some(StoreViewChangeMessage(viewChangeMessage)),
        ),
        ViewChangeStartNestedTimer(blockMetaData, nextView),
        SendPbftMessage(
          newViewMessage,
          store = Some(StoreViewChangeMessage(newViewMessage)),
        ),
        ViewChangeCompleted(blockMetaData, nextView, store = None),
      )

      // block1 is completing again now as part of a view change, but since it has completed previously
      // we do not return its completion again
      val newCompletedBlocks = results.drop(4).collect { case c: CompletedBlock =>
        c.prePrepare.message.blockMetadata
      }
      newCompletedBlocks should have size 2
      newCompletedBlocks should contain theSameElementsAs Seq(
        BlockMetadata.mk(EpochNumber.First, slotNumbers(1)),
        BlockMetadata.mk(EpochNumber.First, slotNumbers(2)),
      )

      segment.isViewChangeInProgress shouldBe false
    }

    "a block with a commit certificate in a valid new-view should immediately be considered completed" in {
      val originalLeader = myId

      val segment = createSegmentState(originalLeader)
      val segmentIndex = allPeers.indexOf(originalLeader)

      segment.isViewChangeInProgress shouldBe false

      val view1 = ViewNumber.First
      val view2 = ViewNumber(ViewNumber.First + 1)
      val view3 = ViewNumber(view2 + 1)
      val block1 = slotNumbers.head1

      val pp1 = createPrePrepare(block1, view1, from = otherPeer1)
      val commits = Seq(
        createCommit(block1, view1, from = otherPeer1, pp1.message.hash),
        createCommit(block1, view1, from = otherPeer2, pp1.message.hash),
        createCommit(block1, view1, from = otherPeer3, pp1.message.hash),
      )
      val commitCertificate = CommitCertificate(pp1, commits)

      val bottomPP1 = createBottomPrePrepare(slotNumbers(1), view2, otherPeer1)
      val bottomPP2 = createBottomPrePrepare(slotNumbers(2), view2, otherPeer1)

      // this view change message from another peer contains a commit certificate for block1
      // which is included in the new-view message
      val viewChangeMessage = ViewChange
        .create(
          blockMetaData,
          segmentIndex,
          view2,
          clock.now,
          Seq(commitCertificate),
          otherPeer1,
        )
        .fakeSign
      val newViewMessage = NewView
        .create(
          blockMetaData,
          segmentIndex,
          view2,
          clock.now,
          Seq(
            viewChangeMessage
          ),
          Vector(
            pp1,
            bottomPP1,
            bottomPP2,
          ),
          from = otherPeer1,
        )
        .fakeSign

      val results = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(newViewMessage)))
      segment.isViewChangeInProgress shouldBe false
      segment.currentView shouldBe view2

      results should contain theSameElementsInOrderAs List(
        ViewChangeCompleted(
          blockMetaData,
          view2,
          store = Some(StoreViewChangeMessage(newViewMessage)),
        ),
        // as a result of processing the new-view that contains a commit certificate for block1,
        // block one gets completed as see in the presence of the result below and the absence of prepares for it
        CompletedBlock(pp1, commits, view2),
        SendPbftMessage(
          createPrepare(slotNumbers(1), view2, myId, bottomPP1.message.hash),
          store = Some(StorePrePrepare(bottomPP1)),
        ),
        SendPbftMessage(
          createPrepare(slotNumbers(2), view2, myId, bottomPP2.message.hash),
          store = Some(StorePrePrepare(bottomPP2)),
        ),
      )

      // in the case of a timeout in the new view, this node will propagate the commit certificate
      val results2 = segment.processEvent(createTimeout(view2))
      val myViewChangeMessage = ViewChange
        .create(
          blockMetaData,
          segmentIndex,
          view3,
          clock.now,
          Seq(commitCertificate),
          myId,
        )
        .fakeSign
      results2 should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          myViewChangeMessage,
          store = Some(StoreViewChangeMessage(myViewChangeMessage)),
        )
      )
      segment.isViewChangeInProgress shouldBe true
      segment.currentView shouldBe view3

    }

    "start view change via receiving f+1 view change messages from peers" in {
      val originalLeader = otherPeer1
      val segment =
        createSegmentState(originalLeader)

      segment.isViewChangeInProgress shouldBe false

      // Create and receive f+1 view change messages from peers
      val nextView = ViewNumber(ViewNumber.First + 1)
      def viewChangeMessage(from: SequencerId = otherPeer2) =
        createViewChange(nextView, from = from, originalLeader, Seq.empty)
      val results1 =
        assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage())))
      results1 shouldBe empty

      val results2 = assertNoLogs(
        segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage(from = otherPeer3)))
      )
      val viewChangeMessage2 = ViewChange
        .create(
          blockMetaData,
          allPeers.indexOf(originalLeader),
          nextView,
          clock.now,
          Seq.empty,
          from = myId,
        )
        .fakeSign
      results2 should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          viewChangeMessage2,
          store = Some(StoreViewChangeMessage(viewChangeMessage2)),
        ),
        ViewChangeStartNestedTimer(blockMetaData, nextView),
      )

      // The local node should be waiting for a NewView from the next leader (otherPeer2)
      segment.isViewChangeInProgress shouldBe true
    }

    "complete view change when serving as the next leader" in {
      val originalLeader = otherPeer3
      val originalSegmentIndex = allPeers.indexOf(originalLeader)
      val segment = createSegmentState(originalLeader)

      val nextView = ViewNumber(ViewNumber.First + 1)
      val botBlock0 = createBottomPrePrepare(slotNumbers(0), nextView, myId)
      val botBlock1 = createBottomPrePrepare(slotNumbers(1), nextView, myId)
      val botBlock2 = createBottomPrePrepare(slotNumbers(2), nextView, myId)

      def viewChangeMessage(from: SequencerId = otherPeer1) =
        createViewChange(nextView, from = from, originalLeader, Seq.empty)

      segment.isViewChangeInProgress shouldBe false

      // Create and receive f+1 view change messages from peers
      assertNoLogs(
        segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage()))
      ) shouldBe empty

      val results = assertNoLogs(
        segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage(from = otherPeer2)))
      )
      val myViewChangeMessage = ViewChange
        .create(
          blockMetaData,
          originalSegmentIndex,
          nextView,
          clock.now,
          Seq.empty,
          from = myId,
        )
        .fakeSign
      val newViewMessage = NewView
        .create(
          blockMetaData,
          originalSegmentIndex,
          nextView,
          clock.now,
          Seq(
            viewChangeMessage(),
            viewChangeMessage(from = otherPeer2),
            viewChangeMessage(from = myId),
          ),
          Vector(botBlock0, botBlock1, botBlock2),
          from = myId,
        )
        .fakeSign
      results should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          myViewChangeMessage,
          store = Some(StoreViewChangeMessage(myViewChangeMessage)),
        ),
        ViewChangeStartNestedTimer(blockMetaData, nextView),
        SendPbftMessage(
          newViewMessage,
          store = Some(StoreViewChangeMessage(newViewMessage)),
        ),
        ViewChangeCompleted(blockMetaData, nextView, store = None),
        SendPbftMessage(
          createPrepare(slotNumbers(0), nextView, myId, botBlock0.message.hash),
          store = None,
        ),
        SendPbftMessage(
          createPrepare(slotNumbers(1), nextView, myId, botBlock1.message.hash),
          store = None,
        ),
        SendPbftMessage(
          createPrepare(slotNumbers(2), nextView, myId, botBlock2.message.hash),
          store = None,
        ),
      )

      // The local node should have completed the view change by sending a NewView after collecting enough ViewChanges
      // In this case, f+1 ViewChange from peers plus the locally created ViewChange
      segment.isViewChangeInProgress shouldBe false
      segment.currentView shouldBe nextView
    }

    "jump ahead by multiple views when receiving f+1 view change messages" in {
      val originalLeader = myId
      val segment = createSegmentState(originalLeader)

      // Jump ahead from view=ViewNumber.First to view=5 once f+1 view change messages are received
      // This also verifies we can go from no active view change to active one w/ jump ahead
      val futureView = ViewNumber(5L)
      def viewChangeMessage5(from: SequencerId = otherPeer1) =
        createViewChange(futureView, from = from, originalLeader, Seq.empty)
      val results1 =
        assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage5())))
      results1 shouldBe empty
      segment.isViewChangeInProgress shouldBe false

      val results2 =
        assertNoLogs(
          segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage5(from = otherPeer2)))
        )
      val myViewChangeMessage5 = ViewChange
        .create(
          blockMetaData,
          allPeers.indexOf(originalLeader),
          futureView,
          clock.now,
          Seq.empty,
          from = myId,
        )
        .fakeSign
      results2 should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          myViewChangeMessage5,
          store = Some(StoreViewChangeMessage(myViewChangeMessage5)),
        ),
        ViewChangeStartNestedTimer(blockMetaData, futureView),
      )
      segment.currentView shouldBe futureView
      segment.isViewChangeInProgress shouldBe true
      segment.isSegmentComplete shouldBe false
      segment.isBlockComplete(slotNumbers.head1) shouldBe false

      // Jump ahead from view=5 to even further view=21, while view change to 5 is still in progress and
      // the node receives f+1 view change messages from the future view (21)
      val evenFurtherView = ViewNumber(21L)
      def viewChangeMessage21(from: SequencerId = otherPeer1) =
        createViewChange(evenFurtherView, from = from, originalLeader, Seq.empty)
      val results3 =
        assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage21())))
      results3 shouldBe empty

      val results4 =
        assertNoLogs(
          segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage21(from = otherPeer2)))
        )
      val myViewChangeMessageEvenFurther = ViewChange
        .create(
          blockMetaData,
          allPeers.indexOf(originalLeader),
          evenFurtherView,
          clock.now,
          Seq.empty,
          from = myId,
        )
        .fakeSign
      results4 should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          myViewChangeMessageEvenFurther,
          store = Some(StoreViewChangeMessage(myViewChangeMessageEvenFurther)),
        ),
        ViewChangeStartNestedTimer(blockMetaData, evenFurtherView),
      )
      segment.isViewChangeInProgress shouldBe true
      segment.currentView shouldBe evenFurtherView
      segment.isSegmentComplete shouldBe false
      segment.isBlockComplete(slotNumbers.head1) shouldBe false
    }

    "replay previously early messages once a view change completes" in {
      val originalLeader = myId
      val segment = createSegmentState(originalLeader)

      val nextView = ViewNumber(ViewNumber.First + 1)
      segment.futureQueueSize shouldBe 0

      // Create and receive f+1 view change messages from peers
      def viewChangeMessage(from: SequencerId = otherPeer2) =
        createViewChange(nextView, from = from, originalLeader, Seq.empty)
      assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage())))
      assertNoLogs(
        segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage(from = otherPeer3)))
      )

      // Create new view message for later, but don't process yet
      val ppBottom1 = createBottomPrePrepare(slotNumbers(0), nextView, from = otherPeer1)
      val ppBottom2 = createBottomPrePrepare(slotNumbers(1), nextView, from = otherPeer1)
      val ppBottom3 = createBottomPrePrepare(slotNumbers(2), nextView, from = otherPeer1)
      val newView = createNewView(
        nextView,
        otherPeer1,
        originalLeader,
        Seq(
          viewChangeMessage(from = myId),
          viewChangeMessage(),
          viewChangeMessage(from = otherPeer2),
        ),
        Seq(ppBottom1, ppBottom2, ppBottom3),
      )
      segment.isViewChangeInProgress shouldBe true

      // Simulate receiving early Prepare messages (for nextView) before receiving the new view message
      def prepare1(from: SequencerId = myId) =
        createPrepare(slotNumbers(0), nextView, from = from, ppBottom1.message.hash)
      val prepare2 = createPrepare(slotNumbers(1), nextView, from = myId, ppBottom2.message.hash)
      val prepare3 = createPrepare(slotNumbers(2), nextView, from = myId, ppBottom3.message.hash)
      assertLogs(
        segment.processEvent(PbftSignedNetworkMessage(prepare1(from = otherPeer1))),
        log => {
          log.level shouldBe INFO
          log.message should include("early PbftNormalCaseMessage")
        },
      )
      segment.futureQueueSize shouldBe 1

      assertLogs(
        segment.processEvent(PbftSignedNetworkMessage(prepare1(from = otherPeer2))),
        log => {
          log.level shouldBe INFO
          log.message should include("early PbftNormalCaseMessage")
        },
      )
      segment.futureQueueSize shouldBe 2

      // Simulate receiving an early message from an even more future view
      val prepareFuture =
        createPrepare(slotNumbers(1), nextView + 1, from = otherPeer2, ppBottom2.message.hash)
      assertLogs(
        segment.processEvent(PbftSignedNetworkMessage(prepareFuture)),
        log => {
          log.level shouldBe INFO
          log.message should include("early PbftNormalCaseMessage")
        },
      )
      segment.futureQueueSize shouldBe 3
      segment.isViewChangeInProgress shouldBe true

      // Now, process the new view message. Upon completing the view change, two of the three queued future
      // prepares should also be processed, resulting in the local node sending a Commit for slot 0
      val results = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(newView)))
      results should contain theSameElementsInOrderAs List(
        ViewChangeCompleted(blockMetaData, nextView, store = Some(StoreViewChangeMessage(newView))),
        SendPbftMessage(prepare1(), Some(StorePrePrepare(ppBottom1))),
        SendPbftMessage(
          Commit
            .create(
              blockMetaData,
              nextView,
              prepare1().message.hash,
              prepare1().message.localTimestamp,
              from = myId,
            )
            .fakeSign,
          Some(
            StorePrepares(
              Seq(prepare1(), prepare1(from = otherPeer1), prepare1(from = otherPeer2))
                .sortBy(_.from)
            )
          ),
        ),
        SendPbftMessage(prepare2, Some(StorePrePrepare(ppBottom2))),
        SendPbftMessage(prepare3, Some(StorePrePrepare(ppBottom3))),
      )

      segment.isViewChangeInProgress shouldBe false
      segment.currentView shouldBe nextView
      segment.futureQueueSize shouldBe 1
    }

    "perform nested view change when local timeout expires during view change" in {
      val originalLeader = myId
      val originalLeaderIndex = allPeers.indexOf(originalLeader)
      val segment = createSegmentState(originalLeader)

      val firstView = ViewNumber.First
      val secondView = ViewNumber(firstView + 1)
      val thirdView = ViewNumber(secondView + 1)

      // Initial view change due to local timeout; move from firstView to secondView
      var results = assertNoLogs(segment.processEvent(createTimeout(firstView)))
      val viewChangeFromTimeout = ViewChange
        .create(
          blockMetaData,
          segmentIndex = originalLeaderIndex,
          secondView,
          clock.now,
          Seq.empty,
          myId,
        )
        .fakeSign
      results should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          viewChangeFromTimeout,
          store = Some(StoreViewChangeMessage(viewChangeFromTimeout)),
        )
      )
      segment.isViewChangeInProgress shouldBe true
      segment.currentView shouldBe secondView

      // Simulate nested view change:
      // - process 2f+1 total view change message for secondView
      results = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(
            createViewChange(secondView, otherPeer1, originalLeader, Seq.empty)
          )
        )
      )
      results shouldBe empty
      results = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(
            createViewChange(secondView, otherPeer2, originalLeader, Seq.empty)
          )
        )
      )
      results should contain theSameElementsInOrderAs List(
        ViewChangeStartNestedTimer(blockMetaData, secondView)
      )
      segment.isViewChangeInProgress shouldBe true
      segment.currentView shouldBe secondView

      // - then process local timeout of secondView to move to thirdView
      results = assertNoLogs(segment.processEvent(createTimeout(secondView, nested = true)))
      val viewChangeFromNewTimeout = ViewChange
        .create(
          blockMetaData,
          allPeers.indexOf(originalLeader),
          thirdView,
          clock.now,
          Seq.empty,
          myId,
        )
        .fakeSign
      results should contain theSameElementsInOrderAs List(
        SendPbftMessage(
          viewChangeFromNewTimeout,
          store = Some(StoreViewChangeMessage(viewChangeFromNewTimeout)),
        )
      )
      segment.isViewChangeInProgress shouldBe true
      segment.currentView shouldBe thirdView
      segment.isSegmentComplete shouldBe false
      segment.isBlockComplete(slotNumbers(0)) shouldBe false

      // Verify the latter view change (view = thirdView) can complete
      val thirdViewLeader = computeLeaderOfView(thirdView, originalLeaderIndex, allPeers)
      def viewChangeMessage(from: SequencerId = otherPeer1) =
        createViewChange(thirdView, from = from, originalLeader, Seq.empty)
      val _ = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage())))
      segment.processEvent(PbftSignedNetworkMessage(viewChangeMessage(from = otherPeer2)))
      val bottomBlocks: Seq[SignedMessage[PrePrepare]] = Seq(
        createBottomPrePrepare(slotNumbers(0), thirdView, thirdViewLeader),
        createBottomPrePrepare(slotNumbers(1), thirdView, thirdViewLeader),
        createBottomPrePrepare(slotNumbers(2), thirdView, thirdViewLeader),
      )
      val newView = createNewView(
        thirdView,
        thirdViewLeader,
        originalLeader,
        Seq(
          viewChangeMessage(from = myId),
          viewChangeMessage(),
          viewChangeMessage(from = otherPeer2),
        ),
        bottomBlocks,
      )
      val _ = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(newView)))

      // For each slot, finish receiving prepares and commits, and confirm DB storage to complete block
      slotNumbers.zipWithIndex.foreach { case (blockNumber, idx) =>
        val _ = assertNoLogs(
          segment.processEvent(
            PbftSignedNetworkMessage(
              createPrepare(blockNumber, thirdView, otherPeer1, bottomBlocks(idx).message.hash)
            )
          )
        )
        val _ = assertNoLogs(
          segment.processEvent(
            PbftSignedNetworkMessage(
              createPrepare(blockNumber, thirdView, thirdViewLeader, bottomBlocks(idx).message.hash)
            )
          )
        )
        val _ = assertNoLogs(
          segment.processEvent(
            PbftSignedNetworkMessage(
              createCommit(blockNumber, thirdView, otherPeer1, bottomBlocks(idx).message.hash)
            )
          )
        )
        val _ = assertNoLogs(
          segment
            .processEvent(
              PbftSignedNetworkMessage(
                createCommit(
                  blockNumber,
                  thirdView,
                  thirdViewLeader,
                  bottomBlocks(idx).message.hash,
                )
              )
            )
        )

        segment.confirmCompleteBlockStored(blockNumber, thirdView)
        segment.isBlockComplete(blockNumber) shouldBe true
      }

      segment.isSegmentComplete shouldBe true
      segment.currentView shouldBe thirdView
      segment.isViewChangeInProgress shouldBe false
    }

    "start and complete several simulated view changes" in {
      val originalLeader = myId
      val segment = createSegmentState(originalLeader)

      val view1 = ViewNumber.First
      val view2 = ViewNumber(view1 + 1)
      val view3 = ViewNumber(view2 + 1)

      val block1 = slotNumbers(0)
      val block2 = slotNumbers(1)
      val block3 = slotNumbers(2)

      val pp1 = createPrePrepare(block1, view1, from = myId)
      val ppBottom2 = createBottomPrePrepare(block2, view2, from = otherPeer1)
      val ppBottom3 = createBottomPrePrepare(block3, view2, from = otherPeer1)

      // Complete slotNumbers(0) in view1
      val _ = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(pp1)))
      val _ = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(createPrepare(block1, view1, otherPeer1, pp1.message.hash))
        )
      )
      val _ = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(createPrepare(block1, view1, otherPeer2, pp1.message.hash))
        )
      )
      val _ = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(createCommit(block1, view1, otherPeer1, pp1.message.hash))
        )
      )
      val _ = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(createCommit(block1, view1, otherPeer2, pp1.message.hash))
        )
      )
      // note: local commit from myId is already created as part of processing logic
      segment.confirmCompleteBlockStored(block1, view1)
      segment.isBlockComplete(block1) shouldBe true

      // Simulate a local timeout via PbftTimeout event
      var results = assertNoLogs(segment.processEvent(createTimeout(view1)))
      inside(results) { case Seq(SendPbftMessage(SignedMessage(vc: ViewChange, _), _)) =>
        vc.consensusCerts should have size 1
        vc.consensusCerts.head.prePrepare shouldBe pp1
        inside(vc.consensusCerts.head) { case cc: CommitCertificate =>
          cc.commits should contain theSameElementsInOrderAs Seq(
            createCommit(block1, view1, otherPeer1, pp1.message.hash),
            createCommit(block1, view1, otherPeer2, pp1.message.hash),
            createCommit(block1, view1, myId, pp1.message.hash),
          )
        }
      }
      segment.isViewChangeInProgress shouldBe true
      segment.currentView shouldBe view2
      // Note: we test that isBlockComplete and isSegmentComplete still function even with a view change in progress
      //   for the segment, as this is a likely scenario to occur in practice; the IssConsensus module is unaware of
      //   view changes, but still queries this state to figure out when the epoch completes
      segment.isBlockComplete(block1) shouldBe true
      segment.isSegmentComplete shouldBe false

      // Simulate completing a view change with View Change message(s) and New View message
      def viewChangeMsgForView2(from: SequencerId = otherPeer1) = createViewChange(
        view2,
        from,
        originalLeader,
        Seq(slotNumbers(0) -> view1),
      )
      val _ = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMsgForView2())))
      segment.isViewChangeInProgress shouldBe true
      val _ = assertNoLogs(
        segment.processEvent(PbftSignedNetworkMessage(viewChangeMsgForView2(from = otherPeer2)))
      )
      segment.isViewChangeInProgress shouldBe true
      val _ = assertNoLogs(
        segment
          .processEvent(
            PbftSignedNetworkMessage(
              createNewView(
                view2,
                otherPeer1,
                originalLeader,
                Seq(
                  viewChangeMsgForView2(from = myId),
                  viewChangeMsgForView2(),
                  viewChangeMsgForView2(from = otherPeer2),
                ),
                Seq(pp1, ppBottom2, ppBottom3),
              )
            )
          )
      )

      // Confirm first view change (from view0 to view1) is now complete
      segment.isViewChangeInProgress shouldBe false

      // Next, simulate some progress being made when view1 is active
      // Suppose block1 (slot0) achieves a fresh prepare certificate in view2 (note: myPrepare already processed)
      val _ = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(createPrepare(block1, view2, otherPeer2, pp1.message.hash))
        )
      )
      val _ = assertNoLogs(
        segment.processEvent(
          PbftSignedNetworkMessage(createPrepare(block1, view2, otherPeer3, pp1.message.hash))
        )
      )

      // Suppose block2 (slot1) achieves a prepare certificate in view2 (note: myPrepare already processed)
      val _ =
        assertNoLogs(
          segment.processEvent(
            PbftSignedNetworkMessage(
              createPrepare(block2, view2, otherPeer2, ppBottom2.message.hash)
            )
          )
        )
      val _ =
        assertNoLogs(
          segment.processEvent(
            PbftSignedNetworkMessage(
              createPrepare(block2, view2, otherPeer3, ppBottom2.message.hash)
            )
          )
        )
      segment.isBlockComplete(block2) shouldBe false

      // Simulate next view change via local timeout again, expecting prepareCert for block1 and block2
      results = assertNoLogs(segment.processEvent(createTimeout(view2)))
      inside(results) { case Seq(SendPbftMessage(SignedMessage(vc: ViewChange, _), _)) =>
        vc.consensusCerts should have size 2
        // For block1, we expect PrePrepare from original view1 but Prepares from new view2
        vc.consensusCerts.head.prePrepare shouldBe pp1
        inside(vc.consensusCerts.head) { case pc: PrepareCertificate =>
          pc.prepares should contain theSameElementsInOrderAs Seq(
            createPrepare(block1, view2, otherPeer2, pp1.message.hash),
            createPrepare(block1, view2, otherPeer3, pp1.message.hash),
            createPrepare(block1, view2, myId, pp1.message.hash),
          )
        }
        vc.consensusCerts(1).prePrepare shouldBe ppBottom2
        inside(vc.consensusCerts(1)) { case pc: PrepareCertificate =>
          pc.prepares should contain theSameElementsInOrderAs Seq(
            createPrepare(block2, view2, otherPeer2, ppBottom2.message.hash),
            createPrepare(block2, view2, otherPeer3, ppBottom2.message.hash),
            createPrepare(block2, view2, myId, ppBottom2.message.hash),
          )
        }
      }
      segment.isViewChangeInProgress shouldBe true
      segment.currentView shouldBe view3

      // Simulate completing a view change with View Change message(s) and New View message
      def viewChangeMsgForView3(from: SequencerId = otherPeer1) = createViewChange(
        view3,
        from,
        originalLeader,
        Seq(slotNumbers(0) -> view1, slotNumbers(1) -> view2),
      )
      val _ = assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMsgForView3())))
      results =
        segment.processEvent(PbftSignedNetworkMessage(viewChangeMsgForView3(from = otherPeer2)))
      results should contain theSameElementsInOrderAs List(
        ViewChangeStartNestedTimer(BlockMetadata(epochInfo.number, block1), view3)
      )
      segment.isViewChangeInProgress shouldBe true
      segment.processEvent(
        PbftSignedNetworkMessage(
          createNewView(
            view3,
            otherPeer2,
            originalLeader,
            Seq(
              viewChangeMsgForView3(from = myId),
              viewChangeMsgForView3(),
              viewChangeMsgForView3(from = otherPeer2),
            ),
            Seq(pp1, ppBottom2, createBottomPrePrepare(block3, view3, otherPeer2)),
          )
        )
      )
      segment.isViewChangeInProgress shouldBe false
      segment.isSegmentComplete shouldBe false
    }

    "immediately move to higher view when getting new-view message alone" in {
      val originalLeader = myId
      val segment = createSegmentState(originalLeader)

      val view1 = ViewNumber.First
      val view2 = ViewNumber(view1 + 1)

      def viewChangeMsgForView2(from: SequencerId = otherPeer1) = createViewChange(
        view2,
        from,
        originalLeader,
        Seq(slotNumbers(0) -> view1),
      )

      val pp1 = createPrePrepare(slotNumbers(0), view1, from = myId)
      val ppBottom2 = createBottomPrePrepare(slotNumbers(1), view2, from = otherPeer1)
      val ppBottom3 = createBottomPrePrepare(slotNumbers(2), view2, from = otherPeer1)

      // getting new-view message without having gotten any view-change messages
      // although this could indeed happen in real life, it is more commonly seen during rehydration of messages
      val results = assertNoLogs(
        segment
          .processEvent(
            PbftSignedNetworkMessage(
              createNewView(
                view2,
                otherPeer1,
                originalLeader,
                Seq(
                  viewChangeMsgForView2(from = myId),
                  viewChangeMsgForView2(),
                  viewChangeMsgForView2(from = otherPeer2),
                ),
                Seq(pp1, ppBottom2, ppBottom3),
              )
            )
          )
      )

      results should matchPattern {
        case List(
              _: ViewChangeCompleted,
              SendPbftMessage(SignedMessage(_: Prepare, _), _),
              SendPbftMessage(SignedMessage(_: Prepare, _), _),
              SendPbftMessage(SignedMessage(_: Prepare, _), _),
            ) =>
      }

      segment.isViewChangeInProgress shouldBe false
      segment.currentView shouldBe view2

    }

    "use rehydrated prepares when rehydrating new view instead of creating new ones" in {
      val originalLeader = myId
      val segment = createSegmentState(originalLeader)

      val view1 = ViewNumber.First
      val view2 = ViewNumber(view1 + 1)

      def viewChangeMsgForView2(from: SequencerId = otherPeer1) = createViewChange(
        view2,
        from,
        originalLeader,
        Seq(slotNumbers(0) -> view1),
      )

      val pp1 = createPrePrepare(slotNumbers(0), view1, from = myId)
      val ppBottom2 = createBottomPrePrepare(slotNumbers(1), view2, from = otherPeer1)
      val ppBottom3 = createBottomPrePrepare(slotNumbers(2), view2, from = otherPeer1)

      val prepare1 = createPrepare(slotNumbers(0), view2, from = myId, pp1.message.hash)
      val prepare2 = createPrepare(slotNumbers(1), view2, from = myId, ppBottom2.message.hash)
      val prepare3 = createPrepare(slotNumbers(2), view2, from = myId, ppBottom3.message.hash)

      segment.processEvent(PbftSignedNetworkMessage(prepare1)) shouldBe empty
      segment.processEvent(PbftSignedNetworkMessage(prepare2)) shouldBe empty
      segment.processEvent(PbftSignedNetworkMessage(prepare3)) shouldBe empty

      // getting new-view message without having gotten any view-change messages
      // although this could indeed happen in real life, it is more commonly seen during rehydration of messages
      clock.advance(Duration.ofMinutes(5))
      val results = assertNoLogs(
        segment
          .processEvent(
            PbftSignedNetworkMessage(
              createNewView(
                view2,
                otherPeer1,
                originalLeader,
                Seq(
                  viewChangeMsgForView2(from = myId),
                  viewChangeMsgForView2(),
                  viewChangeMsgForView2(from = otherPeer2),
                ),
                Seq(pp1, ppBottom2, ppBottom3),
              )
            )
          )
      )

      inside(results) {
        case List(
              _: ViewChangeCompleted,
              SendPbftMessage(p1 @ SignedMessage(_: Prepare, _), _),
              SendPbftMessage(p2 @ SignedMessage(_: Prepare, _), _),
              SendPbftMessage(p3 @ SignedMessage(_: Prepare, _), _),
            ) =>
          // because we advanced the clock time, this would fail if new prepares were being created, with different timestamps
          p1 shouldBe prepare1
          p2 shouldBe prepare2
          p3 shouldBe prepare3
      }

      segment.isViewChangeInProgress shouldBe false
      segment.currentView shouldBe view2
    }

    "immediately start view change when rehydrating view-change message from self" in {
      val originalLeader = otherPeer1
      val segment = createSegmentState(originalLeader)

      val view1 = ViewNumber.First
      val view2 = ViewNumber(view1 + 1)

      val viewChangeMsgForView2 = createViewChange(
        view2,
        from = myId,
        originalLeader,
        Seq(slotNumbers(0) -> view1),
      )

      clock.advance(Duration.ofMinutes(5))
      val results =
        assertNoLogs(segment.processEvent(PbftSignedNetworkMessage(viewChangeMsgForView2)))
      results shouldBe Seq(SendPbftMessage(viewChangeMsgForView2, None))

      segment.currentView shouldBe view2
      segment.isViewChangeInProgress shouldBe true
    }
  }

  private def createSegmentState(
      originalLeader: SequencerId = myId,
      otherPeers: Seq[SequencerId] = otherPeers,
      eligibleLeaders: Seq[SequencerId] = allPeers,
      completedBlocks: Seq[Block] = Seq.empty,
  ) = new SegmentState(
    Segment(originalLeader, slotNumbers),
    epochInfo.number,
    Membership(myId, otherPeers.toSet),
    eligibleLeaders = eligibleLeaders,
    clock,
    completedBlocks,
    abort = fail(_),
    metrics,
    loggerFactory,
  )
}

object SegmentStateTest {
  import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.BftSequencerBaseTest.FakeSigner

  val myId: SequencerId = fakeSequencerId("self")
  val otherPeers: IndexedSeq[SequencerId] = (1 to 3).map { index =>
    fakeSequencerId(
      s"peer$index"
    )
  }
  val fullMembership: Membership =
    Membership(myId, otherPeers.toSet)
  val otherPeer1: SequencerId = otherPeers.head
  val otherPeer2: SequencerId = otherPeers(1)
  val otherPeer3: SequencerId = otherPeers(2)
  val allPeers: IndexedSeq[SequencerId] = (myId +: otherPeers).sorted
  val segmentIndex: Int = allPeers.indexOf(myId)
  val slotNumbers: NonEmpty[Seq[BlockNumber]] =
    NonEmpty.mk(Seq, BlockNumber.First, 4L, 8L).map(BlockNumber(_))
  val epochInfo: EpochInfo =
    EpochInfo.mk(number = EpochNumber.First, startBlockNumber = BlockNumber.First, length = 12)
  val blockMetaData: BlockMetadata = BlockMetadata.mk(epochInfo.number, BlockNumber.First)
  val canonicalCommitSet: CanonicalCommitSet = CanonicalCommitSet(
    Set(
      createCommit(
        epochInfo.startBlockNumber,
        ViewNumber.First,
        myId,
        Hash.digest(HashPurpose.BftOrderingPbftBlock, ByteString.EMPTY, HashAlgorithm.Sha256),
      )
    )
  )

  def createBottomPrePrepare(
      blockNumber: BlockNumber,
      view: Long,
      from: SequencerId,
  ): SignedMessage[PrePrepare] =
    PrePrepare
      .create(
        BlockMetadata(epochInfo.number, blockNumber),
        ViewNumber(view),
        CantonTimestamp.Epoch,
        OrderingBlock(Seq.empty),
        CanonicalCommitSet(Set.empty),
        from,
      )
      .fakeSign

  def createPrePrepare(
      blockNumber: Long,
      view: Long,
      from: SequencerId,
  ): SignedMessage[PrePrepare] =
    PrePrepare
      .create(
        BlockMetadata.mk(epochInfo.number, blockNumber),
        ViewNumber(view),
        CantonTimestamp.Epoch,
        OrderingBlock(Seq.empty),
        canonicalCommitSet,
        from,
      )
      .fakeSign

  def createPrepare(
      blockNumber: Long,
      view: Long,
      from: SequencerId,
      hash: Hash,
  ): SignedMessage[Prepare] =
    Prepare
      .create(
        BlockMetadata.mk(epochInfo.number, blockNumber),
        ViewNumber(view),
        hash,
        CantonTimestamp.Epoch,
        from,
      )
      .fakeSign

  def createCommit(
      blockNumber: Long,
      view: Long,
      from: SequencerId,
      hash: Hash,
  ): SignedMessage[Commit] =
    Commit
      .create(
        BlockMetadata.mk(epochInfo.number, blockNumber),
        ViewNumber(view),
        hash,
        CantonTimestamp.Epoch,
        from,
      )
      .fakeSign

  def createPrepareCertificate(
      blockNumber: Long,
      view: Long,
      prePrepareSource: SequencerId,
  ): PrepareCertificate = {
    val prePrepare = createPrePrepare(blockNumber, view, prePrepareSource)
    val prePrepareHash = prePrepare.message.hash
    val prepareSeq = allPeers
      .filterNot(_ == prePrepareSource)
      .take(fullMembership.orderingTopology.strongQuorum)
      .map(peer => createPrepare(blockNumber, view, peer, prePrepareHash))
    PrepareCertificate(prePrepare, prepareSeq)
  }

  def createViewChange(
      viewNumber: Long,
      from: SequencerId,
      originalLeader: SequencerId,
      slotsAndViewNumbers: Seq[(Long, Long)],
  ): SignedMessage[ViewChange] = {
    val originalLeaderIndex = allPeers.indexOf(originalLeader)
    val certs = slotsAndViewNumbers.map { case (slot, view) =>
      createPrepareCertificate(
        slot,
        view,
        computeLeaderOfView(ViewNumber(view), originalLeaderIndex, allPeers),
      )
    }
    ViewChange
      .create(
        blockMetaData,
        originalLeaderIndex,
        ViewNumber(viewNumber),
        CantonTimestamp.Epoch,
        consensusCerts = certs,
        from,
      )
      .fakeSign
  }

  def createViewChangeSet(
      viewNumber: Long,
      originalLeader: SequencerId,
      viewNumbersPerPeer: Seq[Map[Long, Long]],
  ): IndexedSeq[SignedMessage[ViewChange]] =
    allPeers.zip(viewNumbersPerPeer).map { case (peer, slotToViewNumber) =>
      val slotsAndViewNumbers = slotToViewNumber.toList
      createViewChange(viewNumber, peer, originalLeader, slotsAndViewNumbers)
    }

  def createNewView(
      viewNumber: Long,
      from: SequencerId,
      originalLeader: SequencerId,
      vcSet: Seq[SignedMessage[ViewChange]],
      ppSet: Seq[SignedMessage[PrePrepare]],
  ): SignedMessage[NewView] =
    NewView
      .create(
        blockMetaData,
        segmentIndex = allPeers.indexOf(originalLeader),
        viewNumber = ViewNumber(viewNumber),
        localTimestamp = CantonTimestamp.Epoch,
        viewChanges = vcSet,
        prePrepares = ppSet,
        from,
      )
      .fakeSign

  def createTimeout(view: Long, nested: Boolean = false): PbftTimeout = {
    val viewNumber = ViewNumber(view)
    if (!nested)
      PbftNormalTimeout(BlockMetadata(epochInfo.number, slotNumbers.head1), viewNumber)
    else
      PbftNestedViewChangeTimeout(
        BlockMetadata(epochInfo.number, slotNumbers.head1),
        viewNumber,
      )
  }
}
