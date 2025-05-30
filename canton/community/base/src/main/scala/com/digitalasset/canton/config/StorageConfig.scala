// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.config

import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.RequireTypes.{PositiveInt, PositiveNumeric}
import com.digitalasset.canton.config.manual.CantonConfigValidatorDerivation
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLogging, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import scala.jdk.CollectionConverters.*

/** Various database related settings
  *
  * @param maxConnections
  *   Allows for setting the maximum number of db connections used by Canton and the ledger API
  *   server. If None, the value will be auto-detected from the number of processors. Has no effect,
  *   if the number of connections is already set via slick options (i.e., `config.numThreads`).
  * @param connectionAllocation
  *   Overrides for the sizes of the connection pools managed by a canton node.
  * @param failFastOnStartup
  *   If true, the node will fail-fast when the database cannot be connected to If false, the node
  *   will wait indefinitely for the database to come up
  * @param migrationsPaths
  *   Where should database migrations be read from. Enables specialized DDL for different database
  *   servers (e.g. Postgres).
  * @param connectionTimeout
  *   How long to wait for acquiring a database connection
  * @param failedToFatalDelay
  *   Delay after which, if the storage is continuously in a Failed state, it will escalate to
  *   Fatal. The default value is 5 minutes. Components that use the storage as a health dependency
  *   can then determine how to react. Currently, the sequencer declares it as a fatal dependency
  *   for its liveness health, which means it will transition to NOT_SERVING if this delay is
  *   exceeded, allowing a monitoring infrastructure to restart it. **NOTE**: Currently this only
  *   applies to [[com.digitalasset.canton.resource.DbStorageSingle]], which is only used by the
  *   sequencer. TODO(i24240): Apply the same behavior to `DbStorageMulti`
  * @param warnOnSlowQuery
  *   Optional time when we start logging a query as slow.
  * @param warnOnSlowQueryInterval
  *   How often to repeat the logging statement for slow queries.
  * @param unsafeCleanOnValidationError
  *   TO BE USED ONLY FOR TESTING! Clean the database if validation during DB migration fails.
  * @param unsafeBaselineOnMigrate
  *   TO BE USED ONLY FOR TESTING! <p>Whether to automatically call baseline when migrate is
  *   executed against a non-empty schema with no schema history table. This schema will then be
  *   baselined with the {@code baselineVersion} before executing the migrations. Only migrations
  *   above {@code baselineVersion} will then be applied.</p> <p>This is useful for databases
  *   projects where the initial vendor schema is not empty</p> If baseline should be called on
  *   migrate for non-empty schemas, { @code false} if not. (default: { @code false})
  * @param migrateAndStart
  *   if true, db migrations will be applied to the database (default is to abort start if db
  *   migrates are pending to force an explicit upgrade)
  */
final case class DbParametersConfig(
    maxConnections: Option[PositiveInt] = None,
    connectionAllocation: ConnectionAllocation = ConnectionAllocation(),
    failFastOnStartup: Boolean = true,
    migrationsPaths: Seq[String] = Seq.empty,
    connectionTimeout: NonNegativeFiniteDuration = DbConfig.defaultConnectionTimeout,
    failedToFatalDelay: NonNegativeFiniteDuration = DbConfig.defaultFailedToFatalDelay,
    warnOnSlowQuery: Option[PositiveFiniteDuration] = None,
    warnOnSlowQueryInterval: PositiveFiniteDuration =
      DbParametersConfig.defaultWarnOnSlowQueryInterval,
    unsafeCleanOnValidationError: Boolean = false,
    unsafeBaselineOnMigrate: Boolean = false,
    migrateAndStart: Boolean = false,
) extends PrettyPrinting
    with UniformCantonConfigValidation {
  override protected def pretty: Pretty[DbParametersConfig] =
    prettyOfClass(
      paramIfDefined(
        "migrationsPaths",
        x =>
          if (x.migrationsPaths.nonEmpty)
            Some(x.migrationsPaths.map(_.doubleQuoted))
          else None,
      ),
      paramIfDefined("maxConnections", _.maxConnections),
      param("connectionAllocation", _.connectionAllocation),
      param("failFast", _.failFastOnStartup),
      paramIfNonEmpty("migrationPaths", _.migrationsPaths.map(_.unquoted)),
      param("connectionTimeout", _.connectionTimeout),
      param("failedToFatalDelay", _.failedToFatalDelay),
      paramIfDefined("warnOnSlowQuery", _.warnOnSlowQuery),
      param("migrateAndStart", _.migrateAndStart),
    )
}

