// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{WalletTestUtil}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.metrics.MetricValue

class WalletMetricsTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "Unlocked coin metrics" should {
    "update when tapping coin" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val before = aliceValidatorBackend.metrics
        .get(
          "cn.wallet.unlocked-amulet-balance",
          Map("owner" -> aliceUserParty.toString),
        )
        .select[MetricValue.DoublePoint]
        .value
        .value
      before shouldBe 0
      actAndCheck(
        "alice taps 100 coin",
        aliceWalletClient.tap(100.0),
      )(
        "metrics update to reflect new coins",
        _ => {
          val after = aliceValidatorBackend.metrics
            .get("cn.wallet.unlocked-amulet-balance", Map("owner" -> aliceUserParty.toString))
            .select[MetricValue.DoublePoint]
            .value
            .value
          val tapCC = walletUsdToAmulet(100.0)
          BigDecimal(after) should beWithin(tapCC - smallAmount, tapCC)
        },
      )
    }
  }

  "User wallet automation metrics" should {
    "are labeled with the party ID of the wallet user" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100.0)
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 50.0)

      // Polling triggers
      // Not exhaustive, only triggers configured to run (e.g., no WalletSweepTrigger)
      Seq(
        "AmuletMetricsTrigger",
        "CollectRewardsAndMergeAmuletsTrigger",
        "DomainIngestionService",
        "ExpireAcceptedTransferOfferTrigger",
        "ExpireAppPaymentRequestsTrigger",
        "ExpireBuyTrafficRequestsTrigger",
        "ExpireTransferOfferTrigger",
        "SubscriptionReadyForPaymentTrigger",
        "TransferFollowTrigger",
      ).foreach(triggerName =>
        clue(s"$triggerName should report polling iterations correctly") {
          aliceValidatorBackend.metrics
            .get(
              "cn.trigger.iterations",
              Map(
                "trigger_name" -> triggerName,
                "party" -> aliceUserParty.toString,
              ),
            )
            .select[MetricValue.LongPoint]
            .value
            // We always do one iteration right after startup
            .value should be > 0L
        }
      )

      // Task-based triggers
      // Not exhaustive, only triggers that are invoked during init and transfers
      Seq(
        "AcceptedTransferOfferTrigger",
        "DomainIngestionService",
      ).foreach(triggerName =>
        clue(s"$triggerName should report task completions correctly") {
          aliceValidatorBackend.metrics
            .get(
              "cn.trigger.completed",
              Map(
                "trigger_name" -> triggerName,
                "party" -> aliceUserParty.toString,
              ),
            )
            .select[MetricValue.LongPoint]
            .value
            .value should be > 0L
        }
      )
    }
  }
}