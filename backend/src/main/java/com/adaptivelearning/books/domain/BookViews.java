package com.adaptivelearning.books.domain;

import java.util.List;

/**
 * 图书（微信读书）契约视图，与 ai-service / weread-mcp 共用 camelCase 字段。
 */
public final class BookViews {

    private BookViews() {
    }

    public record BookView(
            String bookId,
            String title,
            String author,
            String coverUrl,
            String category,
            String categoryId,
            double readingProgress,
            boolean isFinished,
            String status,
            String updateTime,
            String lastReadChapter,
            Integer wordCount,
            String format,
            String type,
            boolean isPublic,
            String deepLink) {
    }

    public record BookShelfView(
            int total,
            int readingCount,
            int finishedCount,
            List<BookView> books) {
    }

    public record BookQrLoginView(
            String status,
            String qrBase64,
            String qrToken,
            String message,
            String expiresAt) {
    }

    public record BookLoginStatusView(
            boolean loggedIn,
            String loginType,
            String nickname,
            String headImgUrl,
            boolean isVip,
            String lastLoginAt,
            BookQrLoginView loginQr) {
    }

    public record ApiKeyRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 128)
            String apiKey) {
    }

    public record SearchRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 100)
            String keyword,
            @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(50)
            Integer count) {
    }

    public record BookIdRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 64)
            String bookId) {
    }

    public record RatingDetailView(
            Integer good,
            Integer fair,
            Integer poor,
            Integer recent,
            Integer deepV,
            String myRating) {
    }

    public record BookInfoView(
            String bookId,
            String title,
            String author,
            String coverUrl,
            String category,
            String intro,
            String publisher,
            String publishTime,
            String isbn,
            String translator,
            Integer wordCount,
            Double newRating,
            Integer newRatingCount,
            RatingDetailView ratingDetail,
            String deepLink) {
    }

    public record BookProgressView(
            String bookId,
            Integer progressPercent,
            Integer chapterIdx,
            Integer chapterUid,
            Integer readingTime,
            String updateTime,
            String lastReadAt) {
    }

    public record BookIntroView(
            String bookId,
            String title,
            String author,
            String coverUrl,
            String category,
            String intro,
            Double price,
            String format,
            String type,
            String deepLink) {
    }

    public record ReadPreferBookView(
            String bookId,
            String title,
            String cover,
            Integer type) {
    }

    public record ReadMedalView(
            String name,
            String displayText,
            String rankText) {
    }

    public record ReadDataDetailView(
            Integer totalReadTime,
            Integer wrReadTime,
            Integer wrListenTime,
            Integer readDays,
            Integer readRate,
            String registTime,
            String preferCategoryWord,
            String preferTimeWord,
            List<ReadPreferBookView> preferBooks,
            List<ReadMedalView> medals) {
    }
}
