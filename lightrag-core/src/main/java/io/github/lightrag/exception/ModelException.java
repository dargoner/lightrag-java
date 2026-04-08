package io.github.lightrag.exception;

public class ModelException extends RuntimeException {
    private final Integer statusCode;
    private final String responseBody;
    private final String requestUrl;
    private final String requestId;

    public ModelException(String message) {
        this(message, null, null, null, null, null);
    }

    public ModelException(String message, Throwable cause) {
        this(message, null, null, null, null, cause);
    }

    public ModelException(
        String message,
        Integer statusCode,
        String responseBody,
        String requestUrl,
        String requestId
    ) {
        this(message, statusCode, responseBody, requestUrl, requestId, null);
    }

    public ModelException(
        String message,
        Integer statusCode,
        String responseBody,
        String requestUrl,
        String requestId,
        Throwable cause
    ) {
        super(appendDiagnostics(message, statusCode, responseBody, requestUrl, requestId), cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.requestUrl = requestUrl;
        this.requestId = requestId;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public String requestUrl() {
        return requestUrl;
    }

    public String requestId() {
        return requestId;
    }

    private static String appendDiagnostics(
        String message,
        Integer statusCode,
        String responseBody,
        String requestUrl,
        String requestId
    ) {
        var builder = new StringBuilder(message);
        if (statusCode != null) {
            builder.append("; status=").append(statusCode);
        }
        if (requestId != null && !requestId.isBlank()) {
            builder.append("; requestId=").append(requestId);
        }
        if (requestUrl != null && !requestUrl.isBlank()) {
            builder.append("; requestUrl=").append(requestUrl);
        }
        if (responseBody != null && !responseBody.isBlank()) {
            builder.append("; responseBody=").append(responseBody);
        }
        return builder.toString();
    }
}
