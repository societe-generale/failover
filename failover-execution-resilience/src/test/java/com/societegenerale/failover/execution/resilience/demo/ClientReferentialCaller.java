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

package com.societegenerale.failover.execution.resilience.demo;

import com.societegenerale.failover.annotations.Failover;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * @author Anand Manissery
 */
@Component
@AllArgsConstructor
public class ClientReferentialCaller {

    private ClientReferentialExecutor clientReferentialExecutor;

    @Failover(name = "client-by-id")
    public Client findClientById(Long id) {
        return clientReferentialExecutor.findClientById(id);
    }
}
