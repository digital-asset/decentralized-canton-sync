// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Header } from 'common-frontend';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { config } from '../utils/config';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header
          title={config.spliceInstanceNames.amuletName + ' Scan'}
          navLinks={[
            { name: `${config.spliceInstanceNames.amuletName} Activity`, path: '/' },
            { name: 'Network Info', path: '/dso' },
          ]}
        />
      </Container>

      {props.children}
    </Box>
  );
};
export default Layout;
