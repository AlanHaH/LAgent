package com.adaptivelearning.books.application;

import com.adaptivelearning.books.domain.BookViews.BookInfoView;
import com.adaptivelearning.books.domain.BookViews.BookIntroView;
import com.adaptivelearning.books.domain.BookViews.BookLoginStatusView;
import com.adaptivelearning.books.domain.BookViews.BookProgressView;
import com.adaptivelearning.books.domain.BookViews.BookQrLoginView;
import com.adaptivelearning.books.domain.BookViews.BookShelfView;
import com.adaptivelearning.books.domain.BookViews.BookView;
import com.adaptivelearning.books.domain.BookViews.ReadDataDetailView;
import com.adaptivelearning.shared.ai.BooksClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图书（微信读书）业务服务。当前微信读书账号按「部署」绑定（单账号全局态），
 * 全站共享该账号书架；如需按用户隔离，可在此按 {@code SecurityUtils.currentUser()} 区分。
 */
@Service
@RequiredArgsConstructor
public class BookService {
    private final BooksClient books;

    public BookLoginStatusView loginStatus() {
        return books.loginStatus();
    }

    public BookQrLoginView startQrLogin() {
        return books.startQrLogin();
    }

    public BookLoginStatusView setApiKey(String apiKey) {
        return books.setApiKey(apiKey);
    }

    public void logout() {
        books.logout();
    }

    public BookShelfView bookshelf() {
        return books.bookshelf();
    }

    public List<BookView> search(String keyword, Integer count) {
        return books.search(keyword, count == null ? 10 : count);
    }

    public BookInfoView bookInfo(String bookId) {
        return books.bookInfo(bookId);
    }

    public BookProgressView bookProgress(String bookId) {
        return books.bookProgress(bookId);
    }

    public ReadDataDetailView readDataDetail(String mode) {
        return books.readDataDetail(mode == null ? "overall" : mode);
    }

    public List<BookIntroView> recommend(Integer count) {
        return books.recommend(count == null ? 12 : count);
    }

    public List<BookIntroView> similar(String bookId, Integer count) {
        return books.similar(bookId, count == null ? 12 : count);
    }
}
