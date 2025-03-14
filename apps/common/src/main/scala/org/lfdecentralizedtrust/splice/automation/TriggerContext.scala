// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.time.Clock

/** Convenience class to capture the shared context required to instantiate triggers in an automation service. */
final case class TriggerContext(
    config: AutomationConfig,
    clock: Clock,
    pollingClock: Clock,
    triggerEnabledSync: TriggerEnabledSynchronization,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
    metricsFactory: LabeledMetricsFactory,
) {
  def timeouts: ProcessingTimeout = retryProvider.timeouts
}
