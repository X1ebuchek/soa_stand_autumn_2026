package orders;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public record Config(int port, String jdbcUrl, String dbUser, String dbPassword,
                     URI paymentUrl, URI inventoryUrl, URI eventsUrl) {

    static Config fromEnv() {
        Db db = Db.parse(required("DATABASE_URL"));
        return new Config(
                intEnv("PORT", 8080),
                db.jdbcUrl(), db.user(), db.password(),
                URI.create(stripSlash(required("PAYMENT_URL"))),
                URI.create(stripSlash(required("INVENTORY_URL"))),
                optionalUri("EVENTS_URL"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("не задана переменная окружения " + name);
        }
        return value;
    }

    private static URI optionalUri(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : URI.create(stripSlash(value));
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static String stripSlash(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    record Db(String jdbcUrl, String user, String password) {

        static Db parse(String raw) {
            if (raw.startsWith("jdbc:postgresql:")) {
                return new Db(raw, null, null);
            }
            URI uri = URI.create(raw);

            String user = null;
            String password = null;
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                int colon = userInfo.indexOf(':');
                if (colon < 0) {
                    user = decode(userInfo);
                } else {
                    user = decode(userInfo.substring(0, colon));
                    password = decode(userInfo.substring(colon + 1));
                }
            }

            StringBuilder url = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
            if (uri.getPort() > 0) {
                url.append(':').append(uri.getPort());
            }
            String path = uri.getPath();
            url.append(path == null || path.isEmpty() ? "/" : path);

            String params = jdbcParams(uri.getRawQuery());
            if (!params.isEmpty()) {
                url.append('?').append(params);
            }
            return new Db(url.toString(), user, password);
        }

        private static String jdbcParams(String rawQuery) {
            if (rawQuery == null || rawQuery.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                String mapped = switch (key) {
                    case "sslmode" -> pair;
                    case "connect_timeout" -> "connectTimeout=" + value;
                    case "application_name" -> "ApplicationName=" + value;
                    default -> null;
                };
                if (mapped == null) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append('&');
                }
                out.append(mapped);
            }
            return out.toString();
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }
}
