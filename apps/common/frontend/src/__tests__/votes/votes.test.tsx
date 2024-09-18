// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { QueryClient, UseQueryResult, useQuery, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, fireEvent } from '@testing-library/react';
import { DsoInfo, SvVote, VotesHooks, VotesHooksContext } from 'common-frontend';
import { Contract } from 'common-frontend-utils';
import React from 'react';
import { test, expect, describe } from 'vitest';

import {
  VoteRequest,
  DsoRules_CloseVoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import * as constants from '../mocks/constants';
import { ListVoteRequests } from '../../components';

const queryClient = new QueryClient();
// The linter wants me to add the constants.X in the queryKey,
// which is better than disabling the lint because having a bad query key is BAD
const provider: VotesHooks = {
  isReadOnly: true,
  useDsoInfos(): UseQueryResult<DsoInfo> {
    return useQuery({
      queryKey: ['useDsoInfos', constants.dsoInfo],
      queryFn: async () => constants.dsoInfo,
    });
  },
  useListDsoRulesVoteRequests(): UseQueryResult<Contract<VoteRequest>[]> {
    return useQuery({
      queryKey: ['useListDsoRulesVoteRequests', constants.votedRequest, constants.unvotedRequest],
      queryFn: async () => [constants.votedRequest, constants.unvotedRequest],
    });
  },
  useListVoteRequestResult(
    limit: number,
    actionName: string | undefined,
    requester: string | undefined,
    effectiveFrom: string | undefined,
    effectiveTo: string | undefined,
    executed: boolean | undefined
  ): UseQueryResult<DsoRules_CloseVoteRequestResult[]> {
    return useQuery({
      queryKey: [
        'useListVoteRequestResult',
        effectiveFrom,
        executed,
        constants.rejectedVoteResult,
        constants.plannedVoteResult,
        constants.executedVoteResult,
      ],
      queryFn: async () => {
        if (executed === false) {
          return [constants.rejectedVoteResult];
        } else if (effectiveFrom) {
          return [constants.plannedVoteResult];
        } else {
          return [constants.executedVoteResult];
        }
      },
    });
  },
  useListVotes(contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> {
    return useQuery({
      queryKey: [
        'useListVotes',
        contractIds,
        constants.rejectedVoteResult,
        constants.unvotedRequest,
      ],
      queryFn: async () => {
        console.log(`Called with ${contractIds}`);
        return contractIds.flatMap(cid => {
          return cid === constants.unvotedRequest.contractId
            ? []
            : [constants.myVote(cid, cid === constants.rejectedVoteResult.request.trackingCid)];
        });
      },
    });
  },
  useVoteRequest(contractId: ContractId<VoteRequest>): UseQueryResult<Contract<VoteRequest>> {
    return useQuery({
      queryKey: ['useVoteRequest', contractId, constants.votedRequest, constants.unvotedRequest],
      queryFn: async () =>
        [constants.votedRequest, constants.unvotedRequest].filter(
          req => req.contractId === contractId
        )[0],
    });
  },
};

const TestVotes: React.FC<{ showActionNeeded: boolean }> = ({ showActionNeeded }) => {
  return (
    <QueryClientProvider client={queryClient}>
      <VotesHooksContext.Provider value={provider}>
        <ListVoteRequests showActionNeeded={showActionNeeded} />
      </VotesHooksContext.Provider>
    </QueryClientProvider>
  );
};

// TODO(#14439): these tests should check that the diff in the opened modal (click on the row) are correct
describe('Votes list should', () => {
  test('Show votes requiring action, when that is enabled', async () => {
    render(<TestVotes showActionNeeded />);

    const actionNeeded = await screen.findByText('Action Needed');
    expect(actionNeeded).toBeDefined();
    fireEvent.click(actionNeeded);

    const actionNeededRows = await screen.findAllByText('SRARC_UpdateSvRewardWeight');
    expect(actionNeededRows).toHaveLength(1);
  });

  test('NOT Show votes requiring action, when that is disabled', async () => {
    render(<TestVotes showActionNeeded={false} />);
    expect(screen.queryByText('Action Needed')).toBeNull();
  });

  test('Show votes that are planned', async () => {
    render(<TestVotes showActionNeeded />);

    const planned = await screen.findByText('Planned');
    expect(planned).toBeDefined();
    fireEvent.click(planned);

    const plannedRows = await screen.findAllByText('CRARC_AddFutureAmuletConfigSchedule');
    expect(plannedRows).toHaveLength(1);
  });

  test('Show votes that are executed', async () => {
    render(<TestVotes showActionNeeded />);

    const planned = await screen.findByText('Executed');
    expect(planned).toBeDefined();
    fireEvent.click(planned);

    const plannedRows = await screen.findAllByText('SRARC_UpdateSvRewardWeight');
    expect(plannedRows).toHaveLength(1);
  });

  test('Show votes that are rejected', async () => {
    render(<TestVotes showActionNeeded />);

    const planned = await screen.findByText('Rejected');
    expect(planned).toBeDefined();
    fireEvent.click(planned);

    const plannedRows = await screen.findAllByText('SRARC_UpdateSvRewardWeight');
    expect(plannedRows).toHaveLength(1);
  });
});