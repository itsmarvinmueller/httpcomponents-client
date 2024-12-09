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

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.ParseException;

import org.json.JSONException;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DeprecationInterceptor implements HttpResponseInterceptor {
    private final Set<String> customDeprecationHeader;
    private final Set<String> defaultDeprecationHeader;
    private final CloseableHttpClient client;

    public DeprecationInterceptor(final Set<String> customDeprecationHeader) {
        this.customDeprecationHeader = customDeprecationHeader.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        this.defaultDeprecationHeader = new HashSet<String>();
        defaultDeprecationHeader.add("deprecation");
        defaultDeprecationHeader.add("sunset");
        this.client = HttpClients.createDefault();
    }
    @Override
    public void process(final HttpResponse httpResponse, final EntityDetails entityDetails, final HttpContext httpContext) throws HttpException, IOException {
        // Getting context and request for request method and url.
        final HttpClientContext clientContext = HttpClientContext.castOrCreate(httpContext);
        final HttpRequest request = clientContext.getRequest();
        final String requestMethod = request.getMethod();
        URI requestURL = null;
        try {
            requestURL = request.getUri();
        } catch (final URISyntaxException e) {
            throw new RuntimeException(e);
        }
        // Extract the parameter of the request from the url.
        final String requestQuery = requestURL.getQuery();
        final Set<String> requestParameter = new HashSet<String>();
        if (requestQuery != null) {
            final String[] parameter = requestQuery.split("&");
            for (final String param : parameter) {
                final String[] keyValue = param.split("=", 2);
                requestParameter.add(keyValue[0]);
            }
        }

        // Check for deprecation HTTP-Header
        final Set<String> deprecationHeader = new HashSet<String>();
        deprecationHeader.addAll(defaultDeprecationHeader);
        deprecationHeader.addAll(customDeprecationHeader);
        final boolean deprecationHeaderFound = Arrays.stream(httpResponse.getHeaders()).anyMatch(header -> deprecationHeader.contains(header.getName()));

        // Set default values for deprecation oas
        boolean areParameterDeprecated = false;
        Set<String> deprecatedParameterNames = new HashSet<String>();
        boolean isOperationDeprecated = false;

        // Advanced deprecation analysis if no deprecation header are present.
        if (!deprecationHeaderFound) {
            // Init all variables that are needed for the analysis
            final OpenAPIParser oasParser = new OpenAPIParser();

            // Build the url that is used as an entry point for searching the oas.
            // Here we use the request url but without the query and fragment.
            URI requestBaseURL = null;
            try {
                requestBaseURL = new URI(requestURL.getScheme(), requestURL.getUserInfo(), requestURL.getHost(), requestURL.getPort(), requestURL.getPath(), null, null);
            } catch (final URISyntaxException e) {
                throw new RuntimeException(e);
            }

            // Try to find the oas.
            DeprecationPair<JSONObject, String> oasFindings = null;
            try {
                oasFindings = findOpenAPISpecification(requestBaseURL);
            } catch (final URISyntaxException e) {
                throw new RuntimeException(e);
            }
            final JSONObject openAPISpecification = oasFindings.first;
            final String path = oasFindings.second;

            // Analyse the oas if existent, otherwise skip.
            if (openAPISpecification != null && path.startsWith("/")) {
                isOperationDeprecated = oasParser.isOperationDeprecated(openAPISpecification, path, requestMethod);

                // Check if request parameter are deprecated, if existing.
                if (requestURL.getQuery() != null) {
                    final DeprecationPair<Boolean, Set<String>> oasParameterFindings = oasParser.areParameterDeprecated(openAPISpecification, path, requestMethod, requestParameter);
                    areParameterDeprecated = oasParameterFindings.first;
                    deprecatedParameterNames = oasParameterFindings.second;
                }
            }
        }

        // Create a combined boolean that indicates if anything of the call destination is deprecated.
        final boolean deprecated = deprecationHeaderFound || isOperationDeprecated || areParameterDeprecated;
        // Add deprecation boolean and deprecated parameter to the http context.
        httpContext.setAttribute("deprecated", deprecated);
        httpContext.setAttribute("deprecatedParameter", deprecatedParameterNames);
    }

    private DeprecationPair<JSONObject, String> findOpenAPISpecification(final URI initialURL) throws URISyntaxException, IOException {
        JSONObject openAPISpecification = null;
        boolean searchOAS = true;
        URI oasURL = initialURL;
        final URI endURL = new URI(initialURL.getScheme(), initialURL.getUserInfo(), initialURL.getHost(), initialURL.getPort(), null, null, null);
        String path = "";

        while (searchOAS) {
            // Try the JSON version
            final URI jsonOasURL = new URI(oasURL.toString() + "/openapi.json");
            try (CloseableHttpResponse jsonOasResponse = requestOAS(jsonOasURL)) {
                if (jsonOasResponse.getCode() == 200) {
                    try {
                        final String responseBody = EntityUtils.toString(jsonOasResponse.getEntity());
                        final JSONObject possibleOpenAPISpecification = new JSONObject(responseBody);
                        if (possibleOpenAPISpecification.has("openapi") && possibleOpenAPISpecification.has("info")) {
                            openAPISpecification = possibleOpenAPISpecification;
                            break;
                        }
                    } catch (final JSONException e) {
                        System.err.println("No JSON: " + jsonOasURL.toString());
                        System.err.println("Skip. Try subpath.");
                    }
                }
            } catch (final ParseException e) {
                throw new RuntimeException(e);
            }

            // Try the YAML version
            final URI yamlOasURL = new URI(oasURL.toString() + "/openapi.yaml");
            try (CloseableHttpResponse yamlOasResponse = requestOAS(yamlOasURL)) {
                if (yamlOasResponse.getCode() == 200) {
                    final Yaml yamlParser = new Yaml();
                    try {
                        final String responseBody = EntityUtils.toString(yamlOasResponse.getEntity());
                        final Map<String, Object> parsedYaml = yamlParser.load(responseBody);
                        final JSONObject possibleOpenAPISpecification = new JSONObject(parsedYaml);
                        if (possibleOpenAPISpecification.has("openapi") && possibleOpenAPISpecification.has("info")) {
                            openAPISpecification = possibleOpenAPISpecification;
                            break;
                        }
                    } catch (final JSONException | ClassCastException e) {
                        System.err.println("Error occurred in YAML OAS: " + yamlOasURL.toString());
                        System.err.println("Skip. Try subpath.");
                    } catch (final ParseException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            // Set new url for OAS search or end search if search url is only the url host section.
            if (oasURL != endURL) {
                final int lastPathSegmentIndex = oasURL.getPath().lastIndexOf("/");
                path = (lastPathSegmentIndex > 0) ? oasURL.getPath().substring(lastPathSegmentIndex) + path : path;
                final String newPath = (lastPathSegmentIndex > 0) ? oasURL.getPath().substring(0, lastPathSegmentIndex) : "";
                if (!newPath.equals("")) {
                    oasURL = oasURL.resolve(newPath);
                } else {
                    searchOAS = false;
                }
            } else {
                searchOAS = false;
            }
        }
        return new DeprecationPair<JSONObject, String>(openAPISpecification, path);
    }

    private CloseableHttpResponse requestOAS(final URI url) throws IOException {
        final HttpGet request = new HttpGet(url);
        return client.execute(request);
    }
}
