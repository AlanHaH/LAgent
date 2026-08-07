package com.adaptivelearning.books.api;

import com.adaptivelearning.books.application.BookService;
import com.adaptivelearning.books.domain.BookViews.ApiKeyRequest;
import com.adaptivelearning.books.domain.BookViews.BookIdRequest;
import com.adaptivelearning.books.domain.BookViews.BookInfoView;
import com.adaptivelearning.books.domain.BookViews.BookLoginStatusView;
import com.adaptivelearning.books.domain.BookViews.BookProgressView;
import com.adaptivelearning.books.domain.BookViews.BookQrLoginView;
import com.adaptivelearning.books.domain.BookViews.BookShelfView;
import com.adaptivelearning.books.domain.BookViews.RatingDetailView;
import com.adaptivelearning.books.domain.BookViews.ReadDataDetailView;
import com.adaptivelearning.books.domain.BookViews.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookControllerTest {
    private final BookService service = mock(BookService.class);
    private final BookController controller = new BookController(service);

    @Test
    void loginStatusDelegates() {
        BookLoginStatusView expected = new BookLoginStatusView(false, null, null, null, false, null, null);
        when(service.loginStatus()).thenReturn(expected);

        BookLoginStatusView result = controller.loginStatus().data();

        assertThat(result).isSameAs(expected);
        assertThat(controller.loginStatus().success()).isTrue();
    }

    @Test
    void startQrLoginDelegates() {
        BookQrLoginView expected = new BookQrLoginView("PENDING", "data:image/png;base64,AA",
                "qr_1", "请用微信扫一扫登录", "2026-08-05T10:00:00Z");
        when(service.startQrLogin()).thenReturn(expected);

        BookQrLoginView result = controller.startQrLogin().data();

        assertThat(result.status()).isEqualTo("PENDING");
    }

    @Test
    void setApiKeyDelegates() {
        BookLoginStatusView expected = new BookLoginStatusView(true, "API_KEY", "张三", null, false, null, null);
        when(service.setApiKey("wrk-fake")).thenReturn(expected);

        BookLoginStatusView result = controller.setApiKey(new ApiKeyRequest("wrk-fake")).data();

        assertThat(result.loggedIn()).isTrue();
        verify(service).setApiKey("wrk-fake");
    }

    @Test
    void bookshelfDelegates() {
        BookShelfView expected = new BookShelfView(2, 1, 1, List.of());
        when(service.bookshelf()).thenReturn(expected);

        BookShelfView result = controller.bookshelf().data();

        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    void searchDelegatesWithDefaultCount() {
        when(service.search("Java", null)).thenReturn(List.of());
        controller.search(new SearchRequest("Java", null));
        verify(service).search("Java", null);
    }

    @Test
    void logoutDelegates() {
        controller.logout();
        verify(service).logout();
    }

    @Test
    void bookInfoDelegates() {
        BookInfoView expected = new BookInfoView("1", "深入理解Java虚拟机", "周志明", null, "计算机",
                "JVM 权威指南。", "机械工业出版社", "2019-12-01", "9787111641247", null, 468000,
                9.5, 12034, new RatingDetailView(90, 8, 2, 95, 88, ""),
                "https://weread.qq.com/book-detail?type=1&v=1");
        when(service.bookInfo("1")).thenReturn(expected);

        BookInfoView result = controller.bookInfo(new BookIdRequest("1")).data();

        assertThat(result.bookId()).isEqualTo("1");
        assertThat(result.newRating()).isEqualTo(9.5);
        verify(service).bookInfo("1");
    }

    @Test
    void bookProgressDelegates() {
        BookProgressView expected = new BookProgressView("1", 36, 3, 21, 2896, null, null);
        when(service.bookProgress("1")).thenReturn(expected);

        assertThat(controller.bookProgress(new BookIdRequest("1")).data().progressPercent()).isEqualTo(36);
        verify(service).bookProgress("1");
    }

    @Test
    void readDataDetailDelegatesWithDefaultMode() {
        ReadDataDetailView expected = new ReadDataDetailView(1062443, 884478, 177965, 768, 83,
                null, "偏好阅读心理", "偏好深夜阅读", List.of(), List.of());
        when(service.readDataDetail(null)).thenReturn(expected);

        assertThat(controller.readDataDetail(null).data().readDays()).isEqualTo(768);
        verify(service).readDataDetail(null);
    }

    @Test
    void recommendDelegatesWithDefaultCount() {
        when(service.recommend(null)).thenReturn(List.of());
        controller.recommend(null);
        verify(service).recommend(null);
    }

    @Test
    void similarDelegatesWithDefaultCount() {
        when(service.similar("1", null)).thenReturn(List.of());
        controller.similar(new BookIdRequest("1"), null);
        verify(service).similar("1", null);
    }
}
