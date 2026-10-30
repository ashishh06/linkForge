package com.linkforge.util;

import com.linkforge.config.properties.LinkforgeProperties;
import com.linkforge.exception.BadRequestException;
import com.linkforge.repository.BlockedDomainRepository;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a destination URL before it is stored (decision D5).
 *
 * <p>The server never dereferences the URL, so this is not a server-side SSRF check.
 * It is an input-quality and abuse check:
 *
 * <ul>
 *   <li>parse with {@link URI}, never string prefix matching;</li>
 *   <li>allow only {@code http} and {@code https};</li>
 *   <li>reject userinfo, which turns {@code http://evil.com@trusted.com} into a
 *       misleading link;</li>
 *   <li>reject loopback, private and link-local IP literals;</li>
 *   <li>reject domains on the suffix-matched blocklist.</li>
 * </ul>
 */
@Component
public class UrlValidator {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    private final int maxUrlLength;
    private final BlockedDomainRepository blockedDomains;

    public UrlValidator(LinkforgeProperties properties, BlockedDomainRepository blockedDomains) {
        this.maxUrlLength = properties.link().maxUrlLength();
        this.blockedDomains = blockedDomains;
    }

    /**
     * @return the trimmed URL that should be stored
     * @throws BadRequestException when the URL is not acceptable
     */
    public String validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BadRequestException("url must not be blank");
        }

        String value = rawUrl.trim();
        if (value.length() > maxUrlLength) {
            throw new BadRequestException("url must be at most " + maxUrlLength + " characters");
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new BadRequestException("url is not a valid URI");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BadRequestException("url must use http or https");
        }
        if (uri.isOpaque()) {
            throw new BadRequestException("url must be hierarchical");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException("url must not contain user information");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("url must contain a valid host");
        }
        if (uri.getPort() > 65535) {
            throw new BadRequestException("url contains an invalid port");
        }

        String normalisedHost = host.toLowerCase(Locale.ROOT);
        normalisedHost = stripTrailingDots(normalisedHost);
        if (isLoopbackOrPrivate(normalisedHost)) {
            throw new BadRequestException("url must not point to a local or private address");
        }
        if (isBlocked(normalisedHost)) {
            throw new BadRequestException("url uses a blocked domain");
        }

        return value;
    }

    /**
     * {@code localhost.} and {@code evil.com.} are the same hosts to a DNS resolver as
     * their dotless forms. Stripping the trailing dot before the local/blocklist checks
     * stops a one-character bypass.
     */
    private static String stripTrailingDots(String host) {
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        return host.substring(0, end);
    }

    private boolean isBlocked(String host) {
        List<String> blocked = blockedDomains.findAllDomains();
        for (String domain : blocked) {
            String normalised = domain.toLowerCase(Locale.ROOT);
            if (host.equals(normalised) || host.endsWith("." + normalised)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoopbackOrPrivate(String host) {
        String bare = host;
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }

        if (bare.equals("localhost") || bare.endsWith(".localhost")) {
            return true;
        }
        if (bare.contains(":")) {
            return isPrivateIpv6(bare);
        }

        Matcher ipv4 = IPV4.matcher(bare);
        if (ipv4.matches()) {
            int a = Integer.parseInt(ipv4.group(1));
            int b = Integer.parseInt(ipv4.group(2));
            int c = Integer.parseInt(ipv4.group(3));
            int d = Integer.parseInt(ipv4.group(4));
            if (a > 255 || b > 255 || c > 255 || d > 255) {
                // An invalid literal is rejected rather than treated as a hostname.
                return true;
            }
            return isPrivateIpv4(a, b, c);
        }

        // A bare decimal number is also accepted by browsers as an IPv4 address
        // (e.g. http://2130706433/ is 127.0.0.1).
        if (DIGITS.matcher(bare).matches()) {
            try {
                long numeric = Long.parseLong(bare);
                return isPrivateIpv4Integer(numeric);
            } catch (NumberFormatException ex) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPrivateIpv4(int a, int b, int c) {
        return a == 0                              // 0.0.0.0/8
                || a == 10                         // 10.0.0.0/8
                || a == 127                        // 127.0.0.0/8 (loopback)
                || (a == 169 && b == 254)          // 169.254.0.0/16 (link-local)
                || (a == 172 && b >= 16 && b <= 31) // 172.16.0.0/12
                || (a == 192 && b == 168)          // 192.168.0.0/16
                || (a == 100 && b >= 64 && b <= 127) // 100.64.0.0/10 (CGNAT)
                || (a == 198 && (b == 18 || b == 19)) // 198.18.0.0/15 (benchmark)
                || a >= 224;                       // multicast and reserved
    }

    private static boolean isPrivateIpv4Integer(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            return true;
        }
        return value <= 0x00FFFFFFL                                  // 0.0.0.0/8
                || (value & 0xFF000000L) == 0x7F000000L              // 127.0.0.0/8
                || (value & 0xFF000000L) == 0x0A000000L              // 10.0.0.0/8
                || (value & 0xFFF00000L) == 0xAC100000L              // 172.16.0.0/12
                || (value & 0xFFFF0000L) == 0xC0A80000L              // 192.168.0.0/16
                || (value & 0xFFFF0000L) == 0xA9FE0000L              // 169.254.0.0/16
                || (value & 0xFFC00000L) == 0x64400000L              // 100.64.0.0/10
                || (value & 0xF0000000L) >= 0xE0000000L;             // 224.0.0.0/4 and above
    }

    private static boolean isPrivateIpv6(String host) {
        if (host.equals("::") || host.equals("::1")) {
            return true;
        }
        // fc00::/7 (unique local) and fe80::/10 (link-local).
        if (host.startsWith("fc") || host.startsWith("fd")) {
            return true;
        }
        if (host.startsWith("fe8") || host.startsWith("fe9")
                || host.startsWith("fea") || host.startsWith("feb")) {
            return true;
        }
        // IPv4-mapped and IPv4-compatible forms, e.g. ::ffff:127.0.0.1.
        int lastColon = host.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < host.length() - 1) {
            Matcher embedded = IPV4.matcher(host.substring(lastColon + 1));
            if (embedded.matches()) {
                int a = Integer.parseInt(embedded.group(1));
                int b = Integer.parseInt(embedded.group(2));
                int c = Integer.parseInt(embedded.group(3));
                int d = Integer.parseInt(embedded.group(4));
                if (a > 255 || b > 255 || c > 255 || d > 255) {
                    return true;
                }
                return isPrivateIpv4(a, b, c);
            }
        }
        return false;
    }
}
