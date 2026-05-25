import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class barcode implements HttpHandler {

    public static byte[] generate(String data, BarcodeFormat format, int width, int height, int fgColor, int bgColor) throws Exception {
        MultiFormatWriter barcodeWriter = new MultiFormatWriter();
        BitMatrix bitMatrix = barcodeWriter.encode(data, format, width, height);

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
            String formatStr = args.length > 1 ? args[1] : "CODE_128";
            int width = args.length > 2 ? Integer.parseInt(args[2]) : 300;
            int height = args.length > 3 ? Integer.parseInt(args[3]) : 100;
            int fgColor = args.length > 4 ? parseColor(args[4], 0xFF000000) : 0xFF000000;
            int bgColor = args.length > 5 ? parseColor(args[5], 0xFFFFFFFF) : 0xFFFFFFFF;

            BarcodeFormat barcodeFormat = BarcodeFormat.valueOf(formatStr.trim().toUpperCase());
            if (barcodeFormat == BarcodeFormat.QR_CODE) {
                System.err.println("Error: QR Code format should be accessed via '/api/qrcode'.");
                System.exit(1);
            }

            byte[] pngData = generate(data, barcodeFormat, width, height, fgColor, bgColor);
            System.out.write(pngData);
            System.out.flush();
        } catch (IllegalArgumentException e) {
            System.err.println("Error: Invalid barcode format. Supported formats include: CODE_128, CODE_39, EAN_8, EAN_13, ITF, UPC_A, UPC_E, CODABAR, PDF_417, AZTEC, DATA_MATRIX.");
            System.exit(1);
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

            BarcodeFormat barcodeFormat = BarcodeFormat.CODE_128;
            String formatStr = params.get("format");
            if (formatStr != null && !formatStr.trim().isEmpty()) {
                try {
                    barcodeFormat = BarcodeFormat.valueOf(formatStr.trim().toUpperCase());
                    if (barcodeFormat == BarcodeFormat.QR_CODE) {
                        sendError(exchange, 400, "QR Code format should be accessed via '/api/qrcode'.");
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    sendError(exchange, 400, "Invalid barcode format. Supported formats include: CODE_128, CODE_39, EAN_8, EAN_13, ITF, UPC_A, UPC_E, CODABAR, PDF_417, AZTEC, DATA_MATRIX.");
                    return;
                }
            }

            int width = 300;
            String widthStr = params.get("width");
            if (widthStr != null && !widthStr.isEmpty()) {
                try {
                    width = Integer.parseInt(widthStr);
                    if (width < 10 || width > 3000) {
                        sendError(exchange, 400, "Parameter 'width' must be between 10 and 3000 pixels.");
                        return;
                    }
                } catch (NumberFormatException e) {
                    sendError(exchange, 400, "Parameter 'width' must be a valid integer.");
                    return;
                }
            }

            int height = 100;
            String heightStr = params.get("height");
            if (heightStr != null && !heightStr.isEmpty()) {
                try {
                    height = Integer.parseInt(heightStr);
                    if (height < 10 || height > 2000) {
                        sendError(exchange, 400, "Parameter 'height' must be between 10 and 2000 pixels.");
                        return;
                    }
                } catch (NumberFormatException e) {
                    sendError(exchange, 400, "Parameter 'height' must be a valid integer.");
                    return;
                }
            }

            int fgColor = parseColor(params.get("fg"), 0xFF000000);
            int bgColor = parseColor(params.get("bg"), 0xFFFFFFFF);

            byte[] pngData = generate(data, barcodeFormat, width, height, fgColor, bgColor);

            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, pngData.length);

            OutputStream os = exchange.getResponseBody();
            os.write(pngData);
            os.close();

        } catch (Exception e) {
            sendError(exchange, 500, "Barcode generation failed: " + e.getMessage());
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
