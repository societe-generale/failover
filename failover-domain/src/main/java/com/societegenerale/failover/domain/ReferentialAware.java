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

package com.societegenerale.failover.domain;

import java.time.LocalDateTime;

/**
 * Interface to be implemented by referential entities that wish to be aware of the failover metadata information.
 * The failover metadata information contains UpToDate {@link Boolean} , AsOf {@link LocalDateTime} information.
 *
 * @author Anand Manissery
 * @since 1.0.0
 */
public interface ReferentialAware {

    /**
     * Whether the value of the referential data is up-to-date or not.
     * @param upToDate flag to be set.
     */
    void setUpToDate(Boolean upToDate);


    /**
     * The value of referential data is as of the given time
     * @param asOf time to be set.
     */
    void setAsOf(LocalDateTime asOf);
}
