/*
 *
 *  Copyright 2016 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.web.security.oauth2.pingfederate;

import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.security.oauth2.resource.ResourceServerProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.AccessTokenConverter;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A remote token services extension for Ping Federate based IDPs.
 *
 * @author tgianos
 * @since 3.0.0
 */
@Slf4j
public class PingFederateTokenServices implements ResourceServerTokenServices {

    protected static final String TOKEN_NAME_KEY = "token";
    protected static final String CLIENT_ID_KEY = "client_id";
    protected static final String CLIENT_SECRET_KEY = "client_secret";
    protected static final String GRANT_TYPE_KEY = "grant_type";
    protected static final String ERROR_KEY = "error";
    protected static final String SCOPE_KEY = "scope";
    protected static final String GRANT_TYPE = "urn:pingidentity.com:oauth2:grant_type:validate_bearer";
    protected static final String AUTHENTICATION_TIMER_NAME = "genie.security.oauth2.pingFederate.authentication.timer";
    protected static final String API_TIMER_NAME = "genie.security.oauth2.pingFederate.api.timer";

    private final AccessTokenConverter converter;

    private final String checkTokenEndpointUrl;
    private final String clientId;
    private final String clientSecret;

    // Metrics
    private final Id tokenValidationError;
    private final Timer authenticationTimer;
    private final Timer pingFederateAPITimer;
    private RestTemplate restTemplate;

    /**
     * Constructor.
     *
     * @param serverProperties The properties of the resource server (Genie)
     * @param converter        The access token converter to use
     * @param registry         The metrics registry to use
     */
    public PingFederateTokenServices(
        @NotNull final ResourceServerProperties serverProperties,
        @NotNull final AccessTokenConverter converter,
        @NotNull final Registry registry
    ) {
        this.tokenValidationError = registry.createId("genie.security.oauth2.pingFederate.tokenValidation.error.rate");
        this.authenticationTimer = registry.timer(AUTHENTICATION_TIMER_NAME);
        this.pingFederateAPITimer = registry.timer(API_TIMER_NAME);
        this.restTemplate = new RestTemplate();
        this.restTemplate.setErrorHandler(
            new DefaultResponseErrorHandler() {
                // Ignore 400
                @Override
                public void handleError(final ClientHttpResponse response) throws IOException {
                    final int errorCode = response.getRawStatusCode();
                    registry.counter(tokenValidationError.withTag("status", Integer.toString(errorCode))).increment();
                    if (response.getRawStatusCode() != HttpStatus.BAD_REQUEST.value()) {
                        super.handleError(response);
                    }
                }
            }
        );

        this.checkTokenEndpointUrl = serverProperties.getTokenInfoUri();
        this.clientId = serverProperties.getClientId();
        this.clientSecret = serverProperties.getClientSecret();

        Assert.state(StringUtils.isNotBlank(this.checkTokenEndpointUrl), "Check Endpoint URL is required");
        Assert.state(StringUtils.isNotBlank(this.clientId), "Client ID is required");
        Assert.state(StringUtils.isNotBlank(this.clientSecret), "Client secret is required");

        log.debug("checkTokenEndpointUrl = {}", this.checkTokenEndpointUrl);
        log.debug("clientId = {}", this.clientId);
        log.debug("clientSecret = {}", this.clientSecret);

        this.converter = converter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OAuth2Authentication loadAuthentication(final String accessToken)
        throws AuthenticationException, InvalidTokenException {
        final long start = System.nanoTime();
        try {
            final MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add(TOKEN_NAME_KEY, accessToken);
            formData.add(CLIENT_ID_KEY, this.clientId);
            formData.add(CLIENT_SECRET_KEY, this.clientSecret);
            formData.add(GRANT_TYPE_KEY, GRANT_TYPE);

            final Map<String, Object> map = this.postForMap(this.checkTokenEndpointUrl, formData);

            if (map.containsKey(ERROR_KEY)) {
                final String error = map.get(ERROR_KEY).toString();
                log.debug("Validating the token produced an error: {}", error);
                throw new InvalidTokenException(error);
            }

            Assert.state(map.containsKey(CLIENT_ID_KEY), "Client id must be present in response from auth server");
            Assert.state(map.containsKey(SCOPE_KEY), "No scopes included in response from authentication server");
            this.convertScopes(map);
            final OAuth2Authentication authentication = this.converter.extractAuthentication(map);
            log.info(
                "User {} authenticated with authorities {}",
                authentication.getPrincipal(),
                authentication.getAuthorities()
            );
            return authentication;
        } finally {
            final long finished = System.nanoTime();
            this.authenticationTimer.record(finished - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OAuth2AccessToken readAccessToken(final String accessToken) {
        throw new UnsupportedOperationException("readAccessToken not implemented for Ping Federate");
    }

    /**
     * Get the access token converter.
     *
     * @return The access token converter used by this token service implementation.
     */
    protected AccessTokenConverter getAccessTokenConverter() {
        return this.converter;
    }

    /**
     * Get the rest operations used.
     *
     * @return The rest operations used by this token services.
     */
    protected RestTemplate getRestTemplate() {
        return this.restTemplate;
    }

    /**
     * Set the rest operations to use.
     *
     * @param restTemplate The rest operations to use. Not null.
     */
    protected void setRestTemplate(@NotNull final RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Get the endpoint where tokens will be checked.
     *
     * @return The check token endpoint.
     */
    protected String getCheckTokenEndpointUrl() {
        return this.checkTokenEndpointUrl;
    }

    /**
     * Get the client id sent to the check token endpoint.
     *
     * @return The client id
     */
    protected String getClientId() {
        return this.clientId;
    }

    /**
     * Get the client secret sent to the check token endpoint.
     *
     * @return The client secret
     */
    protected String getClientSecret() {
        return this.clientSecret;
    }

    private Map<String, Object> postForMap(final String path, final MultiValueMap<String, String> formData) {
        final long start = System.nanoTime();
        try {
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            @SuppressWarnings("rawtypes")
            final Map map = this.restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(formData, headers), Map.class
            ).getBody();
            @SuppressWarnings("unchecked")
            final Map<String, Object> result = map;
            return result;
        } finally {
            final long finished = System.nanoTime();
            this.pingFederateAPITimer.record(finished - start, TimeUnit.NANOSECONDS);
        }
    }

    private void convertScopes(final Map<String, Object> oauth2Map) {
        final Object scopesObject = oauth2Map.get(SCOPE_KEY);
        if (scopesObject == null) {
            throw new InvalidTokenException("Scopes were null");
        }

        if (scopesObject instanceof String) {
            final String scopes = (String) scopesObject;
            if (StringUtils.isBlank(scopes)) {
                throw new InvalidTokenException("No scopes found unable to authenticate");
            }

            oauth2Map.put(SCOPE_KEY, Arrays.asList(StringUtils.split(scopes, ' ')));
        } else {
            throw new InvalidTokenException("Scopes was not a String");
        }
    }
}
