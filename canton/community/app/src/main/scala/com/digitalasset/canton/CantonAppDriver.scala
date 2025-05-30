// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton

import better.files.File
import cats.syntax.either.*
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.status.{ErrorStatus, Status, StatusListener, WarnStatus}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.CantonAppDriver.installGCLogging
import com.digitalasset.canton.buildinfo.BuildInfo
import com.digitalasset.canton.cli.Command.Sandbox
import com.digitalasset.canton.cli.{Cli, Command, LogFileAppender}
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.config.{
  CantonConfig,
  ConfigErrors,
  GCLoggingConfig,
  Generate,
  SharedCantonConfig,
}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.environment.{Environment, EnvironmentFactory}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{NoTracing, TraceContext}
import com.digitalasset.canton.util.JarResourceUtils
import com.sun.management.GarbageCollectionNotificationInfo
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference
import javax.management.openmbean.CompositeData
import javax.management.{NotificationEmitter, NotificationListener}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The Canton main application.
  *
  * Starts a set of synchronizers and participant nodes.
  */
abstract class CantonAppDriver extends App with NamedLogging with NoTracing {

  type Config <: SharedCantonConfig[Config]
  type E <: Environment[Config]

  protected def environmentFactory: EnvironmentFactory[Config, E]

  protected def withManualStart(config: Config): Config

  protected def additionalVersions: Map[String, String] = Map.empty

  protected def printVersion(): Unit =
    (Map(
      "Canton" -> BuildInfo.version,
      "Daml Libraries" -> BuildInfo.damlLibrariesVersion,
      "Stable Canton protocol versions" -> BuildInfo.stableProtocolVersions.toString(),
      "Preview Canton protocol versions" -> BuildInfo.betaProtocolVersions.toString(),
    ) ++ additionalVersions) foreach { case (name, version) =>
      Console.out.println(s"$name: $version")
    }

  protected def logAppVersion(): Unit = logger.info(s"Starting Canton version ${BuildInfo.version}")

  // BE CAREFUL: Set the environment variables before you touch anything related to
  // logback as otherwise, the logback configuration will be read without these
  // properties being considered
  private val cliOptions = Cli.parse(args, printVersion()).getOrElse(sys.exit(1))
  cliOptions.installLogging()

  // Fail, if the log configuration cannot be read.
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  private val logbackStatusManager = loggerContext.getStatusManager
  private val killingStatusListener: StatusListener = {
    case status @ (_: WarnStatus | _: ErrorStatus) =>
      Console.err.println(s"Unable to load log configuration.\n$status")
      Console.err.flush()
      sys.exit(-1)
    case _: Status => // ignore
  }
  logbackStatusManager.add(killingStatusListener)

  // Use the root logger as named logger to avoid a prefix "CantonApp" in log files.
  override val loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  // Adjust root and canton loggers which works even if a custom logback.xml is defined
  Seq(
    (cliOptions.levelCanton, "com.digitalasset"),
    (cliOptions.levelCanton, "com.daml"),
    (cliOptions.levelRoot, org.slf4j.Logger.ROOT_LOGGER_NAME),
  )
    .foreach {
      case (Some(level), loggerName) =>
        @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
        val root: Logger = LoggerFactory.getLogger(loggerName).asInstanceOf[Logger]
        root.setLevel(level)
      case (None, _) =>
    }

  logAppVersion()

  if (cliOptions.logTruncate) {
    cliOptions.logFileAppender match {
      case LogFileAppender.Rolling =>
        logger.warn(
          "Ignoring log file truncation request, as it only works with flat log files, but here we use rolling log files."
        )
      case LogFileAppender.Flat =>
      case LogFileAppender.Off =>
    }
  }

  // Now that at least one line has been logged, deregister the killingStatusManager so that
  // Canton does not die on a warning status.
  logbackStatusManager.remove(killingStatusListener)

  private val environmentRef: AtomicReference[Option[E]] = new AtomicReference(None)
  sys.runtime.addShutdownHook(new Thread(() => {
    try {
      logger.info("Shutting down...")
      environmentRef.get().foreach(_.close())
      logger.info("Shutdown complete.")
    } catch {
      case NonFatal(exception) =>
        logger.error("Failed to shut down successfully.", exception)
    } finally {
      LoggerFactory.getILoggerFactory match {
        case logbackLoggerContext: LoggerContext =>
          logger.info("Shutting down logger. Bye bye.")
          logbackLoggerContext.stop()
        case _ =>
          logger.warn(
            "Logback is not bound via slf4j. Cannot shut down logger, this could result in lost log-messages."
          )
      }
    }
  }))
  logger.debug("Registered shutdown-hook.")
  private object Config {
    val sandboxConfig = JarResourceUtils.extractFileFromJar("sandbox/sandbox.conf")
    val sandboxBotstrap = JarResourceUtils.extractFileFromJar("sandbox/bootstrap.canton")
    val configFiles = cliOptions.command
      .collect { case Sandbox => sandboxConfig }
      .toList
      .concat(cliOptions.configFiles)
    val bootstrapFile = cliOptions.command
      .collect { case Sandbox => sandboxBotstrap }
      .orElse(cliOptions.bootstrapScriptPath)
    val configFromMap = {
      import scala.jdk.CollectionConverters.*
      ConfigFactory.parseMap(cliOptions.configMap.asJava)
    }

