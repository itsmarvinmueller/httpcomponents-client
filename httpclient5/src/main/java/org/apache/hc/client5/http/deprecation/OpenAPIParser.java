/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.client5.http.deprecation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

class OpenAPIParser {
    public boolean isOperationDeprecated(final JSONObject oas, final String requestPath, final String requestMethod) {
        // Check if the "Paths Object" exists, if not the OpenAPI-Specification is not valid.
        final JSONObject pathsObject = oas.optJSONObject("paths");
        if (pathsObject == null) {
            throw new RuntimeException("OpenAPI specification invalid.");
        }

        // Check if the "requestPath" exists in the "Paths Object".
        final JSONObject pathItemObject = pathsObject.optJSONObject(requestPath);
        if (pathItemObject == null) {
            throw new RuntimeException("Path " + requestPath + " not found in the OpenAPI specification.");
        }

        // Check if the "requestMethod" exists in the "Path Item Object".
        final JSONObject operationObject = pathItemObject.optJSONObject(requestMethod.toLowerCase());
        if (operationObject == null) {
            throw new RuntimeException("Method " + requestMethod.toUpperCase() + " for path " + requestPath + " not found in the OpenAPI specification.");
        }

        // Return whether the "Operation Object" is deprecated.
        return operationObject.optBoolean("deprecated", false);
    }

    public DeprecationPair<Boolean, Set<String>> areParameterDeprecated(final JSONObject oas, final String requestPath, final String requestMethod, final Set<String> requestParameter) {
        // Check if the "Paths Object" exists, if not the OpenAPI-Specification is not valid.
        final JSONObject pathsObject = oas.optJSONObject("paths");
        if (pathsObject == null) {
            throw new RuntimeException("OpenAPI specification invalid.");
        }

        // Check if the "requestPath" exists in the "Paths Object".
        final JSONObject pathItemObject = pathsObject.optJSONObject(requestPath);
        if (pathItemObject == null) {
            throw new RuntimeException("Path " + requestPath + " not found in the OpenAPI specification.");
        }

        // Check if the "requestMethod" exists in the "Path Item Object".
        final JSONObject operationObject = pathItemObject.optJSONObject(requestMethod.toLowerCase());
        if (operationObject == null) {
            throw new RuntimeException("Method " + requestMethod.toUpperCase() + " for path " + requestPath + " not found in the OpenAPI specification.");
        }

        // Check if the array of "Parameter Object" exists in the "Operation Object".
        final JSONArray parameterObjectArray = operationObject.optJSONArray("parameters");
        if (parameterObjectArray == null) {
            throw new RuntimeException("No parameters found for path $requestPath with method ${requestMethod.uppercase()} in the OpenAPI specification.");
        }

        // Empty set for the parameter that are deprecated and used in the request.
        final Set<String> deprecatedParameters = new HashSet<String>();
        // Loop over all "Parameter Object" in the array, check whether they are deprecated and add the parameter name to the set if deprecated.
        // Additionally, we only look at the parameter that are present in the query, because our "requestParameter" are extracted from the query.
        for (int i = 0; i < parameterObjectArray.length(); i++) {
            final JSONObject parameterObject = parameterObjectArray.optJSONObject(i);
            if (parameterObject.optBoolean("deprecated", false) && requestParameter.contains(parameterObject.optString("name")) && Objects.equals(parameterObject.getString("in"), "query")) {
                deprecatedParameters.add(parameterObject.optString("name"));
            }
        }
        // Return a pair of a boolean that indicates if "requestParameter" are deprecated and a set of those.
        return new DeprecationPair<Boolean, Set<String>>(!deprecatedParameters.isEmpty(), deprecatedParameters);
    }
}