/** Various settings to control batching behaviour related to db queries
  *
  * @param maxItemsInBatch
  *   maximum number of items in a batch
  * @param maxPruningBatchSize
  *   maximum number of events to prune from a participant at a time, used to break up canton
  *   participant-internal batches
  * @param ledgerApiPruningBatchSize
  *   Number of events to prune from the ledger api server index-database at a time during automatic
  *   background pruning. Canton-internal store pruning happens at the smaller batch size of
  *   "maxPruningBatchSize" to minimize memory usage whereas ledger-api-server index-db pruning
  *   needs sufficiently large batches to amortize the database overhead of "skipping over" active
  *   contracts.
  * @param maxAcsImportBatchSize
  *   maximum number of active contracts in a batch to be imported
  * @param parallelism
  *   number of parallel queries to the db. defaults to 8
  * @param aggregator
  *   batching configuration for DB queries
  */
final case class BatchingConfig(
    maxItemsInBatch: PositiveNumeric[Int] = BatchingConfig.defaultMaxItemsBatch,
    maxPruningBatchSize: PositiveNumeric[Int] = BatchingConfig.defaultMaxPruningBatchSize,
    ledgerApiPruningBatchSize: PositiveNumeric[Int] =
      BatchingConfig.defaultLedgerApiPruningBatchSize,
    maxAcsImportBatchSize: PositiveNumeric[Int] = BatchingConfig.defaultMaxAcsImportBatchSize,
    parallelism: PositiveNumeric[Int] = BatchingConfig.defaultBatchingParallelism,
    aggregator: BatchAggregatorConfig = BatchingConfig.defaultAggregator,
) extends UniformCantonConfigValidation

object BatchingConfig {
  implicit val batchingConfigCantonConfigValidator: CantonConfigValidator[BatchingConfig] = {
    import CantonConfigValidatorInstances.*
    CantonConfigValidatorDerivation[BatchingConfig]
  }

  private val defaultMaxItemsBatch: PositiveInt = PositiveNumeric.tryCreate(100)
  private val defaultBatchingParallelism: PositiveInt = PositiveNumeric.tryCreate(8)
  private val defaultMaxPruningBatchSize: PositiveInt = PositiveNumeric.tryCreate(1000)
  private val defaultLedgerApiPruningBatchSize: PositiveInt = PositiveNumeric.tryCreate(50000)
  private val defaultMaxAcsImportBatchSize: PositiveNumeric[Int] = PositiveNumeric.tryCreate(1000)
  private val defaultAggregator: BatchAggregatorConfig.Batching = BatchAggregatorConfig.Batching()
}

final case class ConnectionAllocation(
    numReads: Option[PositiveInt] = None,
    numWrites: Option[PositiveInt] = None,
    numLedgerApi: Option[PositiveInt] = None,
) extends PrettyPrinting
    with UniformCantonConfigValidation {
  override protected def pretty: Pretty[ConnectionAllocation] =
    prettyOfClass(
      paramIfDefined("numReads", _.numReads),
      paramIfDefined("numWrites", _.numWrites),
      paramIfDefined("numLedgerApi", _.numLedgerApi),
    )
}

object ConnectionAllocation {
  implicit val connectionAllocationCantonConfigValidator
      : CantonConfigValidator[ConnectionAllocation] = {
    import CantonConfigValidatorInstances.*
    CantonConfigValidatorDerivation[ConnectionAllocation]
  }
}

object DbParametersConfig {
  import CantonConfigValidatorInstances.*

  implicit val dbParametersConfigCantonConfigValidator: CantonConfigValidator[DbParametersConfig] =
    CantonConfigValidatorDerivation[DbParametersConfig]

  private val defaultWarnOnSlowQueryInterval: PositiveFiniteDuration =
    PositiveFiniteDuration.ofSeconds(5)
}

/** Determines how a node stores persistent data.
  */
sealed trait StorageConfig extends UniformCantonConfigValidation {
  type Self <: StorageConfig

  /** Database specific configuration parameters used by Slick. Also available for in-memory storage
    * to support easy switching between in-memory and database storage.
    */
  def config: Config

  /** General database related parameters. */
  def parameters: DbParametersConfig

  private def maxConnectionsOrDefault: PositiveInt =
    // The following is an educated guess of a sane default for the number of DB connections.
    // https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
    parameters.maxConnections.getOrElse(
      Threading.detectNumberOfThreads(NamedLogging.noopNoTracingLogger)
    )