    def loadConfigFromFiles(
        loggingString: String = "Starting up with resolved config"
    )(implicit traceContext: TraceContext): Either[CantonConfigError, Config] = {
      val mergedUserConfigsE = NonEmpty.from(configFiles) match {
        case None if cliOptions.configMap.isEmpty =>
          Left(ConfigErrors.NoConfigFiles.Error())
        case None => Right(ConfigFactory.empty())
        case Some(neConfigFiles) => CantonConfig.parseAndMergeJustCLIConfigs(neConfigFiles)
      }
      for {
        mergedUserConfigs <- mergedUserConfigsE
        finalConfig = CantonConfig.mergeConfigs(mergedUserConfigs, Seq(configFromMap))
        loadedConfig <- loadConfig(finalConfig)
          .leftMap { err =>
            // if loading failed and there is more than one file, writing it into a temporary file
            if (configFiles.sizeCompare(1) > 0) {
              writeConfigToTmpFile(mergedUserConfigs)
            }
            err
          }
          .map { loaded =>
            if (cliOptions.manualStart) withManualStart(loaded)
            else loaded
          }
      } yield {
        if (loadedConfig.monitoring.logging.logConfigOnStartup) {
          // we have two ways to log the config. both have their pro and cons.
          // full means we include default values. in such a case, it's hard to figure
          // out what really the config settings are.
          // the other method just uses the loaded `Config` object that doesn't have default
          // values, but therefore needs a separate way to handle the rendering
          logger.info(
            s"$loggingString\n" +
              (if (loadedConfig.monitoring.logging.logConfigWithDefaults)
                 loadedConfig.dumpString
               else
                 CantonConfig.renderForLoggingOnStartup(finalConfig))
          )
        }
        loadedConfig
      }
    }
    val startupConfig = loadConfigFromFiles().valueOr { err =>
      logger.error(s"Failed to read config at startup: $err")
      sys.exit(1)
    }

    private def writeConfigToTmpFile(mergedUserConfigs: com.typesafe.config.Config) = {
      val tmp = File.newTemporaryFile("canton-config-error-", ".conf")
      logger.error(
        s"An error occurred after parsing a config file that was obtained by merging multiple config " +
          s"files. The resulting merged-together config file, for which the error occurred, was written to '$tmp'."
      )
      tmp
        .write(
          mergedUserConfigs
            .root()
            .render(CantonConfig.defaultConfigRenderer)
        )
        .discard
    }

  }

  installGCLogging(loggerFactory, Config.startupConfig.monitoring.logging.jvmGc)

  // verify that run script and bootstrap script aren't mixed
  if (Config.bootstrapFile.isDefined) {
    cliOptions.command match {
      case Some(Command.RunScript(_)) =>
        logger.error("--bootstrap script and run script are mutually exclusive")
        sys.exit(1)
      case Some(Command.Generate(_)) =>
        logger.error("--bootstrap script and generate are mutually exclusive")
        sys.exit(1)
      case _ =>
    }
  }

  private lazy val bootstrapScript: Option[CantonScript] =
    Config.bootstrapFile.map(CantonScriptFromFile.apply)

  val environment = environmentFactory.create(Config.startupConfig, loggerFactory)
  val runner: Runner[Config] = cliOptions.command match {
    case Some(Command.Sandbox) =>
      new ServerRunner(
        bootstrapScript,
        loggerFactory,
        cliOptions.exitAfterBootstrap,
        cliOptions.dars,
      )
    case Some(Command.Daemon) =>
      new ServerRunner(bootstrapScript, loggerFactory)
    case Some(Command.RunScript(script)) => ConsoleScriptRunner(script, loggerFactory)
    case Some(Command.Generate(target)) =>
      Generate.process(target, Config.startupConfig)
      sys.exit(0)
    case _ =>
      new ConsoleInteractiveRunner(
        cliOptions.noTty,
        bootstrapScript,
        environment.writePortsFile(),
        loggerFactory,
      )
  }

  environmentRef.set(Some(environment)) // registering for graceful shutdown
  environment.startAndReconnect(runner.run(environment)) match {
    case Right(()) =>
    case Left(_) => sys.exit(1)
  }

  def loadConfig(config: com.typesafe.config.Config): Either[CantonConfigError, Config]
}

object CantonAppDriver {

  @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.AsInstanceOf"))
  private def installGCLogging(loggerFactory: NamedLoggerFactory, config: GCLoggingConfig): Unit =
    if (config.enabled) {
      val logger = loggerFactory.getLogger(getClass)
      try {
        val listener = new NotificationListener() {
          override def handleNotification(
              notification: javax.management.Notification,
              handback: Any,
          ): Unit =
            if (
              notification.getType
                .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)
            ) {
              val gcInfo = GarbageCollectionNotificationInfo.from(
                notification.getUserData.asInstanceOf[CompositeData]
              )
              if (config.filter.isEmpty || gcInfo.getGcName.contains(config.filter)) {
                val str =
                  s"Garbage Collection: name=${gcInfo.getGcName}, cause=${gcInfo.getGcCause}, action=: ${gcInfo.getGcAction}, duration=${gcInfo.getGcInfo.getDuration} ms"
                val out =
                  if (config.details)
                    str + s"\n  before=${gcInfo.getGcInfo.getMemoryUsageBeforeGc}\n  after=${gcInfo.getGcInfo.getMemoryUsageAfterGc}"
                  else str
                if (config.debugLevel) logger.debug(out) else logger.info(out)
              }
            }
        }
        ManagementFactory.getGarbageCollectorMXBeans.asScala.foreach { bean =>
          bean.asInstanceOf[NotificationEmitter].addNotificationListener(listener, null, null)
        }
      } catch {
        case NonFatal(e) =>
          logger.warn("Failed to setup GC logging", e)
      }
    }

}
