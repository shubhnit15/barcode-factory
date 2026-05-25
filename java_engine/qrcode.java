import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class qrcode implements HttpHandler {

    public static byte[] generate(String data, int size, int fgColor, int bgColor) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, size, size, hints);

        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        MatrixToImageConfig config = new MatrixToImageConfig(fgColor, bgColor);
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream, config);
        return pngOutputStream.toByteArray();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Error: Missing required parameter 'data'");
            System.exit(1);
        }
        try {
            String data = args[0];
            int size = args.length > 1 ? Integer.parseInt(args[1]) : 200;
            int fgColor = args.length > 2 ? parseColor(args[2], 0xFF000000) : 0xFF000000;
            int bgColor = args.length > 3 ? parseColor(args[3], 0xFFFFFFFF) : 0xFFFFFFFF;

            byte[] pngData = generate(data, size, fgColor, bgColor);
            System.out.write(pngData);
            System.out.flush();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use GET.");
            return;
        }

        try {
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> params = parseQueryParams(query);

            String data = params.get("data");
            if (data == null || data.trim().isEmpty()) {
                sendError(exchange, 400, "Required query parameter 'data' is missing or empty.");
                return;
            }

            int size = 200;
            String sizeStr = params.get("size");
            if (sizeStr != null && !sizeStr.isEmpty()) {
                try {
                    size = Integer.parseInt(sizeStr);
                    if (size < 10 || size > 2000) {
                        sendError(exchange, 400, "Parameter 'size' must be between 10 and 2000 pixels.");
                        return;
                    }
                } catch (NumberFormatException e) {
                    sendError(exchange, 400, "Parameter 'size' must be a valid integer.");
                    return;
                }
            }

            int fgColor = parseColor(params.get("fg"), 0xFF000000);
            int bgColor = parseColor(params.get("bg"), 0xFFFFFFFF);

            byte[] pngData = generate(data, size, fgColor, bgColor);

            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, pngData.length);

            OutputStream os = exchange.getResponseBody();
            os.write(pngData);
            os.close();

        } catch (Exception e) {
            sendError(exchange, 500, "Generation failed: " + e.getMessage());
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) : pair;
                String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()) : "";
                params.put(key, value);
            } catch (Exception e) {
                // Ignore malformed pairs
            }
        }
        return params;
    }

    public static int parseColor(String hex, int defaultColor) {
        if (hex == null || hex.isEmpty()) {
            return defaultColor;
        }
        hex = hex.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            if (hex.length() == 3) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 3; i++) {
                    char c = hex.charAt(i);
                    sb.append(c).append(c);
                }
                hex = sb.toString();
            }
            if (hex.length() == 6) {
                hex = "FF" + hex;
            }
            long val = Long.parseLong(hex, 16);
            return (int) val;
        } catch (Exception e) {
            return defaultColor;
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String jsonError = String.format("{\"error\":\"%s\"}", message.replace("\"", "\\\""));
        byte[] errorBytes = jsonError.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, errorBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(errorBytes);
        os.close();
    }
}