  /** Returns the size of the Canton read connection pool for the given usage.
    *
    * @param forParticipant
    *   True if the connection pool is used by a participant, then we reserve connections for the
    *   ledger API server.
    * @param withWriteConnectionPool
    *   True for a replicated node's write connection pool, then we split the available connections
    *   between the read and write pools.
    * @param withMainConnection
    *   True for accounting an additional connection (write connection, or main connection with
    *   lock)
    */
  def numReadConnectionsCanton(
      forParticipant: Boolean,
      withWriteConnectionPool: Boolean,
      withMainConnection: Boolean,
  ): PositiveInt =
    parameters.connectionAllocation.numReads.getOrElse(
      numConnectionsCanton(forParticipant, withWriteConnectionPool, withMainConnection)
    )

  /** Returns the size of the Canton write connection pool for the given usage.
    *
    * @param forParticipant
    *   True if the connection pool is used by a participant, then we reserve connections for the
    *   ledger API server.
    * @param withWriteConnectionPool
    *   True for a replicated node's write connection pool, then we split the available connections
    *   between the read and write pools.
    * @param withMainConnection
    *   True for accounting an additional connection (write connection, or main connection with
    *   lock)
    */
  def numWriteConnectionsCanton(
      forParticipant: Boolean,
      withWriteConnectionPool: Boolean,
      withMainConnection: Boolean,
  ): PositiveInt =
    parameters.connectionAllocation.numWrites.getOrElse(
      numConnectionsCanton(forParticipant, withWriteConnectionPool, withMainConnection)
    )

  /** Returns the size of the combined Canton read+write connection pool for the given usage.
    *
    * @param forParticipant
    *   True if the connection pool is used by a participant, then we reserve connections for the
    *   ledger API server.
    * @param withWriteConnectionPool
    *   True for a replicated node's write connection pool, then we split the available connections
    *   between the read and write pools.
    * @param withMainConnection
    *   True for accounting an additional connection (write connection, or main connection with
    *   lock)
    */
  def numCombinedConnectionsCanton(
      forParticipant: Boolean,
      withWriteConnectionPool: Boolean,
      withMainConnection: Boolean,
  ): PositiveInt =
    (parameters.connectionAllocation.numWrites.toList ++ parameters.connectionAllocation.numReads.toList)
      .reduceOption(_ + _)
      .getOrElse(
        numConnectionsCanton(forParticipant, withWriteConnectionPool, withMainConnection)
      )

  /** Returns the size of the Canton connection pool for the given usage.
    *
    * @param forParticipant
    *   True if the connection pool is used by a participant, then we reserve connections for the
    *   ledger API server.
    * @param withWriteConnectionPool
    *   True for a replicated node's write connection pool, then we split the available connections
    *   between the read and write pools.
    * @param withMainConnection
    *   True for accounting an additional connection (write connection, or main connection with
    *   lock)
    */
  private def numConnectionsCanton(
      forParticipant: Boolean,
      withWriteConnectionPool: Boolean,
      withMainConnection: Boolean,
  ): PositiveInt = {
    val c = maxConnectionsOrDefault.value

    // A participant evenly shares the max connections between the ledger API server (not indexer) and canton
    val totalConnectionPoolSize = if (forParticipant) c / 2 else c

    // For replicated nodes we have an additional connection pool for writes. Split evenly between reads and writes.
    val replicatedConnectionPoolSize =
      if (withWriteConnectionPool) totalConnectionPoolSize / 2 else totalConnectionPoolSize

    val resultMaxConnections = if (withMainConnection) {
      // The write connection pool for replicated nodes require an additional connection outside of the pool
      (replicatedConnectionPoolSize - 1)
    } else
      replicatedConnectionPoolSize

    // Return at least one connection
    PositiveInt.tryCreate(resultMaxConnections max 1)
  }

  /** Max connections for the Ledger API server. The Ledger API indexer's max connections are
    * configured separately.
    */
  def numConnectionsLedgerApiServer: PositiveInt =
    parameters.connectionAllocation.numLedgerApi.getOrElse(
      // The Ledger Api Server always gets half of the max connections allocated to canton
      PositiveInt.tryCreate((maxConnectionsOrDefault.value / 2).max(1))
    )
}

object StorageConfig {

  implicit val storageConfigCantonConfigValidator: CantonConfigValidator[StorageConfig] =
    CantonConfigValidatorDerivation[StorageConfig]

