// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetTotalAmuletBalanceResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';

const useTotalAmuletBalance = (): UseQueryResult<GetTotalAmuletBalanceResponse> => {
  const scanClient = useScanClient();
  const latestRoundQuery = useGetRoundOfLatestData();
  const latestRoundNumber = latestRoundQuery.data?.round;

  return useQuery({
    queryKey: ['scan-api', 'getTotalAmuletBalance', latestRoundNumber],
    queryFn: async () => scanClient.getTotalAmuletBalance(latestRoundNumber!),
    enabled: latestRoundNumber !== undefined, // include round 0 as valid,
  });
};

export default useTotalAmuletBalance;
