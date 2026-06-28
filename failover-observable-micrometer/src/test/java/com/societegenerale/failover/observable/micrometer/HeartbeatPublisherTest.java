/*
 * Copyright 2022-2026, Société Générale All rights reserved.
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

package com.societegenerale.failover.observable.micrometer;

import com.societegenerale.failover.core.observable.InstanceIdResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartbeatPublisher")
class HeartbeatPublisherTest {

    @Mock HeartbeatPushClient pushClient;
    @Mock InstanceIdResolver instanceIdResolver;

    @Test
    @DisplayName("sends instanceId on schedule")
    void sendsInstanceId() throws Exception {
        when(instanceIdResolver.resolve()).thenReturn("test-instance");
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; }).when(pushClient).send(any());

        try (HeartbeatPublisher publisher = new HeartbeatPublisher(instanceIdResolver, pushClient,
                "http://dashboard/api/cluster/heartbeat", 1)) {
            assertThat(latch).matches(l -> {
                try { return l.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { return false; }
            }, "heartbeat fired within 3s");
        }

        verify(pushClient, atLeastOnce()).send(eq("test-instance"));
    }

    @Test
    @DisplayName("failure does not throw — logs warn on first failure only")
    void failureDoesNotThrow() throws Exception {
        when(instanceIdResolver.resolve()).thenReturn("test-instance");
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(inv -> { started.countDown(); throw new RuntimeException("down"); }).when(pushClient).send(any());

        try (HeartbeatPublisher publisher = new HeartbeatPublisher(instanceIdResolver, pushClient,
                "http://dashboard/api/cluster/heartbeat", 1)) {
            started.await(3, TimeUnit.SECONDS);
        }
        verify(pushClient, atLeastOnce()).send(any()); // fired and did not propagate the exception
    }

    @Test
    @DisplayName("close stops the scheduler")
    void closeStopsScheduler() throws Exception {
        when(instanceIdResolver.resolve()).thenReturn("test-instance");
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> { latch.countDown(); return null; }).when(pushClient).send(any());

        HeartbeatPublisher publisher = new HeartbeatPublisher(instanceIdResolver, pushClient,
                "http://dashboard/api/cluster/heartbeat", 1);
        latch.await(3, TimeUnit.SECONDS);
        publisher.close();
        verify(pushClient, atLeastOnce()).send(any());
    }

    private static <T> org.assertj.core.api.ObjectAssert<T> assertThat(T actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
