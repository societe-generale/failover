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

package com.societegenerale.failover.core.report.manifest;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

/**
 * Abstraction for locating classpath resources by name.
 *
 * @author Anand Manissery
 */
public interface ResourceLoader {

    /**
     * Returns all resource URLs registered under the given name on the classpath.
     *
     * @param name the resource path (e.g. {@code "META-INF/MANIFEST.MF"})
     * @return enumeration of matching URLs; never {@code null}
     * @throws IOException if the underlying class loader lookup fails
     */
    Enumeration<URL> getResourcesUrls(String name) throws IOException;
}
