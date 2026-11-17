import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class WebhookReceiver {

    public static void main(String[] args) throws Exception {
        String encodedSecret = System.getenv("NOTIFYFLOW_WEBHOOK_SECRET");

        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalStateException(
                    "Set NOTIFYFLOW_WEBHOOK_SECRET before starting");
        }

        byte[] secret = Base64.getDecoder().decode(encodedSecret);

        if (secret.length != 32) {
            throw new IllegalArgumentException(
                    "Signing secret must decode to 32 bytes");
        }

        var processedIds = ConcurrentHashMap.<String>newKeySet();

        int port = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        var server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/webhook", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                var headers = exchange.getRequestHeaders();
                String id = headers.getFirst("webhook-id");

                if (!verifyWebhook(
                        secret,
                        id,
                        headers.getFirst("webhook-timestamp"),
                        body,
                        headers.getFirst("webhook-signature"))) {
                    System.out.println("Rejected invalid webhook");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }

                if (processedIds.add(id)) {
                    System.out.println("Verified webhook " + id + ": "
                            + new String(body, StandardCharsets.UTF_8));
                } else {
                    System.out.println("Skipped duplicate delivery " + id);
                }

                exchange.sendResponseHeaders(204, -1);
            } catch (GeneralSecurityException exception) {
                System.out.println("Receiver could not verify webhook");
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });

        server.start();
        System.out.println("Receiver URL: http://127.0.0.1:"
                + server.getAddress().getPort() + "/webhook");
    }

    private static boolean verifyWebhook(
            byte[] secret,
            String id,
            String timestamp,
            byte[] body,
            String signature) throws GeneralSecurityException {

        if (id == null || id.isBlank()
                || timestamp == null || signature == null
                || signature.isBlank()) {
            return false;
        }

        try {
            Instant sentAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            Instant now = Instant.now();

            if (sentAt.isBefore(now.minusSeconds(300))
                    || sentAt.isAfter(now.plusSeconds(300))) {
                return false;
            }

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((id + "." + timestamp + ".")
                    .getBytes(StandardCharsets.UTF_8));

            byte[] expected = mac.doFinal(body);
            for (String candidate : signature.trim().split("\\s+")) {
                if (!candidate.startsWith("v1,")) {
                    continue;
                }

                try {
                    byte[] received = Base64.getDecoder()
                            .decode(candidate.substring(3));

                    if (MessageDigest.isEqual(expected, received)) {
                        return true;
                    }
                } catch (IllegalArgumentException malformedSignature) {
                    // Try the next candidate.
                }
            }

            return false;
        } catch (IllegalArgumentException | DateTimeException exception) {
            return false;
        }
    }
}
