// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.config

import com.daml.tls.TlsVersion
import com.daml.tls.TlsVersion.TlsVersion
import io.netty.handler.ssl.ClientAuth
import scopt.Read

import java.time.Duration

object Readers {

  implicit val durationRead: Read[Duration] = new Read[Duration] {
    override def arity: Int = 1

    override val reads: String => Duration = Duration.parse
  }

  implicit val clientAuthRead: Read[ClientAuth] = Read.reads {
    case "none" => ClientAuth.NONE
    case "optional" => ClientAuth.OPTIONAL
    case "require" => ClientAuth.REQUIRE
    case _ =>
      throw new InvalidConfigException(s"""Must be one of "none", "optional", or "require".""")
  }

  implicit val tlsVersionRead: Read[TlsVersion] = Read.reads {
    case "1.2" => TlsVersion.V1_2
    case "1.3" => TlsVersion.V1_3
    case _ =>
      throw new InvalidConfigException(s"""Must be one of "1.2" or "1.3".""")
  }

}
