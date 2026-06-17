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

package com.societegenerale.failover.core.payload;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.domain.Metadata;
import com.societegenerale.failover.domain.Referential;
import com.societegenerale.failover.domain.ReferentialAware;

import static com.societegenerale.failover.core.util.CommonsUtil.*;

/**
 * Default {@link PayloadEnricher} that propagates failover metadata ({@code upToDate}, {@code asOf},
 * exception info) into payloads that implement {@link com.societegenerale.failover.domain.Referential}
 * or {@link com.societegenerale.failover.domain.ReferentialAware}.
 *
 * @param <T> the type of the payload to enrich
 * @author Anand Manissery
 */
public class DefaultPayloadEnricher<T> implements PayloadEnricher<T> {

    @Override
    public ReferentialPayload<T> enrichOnStore(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload) {
        var payload = extractPayload(clazz, referentialPayload);
        enrichPayloadInfo(failover, clazz, referentialPayload, payload, null);
        return referentialPayload;
    }

    @Override
    public ReferentialPayload<T> enrichOnRecover(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload, Throwable cause) {
        var rPayload = referentialPayload==null ? new ReferentialPayload<T>() :  referentialPayload;
        var payload = extractPayload(clazz, rPayload);
        enrichPayloadInfo(failover, clazz, rPayload, payload, cause);
        return rPayload;
    }

    private void enrichPayloadInfo(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload, T payload, Throwable cause) {
        if(payload !=null) {
            var finalRootCause = finalRootCauseOf(cause);
            if (Referential.class.isAssignableFrom(payload.getClass())) {
                var referential = (Referential) payload;
                referential.setUpToDate(referentialPayload.isUpToDate());
                referential.setAsOf(referentialPayload.getAsOf());
                if(cause != null) {
                    var metadata = referential.getMetadata();
                    metadata
                            .withInfo("exception-name",  canonicalTypeOf(cause))
                            .withInfo("cause", messageOf(cause))
                            .withInfo("final-root-cause-name", canonicalTypeOf(finalRootCause))
                            .withInfo("final-root-cause", messageOf(finalRootCause));
                    populateAdditionalInfoOnMetadata(failover, clazz, referentialPayload, payload, cause, metadata);
                    referential.setMetadata(metadata);
                }
            }
            if (ReferentialAware.class.isAssignableFrom(payload.getClass())) {
                var referentialAwarePayload = (ReferentialAware) payload;
                referentialAwarePayload.setUpToDate(referentialPayload.isUpToDate());
                referentialAwarePayload.setAsOf(referentialPayload.getAsOf());
                if(cause != null) {
                    var metadata = new Metadata();
                    metadata
                            .withInfo("exception-name",  canonicalTypeOf(cause))
                            .withInfo("cause", messageOf(cause))
                            .withInfo("final-root-cause-name", canonicalTypeOf(finalRootCause))
                            .withInfo("final-root-cause", messageOf(finalRootCause));
                    populateAdditionalInfoOnMetadata(failover, clazz, referentialPayload, payload, cause, metadata);
                    referentialAwarePayload.setMetadata(metadata);
                }
            }
        }
    }

    /**
     * Extracts the payload from the referential wrapper. Override to supply a non-null fallback
     * when the stored payload is absent (so metadata is still populated in the unrecovered case).
     *
     * @param clazz              expected payload type
     * @param referentialPayload the wrapper holding the stored payload
     * @return the extracted payload, possibly {@code null}
     */
    protected T extractPayload(Class<T> clazz, ReferentialPayload<T> referentialPayload) {
        // override to provide an empty payload when null so error info is populated in the unrecovered case
        return referentialPayload.getPayload();
    }

    /**
     * Extension point for adding custom entries to the recovery metadata.
     * Called when the payload implements {@link com.societegenerale.failover.domain.Referential}
     * or {@link com.societegenerale.failover.domain.ReferentialAware} and a cause is present.
     *
     * @param failover           annotation metadata for the failover point
     * @param clazz              expected payload type
     * @param referentialPayload the referential wrapper
     * @param payload            the extracted payload
     * @param cause              the exception that triggered recovery
     * @param metadata           the metadata map to populate
     */
    protected void populateAdditionalInfoOnMetadata(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload, T payload, Throwable cause, Metadata metadata) {
        // do nothing; override to add application-specific metadata entries
    }

}
