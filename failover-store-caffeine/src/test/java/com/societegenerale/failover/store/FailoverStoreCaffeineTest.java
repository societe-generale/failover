/*
 * Copyright 2022-2023, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.store;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.time.LocalDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverStoreCaffeineTest {

    public static final String NAME = "third-party-failover";

    @Mock
    private Failover failover;

    @Mock
    private FailoverScanner failoverScanner;

    private FailoverStoreCaffeine<ThirdParty> failoverStoreCaffeine;

    private final ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(NAME, "1", true, now(), now(), new ThirdParty(1L, "TATA", 5));

    @BeforeEach
    void setUp() {
        given(failoverScanner.findFailoverByName(referentialPayload.getName())).willReturn(failover);
        given(failover.expiryDuration()).willReturn(1L);
        given(failover.expiryUnit()).willReturn(ChronoUnit.MINUTES);
        failoverStoreCaffeine = new FailoverStoreCaffeine<>(failoverScanner);
    }

    @Test
    void shouldStoreTheReferential() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);
    }

    @Test
    void shouldReturnTheReferentialWhenFoundForTheGivenKey() {
        failoverStoreCaffeine.store(referentialPayload);

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreCaffeine.find(NAME, "1");

        assertThat(result).isPresent().contains(referentialPayload);
    }

    @Test
    void shouldReturnEmptyWhenNoReferentialFoundForTheGivenKey() {
        failoverStoreCaffeine.store(referentialPayload);

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreCaffeine.find(NAME, "2");

        assertThat(result).isNotPresent();
    }

    @Test
    void shouldReturnEmptyWhenNoReferentialFoundForTheGivenName() {
        failoverStoreCaffeine.store(referentialPayload);

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreCaffeine.find("DUMMY-KEY", "1");

        assertThat(result).isNotPresent();
    }

    @Test
    void shouldDeleteTheReferential() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreCaffeine.delete(referentialPayload);

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreCaffeine.find(NAME, "1");
        assertThat(result).isNotPresent();
    }


    @Test
    void shouldExecuteDeleteWithoutExceptionWhenNoReferentialNameFoundForDelete() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreCaffeine.delete(new ReferentialPayload<>("DUMMY-NAME", "1", true, now(), now(), new ThirdParty(1L, "TATA", 5)));

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreCaffeine.find(NAME, "1");
        assertThat(result).isPresent();
    }

    @Test
    void shouldExecuteDeleteWithoutExceptionWhenNoReferentialKeyFoundInCacheForDelete() {
        failoverStoreCaffeine.store(referentialPayload);
        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isPresent().contains(referentialPayload);

        failoverStoreCaffeine.delete(new ReferentialPayload<>(NAME, "DUMMY-KEY", true, now(), now(), new ThirdParty(1L, "TATA", 5)));

        Optional<ReferentialPayload<ThirdParty>> result = failoverStoreCaffeine.find(NAME, "1");
        assertThat(result).isPresent();
    }

    @Test
    void shouldInvalidateTheDataOnExpiry() {
        // given
        given(failover.expiryDuration()).willReturn(1L);
        given(failover.expiryUnit()).willReturn(ChronoUnit.SECONDS);

        // when
        failoverStoreCaffeine.store(referentialPayload);

        // and : do nothing on clear
        failoverStoreCaffeine.cleanByExpiry(now());

        // then : wait and assert for cache expiry
        await().atMost(2, TimeUnit.SECONDS).until(()-> !failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey()).isPresent());

        assertThat(failoverStoreCaffeine.find(referentialPayload.getName(), referentialPayload.getKey())).isNotPresent();
    }

}

@Data
@AllArgsConstructor
class ThirdParty  {
    private Long id;
    private String name;
    private int score;
}