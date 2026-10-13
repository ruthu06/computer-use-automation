package com.computeruse.automation.surface;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AddressesTest {

    @Test
    void theQueryAndTheSessionIdAreNotPartOfWhichPageItIs() {
        assertThat(Addresses.page("http://localhost:8080/parabank/openaccount.htm;jsessionid=ABC123?type=1"))
                .isEqualTo("http://localhost:8080/parabank/openaccount.htm");
    }

    @Test
    void aQueryThatCarriesAnAmountIsNotKept() {
        assertThat(Addresses.page("http://localhost:8080/parabank/billpay.htm?amount=250&account=13566"))
                .isEqualTo("http://localhost:8080/parabank/billpay.htm")
                .doesNotContain("250");
    }

    @Test
    void differentPagesOnTheSameHostAreDifferent() {
        assertThat(Addresses.page("http://localhost:8080/parabank/billpay.htm"))
                .isNotEqualTo(Addresses.page("http://localhost:8080/parabank/openaccount.htm"));
    }

    @Test
    void theSamePathOnAnotherHostIsAnotherPage() {
        assertThat(Addresses.page("https://evil.example/parabank/openaccount.htm"))
                .isNotEqualTo(Addresses.page("http://localhost:8080/parabank/openaccount.htm"));
    }

    @Test
    void anAddressWithNoHostIsLeftAlone() {
        assertThat(Addresses.page("about:blank")).isEqualTo("about:blank");
        assertThat(Addresses.page(null)).isEmpty();
    }
}
