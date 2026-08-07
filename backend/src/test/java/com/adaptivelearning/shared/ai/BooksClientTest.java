package com.adaptivelearning.shared.ai;

import com.adaptivelearning.books.domain.BookViews.BookInfoView;
import com.adaptivelearning.books.domain.BookViews.BookIntroView;
import com.adaptivelearning.books.domain.BookViews.BookLoginStatusView;
import com.adaptivelearning.books.domain.BookViews.BookProgressView;
import com.adaptivelearning.books.domain.BookViews.BookQrLoginView;
import com.adaptivelearning.books.domain.BookViews.BookShelfView;
import com.adaptivelearning.books.domain.BookViews.ReadDataDetailView;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BooksClientTest {
    private static final String TOKEN = "test-python-internal-token-longer-than-32";
    private HttpServer server;
    private BooksClient client;
    private final AtomicReference<String> receivedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/books/login-status", exchange -> json(exchange, """
                {"success":true,"data":{"loggedIn":true,"loginType":"API_KEY","nickname":"张三",
                "headImgUrl":"https://x/avatar","isVip":true,"lastLoginAt":"2026-08-01T08:00:00Z",
                "loginQr":null},"requestId":"r1"}
                """));
        server.createContext("/internal/v1/books/login-qrcode", exchange -> json(exchange, """
                {"success":true,"data":{"status":"PENDING","qrBase64":"data:image/png;base64,AAA",
                "qrToken":"qr_1","message":"请用微信扫一扫登录","expiresAt":"2026-08-05T10:00:00Z"},
                "requestId":"r2"}
                """));
        server.createContext("/internal/v1/books/bookshelf", exchange -> json(exchange, """
                {"success":true,"data":{"total":2,"readingCount":1,"finishedCount":1,"books":[
                {"bookId":"1","title":"深入理解Java虚拟机","author":"周志明","coverUrl":"",
                "category":"计算机","categoryId":"18","readingProgress":0.36,"isFinished":false,
                "status":"reading","updateTime":"2026-08-01T08:00:00Z","lastReadChapter":"第3章",
                "wordCount":468000,"format":"txt","type":"book","isPublic":false},
                {"bookId":"2","title":"认知觉醒","author":"周岭","coverUrl":null,"category":null,
                "categoryId":null,"readingProgress":1.0,"isFinished":true,"status":"finished",
                "updateTime":null,"lastReadChapter":null,"wordCount":null,"format":"epub",
                "type":"book","isPublic":true}],"requestId":"r3"}}
                """));
        server.createContext("/internal/v1/books/logout", exchange -> json(exchange, """
                {"success":true,"data":{"logout":true},"requestId":"r4"}
                """));
        server.createContext("/internal/v1/books/api-key", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String payload = new String(body, StandardCharsets.UTF_8);
            if (!payload.contains("\"apiKey\":\"wrk-fake\"")) {
                // 测试桩用 422（非 401）：com.sun.net.httpserver 对 401 不捕获响应体，
                // 生产环境由 FastAPI 返回真实 401 body，BooksClient.translate 会正确解析。
                respond(exchange, 422, "application/json",
                        "{\"success\":false,\"error\":{\"code\":\"WEREAD_API_KEY_INVALID\","
                                + "\"message\":\"invalid api key\",\"retryable\":false,\"details\":{}},"
                                + "\"requestId\":\"r5\"}");
                return;
            }
            json(exchange, """
                    {"success":true,"data":{"loggedIn":true,"loginType":"API_KEY","nickname":"张三",
                    "headImgUrl":null,"isVip":false,"lastLoginAt":null,"loginQr":null},"requestId":"r6"}
                    """);
        });
        server.createContext("/internal/v1/books/info", exchange -> json(exchange, """
                {"success":true,"data":{"bookId":"1","title":"深入理解Java虚拟机","author":"周志明",
                "coverUrl":"","category":"计算机","intro":"JVM 权威指南。","publisher":"机械工业出版社",
                "publishTime":"2019-12-01","isbn":"9787111641247","translator":null,"wordCount":468000,
                "newRating":9.5,"newRatingCount":12034,
                "ratingDetail":{"good":90,"fair":8,"poor":2,"recent":95,"deepV":88,"myRating":""},
                "deepLink":"https://weread.qq.com/book-detail?type=1&v=1"},"requestId":"r8"}
                """));
        server.createContext("/internal/v1/books/getprogress", exchange -> json(exchange, """
                {"success":true,"data":{"bookId":"1","progressPercent":36,"chapterIdx":3,"chapterUid":21,
                "readingTime":2896,"updateTime":"1785775131","lastReadAt":"1785941021"},"requestId":"r9"}
                """));
        server.createContext("/internal/v1/books/recommend", exchange -> json(exchange, """
                {"success":true,"data":{"books":[
                {"bookId":"101","title":"置身事内","author":"兰小欢","coverUrl":"","category":"经济",
                "intro":"入门佳作。","price":19.9,"format":"epub","type":"book",
                "deepLink":"https://weread.qq.com/book-detail?type=1&v=101"}]},"requestId":"r10"}
                """));
        server.createContext("/internal/v1/books/similar", exchange -> json(exchange, """
                {"success":true,"data":{"books":[
                {"bookId":"101","title":"置身事内","author":"兰小欢","coverUrl":"","category":"经济",
                "intro":"入门佳作。","price":19.9,"format":"epub","type":"book","deepLink":null}]},
                "requestId":"r11"}
                """));
        server.createContext("/internal/v1/books/readdata-detail", exchange -> json(exchange, """
                {"success":true,"data":{"totalReadTime":1062443,"wrReadTime":884478,"wrListenTime":177965,
                "readDays":768,"readRate":83,"registTime":"1545717161","preferCategoryWord":"偏好阅读心理",
                "preferTimeWord":"偏好深夜阅读",
                "preferBooks":[{"bookId":"201","title":"我的最爱","cover":"","type":13}],
                "medals":[{"name":"想法发布","displayText":"想法发布 10 条","rankText":"第1位"}]},
                "requestId":"r12"}
                """));
        server.start();
        client = new BooksClient(new ObjectMapper().findAndRegisterModules(), true,
                "http://127.0.0.1:" + server.getAddress().getPort(), TOKEN,
                Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesLoginStatusAndSendsInternalToken() {
        BookLoginStatusView status = client.loginStatus();

        assertThat(status.loggedIn()).isTrue();
        assertThat(status.loginType()).isEqualTo("API_KEY");
        assertThat(status.nickname()).isEqualTo("张三");
        assertThat(status.loginQr()).isNull();
        assertThat(receivedToken.get()).isEqualTo(TOKEN);
    }

    @Test
    void parsesQrLoginView() {
        BookQrLoginView qr = client.startQrLogin();

        assertThat(qr.status()).isEqualTo("PENDING");
        assertThat(qr.qrBase64()).startsWith("data:image/png;base64,");
        assertThat(qr.message()).contains("扫一扫");
    }

    @Test
    void parsesBookshelfAndNormalizesBooks() {
        BookShelfView shelf = client.bookshelf();

        assertThat(shelf.total()).isEqualTo(2);
        assertThat(shelf.readingCount()).isEqualTo(1);
        assertThat(shelf.books()).hasSize(2);
        assertThat(shelf.books().get(0).title()).isEqualTo("深入理解Java虚拟机");
        assertThat(shelf.books().get(0).readingProgress()).isEqualTo(0.36);
        assertThat(shelf.books().get(1).isFinished()).isTrue();
        assertThat(shelf.books().get(1).wordCount()).isNull();
    }

    @Test
    void setApiKeySendsCamelCaseBodyAndMapsInvalidKey() {
        BookLoginStatusView status = client.setApiKey("wrk-fake");
        assertThat(status.loggedIn()).isTrue();

        assertThatThrownBy(() -> client.setApiKey("wrk-bad"))
                .isInstanceOfSatisfying(AiModelException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCode.WEREAD_API_KEY_INVALID);
                    assertThat(error.getUserMessage()).isEqualTo("invalid api key");
                });
    }

    @Test
    void mapsWereadNotLoggedInFromRemote() throws IOException {
        HttpServer expired = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            expired.createContext("/internal/v1/books/bookshelf", exchange -> respond(exchange, 401,
                    "application/json", """
                            {"success":false,"error":{"code":"WEREAD_NOT_LOGGED_IN",
                            "message":"微信读书尚未登录","retryable":false,"details":{}},"requestId":"r7"}
                            """));
            expired.start();
            BooksClient expiredClient = new BooksClient(new ObjectMapper().findAndRegisterModules(), true,
                    "http://127.0.0.1:" + expired.getAddress().getPort(), TOKEN, Duration.ofSeconds(5));

            assertThatThrownBy(expiredClient::bookshelf)
                    .isInstanceOfSatisfying(AiModelException.class, error ->
                            assertThat(error.getCode()).isEqualTo(ErrorCode.WEREAD_NOT_LOGGED_IN));
        } finally {
            expired.stop(0);
        }
    }

    @Test
    void logoutSucceeds() {
        client.logout();
        // 未抛异常即通过（POST 200 且 data 为对象）
        assertThat(receivedToken.get()).isEqualTo(TOKEN);
    }

    @Test
    void parsesBookInfo() {
        BookInfoView info = client.bookInfo("1");

        assertThat(info.bookId()).isEqualTo("1");
        assertThat(info.title()).isEqualTo("深入理解Java虚拟机");
        assertThat(info.newRating()).isEqualTo(9.5);
        assertThat(info.ratingDetail().good()).isEqualTo(90);
        assertThat(info.deepLink()).startsWith("https://weread.qq.com/book-detail");
    }

    @Test
    void parsesBookProgress() {
        BookProgressView progress = client.bookProgress("1");

        assertThat(progress.progressPercent()).isEqualTo(36);
        assertThat(progress.chapterIdx()).isEqualTo(3);
        assertThat(progress.updateTime()).isEqualTo("1785775131");
    }

    @Test
    void parsesRecommendAndSimilar() {
        List<BookIntroView> rec = client.recommend(12);
        assertThat(rec).hasSize(1);
        assertThat(rec.get(0).title()).isEqualTo("置身事内");
        assertThat(rec.get(0).price()).isEqualTo(19.9);

        List<BookIntroView> sim = client.similar("1", 12);
        assertThat(sim).hasSize(1);
        assertThat(sim.get(0).bookId()).isEqualTo("101");
        assertThat(sim.get(0).deepLink()).isNull();
    }

    @Test
    void parsesReadDataDetail() {
        ReadDataDetailView stat = client.readDataDetail("overall");

        assertThat(stat.totalReadTime()).isEqualTo(1062443);
        assertThat(stat.readDays()).isEqualTo(768);
        assertThat(stat.preferCategoryWord()).isEqualTo("偏好阅读心理");
        assertThat(stat.preferBooks()).hasSize(1);
        assertThat(stat.medals().get(0).name()).isEqualTo("想法发布");
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        respond(exchange, "application/json", body);
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        respond(exchange, 200, contentType, body);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
