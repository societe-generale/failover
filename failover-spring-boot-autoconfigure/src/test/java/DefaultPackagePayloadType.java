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

/**
 * Test-only fixture deliberately declared in the <b>default (unnamed) package</b> so that
 * {@code getPackageName()} returns the empty string. Used to exercise the empty-package skip
 * branch in {@code FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses}. Not a Spring bean;
 * never component-scanned (the test application lives under {@code com.societegenerale.*}).
 */
public class DefaultPackagePayloadType {
}