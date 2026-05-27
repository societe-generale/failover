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

package com.societegenerale.failover.core.payload;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.domain.Metadata;
import com.societegenerale.failover.domain.Referential;
import com.societegenerale.failover.domain.ReferentialAware;

/**
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
        enrichPayloadInfo(failover, clazz, referentialPayload, payload, cause);
        return rPayload;
    }

    private void enrichPayloadInfo(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload, T payload, Throwable cause) {
        if(payload !=null) {
            if (Referential.class.isAssignableFrom(payload.getClass())) {
                var referential = (Referential) payload;
                referential.setUpToDate(referentialPayload.isUpToDate());
                referential.setAsOf(referentialPayload.getAsOf());
                if(cause != null) {
                    var metadata = referential.getMetadata();
                    metadata
                            .withInfo("exception-name",  cause.getClass().getName())
                            .withInfo("cause", cause.getMessage());
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
                            .withInfo("exception-name",  cause.getClass().getName())
                            .withInfo("cause", cause.getMessage());
                    populateAdditionalInfoOnMetadata(failover, clazz, referentialPayload, payload, cause, metadata);
                    referentialAwarePayload.setMetadata(metadata);
                }
            }
        }
    }

    protected T extractPayload(Class<T> clazz, ReferentialPayload<T> referentialPayload) {
        // you can override this to provide an empty payload in case payload is null, in that case error info will be populated in all scenarios (unrecovered case)
        return referentialPayload.getPayload();
    }

    protected void populateAdditionalInfoOnMetadata(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload, T payload, Throwable cause, Metadata metadata) {
        // do nothing, this can be used if users want to provide additional information to the payload metadata
    }

}
