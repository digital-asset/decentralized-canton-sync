package com.daml.network.integration.tests.runbook

import com.daml.network.config.Thresholds
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import com.daml.network.util.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.topology.PartyId

import java.net.URI
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional

/** Base for preflight tests running against a deployed validator
  */
abstract class ValidatorPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment("alice-validator", "bob-validator")
    with FrontendLoginUtil
    with PreflightIntegrationTestUtil
    with AnsFrontendTestUtil
    with WalletFrontendTestUtil
    with SplitwellFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  protected val auth0Users: mutable.Map[String, Auth0User] = mutable.Map.empty[String, Auth0User]

  protected val isDevNet: Boolean

  protected val auth0: Auth0Util

  protected val validatorName: String
  protected val validatorAuth0Secret: String
  protected val validatorAuth0Audience: String
  protected val validatorWalletUser: String
  protected val includeSplitwellTests = true

  private lazy val walletUiUrl =
    s"https://wallet.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private lazy val ansUiUrl =
    s"https://cns.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private lazy val splitwellUiUrl =
    s"https://splitwell.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"

  override def beforeEach() = {
    super.beforeEach();

    val aliceUser = retryAuth0Calls(auth0.createUser());
    logger.debug(
      s"Created user Alice ${aliceUser.email} with password ${aliceUser.password} (id: ${aliceUser.id})"
    )

    val bobUser = retryAuth0Calls(auth0.createUser());
    logger.debug(
      s"Created user Bob ${bobUser.email} with password ${bobUser.password} (id: ${bobUser.id})"
    )

    auth0Users += ("alice-validator" -> aliceUser)
    auth0Users += ("bob-validator" -> bobUser)
  }

  override def afterEach() = {
    try super.afterEach()
    finally auth0Users.values.map(user => retryAuth0Calls(user.close))
  }

  override def beforeAll() = {
    super.beforeAll()
    // Offboard some users if we have too many, to make sure validator does not hit the limit of around 200.
    // Note that the actual offboarding will actually happen by wallet automation in the background,
    // and we are not waiting for it here, so it is expected to be happening in parallel to the actual tests
    limitValidatorUsers()
  }

  protected def validatorClient = {
    val env = provideEnvironment("NotUsed")
    // retry on e.g. network errors and rate limits
    val token = eventuallySucceeds() {
      getAuth0ClientCredential(
        validatorAuth0Secret,
        validatorAuth0Audience,
        auth0,
      )(noTracingLogger)
    }

    vc(validatorName)(env).copy(token = Some(token))
  }

  protected def limitValidatorUsers() = {
    val users = validatorClient.listUsers()

    val targetNumber = 40 // TODO(tech-debt): consider de-hardcoding this
    val offboardThreshold = 50 // TODO(tech-debt): consider de-hardcoding this
    if (users.length > offboardThreshold) {
      logger.info(
        s"Validator has ${users.length} users, offboarding some to get below ${targetNumber}"
      )
      users
        .filter(_ != validatorWalletUser)
        .take(users.length - targetNumber)
        .foreach { user =>
          {
            logger.debug(s"Offboarding user: ${user}")
            validatorClient.offboardUser(user)
          }
        }
    } else {
      logger.debug(s"Only ${users.length} users onboarded, not offboarding any")
    }
  }

  protected def checkValidatorIsConnectedToSvRunbook() = {}

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName()
    )

  // when running locally, these tests may fail if the CC DAR deployed to DevNet
  // differs from the latest one on your branch

  checkValidatorIsConnectedToSvRunbook()

  "run through runbook against cluster validator" in { _ =>
    val aliceUser = auth0Users.get("alice-validator").value

    val bobUser = auth0Users.get("bob-validator").value

    val alicePartyId = withFrontEnd("alice-validator") { implicit webDriver =>
      val alicePartyId = loginAndOnboardToWalletUi(aliceUser)
      findAll(className("amulets-table-row")) should have size 0
      alicePartyId
    }

    val bobPartyId = withFrontEnd("bob-validator") { implicit webDriver =>
      val bobPartyId = loginAndOnboardToWalletUi(bobUser)
      findAll(className("amulets-table-row")) should have size 0
      bobPartyId
    }

    withFrontEnd("alice-validator") { implicit webDriver =>
      if (isDevNet) {
        tapAmulets(100)

        clue(s"Creating transfer offer for: $bobPartyId") {
          createTransferOffer(
            PartyId.tryFromProtoPrimitive(bobPartyId),
            BigDecimal("10"),
            90,
            "p2ptransfer",
          )
        }
      }

      clue("Logging out") {
        click on "logout-button"
        waitForQuery(id("oidc-login-button"))
      }
    }

    withFrontEnd("bob-validator") { implicit webDriver =>
      if (isDevNet) {
        val acceptButton = eventually() {
          findAll(className("transfer-offer")).toSeq.headOption match {
            case Some(element) =>
              element.childWebElement(className("transfer-offer-accept"))
            case None => fail("failed to find transfer offer")
          }
        }

        actAndCheck(
          "Accept transfer offer", {
            click on acceptButton
            click on "navlink-transactions"
          },
        )(
          "Transfer appears in transactions log",
          _ => {
            inside(findAll(className("tx-row")).toSeq) { case Seq(tx) =>
              val transaction = readTransactionFromRow(tx)
              transaction.action should matchText("Received")
              val partyR = s"${alicePartyId}.*${validatorName}_validator_service_user::.*".r
              val description =
                transaction.partyDescription.getOrElse(fail("There should be a party."))
              description should fullyMatch regex partyR
              transaction.ccAmount should beWithin(BigDecimal(10) - smallAmount, BigDecimal(10))
              // we can't test a specific amulet price as the amulet price on a live network can change
              val rateR = """^\s*(\d+(?:\.\d+)?)\s*CC/USD\s*$""".r
              inside(transaction.rate) { case rateR(rate) =>
                BigDecimal(rate) should be > BigDecimal(0)
                transaction.usdAmount should beWithin(
                  transaction.ccAmount / BigDecimal(rate) - smallAmount,
                  transaction.ccAmount / BigDecimal(rate) + smallAmount,
                )
              }
            }
          },
        )
      }

      clue("Logging out") {
        click on "logout-button"
        waitForQuery(id("oidc-login-button"))
      }
    }
  }

  // test is similar to 'settle debts with a single party' in SplitwellFrontendIntegrationTest
  "test splitwell group creation and payment against validator" in { _ =>
    if (includeSplitwellTests) {
      val groupName = "troika"

      val aliceUser = auth0Users.get("alice-validator").value
      val bobUser = auth0Users.get("bob-validator").value

      val bobUserPartyId = withFrontEnd("bob-validator") { implicit webDriver =>
        val bobUserPartyId = loginAndOnboardToWalletUi(bobUser)
        if (isDevNet) {
          tapAmulets(710)
        }
        bobUserPartyId
      }

      val (aliceUserPartyId, invite) = withFrontEnd("alice-validator") { implicit webDriver =>
        val aliceUserPartyId = loginAndOnboardToWalletUi(aliceUser)
        loginToSplitwellUi(aliceUser)

        (aliceUserPartyId, createGroupAndInviteLink(groupName))
      }

      withFrontEnd("bob-validator") { implicit webDriver =>
        loginToSplitwellUi(bobUser)
        requestGroupMembership(invite)
      }

      withFrontEnd("alice-validator") { implicit webDriver =>
        eventually() {
          findAll(className("add-user-link")).toSeq should not be (empty)
        }
        actAndCheck("add user", click on className("add-user-link"))(
          "user has been added and invite link disappears",
          _ => findAll(className("add-user-link")).toSeq shouldBe empty,
        )
        addTeamLunch(100)
      }

      if (isDevNet) {
        withFrontEnd("bob-validator") { implicit webDriver =>
          enterSplitwellPayment(
            aliceUserPartyId,
            PartyId.tryFromProtoPrimitive(aliceUserPartyId),
            50,
          )

          // Bob is redirected to wallet ..
          clue("accept payment in wallet") {
            eventuallyClickOn(className("payment-accept"))
          }

          // And then back to splitwell, where he is already logged in.
          // Accepting the payment (which triggers the redirect) and seeing
          // the balance update in the splitwell UI both take time,
          // so we use an eventually for each check.
          eventually(60.seconds) {
            findAll(className("balances-table-row")).toSeq.headOption
              .valueOrFail("Failed to find balances table. Did the payment succeed?")
          }
          eventually(60.seconds) {
            inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
              seleniumText(
                row.childElement(className("balances-table-receiver"))
              ) should matchText(aliceUserPartyId)

              row.childElement(className("balances-table-amount")).text.toDouble shouldBe 0.0
            }
            val rows = findAll(className("balance-updates-list-item")).toSeq
            rows should have size 2
            // We don't guarantee an order on ACS requests atm so we assert independent of the specific order.
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description"),
                Seq(aliceUserPartyId, "paid 100.0 CC for Team lunch"),
              )(row)
            )
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description", "receiver"),
                Seq(bobUserPartyId, "sent 50.0 CC to", aliceUserPartyId),
              )(row)
            )
          }
        }
      }
    }
  }

  "test the CNS ui of a validator" in { _ =>
    val aliceUser = auth0Users.get("alice-validator").value

    withFrontEnd("alice-validator") { implicit webDriver =>
      loginAndOnboardToWalletUi(aliceUser)

      if (isDevNet) {
        // On DevNet-like clusters, we test the full CNS entry creation flow

        // Generate new random ANS names to avoid conflicts between multiple preflight check runs
        val entryId = (new scala.util.Random).nextInt().toHexString
        val ansName = s"alice_${entryId}.unverified.cns"

        tapAmulets(100)
        reserveAnsNameFor(
          () =>
            auth0Login(
              aliceUser,
              ansUiUrl,
              () => {
                waitForQuery(id("entry-name-field"))
                find(id("entry-name-field")) should not be empty
              },
            ),
          ansName,
          "1.0000000000",
          "USD",
          "90 days",
        )
      } else {
        // On non-DevNet clusters, we only test logging in to the directory UI
        auth0Login(
          aliceUser,
          ansUiUrl,
          () => {
            waitForQuery(id("entry-name-field"))
            find(id("entry-name-field")) should not be empty
          },
        )
      }
    }
  }

  "can dump participant identities of validator" in { _ =>
    validatorClient.dumpParticipantIdentities()
  }

  "connect to all sequencers stated in latest DsoRules contract" in { implicit env =>
    val sv1ScanClient = scancl("sv1Scan")
    eventually() {
      val connections = inside(sv1ScanClient.listDsoSequencers()) {
        case Seq(DomainSequencers(_, connections)) => connections
      }
      connections should not be empty
      val latestMigrationId = connections.map(_.migrationId).max
      val availableConnections = connections.filter(connection =>
        connection.migrationId == latestMigrationId &&
          connection.url != "" &&
          // added 60s grace period for the polling trigger interval 30s + other latency
          env.environment.clock.now.toInstant.isAfter(connection.availableAfter.plusSeconds(60))
      )
      val (expectedSequencerConnections, _) =
        Endpoint
          .fromUris(NonEmpty.from(availableConnections.map(conn => new URI(conn.url))).value)
          .value

      val domainConnectionConfig = validatorClient.decentralizedSynchronizerConnectionConfig()
      val connectedEndpointSet =
        domainConnectionConfig.sequencerConnections.connections.flatMap(_.endpoints).toSet

      connectedEndpointSet should contain allElementsOf expectedSequencerConnections.map(_.toString)

      domainConnectionConfig.sequencerConnections.sequencerTrustThreshold shouldBe Thresholds
        .sequencerConnectionsSizeThreshold(
          domainConnectionConfig.sequencerConnections.connections.size
        )
        .value
      domainConnectionConfig.sequencerConnections.submissionRequestAmplification.factor shouldBe Thresholds
        .sequencerSubmissionRequestAmplification(
          domainConnectionConfig.sequencerConnections.connections.size
        )
        .value
    }
  }

  private def auth0Login(
      user: Auth0User,
      url: String,
      assertCompleted: () => org.scalatest.Assertion,
  )(implicit
      webDriver: WebDriverType
  ) = {
    clue(s"Auth0 user login as: ${user.id} (${user.email})") {
      completeAuth0LoginWithAuthorization(
        url,
        user.email,
        user.password,
        assertCompleted,
      )
    }
  }

  private def loginAndOnboardToWalletUi(
      user: Auth0User
  )(implicit webDriver: WebDriverType): String = {
    loginAndOnboardToUiViaAuth0(user, walletUiUrl)
  }

  private def loginToSplitwellUi(
      user: Auth0User
  )(implicit webDriver: WebDriverType) = {
    clue(s"Logging in to splitwell UI at: ${splitwellUiUrl}") {
      auth0Login(
        user,
        splitwellUiUrl,
        () => find(id("group-id-field")) should not be empty,
      )
      waitForQuery(id("logged-in-user"))
    }
  }

  private def loginAndOnboardToUiViaAuth0(
      user: Auth0User,
      url: String,
  )(implicit webDriver: WebDriverType): String = {

    clue(s"Logging in to wallet UI at: ${url}") {
      auth0Login(
        user,
        url,
        () => find(id("onboard-button")) should not be empty,
      )

      // TODO(#12457): This is a workaround to bypass slowness of wallet user onboarding
      actAndCheck(timeUntilSuccess = 2.minute)(
        "Onboard wallet user", {
          click on "onboard-button"
        },
      )(
        "Party ID is displayed after onboarding finishes",
        _ => {
          findAll(className("party-id")) should have size 1
        },
      )

      copyPartyId()
    }
  }

  private def copyPartyId()(implicit webDriver: WebDriverType): String = {
    clue(s"Copying party ID") {
      find(className("party-id")).fold(throw new Error("Party ID display expected, but not found"))(
        elm => seleniumText(elm)
      )
    }
  }
}

