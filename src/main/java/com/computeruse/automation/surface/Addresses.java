package com.computeruse.automation.surface;

import java.net.URI;

/**
 * Turns an address into the part that says which page it is.
 *
 * Two addresses are the same page if they agree on host and path. What comes after "?" is dropped,
 * and so is a ";jsessionid=..." session id, because both change on every visit and the query
 * of a payment page carries amounts and account numbers.
 */
public final class Addresses {

    /** What a surface reports when a person opened a second tab or window. */
    public static final String ANOTHER_TAB = "another tab or window";

    /** "http://host:8080/bank/pay.htm;jsessionid=A?amount=5" becomes "http://host:8080/bank/pay.htm". */
    public static String page(String address) {
        if (address == null) {
            return "";
        }
        try {
            URI uri = URI.create(address);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return address;   // "about:blank" and the like have no page to speak of
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            int matrix = path.indexOf(';');
            if (matrix >= 0) {
                path = path.substring(0, matrix);
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path;
        } catch (IllegalArgumentException e) {
            return address;
        }
    }

    private Addresses() {}
}