  /** Dictates that persistent data is stored in memory. So in fact, the data is not persistent. It
    * is deleted whenever the node is stopped.
    *
    * @param config
    *   IGNORED configuration option, used to allow users to use configuration mixins with postgres
    *   and h2
    */
  final case class Memory(
      override val config: Config = ConfigFactory.empty(),
      override val parameters: DbParametersConfig = DbParametersConfig(),
  ) extends StorageConfig {
    override type Self = Memory

  }

  object Memory {
    implicit val memoryCantonConfigValidator: CantonConfigValidator[Memory] = {
      implicit val configCantonConfigValidator: CantonConfigValidator[Config] =
        CantonConfigValidator.validateAll
      CantonConfigValidatorDerivation[Memory]
    }
  }
}

/** Dictates that persistent data is stored in a database.
  */
sealed trait DbConfig extends StorageConfig with PrettyPrinting {

  /** Function to combine the defined migration path together with dev version changes */
  final def buildMigrationsPaths(alphaVersionSupport: Boolean): Seq[String] =
    if (parameters.migrationsPaths.nonEmpty)
      parameters.migrationsPaths
    else if (alphaVersionSupport)
      Seq(stableMigrationPath, devMigrationPath)
    else Seq(stableMigrationPath)

  protected def devMigrationPath: String
  protected def stableMigrationPath: String

  override protected def pretty: Pretty[DbConfig] =
    prettyOfClass(
      param(
        "config",
        _.config.toString.replaceAll("\"password\":\".*?\"", "\"password\":\"???\"").unquoted,
      ),
      param("parameters", _.parameters),
    )
}

sealed trait ModifiableDbConfig[A <: ModifiableDbConfig[A]] extends DbConfig {
  override type Self = A

  def modify(
      config: Config = this.config,
      parameters: DbParametersConfig = this.parameters,
  ): Self
}

object DbConfig {
  final case class H2(
      override val config: Config,
      override val parameters: DbParametersConfig = DbParametersConfig(),
  ) extends ModifiableDbConfig[H2] {
    def databaseName: Option[String] =
      if (config.hasPath("url")) {
        val url = config.getString("url")
        "(:mem:|:file:)([^:;]+)([:;])".r.findFirstMatchIn(url).map(_.group(2))
      } else None

    protected val devMigrationPath: String = DbConfig.h2MigrationsPathDev
    protected val stableMigrationPath: String = DbConfig.h2MigrationsPathStable

    def modify(config: Config = this.config, parameters: DbParametersConfig = this.parameters): H2 =
      H2(config, parameters)
  }

  object H2 {
    implicit val h2CantonConfigValidator: CantonConfigValidator[H2] = {
      implicit val configCantonConfigValidator: CantonConfigValidator[Config] =
        CantonConfigValidator.validateAll
      CantonConfigValidatorDerivation[H2]
    }

    private val defaultDriver: String = "org.h2.Driver"
    val defaultConfig: Config = DbConfig.toConfig(Map("driver" -> defaultDriver))
  }

  final case class Postgres(
      override val config: Config,
      override val parameters: DbParametersConfig = DbParametersConfig(),
  ) extends ModifiableDbConfig[Postgres] {
    protected def devMigrationPath: String = DbConfig.postgresMigrationsPathDev
    protected val stableMigrationPath: String = DbConfig.postgresMigrationsPathStable

    def modify(
        config: Config = this.config,
        parameters: DbParametersConfig = this.parameters,
    ): Postgres =
      Postgres(config, parameters)
  }

  object Postgres {
    implicit val postgresCantonConfigValidator: CantonConfigValidator[Postgres] = {
      implicit val configCantonConfigValidator: CantonConfigValidator[Config] =
        CantonConfigValidator.validateAll
      CantonConfigValidatorDerivation[Postgres]
    }

    // We enable `tcpKeepAlive` in the Postgres JDBC driver in order to improve detection of
    // failed connections in the Hikari connection pool.
    // See https://github.com/brettwooldridge/HikariCP/wiki/Setting-Driver-or-OS-TCP-Keepalive
    val defaultConfig: Config = DbConfig.toConfig(Map("properties.tcpKeepAlive" -> true))
  }

  val defaultConnectionTimeout: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(5)
  val defaultFailedToFatalDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(5)

