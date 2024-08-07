// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

import scala.annotation.unused
import scala.util.control.NoStackTrace

/** Handle an error from a console.
  * We expect this implementation will either throw or exit, hence the [[scala.Nothing]] return type.
  */
trait ConsoleErrorHandler {
  def handleCommandFailure(cause: Option[String] = None): Nothing

  def handleInternalError(): Nothing
}

final class CommandFailure() extends Throwable("Command execution failed.") with NoStackTrace

final class CantonInternalError()
    extends Throwable(
      "Command execution failed due to an internal error. Please file a bug report."
    )
    with NoStackTrace

/** Throws a [[CommandFailure]] or [[CantonInternalError]] when a command fails.
  * The throwables do not have a stacktraces, to avoid noise in the interactive console.
  */
object ThrowErrorHandler extends ConsoleErrorHandler {
  override def handleCommandFailure(@unused cause: Option[String] = None): Nothing =
    throw new CommandFailure()

  override def handleInternalError(): Nothing = throw new CantonInternalError()
}
