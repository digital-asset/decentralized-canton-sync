package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  TestCommon,
  SpliceTestConsoleEnvironment,
}
import com.digitalasset.canton.DomainAlias
import org.scalactic.source
import org.scalatest.prop.TableDrivenPropertyChecks.forEvery as tForEvery

trait MultiDomainTestUtil extends TestCommon {
  def assertAllOn(
      onDomain: DomainAlias
  )(cids: ContractId[?]*)(implicit env: SpliceTestConsoleEnvironment, pos: source.Position) = {
    val cidStrings = cids.map(c => c.contractId)
    val domainId = splitwellBackend.participantClient.domains.id_of(onDomain)
    val domains =
      splitwellBackend.participantClient.ledger_api_extensions.acs.lookup_contract_domain(
        splitwellBackend.getProviderPartyId(),
        cidStrings.toSet,
      )
    tForEvery(Table("contractId", cidStrings*)) { cid =>
      domains.get(cid) shouldBe Some(domainId)
    }
  }
}