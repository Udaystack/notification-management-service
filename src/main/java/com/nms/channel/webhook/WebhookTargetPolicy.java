package com.nms.channel.webhook;

import com.nms.common.domain.FailureClass;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides whether a webhook URL may be called (SSRF protection): {@code https} only, and every address the host
 * resolves to must be public. With {@code allowPrivateHosts} (local development and tests) {@code http} and
 * non-public addresses are allowed too. Runs right before each call.
 */
final class WebhookTargetPolicy {

    /** Resolves a host name; a seam so tests can control DNS. */
    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Either the URI to call, or the failure class to record without calling. */
    record Decision(URI target, FailureClass failure) {

        static Decision allow(URI target) {
            return new Decision(target, null);
        }

        static Decision reject(FailureClass failure) {
            return new Decision(null, failure);
        }

        boolean allowed() {
            return target != null;
        }
    }

    private final boolean allowPrivateHosts;
    private final AddressResolver resolver;

    WebhookTargetPolicy(boolean allowPrivateHosts, AddressResolver resolver) {
        this.allowPrivateHosts = allowPrivateHosts;
        this.resolver = resolver;
    }

    Decision check(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.trim());
        } catch (URISyntaxException e) {
            return Decision.reject(FailureClass.INVALID_RECIPIENT);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        boolean schemeAllowed = scheme.equals("https") || (allowPrivateHosts && scheme.equals("http"));
        if (!schemeAllowed || host == null || host.isEmpty()) {
            return Decision.reject(FailureClass.INVALID_RECIPIENT);
        }
        if (allowPrivateHosts) {
            return Decision.allow(uri);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            return Decision.reject(FailureClass.TRANSIENT);
        }
        if (addresses.length == 0) {
            return Decision.reject(FailureClass.TRANSIENT);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                return Decision.reject(FailureClass.INVALID_RECIPIENT);
            }
        }
        return Decision.allow(uri);
    }

    static boolean isPublic(InetAddress address) {
        return !(address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocal(address));
    }

    /** IPv6 unique-local {@code fc00::/7}, the IPv6 private range ({@code isSiteLocalAddress} only covers fec0::/10). */
    private static boolean isUniqueLocal(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }
}
