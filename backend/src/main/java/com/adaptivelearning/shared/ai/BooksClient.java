package com.adaptivelearning.shared.ai;

import com.adaptivelearning.books.domain.BookViews.ApiKeyRequest;
import com.adaptivelearning.books.domain.BookViews.BookInfoView;
import com.adaptivelearning.books.domain.BookViews.BookIntroView;
import com.adaptivelearning.books.domain.BookViews.BookLoginStatusView;
import com.adaptivelearning.books.domain.BookViews.BookProgressView;
import com.adaptivelearning.books.domain.BookViews.BookQrLoginView;
import com.adaptivelearning.books.domain.BookViews.BookShelfView;
import com.adaptivelearning.books.domain.BookViews.BookView;
import com.adaptivelearning.books.domain.BookViews.RatingDetailView;
import com.adaptivelearning.books.domain.BookViews.ReadDataDetailView;
import com.adaptivelearning.books.domain.BookViews.ReadMedalView;
import com.adaptivelearning.books.domain.BookViews.ReadPreferBookView;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal-only client for the Python books (微信读书) endpoints under /internal/v1/books.
 *
 * <p>构造与 {@link PythonAiServiceClient} 一致（复用 app.ai-service.* 配置与 X-Internal-Token 鉴权），
 * 业务错误按 ai-service 返回的稳定 code 映射为 {@link ErrorCode}。
 */
@Slf4j
@Service
public class BooksClient {
    private final boolean configured;
    private final ObjectMapper json;
    private final RestClient client;