  private val stableDir = "stable"
  private val devDir = "dev"
  private val basePostgresMigrationsPath: String = "classpath:db/migration/canton-network/postgres/"
  private val baseH2MigrationsPath: String = "classpath:db/migration/canton/h2/"
  val postgresMigrationsPathStable: String = basePostgresMigrationsPath + stableDir
  val h2MigrationsPathStable: String = baseH2MigrationsPath + stableDir
  val postgresMigrationsPathDev: String = basePostgresMigrationsPath + devDir
  val h2MigrationsPathDev: String = baseH2MigrationsPath + devDir

  def postgresUrl(host: String, port: Int, dbName: String): String =
    s"jdbc:postgresql://$host:$port/$dbName"

  def h2Url(dbName: String): String =
    s"jdbc:h2:mem:$dbName;MODE=PostgreSQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1"

  def toConfig(map: Map[String, Any]): Config = ConfigFactory.parseMap(map.asJava)

  /** Apply default values to the given db config
    */
  def configWithFallback(dbConfig: DbConfig)(
      numThreads: PositiveInt,
      poolName: String,
      logger: TracedLogger,
  )(implicit traceContext: TraceContext): Config = {
    val commonDefaults = toConfig(
      Map(
        "poolName" -> poolName,
        "numThreads" -> numThreads.unwrap,
        "connectionTimeout" -> dbConfig.parameters.connectionTimeout.unwrap.toMillis,
        "initializationFailTimeout" -> 1, // Must be greater than 0 to force a connection validation on startup
      )
    )
    (dbConfig match {
      case h2: H2 =>
        def containsOption(c: Config, optionName: String, optionValue: String) = {
          val propertiesPath = s"properties.$optionName"
          val valueIsInProperties =
            c.hasPath(propertiesPath) && c.getString(propertiesPath).contains(optionValue)
          val valueIsInUrl = assertOnString(c, "url", _.contains(s"$optionName=$optionValue"))
          valueIsInProperties || valueIsInUrl
        }
        def enforcePgMode(c: Config): Config =
          if (!containsOption(c, "MODE", "PostgreSQL")) {
            logger.warn(
              "Given H2 config did not contain PostgreSQL compatibility mode. Automatically added it."
            )
            c.withValue("properties.MODE", ConfigValueFactory.fromAnyRef("PostgreSQL"))
          } else c
        def enforceDelayClose(c: Config): Config = {
          val isInMemory =
            assertOnString(c, "url", _.contains(":mem:"))
          if (isInMemory && !containsOption(c, "DB_CLOSE_DELAY", "-1")) {
            logger.warn(
              s"Given H2 config is in-memory and does not contain DB_CLOSE_DELAY=-1. Automatically added this to avoid accidentally losing all data. $c"
            )
            c.withValue("properties.DB_CLOSE_DELAY", ConfigValueFactory.fromAnyRef("-1"))
          } else c
        }
        def enforceSingleConnection(c: Config): Config = {
          if (!c.hasPath("numThreads") || c.getInt("numThreads") != 1) {
            logger.info("Overriding numThreads to 1 to avoid concurrency issues.")
          }
          c.withValue("numThreads", ConfigValueFactory.fromAnyRef(1))
        }
        enforceDelayClose(
          enforcePgMode(enforceSingleConnection(writeH2UrlIfNotSet(h2.config)))
        ).withFallback(H2.defaultConfig)
      case postgres: Postgres => postgres.config.withFallback(Postgres.defaultConfig)
      case other => other.config
    }).withFallback(commonDefaults)
  }

  private def assertOnString(c: Config, path: String, check: String => Boolean): Boolean =
    c.hasPath(path) && check(c.getString(path))

  /** if the URL is not set, we build one here (assuming that config.properties.databaseName is set
    * and should be used as the file name)
    */
  def writeH2UrlIfNotSet(c: Config): Config = {
    val noUrlConfigured = !assertOnString(c, "url", _.nonEmpty)
    if (noUrlConfigured && c.hasPath("properties.databaseName")) {
      val url = "jdbc:h2:file:./" + c.getString(
        "properties.databaseName"
      ) + ";MODE=PostgreSQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1"
      c.withValue("url", ConfigValueFactory.fromAnyRef(url))
    } else
      c
  }

  /** strip the password and the url out of the config object */
  def hideConfidential(config: Config): Config = {
    val hidden = ConfigValueFactory.fromAnyRef("****")
    val replace = Seq("password", "properties.password", "url", "properties.url")
    replace.foldLeft(config) { case (acc, path) =>
      if (acc.hasPath(path))
        acc.withValue(path, hidden)
      else acc
    }
  }

}
