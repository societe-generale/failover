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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.key.KeyGenerator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Custom {@link KeyGenerator} that always returns the same raw key regardless of method arguments.
 *
 * <p>Used by the IT tests to demonstrate that the key-generator extension point is wired into the
 * failover framework correctly. Because every call to the annotated method maps to the same
 * database row, successive calls with different arguments overwrite each other — and recovery for
 * any argument value returns the last-stored payload.
 *
 * <p>Contrast with the default key generator, which derives a unique key per unique argument
 * combination, so each argument value maps to its own row.
 *
 * <p>Bean name: {@code fixedKeyGenerator}, referenced in
 * {@link com.societegenerale.failover.it.service.ThirdPartyService#fetchOneWithFixedKey}.
 *
 * @author Anand Manissery
 */
@Component("fixedKeyGenerator")
public class FixedKeyGenerator implements KeyGenerator {

    static final String FIXED_RAW_KEY = "fixed-key-it-test";

    @Override
    public String key(Failover failover, List<Object> args) {
        return FIXED_RAW_KEY;   // same for every call — args are intentionally ignored
    }
}