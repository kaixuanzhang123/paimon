/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.rest.auth;

/** AuthProviderEnum. */
public enum AuthProviderEnum {
    BEAR("bear"),
    DLF("dlf");

    private final String identifier;

    AuthProviderEnum(String identifier) {
        this.identifier = identifier;
    }

    public String identifier() {
        return identifier;
    }

    public static AuthProviderEnum fromString(String identifier) {
        for (AuthProviderEnum authProviderEnum : AuthProviderEnum.values()) {
            if (authProviderEnum.identifier.equals(identifier)) {
                return authProviderEnum;
            }
        }
        throw new IllegalArgumentException("Unknown AuthProvider type: " + identifier);
    }
}