    public BooksClient(
            ObjectMapper json,
            @Value("${app.ai-service.enabled:false}") boolean enabled,
            @Value("${app.ai-service.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${app.ai-service.internal-token:}") String internalToken,
            @Value("${app.ai-service.timeout:PT330S}") Duration timeout) {
        this.json = json;
        this.configured = enabled && baseUrl != null && !baseUrl.isBlank()
                && internalToken != null && internalToken.length() >= 32;
        if (!configured) {
            this.client = null;
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeout.toSeconds())));
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public boolean isConfigured() {
        return configured;
    }

    public BookLoginStatusView loginStatus() {
        return status(get("/internal/v1/books/login-status"));
    }

    public BookQrLoginView startQrLogin() {
        return qr(post("/internal/v1/books/login-qrcode", Map.of()));
    }

    public BookLoginStatusView setApiKey(String apiKey) {
        return status(post("/internal/v1/books/api-key", new ApiKeyRequest(apiKey)));
    }

    public void logout() {
        post("/internal/v1/books/logout", Map.of());
    }

    public BookShelfView bookshelf() {
        return shelf(get("/internal/v1/books/bookshelf"));
    }

    public List<BookView> search(String keyword, int count) {
        JsonNode data = get("/internal/v1/books/search?keyword={keyword}&count={count}", keyword, count);
        List<BookView> books = new ArrayList<>();
        data.path("books").forEach(item -> books.add(book(item)));
        return List.copyOf(books);
    }

    public BookInfoView bookInfo(String bookId) {
        return info(get("/internal/v1/books/info?bookId={bookId}", bookId));
    }

    public BookProgressView bookProgress(String bookId) {
        return progress(get("/internal/v1/books/getprogress?bookId={bookId}", bookId));
    }

    public ReadDataDetailView readDataDetail(String mode) {
        return readData(get("/internal/v1/books/readdata-detail?mode={mode}", mode));
    }

    public List<BookIntroView> recommend(int count) {
        JsonNode data = get("/internal/v1/books/recommend?count={count}", count);
        List<BookIntroView> books = new ArrayList<>();
        data.path("books").forEach(item -> books.add(introBook(item)));
        return List.copyOf(books);
    }

    public List<BookIntroView> similar(String bookId, int count) {
        JsonNode data = get("/internal/v1/books/similar?bookId={bookId}&count={count}", bookId, count);
        List<BookIntroView> books = new ArrayList<>();
        data.path("books").forEach(item -> books.add(introBook(item)));
        return List.copyOf(books);
    }

    private BookLoginStatusView status(JsonNode data) {
        JsonNode qrNode = data.path("loginQr");
        BookQrLoginView loginQr = qrNode.isObject() ? qr(qrNode) : null;
        return new BookLoginStatusView(
                data.path("loggedIn").asBoolean(),
                textOrNull(data, "loginType"),
                textOrNull(data, "nickname"),
                textOrNull(data, "headImgUrl"),
                data.path("isVip").asBoolean(),
                textOrNull(data, "lastLoginAt"),
                loginQr);
    }

    private BookQrLoginView qr(JsonNode data) {
        return new BookQrLoginView(
                data.path("status").asText(),
                textOrNull(data, "qrBase64"),
                textOrNull(data, "qrToken"),
                textOrNull(data, "message"),
                textOrNull(data, "expiresAt"));
    }

    private BookShelfView shelf(JsonNode data) {
        List<BookView> books = new ArrayList<>();
        data.path("books").forEach(item -> books.add(book(item)));
        return new BookShelfView(
                data.path("total").asInt(),
                data.path("readingCount").asInt(),
                data.path("finishedCount").asInt(),
                List.copyOf(books));
    }

    private BookView book(JsonNode item) {
        return new BookView(
                item.path("bookId").asText(),
                item.path("title").asText(),
                textOrNull(item, "author"),
                textOrNull(item, "coverUrl"),
                textOrNull(item, "category"),
                textOrNull(item, "categoryId"),
                item.path("readingProgress").asDouble(),
                item.path("isFinished").asBoolean(),
                item.path("status").asText("unread"),
                textOrNull(item, "updateTime"),
                textOrNull(item, "lastReadChapter"),
                integral(item, "wordCount"),
                textOrNull(item, "format"),
                item.path("type").asText("book"),
                item.path("isPublic").asBoolean(),
                textOrNull(item, "deepLink"));
    }

    private JsonNode get(String path, Object... uriVars) {
        requireConfigured();
        try {
            JsonNode response = client.get().uri(path, uriVars)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .retrieve().body(JsonNode.class);
            return requiredData(response);
        } catch (RuntimeException error) {
            throw translate(error, "Books GET failed");
        }
    }

    private JsonNode post(String path, Object body) {
        requireConfigured();
        try {
            JsonNode response = client.post().uri(path)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            return requiredData(response);
        } catch (RuntimeException error) {
            throw translate(error, "Books POST failed");
        }
    }

    private JsonNode requiredData(JsonNode response) {
        if (response == null || !response.path("success").asBoolean() || !response.path("data").isObject()) {
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        }
        return response.path("data");
    }

    private AiModelException translate(RuntimeException error, String logMessage) {
        AiModelException nested = findModelError(error);
        if (nested != null) return nested;
        ErrorCode code = error instanceof ResourceAccessException
                ? ErrorCode.MODEL_REQUEST_TIMEOUT : ErrorCode.WEREAD_REMOTE_ERROR;
        if (error instanceof RestClientResponseException response) {
            code = mapStatus(response.getStatusCode().value());
            try {
                JsonNode payload = json.readTree(response.getResponseBodyAsString());
                JsonNode providerError = payload.path("error");
                if (providerError.isObject()) {
                    code = mapPythonCode(providerError.path("code").asText());
                    String message = safeMessage(providerError.path("message").asText());
                    log.warn("{}: status={} code={}", logMessage,
                            response.getStatusCode().value(), providerError.path("code").asText());
                    return new AiModelException(code, message, Map.of(), error);
                }
            } catch (Exception parseError) {
                log.debug("Could not parse books error response", parseError);
            }
        }
        log.warn("{}: {} message={}", logMessage, error.getClass().getSimpleName(), safeMessage(error.getMessage()));
        return new AiModelException(code, error);
    }

    private AiModelException findModelError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof AiModelException modelError) return modelError;
            current = current.getCause();
        }
        return null;
    }

    private ErrorCode mapStatus(int status) {
        return switch (status) {
            case 401 -> ErrorCode.WEREAD_NOT_LOGGED_IN;
            case 503 -> ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE;
            case 504 -> ErrorCode.MODEL_REQUEST_TIMEOUT;
            default -> ErrorCode.WEREAD_REMOTE_ERROR;
        };
    }

    private ErrorCode mapPythonCode(String code) {
        return switch (code) {
            case "WEREAD_NOT_LOGGED_IN" -> ErrorCode.WEREAD_NOT_LOGGED_IN;
            case "WEREAD_LOGIN_EXPIRED" -> ErrorCode.WEREAD_LOGIN_EXPIRED;
            case "WEREAD_API_KEY_INVALID" -> ErrorCode.WEREAD_API_KEY_INVALID;
            case "WEREAD_QR_EXPIRED" -> ErrorCode.WEREAD_QR_EXPIRED;
            case "AI_DEPENDENCY_UNAVAILABLE" -> ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE;
            default -> ErrorCode.WEREAD_REMOTE_ERROR;
        };
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) return null;
        String compact = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() <= 200 ? compact : compact.substring(0, 200);
    }

    private void requireConfigured() {
        if (!configured) throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
    }

    private Integer integral(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    private Double decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.doubleValue() : null;
    }

    private BookInfoView info(JsonNode data) {
        JsonNode rating = data.path("ratingDetail");
        RatingDetailView ratingDetail = rating.isObject()
                ? new RatingDetailView(
                        integral(rating, "good"),
                        integral(rating, "fair"),
                        integral(rating, "poor"),
                        integral(rating, "recent"),
                        integral(rating, "deepV"),
                        textOrNull(rating, "myRating"))
                : null;
        return new BookInfoView(
                data.path("bookId").asText(),
                textOrNull(data, "title"),
                textOrNull(data, "author"),
                textOrNull(data, "coverUrl"),
                textOrNull(data, "category"),
                textOrNull(data, "intro"),
                textOrNull(data, "publisher"),
                textOrNull(data, "publishTime"),
                textOrNull(data, "isbn"),
                textOrNull(data, "translator"),
                integral(data, "wordCount"),
                decimal(data, "newRating"),
                integral(data, "newRatingCount"),
                ratingDetail,
                textOrNull(data, "deepLink"));
    }

    private BookProgressView progress(JsonNode data) {
        return new BookProgressView(
                data.path("bookId").asText(),
                integral(data, "progressPercent"),
                integral(data, "chapterIdx"),
                integral(data, "chapterUid"),
                integral(data, "readingTime"),
                textOrNull(data, "updateTime"),
                textOrNull(data, "lastReadAt"));
    }

    private BookIntroView introBook(JsonNode item) {
        return new BookIntroView(
                item.path("bookId").asText(),
                textOrNull(item, "title"),
                textOrNull(item, "author"),
                textOrNull(item, "coverUrl"),
                textOrNull(item, "category"),
                textOrNull(item, "intro"),
                decimal(item, "price"),
                textOrNull(item, "format"),
                item.path("type").asText("book"),
                textOrNull(item, "deepLink"));
    }

    private ReadDataDetailView readData(JsonNode data) {
        List<ReadPreferBookView> preferBooks = new ArrayList<>();
        data.path("preferBooks").forEach(item -> preferBooks.add(new ReadPreferBookView(
                textOrNull(item, "bookId"),
                textOrNull(item, "title"),
                textOrNull(item, "cover"),
                integral(item, "type"))));
        List<ReadMedalView> medals = new ArrayList<>();
        data.path("medals").forEach(item -> medals.add(new ReadMedalView(
                textOrNull(item, "name"),
                textOrNull(item, "displayText"),
                textOrNull(item, "rankText"))));
        return new ReadDataDetailView(
                integral(data, "totalReadTime"),
                integral(data, "wrReadTime"),
                integral(data, "wrListenTime"),
                integral(data, "readDays"),
                integral(data, "readRate"),
                textOrNull(data, "registTime"),
                textOrNull(data, "preferCategoryWord"),
                textOrNull(data, "preferTimeWord"),
                List.copyOf(preferBooks),
                List.copyOf(medals));
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
