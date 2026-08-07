package com.adaptivelearning.books.api;

import com.adaptivelearning.books.application.BookService;
import com.adaptivelearning.books.domain.BookViews.ApiKeyRequest;
import com.adaptivelearning.books.domain.BookViews.BookIdRequest;
import com.adaptivelearning.books.domain.BookViews.BookInfoView;
import com.adaptivelearning.books.domain.BookViews.BookIntroView;
import com.adaptivelearning.books.domain.BookViews.BookLoginStatusView;
import com.adaptivelearning.books.domain.BookViews.BookProgressView;
import com.adaptivelearning.books.domain.BookViews.BookQrLoginView;
import com.adaptivelearning.books.domain.BookViews.BookShelfView;
import com.adaptivelearning.books.domain.BookViews.BookView;
import com.adaptivelearning.books.domain.BookViews.ReadDataDetailView;
import com.adaptivelearning.books.domain.BookViews.SearchRequest;
import com.adaptivelearning.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 图书（微信读书）端点。安全由 {@code SecurityConfig} 的 anyRequest().authenticated() 统一覆盖。
 */
@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookController {
    private final BookService books;

    @GetMapping("/login-status")
    public ApiResponse<BookLoginStatusView> loginStatus() {
        return ApiResponse.ok(books.loginStatus());
    }

    @PostMapping("/login-qrcode")
    public ApiResponse<BookQrLoginView> startQrLogin() {
        return ApiResponse.ok(books.startQrLogin());
    }

    @PostMapping("/api-key")
    public ApiResponse<BookLoginStatusView> setApiKey(@Valid @RequestBody ApiKeyRequest request) {
        return ApiResponse.ok(books.setApiKey(request.apiKey()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        books.logout();
        return ApiResponse.ok(null);
    }

    @GetMapping("/bookshelf")
    public ApiResponse<BookShelfView> bookshelf() {
        return ApiResponse.ok(books.bookshelf());
    }

    @GetMapping("/search")
    public ApiResponse<List<BookView>> search(@Valid SearchRequest request) {
        return ApiResponse.ok(books.search(request.keyword(), request.count()));
    }

    @GetMapping("/info")
    public ApiResponse<BookInfoView> bookInfo(@Valid BookIdRequest request) {
        return ApiResponse.ok(books.bookInfo(request.bookId()));
    }

    @GetMapping("/getprogress")
    public ApiResponse<BookProgressView> bookProgress(@Valid BookIdRequest request) {
        return ApiResponse.ok(books.bookProgress(request.bookId()));
    }

    @GetMapping("/readdata-detail")
    public ApiResponse<ReadDataDetailView> readDataDetail(
            @RequestParam(required = false) String mode) {
        return ApiResponse.ok(books.readDataDetail(mode));
    }

    @GetMapping("/recommend")
    public ApiResponse<List<BookIntroView>> recommend(
            @RequestParam(required = false) @Min(1) @Max(50) Integer count) {
        return ApiResponse.ok(books.recommend(count));
    }

    @GetMapping("/similar")
    public ApiResponse<List<BookIntroView>> similar(
            @Valid BookIdRequest request,
            @RequestParam(required = false) @Min(1) @Max(50) Integer count) {
        return ApiResponse.ok(books.similar(request.bookId(), count));
    }
}
