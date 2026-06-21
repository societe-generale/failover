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

package com.societegenerale.failover.dashboard.metrics.source.prometheus;

/**
 * Signals that a Prometheus query could not be completed (transport error, non-success response, or an
 * unparseable body). {@link PrometheusMetricsSource} catches it and falls back to the local registry so
 * the dashboard never goes dark.
 *
 * @author Anand Manissery
 */
class PrometheusException extends RuntimeException {

    PrometheusException(String message, Throwable cause) {
        super(message, cause);
    }
}