class RunbookValidatorPreflightIntegrationTest extends ValidatorPreflightIntegrationTestBase {

  override protected val isDevNet = true
  override protected val auth0 =
    auth0UtilFromEnvVars("https://canton-network-validator-test.us.auth0.com", "validator")

  override protected val validatorName = "validator"
  override protected val validatorAuth0Secret = "cznBUeB70fnpfjaq9TzblwiwjkVyvh5z"
  override protected val validatorAuth0Audience = "https://validator.example.com/api"
  override protected val includeSplitwellTests = false

  // TODO(tech-debt): consider de-hardcoding this
  override protected val validatorWalletUser = "auth0|6526fab5214c99a9a8e1e3cc"

  // TODO(#8300): remove this check once canton handles sequencer connections more gracefully
  override def checkValidatorIsConnectedToSvRunbook() = "Validator is connected to SV runbook" in {
    implicit env =>
      val sv = sv_client("sv")
      eventually() {
        val dsoInfo = sv.getDsoInfo()
        val nodeState = dsoInfo.svNodeStates.get(dsoInfo.svParty).value.payload
        val domainConfig = nodeState.state.synchronizerNodes.asScala.values.headOption.value
        val (svSequencerEndpoint, _) = Endpoint
          .fromUris(NonEmpty.from(Seq(new URI(domainConfig.sequencer.toScala.value.url))).value)
          .value
        val domainConnectionConfig = validatorClient.decentralizedSynchronizerConnectionConfig()
        val connectedEndpointSet =
          domainConnectionConfig.sequencerConnections.connections.flatMap(_.endpoints).toSet
        connectedEndpointSet should contain(svSequencerEndpoint.forgetNE.loneElement.toString)
      }
  }

}

class Validator1PreflightIntegrationTest extends ValidatorPreflightIntegrationTestBase {

  override protected val isDevNet = true
  override protected val auth0 =
    auth0UtilFromEnvVars("https://canton-network-dev.us.auth0.com", "dev")
  override protected val validatorName = "validator1"
  override protected val validatorAuth0Secret = "cf0cZaTagQUN59C1HBL2udiIBdFh2CWq"
  override protected val validatorAuth0Audience = "https://canton.network.global"

  // TODO(tech-debt): consider de-hardcoding this
  override protected val validatorWalletUser = "auth0|63e3d75ff4114d87a2c1e4f5"
}
