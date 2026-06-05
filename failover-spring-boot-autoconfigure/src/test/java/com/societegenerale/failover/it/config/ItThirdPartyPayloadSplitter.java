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

package com.societegenerale.failover.it.config;

import com.societegenerale.failover.core.payload.splitter.PayloadSplitter;
import com.societegenerale.failover.core.payload.splitter.RecoverContext;
import com.societegenerale.failover.core.payload.splitter.StoreContext;
import com.societegenerale.failover.it.domain.ThirdPartiesResult;
import com.societegenerale.failover.it.domain.ThirdParty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Custom {@link PayloadSplitter} for integration tests.
 *
 * <p>Args shape (3 elements, matching {@link com.societegenerale.failover.it.service.ThirdPartyService#fetchAll}):
 * <pre>
 *   composite args : ["status",  "1,2,3",  "region"]
 *   slice args     : ["status",  "1",      "region"]   (one per ID)
 * </pre>
 *
 * <ul>
 *   <li>{@code args[0]} = status filter — preserved unchanged in every slice</li>
 *   <li>{@code args[1]} = comma-separated IDs — split into individual IDs on scatter; rebuilt on merge</li>
 *   <li>{@code args[2]} = region — preserved unchanged in every slice</li>
 * </ul>
 *
 * <p>Bean name: {@code itThirdPartyPayloadSplitter}, referenced in
 * {@link com.societegenerale.failover.it.service.ThirdPartyService#fetchAll}.
 *
 * @author Anand Manissery
 */
@Component("itThirdPartyPayloadSplitter")
public class ItThirdPartyPayloadSplitter implements PayloadSplitter<ThirdPartiesResult, ThirdParty> {

    @Override
    public List<StoreContext<ThirdParty>> splitOnStore(StoreContext<ThirdPartiesResult> context) {
        String status = (String) context.getArgs().getFirst();
        String region = (String) context.getArgs().getLast();
        return context.getPayload().getThirdParties().stream()
                .map(tp -> StoreContext.<ThirdParty>builder()
                        .failover(context.getFailover())
                        .args(List.of(status, String.valueOf(tp.getId()), region))
                        .payload(tp)
                        .build())
                .toList();
    }

    @Override
    public List<RecoverContext<ThirdParty>> splitOnRecover(RecoverContext<ThirdPartiesResult> context) {
        String status  = (String) context.getArgs().getFirst();
        String csvIds  = (String) context.getArgs().get(1);
        String region  = (String) context.getArgs().getLast();
        return Arrays.stream(csvIds.split(","))
                .map(id -> RecoverContext.<ThirdParty>builder()
                        .failover(context.getFailover())
                        .args(List.of(status, id.trim(), region))
                        .clazz(ThirdParty.class)
                        .cause(context.getCause())
                        .build())
                .toList();
    }

    @Override
    public RecoverContext<ThirdPartiesResult> merge(List<RecoverContext<ThirdParty>> contexts) {
        List<ThirdParty> parties = contexts.stream()
                .map(RecoverContext::getPayload)
                .toList();
        ThirdPartiesResult merged = new ThirdPartiesResult();
        merged.setThirdParties(parties);

        String status    = (String) contexts.getFirst().getArgs().getFirst();
        String mergedIds = contexts.stream()
                .map(ctx -> (String) ctx.getArgs().get(1))
                .collect(Collectors.joining(","));
        String region    = (String) contexts.getFirst().getArgs().getLast();

        return RecoverContext.<ThirdPartiesResult>builder()
                .failover(contexts.getFirst().getFailover())
                .args(List.of(status, mergedIds, region))
                .clazz(ThirdPartiesResult.class)
                .cause(contexts.getFirst().getCause())
                .payload(merged)
                .build();
    }
}