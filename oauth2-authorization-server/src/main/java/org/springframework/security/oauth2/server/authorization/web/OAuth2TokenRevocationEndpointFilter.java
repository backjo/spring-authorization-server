/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A {@code Filter} for the OAuth 2.0 Token Revocation endpoint.
 *
 * @author Vivek Babu
 * @author Joe Grandja
 * @author Arfat Chaus
 * @see OAuth2TokenRevocationAuthenticationProvider
 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc7009#section-2">Section 2 Token Revocation</a>
 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc7009#section-2.1">Section 2.1 Revocation Request</a>
 * @since 0.0.3
 */
public final class OAuth2TokenRevocationEndpointFilter extends OncePerRequestFilter {
	/**
	 * The default endpoint {@code URI} for token revocation requests.
	 */
	private static final String DEFAULT_TOKEN_REVOCATION_ENDPOINT_URI = "/oauth2/revoke";

	private final AuthenticationManager authenticationManager;
	private final RequestMatcher tokenRevocationEndpointMatcher;
	private AuthenticationConverter authenticationConverter =
			new DefaultTokenRevocationAuthenticationConverter();
	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter =
			new OAuth2ErrorHttpMessageConverter();
	private AuthenticationSuccessHandler authenticationSuccessHandler = this::sendRevocationSuccessResponse;
	private AuthenticationFailureHandler authenticationFailureHandler = this::sendErrorResponse;

	/**
	 * Constructs an {@code OAuth2TokenRevocationEndpointFilter} using the provided parameters.
	 *
	 * @param authenticationManager the authentication manager
	 */
	public OAuth2TokenRevocationEndpointFilter(AuthenticationManager authenticationManager) {
		this(authenticationManager, DEFAULT_TOKEN_REVOCATION_ENDPOINT_URI);
	}

	/**
	 * Constructs an {@code OAuth2TokenRevocationEndpointFilter} using the provided parameters.
	 *
	 * @param authenticationManager the authentication manager
	 * @param tokenRevocationEndpointUri the endpoint {@code URI} for token revocation requests
	 */
	public OAuth2TokenRevocationEndpointFilter(AuthenticationManager authenticationManager,
			String tokenRevocationEndpointUri) {
		Assert.notNull(authenticationManager, "authenticationManager cannot be null");
		Assert.hasText(tokenRevocationEndpointUri, "tokenRevocationEndpointUri cannot be empty");
		this.authenticationManager = authenticationManager;
		this.tokenRevocationEndpointMatcher = new AntPathRequestMatcher(
				tokenRevocationEndpointUri, HttpMethod.POST.name());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		if (!this.tokenRevocationEndpointMatcher.matches(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			Authentication tokenRevocationAuthentication = this.authenticationConverter.convert(request);
			Authentication tokenRevocationAuthenticationResult =
					this.authenticationManager.authenticate(tokenRevocationAuthentication);
			this.authenticationSuccessHandler.onAuthenticationSuccess(request, response, tokenRevocationAuthenticationResult);
		} catch (OAuth2AuthenticationException ex) {
			SecurityContextHolder.clearContext();
			this.authenticationFailureHandler.onAuthenticationFailure(request, response, ex);
		}
	}

	/**
	 * Sets the {@link AuthenticationConverter} used when attempting to extract a Revoke Token Request from {@link HttpServletRequest}
	 * to an instance of {@link OAuth2TokenRevocationAuthenticationToken} used for authenticating the client.
	 *
	 * @param authenticationConverter the {@link AuthenticationConverter} used when attempting to extract client credentials from {@link HttpServletRequest}
	 * @since 0.2.2
	 */
	public void setAuthenticationConverter(AuthenticationConverter authenticationConverter) {
		Assert.notNull(authenticationConverter, "authenticationConverter cannot be null");
		this.authenticationConverter = authenticationConverter;
	}

	/**
	 * Sets the {@link AuthenticationSuccessHandler} used for handling an {@link OAuth2TokenRevocationAuthenticationToken}.
	 *
	 * @param authenticationSuccessHandler the {@link AuthenticationSuccessHandler} used for handling an {@link OAuth2TokenRevocationAuthenticationToken}
	 * @since 0.2.2
	 */
	public void setAuthenticationSuccessHandler(AuthenticationSuccessHandler authenticationSuccessHandler) {
		Assert.notNull(authenticationSuccessHandler, "authenticationSuccessHandler cannot be null");
		this.authenticationSuccessHandler = authenticationSuccessHandler;
	}

	/**
	 * Sets the {@link AuthenticationFailureHandler} used for handling an {@link OAuth2AuthenticationException}
	 * and returning the {@link OAuth2Error Error Response}.
	 *
	 * @param authenticationFailureHandler the {@link AuthenticationFailureHandler} used for handling an {@link OAuth2AuthenticationException}
	 * @since 0.2.2
	 */
	public void setAuthenticationFailureHandler(AuthenticationFailureHandler authenticationFailureHandler) {
		Assert.notNull(authenticationFailureHandler, "authenticationFailureHandler cannot be null");
		this.authenticationFailureHandler = authenticationFailureHandler;
	}

	private void sendRevocationSuccessResponse(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		response.setStatus(HttpStatus.OK.value());
	}

	private void sendErrorResponse(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		OAuth2Error error = ((OAuth2AuthenticationException) exception).getError();
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		httpResponse.setStatusCode(HttpStatus.BAD_REQUEST);
		this.errorHttpResponseConverter.write(error, null, httpResponse);
	}

	private static void throwError(String errorCode, String parameterName) {
		OAuth2Error error = new OAuth2Error(errorCode, "OAuth 2.0 Token Revocation Parameter: " + parameterName,
				"https://datatracker.ietf.org/doc/html/rfc7009#section-2.1");
		throw new OAuth2AuthenticationException(error);
	}

	private static class DefaultTokenRevocationAuthenticationConverter
			implements AuthenticationConverter {

		@Override
		public Authentication convert(HttpServletRequest request) {
			Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();

			MultiValueMap<String, String> parameters = OAuth2EndpointUtils.getParameters(request);

			// token (REQUIRED)
			String token = parameters.getFirst(OAuth2ParameterNames.TOKEN);
			if (!StringUtils.hasText(token) ||
					parameters.get(OAuth2ParameterNames.TOKEN).size() != 1) {
				throwError(OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ParameterNames.TOKEN);
			}

			// token_type_hint (OPTIONAL)
			String tokenTypeHint = parameters.getFirst(OAuth2ParameterNames.TOKEN_TYPE_HINT);
			if (StringUtils.hasText(tokenTypeHint) &&
					parameters.get(OAuth2ParameterNames.TOKEN_TYPE_HINT).size() != 1) {
				throwError(OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ParameterNames.TOKEN_TYPE_HINT);
			}

			return new OAuth2TokenRevocationAuthenticationToken(token, clientPrincipal, tokenTypeHint);
		}
	}
}
