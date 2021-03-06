package org.apereo.cas.authentication;

import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.configuration.model.support.mfa.MultifactorAuthenticationProperties;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.RegisteredServiceMultifactorPolicy;
import org.apereo.cas.util.CollectionUtils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.commaDelimitedListToSet;

/**
 * Default MFA Trigger selection strategy. This strategy looks for valid triggers in the following order: request
 * parameter, RegisteredService policy, principal attribute.
 *
 * @author Daniel Frett
 * @since 5.0.0
 */
@RequiredArgsConstructor
public class DefaultMultifactorTriggerSelectionStrategy implements MultifactorTriggerSelectionStrategy {
    private final MultifactorAuthenticationProperties mfaProperties;

    @Override
    public Optional<String> resolve(final Collection<MultifactorAuthenticationProvider> providers,
                                    final HttpServletRequest request, final RegisteredService service,
                                    final Authentication authentication) {
        if (providers == null || providers.isEmpty()) {
            return Optional.empty();
        }
        val validProviderIds = providers.stream()
            .map(MultifactorAuthenticationProvider::getId)
            .collect(Collectors.toSet());
        val principal = authentication != null ? authentication.getPrincipal() : null;


        var provider = resolveRequestParameterTrigger(request, validProviderIds);

        if (provider.isEmpty()) {
            provider = resolveRegisteredServiceTrigger(service, principal, validProviderIds);
        }

        if (provider.isEmpty()) {
            provider = resolvePrincipalAttributeTrigger(principal, validProviderIds);
        }

        if (provider.isEmpty()) {
            provider = resolveAuthenticationAttributeTrigger(authentication, validProviderIds);
        }

        return provider;
    }

    /**
     * Checks for an opt-in provider id parameter trigger, we only care about the first value.
     */
    private Optional<String> resolveRequestParameterTrigger(final HttpServletRequest request,
                                                            final Set<String> providerIds) {
        return Optional.ofNullable(request)
            .map(r -> r.getParameter(mfaProperties.getRequestParameter()))
            .filter(providerIds::contains);
    }

    private Optional<String> resolveRegisteredServiceTrigger(final RegisteredService service, final Principal principal, final Set<String> providerIds) {
        if (service == null) {
            return Optional.empty();
        }

        val policy = service.getMultifactorPolicy();
        val attrName = policy.getPrincipalAttributeNameTrigger();
        val attrValue = policy.getPrincipalAttributeValueToMatch();

        if (!StringUtils.hasText(attrName) || !StringUtils.hasText(attrValue)) {
            return resolveRegisteredServicePolicyTrigger(policy, providerIds);
        }

        if (principal == null) {
            return resolveRegisteredServicePolicyTrigger(policy, providerIds);
        }

        if (hasMatchingAttribute(principal.getAttributes(), attrName, attrValue)) {
            return resolveRegisteredServicePolicyTrigger(policy, providerIds);
        }

        return Optional.empty();
    }

    private Optional<String> resolveRegisteredServicePolicyTrigger(final RegisteredServiceMultifactorPolicy policy,
                                                                   final Set<String> providerIds) {
        return policy.getMultifactorAuthenticationProviders().stream()
            .filter(providerIds::contains)
            .findFirst();
    }

    private Optional<String> resolveAuthenticationAttributeTrigger(final Authentication authentication,
                                                                   final Set<String> providerIds) {
        if (authentication == null) {
            return Optional.empty();
        }

        return resolveAttributeTrigger(authentication.getAttributes(),
            mfaProperties.getGlobalAuthenticationAttributeNameTriggers(),
            mfaProperties.getGlobalAuthenticationAttributeValueRegex(),
            providerIds);
    }

    private Optional<String> resolvePrincipalAttributeTrigger(final Principal principal,
                                                              final Set<String> providerIds) {
        if (principal == null) {
            return Optional.empty();
        }

        return resolveAttributeTrigger(principal.getAttributes(),
            mfaProperties.getGlobalPrincipalAttributeNameTriggers(),
            mfaProperties.getGlobalPrincipalAttributeValueRegex(),
            providerIds);
    }

    private Optional<String> resolveAttributeTrigger(final Map<String, Object> attributes, final String names,
                                                     final String value, final Set<String> providerIds) {
        if (!StringUtils.hasText(names)) {
            return Optional.empty();
        }

        if (providerIds.size() == 1 && hasMatchingAttribute(attributes, names, value)) {
            return providerIds.stream().findAny();
        }

        return resolveAttributeTrigger(attributes, names, providerIds);
    }

    @SuppressFBWarnings("PRMC_POSSIBLY_REDUNDANT_METHOD_CALLS")
    private Optional<String> resolveAttributeTrigger(final Map<String, Object> attributes, final String names,
                                                     final Set<String> providerIds) {
        return commaDelimitedListToSet(names).stream()
            .map(attributes::get)
            .filter(Objects::nonNull)
            .map(CollectionUtils::toCollection)
            .flatMap(Set::stream)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(providerIds::contains)
            .findFirst();
    }

    /**
     * Has matching attribute.
     * If there isn't any attribute names or an attribute value to match, return that there isn't a matching attribute
     *
     * @param attributes the attributes
     * @param names      the names
     * @param value      the value
     * @return the boolean
     */
    @SuppressFBWarnings("PRMC_POSSIBLY_REDUNDANT_METHOD_CALLS")
    private boolean hasMatchingAttribute(final Map<String, Object> attributes, final String names, final String value) {
        if (!StringUtils.hasText(names) || !StringUtils.hasText(value)) {
            return false;
        }

        val valuePredicate = Pattern.compile(value).asPredicate();
        return commaDelimitedListToSet(names).stream()
            .map(attributes::get)
            .filter(Objects::nonNull)
            .map(CollectionUtils::toCollection)
            .flatMap(Set::stream)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .anyMatch(valuePredicate);
    }
}
