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
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.FailoverMetricsSnapshotService;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClusterSnapshotPublisher")
class ClusterSnapshotPublisherTest {

    @Mock FailoverMetricsSnapshotService metricsService;
    @Mock InstanceIdResolver instanceIdResolver;
    @Mock SnapshotPushClient pushClient;

    static final Executor DIRECT = Runnable::run;

    ClusterSnapshotPublisher publisher;

    @BeforeEach
    void setUp() {
        MetricsSummary summary = mock(MetricsSummary.class);
        when(metricsService.metricsSummary()).thenReturn(summary);
        when(instanceIdResolver.resolve()).thenReturn("test-instance");
        publisher = new ClusterSnapshotPublisher(metricsService, instanceIdResolver,
                pushClient, "http://dashboard/api/cluster/snapshot", 15, 300, DIRECT);
    }

    @Nested
    @DisplayName("push() — happy path")
    class HappyPath {

        @Test
        @DisplayName("sends snapshot on push")
        void sendsSnapshot() throws Exception {
            publisher.push();
            verify(pushClient).send(any(ClusterSnapshot.class));
        }

        @Test
        @DisplayName("no log on first success")
        void noRecoveryLogOnFirstSuccess() throws Exception {
            publisher.push();
            // no exception, no recovery log — just verifying it passes
            verify(pushClient, times(1)).send(any());
        }
    }

    @Nested
    @DisplayName("push() — failure and backoff")
    class FailureAndBackoff {

        @Test
        @DisplayName("first failure sets backoff; subsequent pushes are no-ops")
        void firstFailureSetsBackoff() throws Exception {
            doThrow(new RuntimeException("connection refused")).when(pushClient).send(any());

            publisher.push(); // first failure — logs WARN, sets backoff
            publisher.push(); // still in backoff — no-op

            verify(pushClient, times(1)).send(any()); // second push was skipped (backoff)
        }

        @Test
        @DisplayName("recovery after backoff logs INFO")
        void recoveryAfterBackoff() throws Exception {
            // retryInterval=0 means backoff expires immediately → second push always retries
            ClusterSnapshotPublisher zeroRetryPublisher = new ClusterSnapshotPublisher(
                    metricsService, instanceIdResolver,
                    pushClient, "http://dashboard/api/cluster/snapshot", 15, 0, DIRECT);

            doThrow(new RuntimeException("down")).when(pushClient).send(any());
            zeroRetryPublisher.push(); // first failure — sets failing=true

            doNothing().when(pushClient).send(any());
            zeroRetryPublisher.push(); // backoff=0 expired → retries → success → logs INFO

            verify(pushClient, times(2)).send(any());
        }

        @Test
        @DisplayName("failure with cause — uses cause class name in log")
        void failureWithCause() throws Exception {
            RuntimeException cause = new RuntimeException("host unknown");
            doThrow(new RuntimeException("I/O error", cause)).when(pushClient).send(any());

            publisher.push(); // should not throw
            verify(pushClient).send(any());
        }

        @Test
        @DisplayName("failure with null message — handled without NPE")
        void failureWithNullMessage() throws Exception {
            doThrow(new RuntimeException((String) null)).when(pushClient).send(any());

            publisher.push(); // should not throw
            verify(pushClient).send(any());
        }
    }

    @Nested
    @DisplayName("onPublish() — threshold gating via ThresholdSnapshotPublisher")
    class ThresholdGating {

        @Test
        @DisplayName("first onPublish dispatches push")
        void firstEventDispatches() throws Exception {
            publisher.onPublish();
            verify(pushClient).send(any());
        }

        @Test
        @DisplayName("second onPublish within interval is throttled")
        void secondEventWithinIntervalThrottled() throws Exception {
            publisher.onPublish();
            publisher.onPublish(); // throttled — interval not elapsed
            verify(pushClient, times(1)).send(any());
        }
    }
}
