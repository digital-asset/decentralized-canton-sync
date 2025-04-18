package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon
import org.openqa.selenium.By
import org.scalatest.Assertion
import scala.concurrent.duration.DurationInt

trait SvFrontendTestUtil extends TestCommon {
  this: CommonAppInstanceReferences & FrontendTestCommon =>

  def setExpirationDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-expiration", dateTime)
  }

  def setEffectiveDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-effectivity", dateTime)
  }

  def setAmuletConfigDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-amulet-configuration", dateTime)
  }

  def setDateTime(party: String, pickerId: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ): Assertion = {
    clue(s"$party selects the date $dateTime") {
      val dateTimePicker = webDriver.findElement(By.id(pickerId))
      eventually() {
        dateTimePicker.clear()
        dateTimePicker.click()
        // Typing in the "filler" characters can mess up the input badly
        // Note: this breaks on Feb 29th because the date library validates that the day
        // of the month is valid for the year you enter and because the year is entered
        // one digit at a time that fails and it resets it to Feb 28th. Luckily,
        // this does not happen very often …
        dateTimePicker.sendKeys(dateTime.replaceAll("[^0-9APM]", ""))
        eventually()(
          dateTimePicker.getAttribute("value").toLowerCase shouldBe dateTime.toLowerCase
        )
      }
    }
  }

  def clickVoteRequestSubmitButtonOnceEnabled(
      enabled: Boolean = true
  )(implicit webDriver: WebDriverType) = {
    clue("wait for the submit button to become clickable") {
      eventually(5.seconds)(
        find(id("create-voterequest-submit-button")).value.isEnabled shouldBe enabled
      )
    }
    if (enabled) {
      clue("click the submit button") {
        click on "create-voterequest-submit-button"
      }
      clue("click accept on the confirmation dialog") {
        click on "vote-confirmation-dialog-accept-button"
      }
    }
  }
}
